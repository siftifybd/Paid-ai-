package com.jarvis.assistant.remote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.databinding.ActivityRemoteControlBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RemoteControlActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RemoteControlActivity::class.java))
        }
    }

    private lateinit var binding: ActivityRemoteControlBinding
    private lateinit var store: RemoteStore
    private lateinit var auth: RemoteAuth

    private val postNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            RemoteControlServer.start(this)
            Toast.makeText(this, "Remote Control Server started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission is required for foreground service", Toast.LENGTH_LONG).show()
        }
        updateServerUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = RemoteStore(this)
        auth = RemoteAuth(store)

        setupToolbar()
        setupAccessibilityCard()
        setupServerCard()
        setupSecurityCard()
        setupWanCard()
        setupTestStudio()

        observeStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityUi()
        updateServerUi()
        updatePinUi()
    }

    private fun setupToolbar() {
        binding.btnRemoteBack.setOnClickListener { finish() }
    }

    private fun setupAccessibilityCard() {
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
        updateAccessibilityUi()
    }

    private fun updateAccessibilityUi() {
        val enabled = JarvisAccessibilityService.isEnabled()
        if (enabled) {
            binding.tvAccessibilityStatus.text = "Accessibility: Enabled"
            binding.tvAccessibilityStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnEnableAccessibility.visibility = View.GONE
        } else {
            binding.tvAccessibilityStatus.text = "Accessibility: Disabled"
            binding.tvAccessibilityStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.btnEnableAccessibility.visibility = View.VISIBLE
        }
    }

    private fun setupServerCard() {
        binding.btnCopyConnectionInfo.setOnClickListener {
            val ip = RemoteControlServer.serverIp ?: "127.0.0.1"
            val port = RemoteControlServer.serverPort
            val fp = try { RemoteTls.fingerprintSha256() } catch (_: Exception) { "" }
            val devId = store.deviceId
            val configJson = """
                {
                  "deviceId": "$devId",
                  "lanUrl": "wss://$ip:$port",
                  "fingerprintSha256": "$fp",
                  "hasPinConfigured": ${auth.hasPin()}
                }
            """.trimIndent()
            copyToClipboard("Jarvis Controller Connection Config", configJson)
            Toast.makeText(this, "Controller connection config copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!auth.hasPin()) {
                    binding.switchServer.isChecked = false
                    Toast.makeText(this, "Please generate or set a PIN first!", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                if (!JarvisAccessibilityService.isEnabled()) {
                    Toast.makeText(this, "Accessibility is not enabled. Gestures will fail until enabled.", Toast.LENGTH_LONG).show()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@setOnCheckedChangeListener
                }
                RemoteControlServer.start(this)
                Toast.makeText(this, "Remote Control Server started", Toast.LENGTH_SHORT).show()
            } else {
                RemoteControlServer.stop(this)
                Toast.makeText(this, "Remote Control Server stopped", Toast.LENGTH_SHORT).show()
            }
            updateServerUi()
        }
        updateServerUi()
    }

    private fun updateServerUi() {
        val running = RemoteControlServer.isRunning
        val ip = RemoteControlServer.serverIp
        val port = RemoteControlServer.serverPort
        val activeClients = RemoteControlServer.activeConnectionsCount

        if (binding.switchServer.isChecked != running) {
            binding.switchServer.setOnCheckedChangeListener(null)
            binding.switchServer.isChecked = running
            setupServerCard()
        }
        if (running) {
            binding.tvServerStatus.text = "Server: Running"
            binding.tvServerStatus.setTextColor(Color.parseColor("#10B981"))
            val urlText = if (ip != null && port > 0) "wss://$ip:$port" else "Listening on LAN..."
            binding.tvServerDetails.text = "URL: $urlText\nActive on LAN (TLS WebSocket) + NSD discovery.\nTap Stop in notification or switch off above to disable."
            binding.tvActiveClients.visibility = View.VISIBLE
            binding.tvActiveClients.text = "Active Connections: $activeClients / ${Proto.MAX_CONNECTIONS}"
            binding.btnCopyConnectionInfo.visibility = View.VISIBLE
        } else {
            binding.tvServerStatus.text = "Server: Stopped"
            binding.tvServerStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.tvServerDetails.text = "LAN TLS WebSocket Server + NSD (mDNS) discovery."
            binding.tvActiveClients.visibility = View.GONE
            binding.btnCopyConnectionInfo.visibility = View.GONE
        }
    }

    private fun setupSecurityCard() {
        updatePinUi()

        binding.btnGeneratePin.setOnClickListener {
            val pin = auth.newRandomPin()
            binding.tvActivePin.visibility = View.VISIBLE
            binding.tvActivePin.text = "PIN: $pin"
            copyToClipboard("Jarvis Remote PIN", pin)
            AlertDialog.Builder(this)
                .setTitle("New 8-Digit PIN Generated")
                .setMessage("Your new Remote Control PIN is:\n\n$pin\n\n(Copied to clipboard. Provide this to your controller).")
                .setPositiveButton("OK", null)
                .show()
            updatePinUi()
        }

        binding.btnSetCustomPin.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Enter 6-12 digit PIN"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this)
                .setTitle("Set Custom PIN")
                .setMessage("Enter a secure 6-12 digit numeric PIN:")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val text = input.text.toString().trim()
                    try {
                        auth.setPin(text)
                        binding.tvActivePin.visibility = View.VISIBLE
                        binding.tvActivePin.text = "PIN: $text"
                        Toast.makeText(this, "Custom PIN set successfully", Toast.LENGTH_SHORT).show()
                        updatePinUi()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "Invalid PIN format", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val fp = try {
            RemoteTls.fingerprintSha256()
        } catch (e: Exception) {
            "Unavailable: ${e.message}"
        }
        binding.tvFingerprint.text = fp

        binding.btnCopyFingerprint.setOnClickListener {
            copyToClipboard("Jarvis TLS Fingerprint", binding.tvFingerprint.text.toString())
            Toast.makeText(this, "TLS Fingerprint copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePinUi() {
        if (auth.hasPin()) {
            binding.tvPinStatus.text = "PIN Status: Configured & Ready"
            binding.tvPinStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvPinStatus.text = "PIN Status: Not Set (Required to start)"
            binding.tvPinStatus.setTextColor(Color.parseColor("#EF4444"))
        }
    }

    private fun setupWanCard() {
        binding.switchWan.isChecked = store.wanEnabled
        binding.layoutWanConfig.visibility = if (store.wanEnabled) View.VISIBLE else View.GONE
        binding.etRelayUrl.setText(store.relayUrl ?: "")
        binding.etRelayToken.setText(store.relayToken ?: "")
        binding.tvPairingKey.text = store.ensurePairingKey()

        binding.switchWan.setOnCheckedChangeListener { _, isChecked ->
            store.wanEnabled = isChecked
            binding.layoutWanConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (RemoteControlServer.isRunning) {
                // Restart server to apply WAN relay settings
                RemoteControlServer.start(this)
            }
        }

        binding.btnSaveRelayConfig.setOnClickListener {
            val url = binding.etRelayUrl.text.toString().trim()
            val token = binding.etRelayToken.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("wss://")) {
                Toast.makeText(this, "Relay URL must start with wss://", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            store.relayUrl = url.ifEmpty { null }
            store.relayToken = token.ifEmpty { null }
            Toast.makeText(this, "Relay settings saved", Toast.LENGTH_SHORT).show()
            if (RemoteControlServer.isRunning && store.wanEnabled) {
                RemoteControlServer.start(this)
            }
        }

        binding.btnCopyPairingKey.setOnClickListener {
            copyToClipboard("Jarvis WAN Pairing Key", binding.tvPairingKey.text.toString())
            Toast.makeText(this, "Pairing key copied", Toast.LENGTH_SHORT).show()
        }

        binding.btnRotatePairingKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Rotate WAN Pairing Key?")
                .setMessage("Rotating the key will disconnect all existing WAN controllers until they import the new key.")
                .setPositiveButton("Rotate") { _, _ ->
                    val newKey = store.rotatePairingKey()
                    binding.tvPairingKey.text = newKey
                    Toast.makeText(this, "Pairing key rotated", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun setupTestStudio() {
        binding.btnTestTapCenter.setOnClickListener {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Accessibility Service is NOT enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val (w, h) = svc.screenSize()
                val ok = svc.remoteTap(w / 2f, h / 2f)
                Toast.makeText(this@RemoteControlActivity, if (ok) "Tap center executed!" else "Tap failed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestHome.setOnClickListener {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Accessibility Service is NOT enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = svc.remoteKey("home")
            Toast.makeText(this, if (ok) "Home key executed!" else "Home key failed", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestRecents.setOnClickListener {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Accessibility Service is NOT enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = svc.remoteKey("recents")
            Toast.makeText(this, if (ok) "Recents key executed!" else "Recents key failed", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestSwipe.setOnClickListener {
            val svc = JarvisAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Accessibility Service is NOT enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = svc.remoteScroll("up", 300)
                Toast.makeText(this@RemoteControlActivity, if (ok) "Swipe up executed!" else "Swipe failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            while (isActive) {
                updateAccessibilityUi()
                updateServerUi()
                delay(2000)
            }
        }
    }
}
