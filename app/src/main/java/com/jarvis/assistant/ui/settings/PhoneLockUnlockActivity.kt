package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.phone.UnlockPromptActivity
import com.jarvis.assistant.security.PhoneLockController

class PhoneLockUnlockActivity : AppCompatActivity() {

    private lateinit var controller: PhoneLockController
    private lateinit var adminHelper: com.jarvis.assistant.security.DeviceAdminPermissionHelper
    private lateinit var tvAdminStatusPill: TextView
    private lateinit var btnEnableAdmin: Button
    private lateinit var btnTestLock: Button
    private lateinit var btnTestUnlock: Button
    private lateinit var tvActionOutput: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var tvAuditHistory: TextView
    private lateinit var btnClearAudit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_lock_unlock)

        controller = PhoneLockController(this)
        adminHelper = com.jarvis.assistant.security.DeviceAdminPermissionHelper.getInstance(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        tvAdminStatusPill = findViewById(R.id.tvAdminStatusPill)
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
        btnTestLock = findViewById(R.id.btnTestLock)
        btnTestUnlock = findViewById(R.id.btnTestUnlock)
        tvActionOutput = findViewById(R.id.tvActionOutput)
        tvMicStatus = findViewById(R.id.tvMicStatus)
        tvAuditHistory = findViewById(R.id.tvAuditHistory)
        btnClearAudit = findViewById(R.id.btnClearAudit)

        btnEnableAdmin.setOnClickListener {
            if (adminHelper.isDeviceAdminActive()) {
                Toast.makeText(this, "Device Admin is already active.", Toast.LENGTH_SHORT).show()
            } else {
                adminHelper.launchActivationPrompt(this)
            }
        }

        btnTestLock.setOnClickListener {
            val isPremium = com.jarvis.assistant.license.LicenseManager.getInstance(this).isPremium()
            if (!isPremium) {
                tvActionOutput.text = "🔒 ফোন লক ফিচারটি প্রিমিয়াম ব্যবহারকারীদের জন্য সংরক্ষিত। অনুগ্রহ করে লাইসেন্স সক্রিয় করুন।"
                tvActionOutput.setTextColor(Color.parseColor("#F59E0B"))
                Toast.makeText(this, "🔒 Premium Required: ফোন লক ফিচার আনলক করতে লাইসেন্স দিন।", Toast.LENGTH_LONG).show()
                com.jarvis.assistant.license.ui.LicenseActivationActivity.start(this)
                return@setOnClickListener
            }

            if (!adminHelper.isDeviceAdminActive()) {
                tvActionOutput.text = "Error: Device Admin permission not granted. Please activate admin first."
                tvActionOutput.setTextColor(Color.parseColor("#FF5252"))
                adminHelper.launchActivationPrompt(this)
                return@setOnClickListener
            }

            val result = adminHelper.lockScreen()
            if (result.isSuccess) {
                tvActionOutput.text = "Lock request successfully transmitted to DevicePolicyManager."
                tvActionOutput.setTextColor(Color.parseColor("#00E5FF"))
            } else {
                tvActionOutput.text = "Failed to lock device: ${result.exceptionOrNull()?.message}"
                tvActionOutput.setTextColor(Color.parseColor("#FF5252"))
            }
            refreshAuditHistory()
        }

        btnTestUnlock.setOnClickListener {
            val isPremium = com.jarvis.assistant.license.LicenseManager.getInstance(this).isPremium()
            if (!isPremium) {
                tvActionOutput.text = "🔒 আনলক স্ক্রিন টুলটি প্রিমিয়াম ব্যবহারকারীদের জন্য সংরক্ষিত। অনুগ্রহ করে লাইসেন্স সক্রিয় করুন।"
                tvActionOutput.setTextColor(Color.parseColor("#F59E0B"))
                Toast.makeText(this, "🔒 Premium Required: আনলক স্ক্রিন ফিচার আনলক করতে লাইসেন্স দিন।", Toast.LENGTH_LONG).show()
                com.jarvis.assistant.license.ui.LicenseActivationActivity.start(this)
                return@setOnClickListener
            }

            tvActionOutput.text = "Launching system keyguard dismissal prompt..."
            tvActionOutput.setTextColor(Color.parseColor("#00E5FF"))
            val intent = Intent(this, UnlockPromptActivity::class.java)
            startActivity(intent)
        }

        btnClearAudit.setOnClickListener {
            getSharedPreferences(PhoneLockController.PREFS_AUDIT, Context.MODE_PRIVATE)
                .edit().clear().apply()
            refreshAuditHistory()
            Toast.makeText(this, "Audit trail cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        refreshAuditHistory()
    }

    private fun updateUiState() {
        val isAdminActive = adminHelper.isDeviceAdminActive()
        if (isAdminActive) {
            tvAdminStatusPill.text = "ACTIVE"
            tvAdminStatusPill.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            btnEnableAdmin.text = "DEVICE ADMIN ENABLED"
            btnEnableAdmin.isEnabled = false
            btnEnableAdmin.alpha = 0.6f
        } else {
            tvAdminStatusPill.text = "INACTIVE"
            tvAdminStatusPill.setBackgroundResource(R.drawable.bg_jarvis_row_item)
            btnEnableAdmin.text = "ACTIVATE DEVICE ADMIN"
            btnEnableAdmin.isEnabled = true
            btnEnableAdmin.alpha = 1.0f
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            tvMicStatus.text = "MIC: ACTIVE"
            tvMicStatus.setTextColor(Color.parseColor("#00E5FF"))
        } else {
            tvMicStatus.text = "MIC: NEED PERMISSION"
            tvMicStatus.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun refreshAuditHistory() {
        val history = PhoneLockController.getAuditHistory(this)
        if (history.isEmpty()) {
            tvAuditHistory.text = "No lock/unlock actions recorded yet."
        } else {
            tvAuditHistory.text = history.take(8).joinToString("\n\n")
        }
    }
}
