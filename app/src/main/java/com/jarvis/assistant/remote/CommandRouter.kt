package com.jarvis.assistant.remote

import android.content.Context
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One controller connection (LAN socket or relay session). */
class Session(
    val id: String,
    val peerKey: String,           // used for lockout accounting, e.g. "lan:192.168.1.20"
    val transport: String,         // "lan" | "wan"
    val send: (String) -> Unit,
    val close: () -> Unit
) {
    @Volatile var authed = false
    @Volatile var strikes = 0
    internal val recent = ArrayDeque<Long>()
}

/** A fully validated command with coordinates already converted to pixels. */
private data class Cmd(
    val id: String?,
    val action: String,
    val x: Float = 0f, val y: Float = 0f,
    val x2: Float = 0f, val y2: Float = 0f,
    val durationMs: Long = 0,
    val direction: String? = null,
    val key: String? = null,
    val text: String? = null,
    val pkg: String? = null
)

/**
 * Single entry point for BOTH transports. Order of checks for every frame:
 *   size -> JSON -> auth state -> rate limit -> validation -> serialized execution.
 * Nothing touches the accessibility service before the session is authenticated.
 */
class CommandRouter(
    private val ctx: Context,
    private val auth: RemoteAuth,
    private val scope: CoroutineScope
) {
    private val queue = Channel<Pair<Session, Cmd>>(64)

    init {
        // One consumer => gestures execute strictly in the order they were received.
        scope.launch { for ((session, cmd) in queue) execute(session, cmd) }
    }

    fun handle(session: Session, raw: String) {
        if (raw.length > Proto.MAX_MESSAGE_CHARS) {
            reply(session, null, "error", false, "too_large"); session.close(); return
        }
        val msg = RemoteJson.parse(raw)
        if (msg == null) {
            reply(session, null, "error", false, "bad_json"); strike(session); return
        }
        when (msg.type ?: if (msg.action != null) "cmd" else null) {
            "auth" -> doAuth(session, msg)
            "ping" ->
                if (session.authed) reply(session, msg.id, "pong", true)
                else deny(session, msg.id)
            "cmd" -> {
                if (!session.authed) return deny(session, msg.id)
                if (!rateOk(session)) return reply(session, msg.id, "error", false, "rate_limited")
                val (cmd, err) = buildCmd(msg)
                if (cmd == null) return reply(session, msg.id, "error", false, err ?: "invalid")
                if (queue.trySend(session to cmd).isFailure) reply(session, msg.id, "error", false, "busy")
            }
            else -> { reply(session, msg.id, "error", false, "unknown_type"); strike(session) }
        }
    }

    // ---------- auth ----------

    private fun doAuth(session: Session, msg: RemoteMessage) {
        val viaToken = msg.token != null
        val result = if (viaToken) auth.verifyToken(session.peerKey, msg.token)
        else auth.verifyPin(session.peerKey, msg.pin)
        when (result) {
            is RemoteAuth.Result.Ok -> {
                session.authed = true
                session.strikes = 0
                send(session, RemoteReply(
                    id = msg.id, type = "auth_ok", ok = true,
                    token = result.token, expiresInSec = result.ttlSec
                ))
                Log.i(TAG, "auth ok: ${session.peerKey} (${session.transport})")
            }
            is RemoteAuth.Result.Fail -> {
                send(session, RemoteReply(
                    id = msg.id, type = "error", ok = false, error = result.reason,
                    retryAfterSec = result.retryAfterSec.takeIf { it > 0 }
                ))
                Log.w(TAG, "auth failed: ${session.peerKey} (${result.reason})")
                strike(session)
                if (result.retryAfterSec > 0) session.close()
            }
        }
    }

    private fun deny(session: Session, id: String?) {
        reply(session, id, "error", false, "unauthorized")
        strike(session)
    }

    private fun strike(session: Session) {
        if (++session.strikes >= 3) session.close()
    }

    private fun rateOk(session: Session): Boolean {
        val now = System.currentTimeMillis()
        val w = session.recent
        while (w.isNotEmpty() && now - w.peekFirst() > 1000) w.pollFirst()
        if (w.size >= Proto.CMDS_PER_SECOND) return false
        w.addLast(now)
        return true
    }

    // ---------- validation ----------

    private fun buildCmd(m: RemoteMessage): Pair<Cmd?, String?> {
        val action = m.action ?: return null to "missing_action"
        if (action !in Proto.ACTIONS) return null to "unknown_action"
        val svc = JarvisAccessibilityService.instance ?: return null to "accessibility_unavailable"
        val (w, h) = svc.screenSize()

        fun px(v: Float?, dim: Int): Float? {
            if (v == null || !v.isFinite()) return null
            val p = if (m.unit == "pct") v / 100f * dim else v
            return if (p < 0f || p > dim) null else p.coerceIn(0f, (dim - 1).toFloat())
        }

        fun dur(default: Long): Long? {
            val d = m.durationMs ?: default
            return if (d in 1..Proto.MAX_GESTURE_MS) d else null
        }

        return when (action) {
            "tap", "double_tap", "long_press" -> {
                val x = px(m.x, w); val y = px(m.y, h)
                val d = dur(if (action == "long_press") 600 else 50)
                if (x == null || y == null) null to "bad_coordinates"
                else if (d == null) null to "bad_duration"
                else Cmd(m.id, action, x = x, y = y, durationMs = d) to null
            }
            "swipe" -> {
                val x = px(m.x, w); val y = px(m.y, h); val x2 = px(m.x2, w); val y2 = px(m.y2, h)
                val d = dur(300)
                if (x == null || y == null || x2 == null || y2 == null) null to "bad_coordinates"
                else if (d == null) null to "bad_duration"
                else Cmd(m.id, action, x = x, y = y, x2 = x2, y2 = y2, durationMs = d) to null
            }
            "scroll" -> {
                val d = dur(350)
                if (m.direction !in Proto.DIRECTIONS) null to "bad_direction"
                else if (d == null) null to "bad_duration"
                else Cmd(m.id, action, direction = m.direction, durationMs = d) to null
            }
            "key" ->
                if (m.key in Proto.KEYS) Cmd(m.id, action, key = m.key) to null else null to "bad_key"
            "text" -> {
                val t = m.text
                if (t == null || t.length > Proto.MAX_TEXT) null to "bad_text"
                else Cmd(m.id, action, text = t) to null
            }
            "launch_app" -> {
                val p = m.packageName
                if (p == null || p.length > 200 || !Proto.PACKAGE_RE.matches(p)) null to "bad_package"
                else Cmd(m.id, action, pkg = p) to null
            }
            else -> null to "unknown_action"
        }
    }

    // ---------- execution ----------

    private suspend fun execute(session: Session, c: Cmd) {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) { reply(session, c.id, "result", false, "accessibility_unavailable"); return }
        Log.i(TAG, "exec ${c.action} from ${session.peerKey}")   // never log text/pin contents
        val ok = try {
            withTimeoutOrNull(8_000) {
                when (c.action) {
                    "tap" -> svc.remoteTap(c.x, c.y, c.durationMs)
                    "double_tap" -> svc.remoteDoubleTap(c.x, c.y)
                    "long_press" -> svc.remoteLongPress(c.x, c.y, c.durationMs)
                    "swipe" -> svc.remoteSwipe(c.x, c.y, c.x2, c.y2, c.durationMs)
                    "scroll" -> svc.remoteScroll(c.direction!!, c.durationMs)
                    "key" -> svc.remoteKey(c.key!!)
                    "text" -> svc.remoteSetText(c.text!!)
                    "launch_app" -> svc.remoteLaunchApp(c.pkg!!)
                    else -> false
                }
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.javaClass.simpleName}")
            false
        }
        reply(session, c.id, "result", ok, if (ok) null else "failed")
    }

    // ---------- helpers ----------

    private fun reply(s: Session, id: String?, type: String, ok: Boolean, error: String? = null) =
        send(s, RemoteReply(id = id, type = type, ok = ok, error = error))

    private fun send(s: Session, r: RemoteReply) {
        try { s.send(RemoteJson.toJson(r)) } catch (e: Exception) { Log.w(TAG, "send failed") }
    }

    private companion object {
        const val TAG = "RemoteRouter"
    }
}
