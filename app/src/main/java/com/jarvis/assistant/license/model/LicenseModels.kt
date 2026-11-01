package com.jarvis.assistant.license.model

import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Data model strictly matching the Admin Control Center license contract.
 */
@IgnoreExtraProperties
data class LicenseRecord(
    val licenseKey: String = "",
    val tier: String = "basic", // "basic", "pro", "master"
    val userId: String? = null,
    val userEmail: String? = null,
    val isUsed: Boolean = false,
    val revoked: Boolean = false,
    val durationDays: Int = 30,
    val expiresAt: Long = 0L,
    val maxDevices: Int = 1,
    val boundDevices: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L
) {
    fun isExpired(): Boolean {
        return expiresAt > 0L && expiresAt < System.currentTimeMillis()
    }

    fun isValidForActivation(targetDeviceId: String, currentUid: String): Pair<Boolean, String> {
        if (revoked) return false to "REVOKED"
        if (isExpired()) return false to "EXPIRED"
        if (!userId.isNullOrBlank() && userId != currentUid) return false to "OWNER_MISMATCH"
        val alreadyBound = boundDevices.contains(targetDeviceId)
        if (!alreadyBound && boundDevices.size >= maxDevices) return false to "DEVICE_LIMIT_REACHED"
        return true to "OK"
    }
}

/**
 * Result states for license activation.
 */
sealed class ActivationResult {
    data class Success(val tier: String, val expiresAt: Long, val boundDevicesCount: Int, val maxDevices: Int) : ActivationResult()
    data class InvalidKey(val message: String) : ActivationResult()
    data class Revoked(val message: String) : ActivationResult()
    data class Expired(val message: String) : ActivationResult()
    data class DeviceLimitReached(val maxDevices: Int) : ActivationResult()
    data class OwnerMismatch(val message: String) : ActivationResult()
    data class NetworkError(val message: String) : ActivationResult()
}

/**
 * Remote APK update model matching Admin Panel `system_config/app_update`.
 */
@IgnoreExtraProperties
data class AppUpdateMetadata(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val downloadUrl: String = "",
    val fileSize: Long = 0L,
    val checksumSha256: String = "",
    val releaseNotes: String = "",
    val forceUpdate: Boolean = false,
    val publishedAt: Long = 0L,
    val isActive: Boolean = true
)

/**
 * Remote Maintenance configuration model matching Admin Panel `system_config/maintenance`.
 */
@IgnoreExtraProperties
data class MaintenanceConfig(
    val maintenanceEnabled: Boolean = false,
    val maintenanceTitle: String = "SYSTEM MAINTENANCE",
    val maintenanceMessage: String = "Systems are being upgraded by Admin. Please check back soon.",
    val startAt: Long = 0L,
    val endAt: Long = 0L,
    val estimatedEndAt: Long = 0L,
    val allowAdminBypass: Boolean = false,
    val updatedAt: Long = 0L
)
