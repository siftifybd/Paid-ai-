package com.jarvis.assistant.license.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.license.LicenseManager
import com.jarvis.assistant.license.ui.LicenseActivationActivity

class LicenseExpiryAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jarvis_license_alerts"
        const val NOTIF_ID_1_DAY = 8801
        const val NOTIF_ID_10_MIN = 8802
        const val NOTIF_ID_EXPIRED = 8803
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        createNotificationChannel(context)

        when (action) {
            LicenseExpiryNotifier.ACTION_ALERT_1_DAY -> {
                showNotification(
                    context,
                    NOTIF_ID_1_DAY,
                    "⚠️ JARVIS License Expiring in 24 Hours",
                    "আপনার JARVIS প্রিমিয়াম লাইসেন্সের মেয়াদ আগামীকাল শেষ হবে। এখনই অ্যাডমিনের সাথে যোগাযোগ করে রিনিউ করুন।"
                )
            }
            LicenseExpiryNotifier.ACTION_ALERT_10_MIN -> {
                showNotification(
                    context,
                    NOTIF_ID_10_MIN,
                    "🚨 URGENT: License Expiring in 10 Minutes!",
                    "আর মাত্র ১০ মিনিট পর প্রিমিয়াম ফিচারগুলো লক হয়ে যাবে। নতুন লাইসেন্স কি প্রবেশ করান।"
                )
            }
            LicenseExpiryNotifier.ACTION_EXPIRED -> {
                LicenseManager.getInstance(context).onLicenseExpired()
                showNotification(
                    context,
                    NOTIF_ID_EXPIRED,
                    "🔒 JARVIS License Expired",
                    "আপনার লাইসেন্সের মেয়াদ শেষ হয়েছে। প্রিমিয়াম ফিচারগুলো লক করা হয়েছে। রিনিউ করতে ট্যাপ করুন।"
                )
            }
        }
    }

    private fun showNotification(context: Context, notifId: Int, title: String, text: String) {
        val tapIntent = Intent(context, LicenseActivationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, notifId, tapIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_settings_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(notifId, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS License Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts for license expiration and renewal"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
