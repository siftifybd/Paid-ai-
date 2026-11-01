package com.jarvis.assistant.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.IProtocol
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer

/**
 * Foreground service that exposes this phone for remote control:
 *   LAN : wss:// server (TLS, bound to the Wi-Fi IP only) + NSD (mDNS) + UDP discovery
 *   WAN : outbound wss:// connection to the relay ([RelayClient])
 * Every frame goes through [CommandRouter] (PIN/token auth, validation, rate limits).
 *
 * It never auto-starts, never restarts itself after being killed, and always shows a
 * persistent notification with a Stop button.
 */
class RemoteControlServer : Service() {

    companion object {
        private const val TAG = "RemoteControlServer"
        const val ACTION_START = "com.jarvis.assistant.remote.START"
        const val ACTION_STOP = "com.jarvis.assistant.remote.STOP"
        private const val CHANNEL_ID = "jarvis_remote_control"
        private const val NOTIF_ID = 9101
        private const val NSD_TYPE = "_jarvis-remote._tcp."
        private const val UDP_PORT = 8791
        private const val UDP_PROBE = "JARVIS_DISCOVER_V1"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var serverIp: String? = null
            private set

        @Volatile
        var serverPort: Int = 0
            private set

        @Volatile
        var activeConnectionsCount: Int = 0
            private set

        fun start(context: Context) {
            val i = Intent(context, RemoteControlServer::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RemoteControlServer::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: RemoteStore
    private lateinit var auth: RemoteAuth
    private lateinit var router: CommandRouter

    private var lan: LanSocketServer? = null
    private var relay: RelayClient? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var udp: DatagramSocket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var boundIp: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RemoteStore(this)
        auth = RemoteAuth(store)
        router = CommandRouter(applicationContext, auth, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        showForeground("Starting remote control…")
        if (!auth.hasPin()) {
            Toast.makeText(this, "Create a remote-control PIN first", Toast.LENGTH_LONG).show()
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startLan()
            if (store.wanEnabled) startRelay()
            watchNetwork()
        }
        return START_NOT_STICKY   // after a kill the user must re-enable it explicitly
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ LAN

    @Synchronized
    private fun startLan() {
        stopLan()
        val ip = wifiIpv4()
        if (ip == null) {
            updateNotification("No Wi-Fi connection — LAN control paused"); return
        }
        try {
            val server = LanSocketServer(InetSocketAddress(ip, store.lanPort))
            server.isReuseAddr = true
            server.connectionLostTimeout = 30
            server.setWebSocketFactory(DefaultSSLWebSocketServerFactory(RemoteTls.sslContext()))
            server.start()                 // onStart() fires once the socket is bound
            lan = server
            boundIp = ip.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "LAN server failed: ${e.javaClass.simpleName}: ${e.message}")
            updateNotification("LAN server failed to start")
        }
    }

    @Synchronized
    private fun stopLan() {
        unregisterNsd()
        udp?.close(); udp = null
        val s = lan
        lan = null
        boundIp = null
        if (s != null) Thread { try { s.stop(500) } catch (_: Exception) {} }.start()
    }

    private inner class LanSocketServer(addr: InetSocketAddress) : WebSocketServer(
        addr,
        listOf<Draft>(
            Draft_6455(
                emptyList<IExtension>(),
                listOf<IProtocol>(Protocol("")),
                Proto.MAX_FRAME_BYTES
            )
        )
    ) {
        private val sessions = ConcurrentHashMap<WebSocket, Session>()

        override fun onStart() {
            val p = port
            serverIp = boundIp
            serverPort = p
            activeConnectionsCount = 0
            Log.i(TAG, "LAN wss listening on $boundIp:$p")
            registerNsd(p)
            startUdpResponder(p)
            updateNotification("LAN ${boundIp}:$p • tap Stop to disable")
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val addr = conn.remoteSocketAddress?.address
            val privateAddr = addr != null &&
                (addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress)
            if (!privateAddr || sessions.size >= Proto.MAX_CONNECTIONS) {
                conn.close(1008, "rejected"); return
            }
            val session = Session(
                id = UUID.randomUUID().toString(),
                peerKey = "lan:${addr!!.hostAddress}",
                transport = "lan",
                send = { json -> if (conn.isOpen) conn.send(json) },
                close = { conn.close(1008, "policy") }
            )
            sessions[conn] = session
            activeConnectionsCount = sessions.size
            session.send(RemoteJson.toJson(RemoteReply(type = "hello", ok = true)))
            scope.launch {                    // must authenticate quickly or be dropped
                delay(Proto.AUTH_DEADLINE_MS)
                if (!session.authed && conn.isOpen) conn.close(1008, "auth timeout")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            sessions[conn]?.let { router.handle(it, message) }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            sessions.remove(conn)
            activeConnectionsCount = sessions.size
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.w(TAG, "ws error: ${ex?.javaClass?.simpleName}")
        }
    }

    private fun wifiIpv4(): Inet4Address? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
    }

    /** Re-bind when the Wi-Fi IP changes or Wi-Fi drops. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val ip = wifiIpv4()?.hostAddress
                if (isRunning && ip != boundIp) scope.launch { startLan() }
            }

            override fun onLost(network: Network) {
                if (isRunning) scope.launch { startLan() }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    // ------------------------------------------------------------- discovery

    private fun registerNsd(port: Int) {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("jarvis_remote").apply {
                setReferenceCounted(false); acquire()
            }
            val info = NsdServiceInfo().apply {
                serviceName = "Jarvis-${Build.MODEL}".take(40)
                serviceType = NSD_TYPE
                setPort(port)
                // Public hints only — never put a PIN or token here.
                setAttribute("v", Proto.VERSION.toString())
                setAttribute("fp", RemoteTls.fingerprintSha256().replace(":", "").take(16).lowercase())
                setAttribute("id", store.deviceId.take(8))
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "NSD registered: ${i.serviceName}") }
                override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) { Log.w(TAG, "NSD register failed $err") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
            }
            val mgr = getSystemService(Context.NSD_SERVICE) as NsdManager
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = mgr; nsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "NSD unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterNsd() {
        try { nsdListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        nsdListener = null; nsd = null
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    /** Fallback for clients without mDNS: broadcast "JARVIS_DISCOVER_V1" to UDP 8791, get a JSON reply. */
    private fun startUdpResponder(wssPort: Int) {
        Thread {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true; broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                }
                udp = sock
                val buf = ByteArray(64)
                val fp = RemoteTls.fingerprintSha256().replace(":", "").take(16).lowercase()
                var windowStart = 0L
                var count = 0
                while (!sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val now = System.currentTimeMillis()
                    if (now - windowStart > 1000) { windowStart = now; count = 0 }
                    if (++count > 5) continue                                   // rate limit
                    val from = pkt.address
                    if (!(from.isSiteLocalAddress || from.isLinkLocalAddress)) continue  // LAN only
                    if (String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim() != UDP_PROBE) continue
                    val body = """{"service":"jarvis-remote","v":${Proto.VERSION},"name":"${Build.MODEL.replace("\"", "")}","port":$wssPort,"fp":"$fp"}"""
                        .toByteArray()
                    sock.send(DatagramPacket(body, body.size, from, pkt.port))
                }
            } catch (e: Exception) {
                if (udp?.isClosed != true) Log.w(TAG, "UDP responder stopped: ${e.javaClass.simpleName}")
            }
        }.apply { isDaemon = true; name = "jarvis-udp-discovery" }.start()
    }

    // ------------------------------------------------------------------- WAN

    private fun startRelay() {
        relay?.stop()
        relay = RelayClient(store, router, scope).also { it.start() }
    }

    // -------------------------------------------------------------- lifecycle

    @Synchronized
    private fun shutdown() {
        isRunning = false
        serverIp = null
        serverPort = 0
        activeConnectionsCount = 0
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        relay?.stop(); relay = null
        stopLan()
        auth.revokeAll()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun showForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote control", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteControlServer::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexus_wifi)
            .setContentTitle("Jarvis remote control is ON")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }
}
