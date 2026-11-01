package com.jarvis.assistant.remote

import com.google.gson.Gson

/**
 * Wire format v1 (JSON text frames, UTF-8).
 *
 * Every field is nullable on purpose: Gson bypasses Kotlin constructors, so non-null
 * fields could silently end up null. Validation happens in [CommandRouter].
 */
data class RemoteMessage(
    val v: Int? = null,
    val id: String? = null,          // client-chosen request id, echoed in the reply
    val type: String? = null,        // "auth" | "cmd" | "ping"; omitted + action present => "cmd"
    val ts: Long? = null,            // epoch millis; REQUIRED on relay (WAN) frames
    val pin: String? = null,         // auth only
    val token: String? = null,       // auth only (re-auth with a session token)
    val action: String? = null,      // see Proto.ACTIONS
    val unit: String? = null,        // "px" (default) | "pct" (0..100 of screen width/height)
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long? = null,
    val direction: String? = null,   // scroll: up | down | left | right (direction the CONTENT moves)
    val key: String? = null,         // key: see Proto.KEYS
    val text: String? = null,        // text
    val packageName: String? = null  // launch_app
)

data class RemoteReply(
    val v: Int = Proto.VERSION,
    val id: String? = null,
    val type: String,                // "hello" | "auth_ok" | "result" | "pong" | "error"
    val ok: Boolean,
    val error: String? = null,
    val token: String? = null,
    val expiresInSec: Long? = null,
    val retryAfterSec: Long? = null,
    val ts: Long = System.currentTimeMillis()
)

object Proto {
    const val VERSION = 1
    const val MAX_MESSAGE_CHARS = 4096
    const val MAX_FRAME_BYTES = 16 * 1024
    const val MAX_CONNECTIONS = 3
    const val AUTH_DEADLINE_MS = 10_000L
    const val MAX_GESTURE_MS = 5_000L
    const val MAX_TEXT = 500
    const val CMDS_PER_SECOND = 20
    const val RELAY_MAX_SKEW_MS = 60_000L

    val ACTIONS = setOf("tap", "double_tap", "long_press", "swipe", "scroll", "key", "text", "launch_app")
    val KEYS = setOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot")
    val DIRECTIONS = setOf("up", "down", "left", "right")
    val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}

object RemoteJson {
    private val gson = Gson()

    fun parse(raw: String): RemoteMessage? = try {
        gson.fromJson(raw, RemoteMessage::class.java)
    } catch (e: Exception) {
        null
    }

    fun toJson(reply: RemoteReply): String = gson.toJson(reply)
}
