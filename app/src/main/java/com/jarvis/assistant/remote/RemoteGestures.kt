package com.jarvis.assistant.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Remote-control primitives for JarvisAccessibilityService.
 *
 * They are extension functions on AccessibilityService, so JarvisAccessibilityService.kt
 * (already very large) needs NO edits to get them: JarvisAccessibilityService.instance
 * is already a public singleton in your companion object.
 *
 * Requirements (already present in your project):
 *  - accessibility_service_config.xml: canPerformGestures="true", canRetrieveWindowContent="true"
 */

fun AccessibilityService.screenSize(): Pair<Int, Int> {
    val m = resources.displayMetrics
    return m.widthPixels to m.heightPixels
}

/** Dispatches one gesture and suspends until Android reports completed/cancelled. */
private suspend fun AccessibilityService.rcDispatch(
    vararg strokes: GestureDescription.StrokeDescription
): Boolean = suspendCancellableCoroutine { cont ->
    val gesture = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
    val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (cont.isActive) cont.resume(true)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (cont.isActive) cont.resume(false)
        }
    }, null)
    if (!accepted && cont.isActive) cont.resume(false)
}

private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

suspend fun AccessibilityService.remoteTap(x: Float, y: Float, durationMs: Long = 50): Boolean =
    rcDispatch(GestureDescription.StrokeDescription(point(x, y), 0, durationMs.coerceAtLeast(1)))

suspend fun AccessibilityService.remoteDoubleTap(x: Float, y: Float): Boolean =
    rcDispatch(
        GestureDescription.StrokeDescription(point(x, y), 0, 40),
        GestureDescription.StrokeDescription(point(x, y), 140, 40)
    )

suspend fun AccessibilityService.remoteLongPress(x: Float, y: Float, durationMs: Long = 600): Boolean =
    rcDispatch(GestureDescription.StrokeDescription(point(x, y), 0, durationMs.coerceAtLeast(400)))

suspend fun AccessibilityService.remoteSwipe(
    x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300
): Boolean {
    val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
    return rcDispatch(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
}

/** direction = direction the CONTENT moves: "down" reveals what is below (finger swipes up). */
suspend fun AccessibilityService.remoteScroll(direction: String, durationMs: Long = 350): Boolean {
    val (w, h) = screenSize()
    val cx = w / 2f
    val cy = h / 2f
    val dy = h * 0.30f
    val dx = w * 0.30f
    return when (direction) {
        "down" -> remoteSwipe(cx, cy + dy, cx, cy - dy, durationMs)
        "up" -> remoteSwipe(cx, cy - dy, cx, cy + dy, durationMs)
        "right" -> remoteSwipe(cx + dx, cy, cx - dx, cy, durationMs)
        "left" -> remoteSwipe(cx - dx, cy, cx + dx, cy, durationMs)
        else -> false
    }
}

fun AccessibilityService.remoteKey(key: String): Boolean {
    val action = when (key) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        "lock_screen" ->
            if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
        "screenshot" ->
            if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
        else -> return false
    }
    return performGlobalAction(action)
}

/** Replaces the text of the currently focused input field. */
fun AccessibilityService.remoteSetText(text: String): Boolean {
    val root = rootInActiveWindow ?: return false
    val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}

/** Needs a <queries> LAUNCHER entry in the manifest on Android 11+ (see INTEGRATION.md). */
fun AccessibilityService.remoteLaunchApp(packageName: String): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    return try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
