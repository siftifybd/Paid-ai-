package com.jarvis.assistant.license.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityMaintenanceBinding
import com.jarvis.assistant.license.LicenseManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MaintenanceActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MaintenanceActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityMaintenanceBinding
    private val licenseManager by lazy { LicenseManager.getInstance(this) }
    private var ringAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaintenanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Block exiting while maintenance is active
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@MaintenanceActivity, "System is undergoing maintenance", Toast.LENGTH_SHORT).show()
            }
        })

        startReactorRingAnimation()
        setupListeners()
        observeMaintenanceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringAnimator?.cancel()
    }

    private fun startReactorRingAnimation() {
        ringAnimator = ObjectAnimator.ofFloat(binding.ivReactorRing, "rotation", 0f, 360f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun setupListeners() {
        binding.btnRefreshMaintenance.setOnClickListener {
            Toast.makeText(this, "Checking server status...", Toast.LENGTH_SHORT).show()
            licenseManager.attachRealtimeSync()
            if (!licenseManager.isMaintenanceActive.value) {
                Toast.makeText(this, "Maintenance finished! Resuming system...", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun observeMaintenanceState() {
        lifecycleScope.launch {
            licenseManager.isMaintenanceActive.collect { active ->
                if (!active) {
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            licenseManager.maintenanceConfig.collect { config ->
                if (config != null) {
                    if (config.maintenanceTitle.isNotBlank()) {
                        binding.tvMaintenanceTitle.text = config.maintenanceTitle
                    }
                    if (config.maintenanceMessage.isNotBlank()) {
                        binding.tvMaintenanceMessage.text = config.maintenanceMessage
                    }
                    val end = if (config.estimatedEndAt > 0L) config.estimatedEndAt else config.endAt
                    if (end > 0L) {
                        val formatted = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(end))
                        binding.tvMaintenanceEta.text = "Estimated Completion: $formatted"
                    } else {
                        binding.tvMaintenanceEta.text = "Estimated Completion: To be announced by Admin"
                    }
                }
            }
        }
    }
}
