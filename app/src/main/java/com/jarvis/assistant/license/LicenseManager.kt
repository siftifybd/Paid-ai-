package com.jarvis.assistant.license

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.jarvis.assistant.license.model.ActivationResult
import com.jarvis.assistant.license.model.AppUpdateMetadata
import com.jarvis.assistant.license.model.LicenseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Central License & Entitlement Manager for JARVIS NEXUS.
 * Synchronizes license activation, tier entitlements, device binding, and updates with Firebase.
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LicenseManager"
        private const val PREFS_FILE = "jarvis_license_prefs_secure"
        private const val KEY_DEVICE_ID = "app_scoped_device_id"
        private const val KEY_TIER = "cached_tier"
        private const val KEY_LICENSE_KEY = "cached_license_key"
        private const val KEY_EXPIRES_AT = "cached_expires_at"
        private const val KEY_STATUS = "cached_status"
        private const val KEY_DEVICE_ACTIVE = "cached_device_active"
        private const val KEY_DEVICE_STATUS = "cached_device_status"

        const val TIER_BASIC = "basic"
        const val TIER_PRO = "pro"
        const val TIER_MASTER = "master"

        @Volatile
        private var INSTANCE: LicenseManager? = null

        fun getInstance(context: Context): LicenseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LicenseManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isPremium(context: Context): Boolean {
            return getInstance(context).isPremium()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val deviceId: String by lazy { resolveDeviceId() }

    private val _isDeviceActiveInFirestore = MutableStateFlow(securePrefs.getBoolean(KEY_DEVICE_ACTIVE, false))
    val isDeviceActiveInFirestore: StateFlow<Boolean> = _isDeviceActiveInFirestore.asStateFlow()

    private val _deviceCloudStatus = MutableStateFlow(securePrefs.getString(KEY_DEVICE_STATUS, "pending") ?: "pending")
    val deviceCloudStatus: StateFlow<String> = _deviceCloudStatus.asStateFlow()

    private val _currentTier = MutableStateFlow(securePrefs.getString(KEY_TIER, TIER_BASIC) ?: TIER_BASIC)
    val currentTier: StateFlow<String> = _currentTier.asStateFlow()

    private val _isPremiumActive = MutableStateFlow(
        checkIsPremiumActive(securePrefs.getString(KEY_TIER, TIER_BASIC) ?: TIER_BASIC) &&
        (securePrefs.getLong(KEY_EXPIRES_AT, 0L) == 0L || securePrefs.getLong(KEY_EXPIRES_AT, 0L) > System.currentTimeMillis())
    )
    val isPremiumActive: StateFlow<Boolean> = _isPremiumActive.asStateFlow()

    private val _activeLicenseKey = MutableStateFlow(securePrefs.getString(KEY_LICENSE_KEY, null))
    val activeLicenseKey: StateFlow<String?> = _activeLicenseKey.asStateFlow()

    private val _expiresAt = MutableStateFlow(securePrefs.getLong(KEY_EXPIRES_AT, 0L))
    val expiresAt: StateFlow<Long> = _expiresAt.asStateFlow()

    private val _isUserBlocked = MutableStateFlow(false)
    val isUserBlocked: StateFlow<Boolean> = _isUserBlocked.asStateFlow()

    private val _boundDevicesCount = MutableStateFlow(0 to 1)
    val boundDevicesCount: StateFlow<Pair<Int, Int>> = _boundDevicesCount.asStateFlow()

    private val _isMaintenanceActive = MutableStateFlow(false)
    val isMaintenanceActive: StateFlow<Boolean> = _isMaintenanceActive.asStateFlow()

    private val _maintenanceConfig = MutableStateFlow<com.jarvis.assistant.license.model.MaintenanceConfig?>(null)
    val maintenanceConfig: StateFlow<com.jarvis.assistant.license.model.MaintenanceConfig?> = _maintenanceConfig.asStateFlow()

    private var deviceListener: ListenerRegistration? = null
    private var licenseListener: ListenerRegistration? = null
    private var userListener: ListenerRegistration? = null
    private var maintenanceListener: ListenerRegistration? = null

    init {
        scope.launch {
            ensureAuthSession()
            syncDeviceTelemetry()
            attachRealtimeSync()
        }
    }

    suspend fun syncDeviceTelemetry() = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection("devices").document(deviceId)
            val snapshot = docRef.get().await()

            val deviceData = hashMapOf<String, Any>(
                "deviceId" to deviceId,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "brand" to Build.BRAND,
                "osVersion" to Build.VERSION.RELEASE,
                "sdkVersion" to Build.VERSION.SDK_INT,
                "lastSeenAt" to System.currentTimeMillis()
            )
            if (!snapshot.exists()) {
                deviceData["status"] = "pending"
                deviceData["tier"] = "basic"
                deviceData["isPremium"] = false
            }
            docRef.set(deviceData, SetOptions.merge()).await()

            if (snapshot.exists()) {
                updateFirestoreDeviceStatus(
                    snapshot.getString("status") ?: "pending",
                    snapshot.getString("tier"),
                    snapshot.getLong("expiresAt"),
                    snapshot.getString("licenseKey") ?: snapshot.getString("activeLicenseKey")
                )
            }
            Log.d(TAG, "Device telemetry successfully synced to Firebase: $deviceId")
        } catch (e: Exception) {
            Log.w(TAG, "Device telemetry sync deferred (offline/error): ${e.message}")
        }
    }

    suspend fun checkDeviceActiveInFirestore(): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("devices").document(deviceId).get().await()
            if (doc.exists()) {
                val status = doc.getString("status")?.trim() ?: "pending"
                val tier = doc.getString("tier")
                val expiresAt = doc.getLong("expiresAt")
                val key = doc.getString("licenseKey") ?: doc.getString("activeLicenseKey")
                updateFirestoreDeviceStatus(status, tier, expiresAt, key)
                status.equals("active", ignoreCase = true)
            } else {
                _isDeviceActiveInFirestore.value = false
                _deviceCloudStatus.value = "pending"
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkDeviceActiveInFirestore error: ${e.message}")
            _isDeviceActiveInFirestore.value
        }
    }

    fun updateFirestoreDeviceStatus(status: String, tier: String? = null, expiresAt: Long? = null, key: String? = null) {
        val normalizedStatus = status.trim().lowercase()
        val isActive = (normalizedStatus == "active")
        _deviceCloudStatus.value = normalizedStatus
        _isDeviceActiveInFirestore.value = isActive
        
        securePrefs.edit()
            .putBoolean(KEY_DEVICE_ACTIVE, isActive)
            .putString(KEY_DEVICE_STATUS, normalizedStatus)
            .apply()

        if (isActive) {
            val now = System.currentTimeMillis()
            val serverExpiry = expiresAt ?: 0L
            val isNotExpired = serverExpiry == 0L || serverExpiry > now
            
            if (isNotExpired && tier != null && tier.lowercase() != TIER_BASIC) {
                _isPremiumActive.value = true
                applyTierLocally(tier.lowercase().trim(), key ?: _activeLicenseKey.value ?: "SERVER_SYNC", serverExpiry)
            } else {
                Log.w(TAG, "Device marked active but tier is missing, basic, or expired. Demoting.")
                _isPremiumActive.value = false
                applyTierLocally(TIER_BASIC, null, 0L)
            }
        } else if (normalizedStatus == "revoked" || normalizedStatus == "blocked") {
            Log.w(TAG, "Device status is $normalizedStatus. Demoting locally.")
            _isPremiumActive.value = false
            applyTierLocally(TIER_BASIC, null, 0L)
        }
    }

    private fun resolveDeviceId(): String {
        val existing = securePrefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newId = "nexus_dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        securePrefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    suspend fun ensureAuthSession(): String {
        return withContext(Dispatchers.IO) {
            val current = auth.currentUser
            if (current != null) {
                current.uid
            } else {
                try {
                    val result = auth.signInAnonymously().await()
                    result.user?.uid ?: "anonymous_${deviceId.take(8)}"
                } catch (e: Exception) {
                    Log.w(TAG, "Anonymous auth failed (offline or unconfigured): ${e.message}")
                    "guest_${deviceId.take(8)}"
                }
            }
        }
    }

    /**
     * Activates a license key with atomic Firestore transaction and checks device binding.
     */
    suspend fun activateLicense(rawKey: String): ActivationResult = withContext(Dispatchers.IO) {
        val cleanKey = rawKey.trim().uppercase()
        if (cleanKey.isBlank() || cleanKey.length < 4) {
            return@withContext ActivationResult.InvalidKey("Please enter a valid License Key.")
        }

        try {
            val uid = ensureAuthSession()
            val licenseDocRef = firestore.collection("licenses").document(cleanKey)
            val userDocRef = firestore.collection("users").document(uid)
            val deviceDocRef = firestore.collection("devices").document(deviceId)

            val result = firestore.runTransaction { transaction ->
                val userSnap = transaction.get(userDocRef)
                if (userSnap.exists() && userSnap.getString("status") == "blocked") {
                    return@runTransaction ActivationResult.Revoked("Your account is blocked. Activation denied.")
                }

                val snapshot = transaction.get(licenseDocRef)
                if (!snapshot.exists()) {
                    return@runTransaction ActivationResult.InvalidKey("License key does not exist.")
                }

                val record = snapshot.toObject(LicenseRecord::class.java)
                    ?: return@runTransaction ActivationResult.InvalidKey("Invalid license structure.")

                if (record.revoked) {
                    return@runTransaction ActivationResult.Revoked("This license has been revoked by Admin.")
                }

                val now = System.currentTimeMillis()
                if (record.expiresAt > 0L && record.expiresAt < now) {
                    return@runTransaction ActivationResult.Expired("This license has expired.")
                }

                if (!record.userId.isNullOrBlank() && record.userId != uid) {
                    return@runTransaction ActivationResult.OwnerMismatch("This license is assigned to another user account.")
                }

                val currentBound = record.boundDevices.toMutableList()
                val alreadyBound = currentBound.contains(deviceId)

                if (!alreadyBound && currentBound.size >= record.maxDevices) {
                    return@runTransaction ActivationResult.DeviceLimitReached(record.maxDevices)
                }

                if (!alreadyBound) {
                    currentBound.add(deviceId)
                }

                // Compute expiration timestamp if not set
                val calculatedExpiry = if (record.expiresAt > 0L) {
                    record.expiresAt
                } else {
                    now + (record.durationDays.coerceAtLeast(1) * 86_400_000L)
                }

                val normalizedTier = record.tier.lowercase().trim()

                // 1. Update license record
                transaction.update(
                    licenseDocRef,
                    mapOf(
                        "userId" to uid,
                        "isUsed" to true,
                        "boundDevices" to currentBound,
                        "expiresAt" to calculatedExpiry,
                        "lastUsedAt" to now
                    )
                )

                // 2. user record — doc may not exist: use set+merge (NOT update). Never write "status".
                transaction.set(
                    userDocRef,
                    mapOf(
                        "uid" to uid,
                        "tier" to normalizedTier,
                        "activeLicenseKey" to cleanKey,
                        "licenseExpiresAt" to calculatedExpiry,
                        "lastSeenAt" to now
                    ),
                    SetOptions.merge()
                )

                // 3. device record — write tier/isPremium/expiresAt too, otherwise the device listener demotes to basic
                transaction.set(
                    deviceDocRef,
                    mapOf(
                        "deviceId" to deviceId,
                        "userId" to uid,
                        "licenseKey" to cleanKey,
                        "tier" to normalizedTier,
                        "isPremium" to true,
                        "expiresAt" to calculatedExpiry,
                        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "appVersion" to com.jarvis.assistant.BuildConfig.VERSION_NAME,
                        "platform" to "android",
                        "status" to "active",
                        "lastSeenAt" to now
                    ),
                    SetOptions.merge()
                )

                ActivationResult.Success(
                    tier = normalizedTier,
                    expiresAt = calculatedExpiry,
                    boundDevicesCount = currentBound.size,
                    maxDevices = record.maxDevices
                )
            }.await()

            if (result is ActivationResult.Success) {
                applyTierLocally(result.tier, cleanKey, result.expiresAt)
                _boundDevicesCount.value = result.boundDevicesCount to result.maxDevices
                updateFirestoreDeviceStatus("active", result.tier, result.expiresAt, cleanKey)
                attachRealtimeSync()
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "License activation error: ${e.message}", e)
            ActivationResult.NetworkError("Network / Server error: ${e.localizedMessage ?: "Failed to connect"}")
        }
    }

    private fun applyTierLocally(tier: String, key: String?, expiry: Long) {
        val normTier = tier.lowercase().trim()
        val isPrem = checkIsPremiumActive(normTier)

        securePrefs.edit()
            .putString(KEY_TIER, normTier)
            .putString(KEY_LICENSE_KEY, key)
            .putLong(KEY_EXPIRES_AT, expiry)
            .apply()

        _currentTier.value = normTier
        _isPremiumActive.value = isPrem
        _activeLicenseKey.value = key
        _expiresAt.value = expiry

        if (isPrem && expiry > System.currentTimeMillis()) {
            com.jarvis.assistant.license.notification.LicenseExpiryNotifier.scheduleExpiryAlerts(context, expiry)
        } else {
            com.jarvis.assistant.license.notification.LicenseExpiryNotifier.cancelExpiryAlerts(context)
        }
    }

    fun onLicenseExpired() {
        Log.w(TAG, "License expiration triggered. Demoting tier to basic.")
        applyTierLocally(TIER_BASIC, null, 0L)
    }

    private fun checkIsPremiumActive(tier: String): Boolean {
        return tier.equals(TIER_PRO, ignoreCase = true) || tier.equals(TIER_MASTER, ignoreCase = true)
    }

    /**
     * Central entitlement verification method.
     * Returns true ONLY if device has an active, valid, non-expired PRO or MASTER tier license.
     * In Basic mode, returns false (restricting all phone control and automation).
     */
    fun isPremium(): Boolean {
        val now = System.currentTimeMillis()
        val exp = _expiresAt.value
        val hasNotExpired = (exp == 0L || exp > now)
        val tier = _currentTier.value.lowercase().trim()
        val isTierPremium = tier == TIER_PRO || tier == TIER_MASTER
        return (_isPremiumActive.value || isTierPremium) && hasNotExpired && tier != TIER_BASIC
    }

    /**
     * Attaches real-time Firestore listeners for device status, license, user blocking, and maintenance.
     */
    fun attachRealtimeSync() {
        val activeKey = _activeLicenseKey.value
        val uid = auth.currentUser?.uid

        deviceListener?.remove()
        licenseListener?.remove()
        userListener?.remove()
        maintenanceListener?.remove()

        // 0. Listen to device document status in real-time
        deviceListener = firestore.collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (snapshot.exists()) {
                    val status = snapshot.getString("status") ?: "pending"
                    val tier = snapshot.getString("tier")
                    val expiresAt = snapshot.getLong("expiresAt")
                    val key = snapshot.getString("licenseKey") ?: snapshot.getString("activeLicenseKey")
                    updateFirestoreDeviceStatus(status, tier, expiresAt, key)
                } else {
                    _isDeviceActiveInFirestore.value = false
                    _deviceCloudStatus.value = "pending"
                }
            }

        if (!activeKey.isNullOrBlank()) {
            licenseListener = firestore.collection("licenses").document(activeKey)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val record = snapshot.toObject(LicenseRecord::class.java) ?: return@addSnapshotListener

                    if (record.revoked || record.isExpired()) {
                        Log.w(TAG, "Active license was revoked or expired on server")
                        applyTierLocally(TIER_BASIC, null, 0L)
                    } else {
                        _boundDevicesCount.value = record.boundDevices.size to record.maxDevices
                        _expiresAt.value = record.expiresAt
                        if (record.expiresAt > System.currentTimeMillis()) {
                            com.jarvis.assistant.license.notification.LicenseExpiryNotifier.scheduleExpiryAlerts(context, record.expiresAt)
                        }
                    }
                }
        }

        if (!uid.isNullOrBlank()) {
            userListener = firestore.collection("users").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val status = snapshot.getString("status") ?: "active"
                    _isUserBlocked.value = status.equals("blocked", ignoreCase = true)
                    if (_isUserBlocked.value) {
                        applyTierLocally(TIER_BASIC, null, 0L)
                    }
                }
        }

        maintenanceListener = firestore.collection("system_config").document("maintenance")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    _isMaintenanceActive.value = false
                    _maintenanceConfig.value = null
                    return@addSnapshotListener
                }
                val config = snapshot.toObject(com.jarvis.assistant.license.model.MaintenanceConfig::class.java)
                _maintenanceConfig.value = config
                
                if (config == null || !config.maintenanceEnabled) {
                    _isMaintenanceActive.value = false
                } else {
                    val now = System.currentTimeMillis()
                    val afterStart = config.startAt == 0L || now >= config.startAt
                    val beforeEnd = config.endAt == 0L || now < config.endAt
                    _isMaintenanceActive.value = afterStart && beforeEnd
                }
            }
    }

    /**
     * Checks for remote app updates from Firestore `system_config/app_update`.
     */
    suspend fun checkAppUpdate(): AppUpdateMetadata? = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("system_config").document("app_update").get().await()
            if (doc.exists()) {
                doc.toObject(AppUpdateMetadata::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkAppUpdate error: ${e.message}")
            null
        }
    }
}
