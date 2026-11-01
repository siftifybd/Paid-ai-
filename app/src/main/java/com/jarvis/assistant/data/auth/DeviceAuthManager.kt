package com.jarvis.assistant.data.auth

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Handles device-ID based authentication for Jarvis.
 * Verifies if the current device is registered in Firestore 'devices' collection
 * and manages premium status based on server-side validation.
 */
class DeviceAuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DeviceAuthManager"
        private const val COLLECTION_DEVICES = "devices"

        @Volatile
        private var INSTANCE: DeviceAuthManager? = null

        fun getInstance(context: Context): DeviceAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val firestore = FirebaseFirestore.getInstance()

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _expiryDate = MutableStateFlow<Date?>(null)
    val expiryDate: StateFlow<Date?> = _expiryDate.asStateFlow()

    val deviceId: String by lazy {
        com.jarvis.assistant.license.LicenseManager.getInstance(context).deviceId
    }

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var licenseListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private val _licenseStatus = MutableStateFlow<String?>(null)
    val licenseStatus: StateFlow<String?> = _licenseStatus.asStateFlow()

    /**
     * Starts a real-time listener for the device's authorization status.
     * This is more robust than a one-time 'get' call as it handles offline/online
     * transitions automatically.
     */
    fun startAuthListener() {
        if (listenerRegistration != null) return
        
        Log.d(TAG, "Starting real-time auth listener for: $deviceId")
        listenerRegistration = firestore.collection(COLLECTION_DEVICES)
            .document(deviceId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Auth listener error: ${e.message}")
                    if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        _isAuthorized.value = false
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")?.lowercase() ?: "pending"
                    val tier = snapshot.getString("tier")?.lowercase() ?: "basic"
                    val expiresAt = snapshot.getLong("expiresAt") ?: 0L

                    _isPremium.value = (tier == "pro" || tier == "master")
                    _expiryDate.value = if (expiresAt > 0L) Date(expiresAt) else null

                    val now = System.currentTimeMillis()
                    val isExpired = expiresAt in 1L..now
                    
                    if (status == "active" && !isExpired) {
                        Log.i(TAG, "Device authorized in real-time. Tier: $tier")
                        _isAuthorized.value = true
                        
                        // Start secondary license listener if a key is present
                        val licenseKey = snapshot.getString("licenseKey")
                        if (!licenseKey.isNullOrEmpty()) {
                            startLicenseListener(licenseKey)
                        } else {
                            stopLicenseListener()
                        }
                    } else {
                        Log.w(TAG, "Device unauthorized or expired. Status: $status, Expired: $isExpired")
                        _isAuthorized.value = false
                        _isPremium.value = false
                        stopLicenseListener()
                    }
                } else {
                    Log.w(TAG, "Device document not found.")
                    _isAuthorized.value = false
                    stopLicenseListener()
                }
            }
    }

    private fun startLicenseListener(licenseKey: String) {
        if (licenseListenerRegistration != null) return
        Log.d(TAG, "Starting secondary license listener for: $licenseKey")
        licenseListenerRegistration = firestore.collection("licenses")
            .document(licenseKey)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) {
                    _licenseStatus.value = "invalid"
                    return@addSnapshotListener
                }
                
                val revoked = snapshot.getBoolean("revoked") ?: false
                if (revoked) {
                    Log.w(TAG, "License $licenseKey has been REVOKED. Revoking premium access.")
                    _isPremium.value = false
                    _isAuthorized.value = false // Block usage if license is revoked
                    _licenseStatus.value = "revoked"
                } else {
                    _licenseStatus.value = "active"
                }
            }
    }

    private fun stopLicenseListener() {
        licenseListenerRegistration?.remove()
        licenseListenerRegistration = null
    }

    fun stopAuthListener() {
        listenerRegistration?.remove()
        listenerRegistration = null
        stopLicenseListener()
    }

    /**
     * Performs a check and waits for the initial state if needed.
     * In 'silent' mode, we just trigger the listener and return.
     */
    suspend fun authenticateDevice(retryCount: Int = 5): Boolean {
        startAuthListener()
        
        // Give it a few seconds to try and get the first result
        var waitCount = 0
        while (waitCount < 10 && !_isAuthorized.value) {
            kotlinx.coroutines.delay(1000L)
            waitCount++
            if (_isAuthorized.value) break
        }
        
        // If we have network but it's still not authorized, we might be new or blocked
        if (!isNetworkAvailable() && waitCount >= 10) {
            Log.w(TAG, "Authentication timed out while offline. Proceeding with last known state.")
        }
        
        return _isAuthorized.value
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }
}
