package com.jarvis.assistant.remote

import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Outbound-only connection to your cloud relay (works behind NAT, no port forwarding).
 *
 * Trust model: the relay is UNTRUSTED. It only forwards opaque AES-256-GCM envelopes that
 * are encrypted with the pairing key ([RemoteStore.ensurePairingKey]). On top of that every
 * relay session still has to pass the PIN handshake in [CommandRouter]. Each frame carries
 * a timestamp and a unique id, so a malicious relay cannot replay old commands.
 *
 * Uses the message names of your existing backend/server.js:
 *   REGISTER_DEVICE, RELAYED_COMMAND (in), RELAY_RESPONSE (out).
 * The server must be patched as described in INTEGRATION.md (relayToken check, sender id).
 */
class RelayClient(
    private val store: RemoteStore,
    private val router: CommandRouter,
    private val scope: CoroutineScope
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var attempt = 0

    private val sessions = ConcurrentHashMap<String, Session>()
    private val seenIds = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 1000
    }

    fun start() {
        val url = store.relayUrl
        if (url == null || !url.startsWith("wss://")) {
            Log.w(TAG, "Relay disabled: URL must start with wss://"); return
        }
        if (store.relayToken.isNullOrEmpty() || store.pairingKeyBytes() == null) {
            Log.w(TAG, "Relay disabled: relayToken / pairing key not configured"); return
        }
        running = true
        connect(url)
    }

    fun stop() {
        running = false
        socket?.close(1000, "stop")
        socket = null
        sessions.clear()
        http.dispatcher.cancelAll()
    }

    private fun connect(url: String) {
        if (!running) return
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                // Step 1: Request an auth challenge from the relay server
                webSocket.send(JsonObject().apply {
                    addProperty("type", "GET_CHALLENGE")
                    add("payload", JsonObject().apply {
                        addProperty("deviceId", store.deviceId)
                    })
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length <= 32 * 1024) onFrame(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect(url)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "relay failure: ${t.javaClass.simpleName}")
                reconnect(url)
            }
        })
    }

    private fun reconnect(url: String) {
        if (!running) return
        sessions.clear()
        val backoff = min(60_000L, 1_000L shl min(attempt, 6)) + Random.nextLong(0, 1_000)
        attempt++
        scope.launch { delay(backoff); connect(url) }
    }

    private fun onFrame(webSocket: WebSocket, text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            val type = root.get("type")?.asString
            
            if (type == "AUTH_CHALLENGE") {
                val challenge = root.getAsJsonObject("payload")?.get("challenge")?.asString ?: return
                val signature = store.signChallenge(challenge)
                
                val payload = JsonObject().apply {
                    addProperty("deviceId", store.deviceId)
                    addProperty("deviceName", "Jarvis ${Build.MODEL}".take(40))
                    addProperty("publicKey", store.getRelayPublicKeyB64())
                    addProperty("signature", signature)
                    addProperty("relayToken", store.relayToken)
                }
                
                webSocket.send(JsonObject().apply {
                    addProperty("type", "REGISTER_DEVICE")
                    add("payload", payload)
                }.toString())
                return
            }

            if (type == "REGISTER_ACK") {
                Log.i(TAG, "Successfully registered with relay server")
                return
            }

            if (type == "ERROR") {
                Log.e(TAG, "Relay server error: ${root.get("message")?.asString}")
                return
            }

            if (type != "RELAYED_COMMAND") return
            val p = root.getAsJsonObject("payload") ?: return
            val from = p.get("fromDeviceId")?.asString ?: return
            val sid = p.get("sessionId")?.asString ?: return
            val enc = p.get("encryptedPayload")?.asString ?: return
            val iv = p.get("nonce")?.asString ?: return
            if (from.length > 64 || sid.length > 64 || enc.length > 16 * 1024) return

            val key = store.pairingKeyBytes() ?: return
            val plain = RemoteCrypto.decrypt(key, enc, iv, sid.toByteArray()) ?: return
            val raw = String(plain, Charsets.UTF_8)

            // Replay protection: fresh timestamp + never-seen id, both mandatory on WAN.
            val msg = RemoteJson.parse(raw) ?: return
            val id = msg.id ?: return
            val ts = msg.ts ?: return
            if (abs(System.currentTimeMillis() - ts) > Proto.RELAY_MAX_SKEW_MS) return
            synchronized(seenIds) {
                if (seenIds.containsKey("$from|$id")) return
                seenIds["$from|$id"] = true
            }

            val sKey = "$from|$sid"
            val session = sessions[sKey] ?: run {
                if (sessions.size >= MAX_SESSIONS) return
                Session(
                    id = sKey, peerKey = "wan:$from", transport = "wan",
                    send = { json -> sendResponse(from, sid, json) },
                    close = { sessions.remove(sKey) }
                ).also { sessions[sKey] = it }
            }
            router.handle(session, raw)
        } catch (e: Exception) {
            Log.w(TAG, "bad relay frame: ${e.javaClass.simpleName}")
        }
    }

    private fun sendResponse(target: String, sid: String, json: String) {
        val key = store.pairingKeyBytes() ?: return
        val (cipher, iv) = RemoteCrypto.encrypt(key, json.toByteArray(Charsets.UTF_8), sid.toByteArray())
        val payload = JsonObject().apply {
            addProperty("targetDeviceId", target)
            addProperty("sessionId", sid)
            addProperty("encryptedPayload", cipher)
            addProperty("nonce", iv)
        }
        socket?.send(JsonObject().apply {
            addProperty("type", "RELAY_RESPONSE")
            add("payload", payload)
        }.toString())
    }

    private companion object {
        const val TAG = "RelayClient"
        const val MAX_SESSIONS = 8
    }
}
