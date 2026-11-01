package com.jarvis.assistant.network

import android.util.Log
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Diagnostic bridge class 'GeminiDebugBridge' that intercepts WebSocket frame negotiation,
 * logs exact handshake packets between the Android app and Gemini, and validates that
 * the model 'gemini-2.0-flash-exp' is correctly accepted by the server.
 */
class GeminiDebugBridge(
    private val originalModel: String,
    private val delegateListener: WebSocketListener
) : WebSocketListener() {

    companion object {
        private const val TAG = "GeminiDebugBridge"
        const val TARGET_STABLE_MODEL = "gemini-2.0-flash-exp"
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "🟢 [GeminiDebugBridge] WebSocket onOpen: Handshake connection opened successfully.")
        Log.i(TAG, "🔍 [GeminiDebugBridge] Response protocol: ${response.protocol}, code: ${response.code}, message: ${response.message}")
        
        // Log all response headers for debugging connection rejections
        val headers = response.headers
        for (i in 0 until headers.size) {
            Log.d(TAG, "📋 [Header] ${headers.name(i)}: ${headers.value(i)}")
        }

        delegateListener.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d(TAG, "📥 [GeminiDebugBridge RX Text] (len=${text.length}): ${text.take(400)}")
        
        if (text.contains("setupComplete", ignoreCase = true)) {
            Log.i(TAG, "✅ [GeminiDebugBridge] Model verification SUCCESS: Server accepted model setup handshake!")
        } else if (text.contains("error", ignoreCase = true) || text.contains("SETUP_REJECTED", ignoreCase = true)) {
            Log.e(TAG, "❌ [GeminiDebugBridge] Model setup rejected or error received: $text")
        }

        delegateListener.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(TAG, "📥 [GeminiDebugBridge RX Bytes] (size=${bytes.size})")
        delegateListener.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "⚠️ [GeminiDebugBridge] WebSocket onClosing: code=$code, reason=$reason")
        delegateListener.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "⚠️ [GeminiDebugBridge] WebSocket onClosed: code=$code, reason=$reason")
        delegateListener.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val code = response?.code ?: 0
        val body = try { response?.body?.string() } catch (_: Exception) { null }
        Log.e(TAG, "❌ [GeminiDebugBridge] WebSocket onFailure: code=$code, error=${t.message}, body=$body", t)
        
        if (response != null) {
            Log.e(TAG, "📋 [Failure Response Headers]:")
            for (i in 0 until response.headers.size) {
                Log.e(TAG, "   ${response.headers.name(i)}: ${response.headers.value(i)}")
            }
        }

        delegateListener.onFailure(webSocket, t, response)
    }
}
