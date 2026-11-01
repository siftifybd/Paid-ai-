package com.jarvis.assistant.license.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules automatic Android system notifications 24 hours (1 day) and 10 minutes
 * before the active license expires, and handles immediate expiration.
 */
object LicenseExpiryNotifier {

    private const val TAG = "LicenseExpiryNotifier"

    const val ACTION_ALERT_1_DAY = "com.jarvis.assistant.license.ACTION_ALERT_1_DAY"
    const val ACTION_ALERT_10_MIN = "com.jarvis.assistant.license.ACTION_ALERT_10_MIN"
    const val ACTION_EXPIRED = "com.jarvis.assistant.license.ACTION_EXPIRED"

    private const val RC_1_DAY = 7001
    private const val RC_10_MIN = 7002
    private const val RC_EXPIRED = 7003

    fun scheduleExpiryAlerts(context: Context, expiresAt: Long) {
        if (expiresAt <= 0L) return

        val now = System.currentTimeMillis()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val time1DayBefore = expiresAt - (24 * 60 * 60 * 1000L)
        val time10MinBefore = expiresAt - (10 * 60 * 1000L)

        // 1. Schedule 24 Hours Before Alert
        if (time1DayBefore > now) {
            scheduleAlarm(context, alarmManager, time1DayBefore, ACTION_ALERT_1_DAY, RC_1_DAY)
            Log.i(TAG, "Scheduled 24h expiry alert for $time1DayBefore")
        }

        // 2. Schedule 10 Minutes Before Alert
        if (time10MinBefore > now) {
            scheduleAlarm(context, alarmManager, time10MinBefore, ACTION_ALERT_10_MIN, RC_10_MIN)
            Log.i(TAG, "Scheduled 10m urgent expiry alert for $time10MinBefore")
        }

        // 3. Schedule Exact Expiry Action
        if (expiresAt > now) {
            scheduleAlarm(context, alarmManager, expiresAt, ACTION_EXPIRED, RC_EXPIRED)
            Log.i(TAG, "Scheduled exact expiry action for $expiresAt")
        }
    }

    private fun scheduleAlarm(
        context: Context,
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        action: String,
        requestCode: Int
    ) {
        val intent = Intent(context, LicenseExpiryAlarmReceiver::class.java).apply {
            setAction(action)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission restricted: ${e.message}, falling back to standard alarm")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancelExpiryAlerts(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val actions = listOf(ACTION_ALERT_1_DAY to RC_1_DAY, ACTION_ALERT_10_MIN to RC_10_MIN, ACTION_EXPIRED to RC_EXPIRED)

        for ((action, rc) in actions) {
            val intent = Intent(context, LicenseExpiryAlarmReceiver::class.java).setAction(action)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            val pendingIntent = PendingIntent.getBroadcast(context, rc, intent, flags)
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
            }
        }
    }
}
