package com.jarvis.assistant.license.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityLicenseActivationBinding
import com.jarvis.assistant.databinding.ItemDeviceSlotBinding
import com.jarvis.assistant.license.LicenseManager
import com.jarvis.assistant.license.model.ActivationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LicenseActivationActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LicenseActivationActivity::class.java))
        }
    }

    private lateinit var binding: ActivityLicenseActivationBinding
    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private var countdownJob: Job? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        handleExit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupStatusCard()
        setupInputCard()
        observeLicenseState()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownJob?.cancel()
    }

    private fun setupToolbar() {
        binding.btnLicenseBack.setOnClickListener {
            handleExit()
        }
        binding.btnLicenseSkip.setOnClickListener {
            Toast.makeText(this, "বেসিক মোডে চলমান। পরবর্তীতে টপ বার বা মেনু থেকে প্রিমিয়াম আনলক করতে পারবেন।", Toast.LENGTH_SHORT).show()
            handleExit()
        }
    }

    private fun handleExit() {
        if (isTaskRoot) {
            startActivity(Intent(this, com.jarvis.assistant.ui.home.MainActivity::class.java))
        }
        finish()
    }

    private fun setupStatusCard() {
        binding.tvCurrentDeviceId.text = "Device ID: ${licenseManager.deviceId}"
        binding.btnCopyDeviceId.setOnClickListener {
            copyToClipboard("JARVIS Device ID", licenseManager.deviceId)
            Toast.makeText(this, "Device ID copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupInputCard() {
        binding.btnPasteKey.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                binding.etLicenseKey.setText(text)
                binding.etLicenseKey.setSelection(text.length)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnActivateLicense.setOnClickListener {
            val key = binding.etLicenseKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter a License Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performActivation(key)
        }

        binding.btnContactAdminWebsite.setOnClickListener {
            val devDetails = "Device ID: ${licenseManager.deviceId}\nBrand: ${Build.BRAND}\nModel: ${Build.MODEL}\nOS: Android ${Build.VERSION.RELEASE}\nSDK: ${Build.VERSION.SDK_INT}"
            copyToClipboard("JARVIS Device Info", devDetails)
            Toast.makeText(this, "ডিভাইস ডিটেইলস কপি করা হয়েছে! ওয়েবসাইট বা এডমিন যোগাযোগ খুলছে...", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://jarvis-nexus.web.app"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "ডিভাইস ডিটেইলস ক্লিপবোর্ডে কপি করা আছে।", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performActivation(key: String) {
        binding.btnActivateLicense.isEnabled = false
        binding.btnActivateLicense.text = ""
        binding.pbActivating.visibility = View.VISIBLE
        binding.cardResultFeedback.visibility = View.GONE

        lifecycleScope.launch {
            val result = licenseManager.activateLicense(key)

            binding.btnActivateLicense.isEnabled = true
            binding.btnActivateLicense.text = "ACTIVATE LICENSE KEY 🗝️"
            binding.pbActivating.visibility = View.GONE
            binding.cardResultFeedback.visibility = View.VISIBLE

            when (result) {
                is ActivationResult.Success -> {
                    binding.tvFeedbackTitle.text = "🎉 ACTIVATION SUCCESSFUL!"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#34D399"))
                    val expiryStr = if (result.expiresAt > 0L) {
                        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(result.expiresAt))
                    } else "Lifetime"
                    binding.tvFeedbackMessage.text = "License verified successfully!\nPlan Tier: ${result.tier.uppercase()}\nValid until: $expiryStr\nDevice slot: ${result.boundDevicesCount} / ${result.maxDevices}\n\nসব প্রিমিয়াম ফিচার আনলক করা হয়েছে।"
                    binding.etLicenseKey.setText("")
                    val missingPerms = com.jarvis.assistant.util.PermissionManager.getMissingPremiumPermissions(this@LicenseActivationActivity)
                    if (missingPerms.isNotEmpty()) {
                        Toast.makeText(this@LicenseActivationActivity, "🎉 Premium unlocked! প্রিমিয়াম পারমিশনগুলো এলাউ করুন।", Toast.LENGTH_SHORT).show()
                        delay(700L)
                        requestPermissionsLauncher.launch(missingPerms)
                    } else {
                        Toast.makeText(this@LicenseActivationActivity, "🎉 Premium unlocked! সব প্রিমিয়াম ফিচার সক্রিয়।", Toast.LENGTH_LONG).show()
                        delay(1200L)
                        handleExit()
                    }
                }
                is ActivationResult.InvalidKey -> {
                    binding.tvFeedbackTitle.text = "❌ INVALID LICENSE KEY"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvFeedbackMessage.text = "${result.message}\nসঠিক লাইসেন্স কি প্রবেশ করান।"
                }
                is ActivationResult.Revoked -> {
                    binding.tvFeedbackTitle.text = "🚫 LICENSE REVOKED"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvFeedbackMessage.text = "${result.message}\nএই লাইসেন্সটি অ্যাডমিন কর্তৃক বাতিল করা হয়েছে।"
                }
                is ActivationResult.Expired -> {
                    binding.tvFeedbackTitle.text = "⌛ LICENSE EXPIRED"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#F59E0B"))
                    binding.tvFeedbackMessage.text = "${result.message}\nলাইসেন্সের মেয়াদ শেষ হয়ে গেছে। অনুগ্রহ করে নতুন লাইসেন্স সংগ্রহ করুন।"
                }
                is ActivationResult.DeviceLimitReached -> {
                    binding.tvFeedbackTitle.text = "📱 DEVICE LIMIT EXCEEDED"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#F59E0B"))
                    binding.tvFeedbackMessage.text = "Maximum device limit reached (${result.maxDevices} devices allowed).\nঅন্য ডিভাইস থেকে আনবাইন্ড করতে অ্যাডমিনের সাথে যোগাযোগ করুন।"
                }
                is ActivationResult.OwnerMismatch -> {
                    binding.tvFeedbackTitle.text = "🔒 OWNER MISMATCH"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvFeedbackMessage.text = "${result.message}\nএই লাইসেন্সটি অন্য অ্যাকাউন্টে যুক্ত রয়েছে।"
                }
                is ActivationResult.NetworkError -> {
                    binding.tvFeedbackTitle.text = "⚠️ NETWORK / SERVER ERROR"
                    binding.tvFeedbackTitle.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvFeedbackMessage.text = "${result.message}\nইন্টারনেট সংযোগ চেক করে পুনরায় চেষ্টা করুন।"
                }
            }
        }
    }

    private fun observeLicenseState() {
        lifecycleScope.launch {
            licenseManager.currentTier.collect { tier ->
                when (tier.lowercase()) {
                    LicenseManager.TIER_MASTER -> {
                        binding.tvTierBadge.text = "🌟 MASTER TIER"
                        binding.tvTierBadge.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvActivePlanStatus.text = "ACTIVE PREMIUM"
                        binding.tvActivePlanStatus.setTextColor(Color.parseColor("#10B981"))
                    }
                    LicenseManager.TIER_PRO -> {
                        binding.tvTierBadge.text = "👑 PRO TIER"
                        binding.tvTierBadge.setTextColor(Color.parseColor("#A78BFA"))
                        binding.tvActivePlanStatus.text = "ACTIVE PREMIUM"
                        binding.tvActivePlanStatus.setTextColor(Color.parseColor("#10B981"))
                    }
                    else -> {
                        binding.tvTierBadge.text = "BASIC TIER"
                        binding.tvTierBadge.setTextColor(Color.parseColor("#9CA3AF"))
                        binding.tvActivePlanStatus.text = "FREE USER"
                        binding.tvActivePlanStatus.setTextColor(Color.parseColor("#6B7280"))
                    }
                }
            }
        }

        lifecycleScope.launch {
            licenseManager.activeLicenseKey.collect { key ->
                if (!key.isNullOrBlank()) {
                    binding.tvActiveKeyDisplay.text = "Key: $key"
                } else {
                    binding.tvActiveKeyDisplay.text = "No active license assigned"
                }
            }
        }

        lifecycleScope.launch {
            licenseManager.expiresAt.collect { expiry ->
                if (expiry > 0L) {
                    val formatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(expiry))
                    binding.tvExpiryDate.text = "Expires: $formatted"
                    binding.tvExpiryDate.visibility = View.VISIBLE
                    binding.cardLiveCountdown.visibility = View.VISIBLE
                } else {
                    binding.tvExpiryDate.text = "Expires: N/A"
                    binding.tvExpiryDate.visibility = View.GONE
                    binding.cardLiveCountdown.visibility = View.GONE
                }
                startLiveCountdownTicker(expiry)
            }
        }

        lifecycleScope.launch {
            licenseManager.boundDevicesCount.collect { (current, max) ->
                binding.tvBoundDevicesCount.text = "Devices: $current / $max active"
                binding.tvSlotsOverview.text = "$current / $max Slots"
                renderDeviceSlots(current, max)
            }
        }
    }

    private fun startLiveCountdownTicker(targetExpiresAt: Long) {
        countdownJob?.cancel()

        if (targetExpiresAt <= 0L) {
            binding.tvCountdownDays.text = "00"
            binding.tvCountdownHours.text = "00"
            binding.tvCountdownMins.text = "00"
            binding.tvCountdownSecs.text = "00"
            binding.tvCountdownStatusBadge.text = "INACTIVE"
            binding.tvCountdownStatusBadge.setTextColor(Color.parseColor("#6B7280"))
            return
        }

        countdownJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val diffMs = targetExpiresAt - now

                if (diffMs <= 0L) {
                    binding.tvCountdownDays.text = "00"
                    binding.tvCountdownHours.text = "00"
                    binding.tvCountdownMins.text = "00"
                    binding.tvCountdownSecs.text = "00"
                    binding.tvCountdownStatusBadge.text = "EXPIRED"
                    binding.tvCountdownStatusBadge.setTextColor(Color.parseColor("#EF4444"))

                    // Auto-lock and demote tier immediately
                    licenseManager.onLicenseExpired()
                    break
                }

                val totalSecs = diffMs / 1000
                val days = totalSecs / (24 * 3600)
                val hours = (totalSecs % (24 * 3600)) / 3600
                val mins = (totalSecs % 3600) / 60
                val secs = totalSecs % 60

                binding.tvCountdownDays.text = String.format(Locale.US, "%02d", days)
                binding.tvCountdownHours.text = String.format(Locale.US, "%02d", hours)
                binding.tvCountdownMins.text = String.format(Locale.US, "%02d", mins)
                binding.tvCountdownSecs.text = String.format(Locale.US, "%02d", secs)

                binding.tvCountdownStatusBadge.text = "ACTIVE"
                binding.tvCountdownStatusBadge.setTextColor(Color.parseColor("#10B981"))

                delay(1000)
            }
        }
    }

    private fun renderDeviceSlots(boundCount: Int, maxDevices: Int) {
        val container = binding.containerDeviceSlots
        container.removeAllViews()

        val safeMax = if (maxDevices <= 0) 1 else maxDevices
        val currentDevId = licenseManager.deviceId
        val isCurrentDeviceBound = boundCount > 0

        for (i in 1..safeMax) {
            val slotBinding = ItemDeviceSlotBinding.inflate(layoutInflater, container, false)

            if (i == 1) {
                slotBinding.tvSlotTitle.text = "Slot 1: This Device (${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL})"
                slotBinding.tvSlotDetails.text = "ID: ${currentDevId.take(18)}... • ${if (isCurrentDeviceBound) "Bound & Active" else "Ready to bind"}"
                if (isCurrentDeviceBound) {
                    slotBinding.tvSlotBadge.text = "BOUND"
                    slotBinding.tvSlotBadge.setTextColor(Color.parseColor("#34D399"))
                } else {
                    slotBinding.tvSlotBadge.text = "AVAILABLE"
                    slotBinding.tvSlotBadge.setTextColor(Color.parseColor("#9CA3AF"))
                }
            } else {
                if (i <= boundCount) {
                    slotBinding.tvSlotTitle.text = "Slot $i: Remote Device #$i"
                    slotBinding.tvSlotDetails.text = "Authorized device connected to account"
                    slotBinding.tvSlotBadge.text = "BOUND"
                    slotBinding.tvSlotBadge.setTextColor(Color.parseColor("#34D399"))
                } else {
                    slotBinding.tvSlotTitle.text = "Slot $i: Available Slot"
                    slotBinding.tvSlotDetails.text = "Can be bound to another phone or tablet"
                    slotBinding.tvSlotBadge.text = "FREE"
                    slotBinding.tvSlotBadge.setTextColor(Color.parseColor("#6B7280"))
                }
            }
            container.addView(slotBinding.root)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
