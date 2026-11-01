package com.jarvis.assistant.license.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivitySystemUpdateBinding
import com.jarvis.assistant.license.LicenseManager
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback

class SystemUpdateActivity : AppCompatActivity() {

    companion object {
        val CURRENT_VERSION_CODE = com.jarvis.assistant.BuildConfig.VERSION_CODE.toLong()
        val CURRENT_VERSION_NAME = com.jarvis.assistant.BuildConfig.VERSION_NAME

        fun start(context: Context) {
            context.startActivity(Intent(context, SystemUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }

    private lateinit var binding: ActivitySystemUpdateBinding
    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private var isForcedUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySystemUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        binding.tvCurrentVersion.text = "JARVIS NEXUS v$CURRENT_VERSION_NAME (Build $CURRENT_VERSION_CODE)"

        binding.btnCheckUpdates.setOnClickListener {
            checkForUpdates()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isForcedUpdate) {
                    Toast.makeText(this@SystemUpdateActivity, "Mandatory update required to continue.", Toast.LENGTH_SHORT).show()
                } else {
                    finish()
                }
            }
        })

        checkForUpdates()
    }

    private fun setupToolbar() {
        binding.btnUpdateBack.setOnClickListener { finish() }
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdates.isEnabled = false
        binding.tvUpdateStatusTitle.text = "Checking for updates..."
        binding.tvUpdateStatusTitle.setTextColor(Color.parseColor("#93C5FD"))
        binding.tvUpdateStatusDetails.text = "Connecting to Firebase OTA Distribution..."
        binding.ivUpdateIcon.setImageResource(R.drawable.ic_sparkle)
        binding.btnDownloadUpdate.visibility = View.GONE
        binding.layoutReleaseNotes.visibility = View.GONE

        lifecycleScope.launch {
            val update = licenseManager.checkAppUpdate()
            binding.btnCheckUpdates.isEnabled = true

            if (update != null && update.isActive && update.versionCode > CURRENT_VERSION_CODE) {
                isForcedUpdate = update.forceUpdate
                binding.tvUpdateStatusTitle.text = if (isForcedUpdate) "Mandatory Update Required!" else "New Update Available! (v${update.versionName})"
                binding.tvUpdateStatusTitle.setTextColor(if (isForcedUpdate) Color.parseColor("#EF4444") else Color.parseColor("#A78BFA"))
                val sizeMb = if (update.fileSize > 0) String.format("%.1f MB", update.fileSize / (1024f * 1024f)) else "N/A"
                binding.tvUpdateStatusDetails.text = "A new version of JARVIS NEXUS is ready for download.\nVersion: ${update.versionName} (Build ${update.versionCode})\nSize: $sizeMb"
                binding.ivUpdateIcon.setImageResource(if (isForcedUpdate) R.drawable.ic_settings_lock else R.drawable.ic_arrow_right)

                if (update.releaseNotes.isNotBlank()) {
                    binding.layoutReleaseNotes.visibility = View.VISIBLE
                    binding.tvReleaseNotes.text = update.releaseNotes
                }

                binding.btnDownloadUpdate.visibility = View.VISIBLE
                binding.btnDownloadUpdate.setOnClickListener {
                    if (update.downloadUrl.isNotBlank()) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@SystemUpdateActivity, "Unable to open download URL: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@SystemUpdateActivity, "Download URL is not configured by Admin", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                binding.tvUpdateStatusTitle.text = "Your system is up to date"
                binding.tvUpdateStatusTitle.setTextColor(Color.parseColor("#34D399"))
                binding.tvUpdateStatusDetails.text = "You are running the latest version of JARVIS NEXUS ($CURRENT_VERSION_NAME)."
                binding.ivUpdateIcon.setImageResource(R.drawable.ic_verified_badge)
            }
        }
    }
}
