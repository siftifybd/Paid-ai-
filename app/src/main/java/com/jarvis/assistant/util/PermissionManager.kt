package com.jarvis.assistant.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralized PermissionManager for JARVIS Assistant.
 * Coordinates basic voice permissions (Microphone & Notifications) and
 * advanced premium feature permissions (Contacts, Phone Calls, SMS, Location, Camera, Wi-Fi).
 * Eliminates generic "Please allow all permissions in Settings" blockers by directly requesting
 * required permissions at point of interaction.
 */
object PermissionManager {

    private const val TAG = "PermissionManager"

    enum class FeatureCategory {
        VOICE,
        CONTACTS,
        PHONE_CALLS,
        SMS,
        LOCATION,
        CAMERA,
        WIFI,
        SETTINGS_WRITE
    }

    // --- Basic Voice Permissions ---
    fun getRequiredVoicePermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasVoicePermissions(context: Context): Boolean {
        return hasRecordAudioPermission(context) && hasNotificationPermission(context)
    }

    fun getMissingVoicePermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (!hasRecordAudioPermission(context)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return missing.toTypedArray()
    }

    // --- Unified Core Runtime Permissions (Microphone, Camera, Notifications) ---
    fun getUnifiedRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun hasUnifiedPermissions(context: Context): Boolean {
        val hasAudio = hasRecordAudioPermission(context)
        val hasNotif = hasNotificationPermission(context)
        return hasAudio && hasNotif
    }

    fun hasAllCorePermissions(context: Context): Boolean {
        return hasRecordAudioPermission(context) && hasCameraPermission(context) && hasNotificationPermission(context)
    }

    fun getMissingUnifiedPermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (!hasRecordAudioPermission(context)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!hasCameraPermission(context)) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return missing.toTypedArray()
    }

    // --- Premium Feature Permissions ---
    val CONTACTS_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    val PHONE_CALL_PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )

    val SMS_PERMISSIONS = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )

    fun hasContactsPermission(context: Context): Boolean {
        return CONTACTS_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasPhoneCallPermission(context: Context): Boolean {
        return PHONE_CALL_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasSmsPermission(context: Context): Boolean {
        return SMS_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun getWifiPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.toTypedArray()
    }

    fun hasWifiPermissions(context: Context): Boolean {
        return getWifiPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getMissingPremiumPermissions(context: Context): Array<String> {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission(context)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasContactsPermission(context)) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (!hasCameraPermission(context)) {
            missing.add(Manifest.permission.CAMERA)
        }
        if (!hasPhoneCallPermission(context)) {
            missing.add(Manifest.permission.CALL_PHONE)
        }
        if (!hasSmsPermission(context)) {
            missing.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        return missing.distinct().toTypedArray()
    }

    fun hasAllPremiumPermissions(context: Context): Boolean {
        return getMissingPremiumPermissions(context).isEmpty()
    }

    fun hasBatteryOptimizationPermission(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun requestBatteryOptimization(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open application details settings: ${e.message}")
        }
    }

    fun showVoicePermissionDialog(activity: Activity, onSettingsClick: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("মাইক্রোফোন ও নোটিফিকেশন পারমিশন")
            .setMessage("JARVIS-এর সাথে লাইভ কথা বলতে এবং নোটিফিকেশনে সার্ভিস বজায় রাখতে মাইক্রোফোন ও নোটিফিকেশন পারমিশন প্রয়োজন।")
            .setPositiveButton("সেটিংস খুলুন") { _, _ -> onSettingsClick() }
            .setNegativeButton("বাতিল", null)
            .show()
    }
}
