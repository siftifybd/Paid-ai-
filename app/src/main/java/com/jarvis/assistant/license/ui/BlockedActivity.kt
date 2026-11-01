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

/**
 * Full-screen lock for blocked users.
 * Reuses the maintenance layout but with a 'BLOCKED' theme.
 */
class BlockedActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BlockedActivity::class.java).apply {
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

        // Force 'Blocked' UI mode
        binding.tvMaintenanceTitle.text = "ACCESS DENIED"
        binding.tvMaintenanceTitle.setTextColor(android.graphics.Color.parseColor("#EF4444")) // Red
        binding.tvMaintenanceMessage.text = "Your account has been blocked by system administrators. All neural features are disabled."
        binding.tvMaintenanceEta.text = "Reason: Security Violation or License Abuse"
        binding.btnRefreshMaintenance.text = "CONTACT SUPPORT"

        // Block exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@BlockedActivity, "Account is blocked", Toast.LENGTH_SHORT).show()
            }
        })

        startReactorRingAnimation()
        setupListeners()
        observeBlockState()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringAnimator?.cancel()
    }

    private fun startReactorRingAnimation() {
        ringAnimator = ObjectAnimator.ofFloat(binding.ivReactorRing, "rotation", 0f, 360f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun setupListeners() {
        binding.btnRefreshMaintenance.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://jarvis-nexus.web.app/support")))
            } catch (_: Exception) {}
        }
    }

    private fun observeBlockState() {
        lifecycleScope.launch {
            licenseManager.isUserBlocked.collect { blocked ->
                if (!blocked) {
                    finish()
                }
            }
        }
    }
}
