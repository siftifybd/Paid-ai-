package com.jarvis.assistant.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.Signature

/** Encrypted settings for remote control. Nothing here is ever logged. */
class RemoteStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, "jarvis_remote_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val deviceId: String
        get() = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    var pinHash: String?
        get() = prefs.getString("pin_hash", null)
        set(v) = prefs.edit().putString("pin_hash", v).apply()

    var pinSalt: String?
        get() = prefs.getString("pin_salt", null)
        set(v) = prefs.edit().putString("pin_salt", v).apply()

    /** 0 = pick a free port (advertised through NSD/UDP). */
    var lanPort: Int
        get() = prefs.getInt("lan_port", 0)
        set(v) = prefs.edit().putInt("lan_port", v).apply()

    var wanEnabled: Boolean
        get() = prefs.getBoolean("wan_enabled", false)
        set(v) = prefs.edit().putBoolean("wan_enabled", v).apply()

    /** Must start with wss:// */
    var relayUrl: String?
        get() = prefs.getString("relay_url", null)
        set(v) = prefs.edit().putString("relay_url", v).apply()

    var relayToken: String?
        get() = prefs.getString("relay_token", null)
        set(v) = prefs.edit().putString("relay_token", v).apply()

    /** 256-bit end-to-end key for WAN traffic. Show it once (QR/copy) so the controller app can import it. */
    fun ensurePairingKey(): String {
        prefs.getString("pairing_key", null)?.let { return it }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.getEncoder().encodeToString(k)
        prefs.edit().putString("pairing_key", b64).apply()
        return b64
    }

    fun pairingKeyBytes(): ByteArray? =
        prefs.getString("pairing_key", null)?.let { Base64.getDecoder().decode(it) }

    fun rotatePairingKey(): String {
        prefs.edit().remove("pairing_key").apply()
        return ensurePairingKey()
    }

    /** RSA-2048 key pair used for signing relay registration challenges. */
    fun getRelayKeyPair(): KeyPair {
        val pubB64 = prefs.getString("relay_pub", null)
        val privB64 = prefs.getString("relay_priv", null)
        
        if (pubB64 != null && privB64 != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.e("RemoteStore", "Failed to load relay keys, regenerating", e)
            }
        }
        
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        
        prefs.edit()
            .putString("relay_pub", Base64.getEncoder().encodeToString(kp.public.encoded))
            .putString("relay_priv", Base64.getEncoder().encodeToString(kp.private.encoded))
            .apply()
            
        return kp
    }

    fun getRelayPublicKeyB64(): String =
        Base64.getEncoder().encodeToString(getRelayKeyPair().public.encoded)

    fun signChallenge(challengeHex: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(getRelayKeyPair().private)
        sig.update(challengeHex.toByteArray(Charsets.UTF_8))
        return bytesToHex(sig.sign())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}

private const val TAG = "RemoteStore"

/**
 * PIN -> session-token authentication with brute-force protection.
 * There is NO default PIN: remote control cannot start until the user creates one.
 */
class RemoteAuth(private val store: RemoteStore) {

    sealed class Result {
        data class Ok(val token: String?, val ttlSec: Long) : Result()
        data class Fail(val reason: String, val retryAfterSec: Long = 0) : Result()
    }

    private class Attempt { var fails = 0; var lockedUntil = 0L }

    private val rng = SecureRandom()
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val tokens = ConcurrentHashMap<String, Long>() // token -> expiry millis
    private var globalFails = 0
    private var globalWindowStart = 0L
    private var globalLockedUntil = 0L

    fun hasPin(): Boolean = store.pinHash != null && store.pinSalt != null

    /** Generates and stores a random 8-digit PIN; the plaintext is returned ONCE for display. */
    fun newRandomPin(): String {
        val pin = (0 until PIN_DIGITS).joinToString("") { rng.nextInt(10).toString() }
        setPin(pin)
        return pin
    }

    fun setPin(pin: String) {
        require(Regex("^\\d{6,12}$").matches(pin)) { "PIN must be 6-12 digits" }
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        store.pinSalt = Base64.getEncoder().encodeToString(salt)
        store.pinHash = Base64.getEncoder().encodeToString(pbkdf2(pin, salt))
        revokeAll()
    }

    @Synchronized
    fun verifyPin(peer: String, pin: String?): Result {
        val now = System.currentTimeMillis()
        lockRemainingSec(peer, now)?.let { return Result.Fail("locked", it) }
        val salt = store.pinSalt
        val hash = store.pinHash
        if (pin.isNullOrEmpty() || salt == null || hash == null || pin.length > 32) return fail(peer, now)
        val candidate = pbkdf2(pin, Base64.getDecoder().decode(salt))
        return if (MessageDigest.isEqual(candidate, Base64.getDecoder().decode(hash))) {
            attempts.remove(peer)
            Result.Ok(issueToken(now), TOKEN_TTL_SEC)
        } else fail(peer, now)
    }

    @Synchronized
    fun verifyToken(peer: String, token: String?): Result {
        val now = System.currentTimeMillis()
        lockRemainingSec(peer, now)?.let { return Result.Fail("locked", it) }
        val exp = token?.let { tokens[it] }
        return if (exp != null && exp > now) Result.Ok(null, (exp - now) / 1000) else fail(peer, now)
    }

    fun revokeAll() = tokens.clear()

    // ---- internals ----

    private fun issueToken(now: Long): String {
        tokens.entries.removeIf { it.value <= now }
        if (tokens.size >= 20) tokens.clear()
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        tokens[token] = now + TOKEN_TTL_SEC * 1000
        return token
    }

    private fun lockRemainingSec(peer: String, now: Long): Long? {
        val until = maxOf(attempts[peer]?.lockedUntil ?: 0L, globalLockedUntil)
        return if (until > now) (until - now + 999) / 1000 else null
    }

    private fun fail(peer: String, now: Long): Result {
        val a = attempts.getOrPut(peer) { Attempt() }
        a.fails++
        if (a.fails >= MAX_FAILS_PER_PEER) { a.lockedUntil = now + LOCK_MS; a.fails = 0 }

        // Global limit stops an attacker from rotating peer ids (e.g. through the relay).
        if (now - globalWindowStart > GLOBAL_WINDOW_MS) { globalWindowStart = now; globalFails = 0 }
        globalFails++
        if (globalFails >= MAX_GLOBAL_FAILS) { globalLockedUntil = now + LOCK_MS; globalFails = 0 }

        val retry = lockRemainingSec(peer, now) ?: 0L
        return Result.Fail("invalid_credentials", retry)
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    companion object {
        const val PIN_DIGITS = 8
        const val TOKEN_TTL_SEC = 3600L
        private const val MAX_FAILS_PER_PEER = 5
        private const val MAX_GLOBAL_FAILS = 30
        private const val LOCK_MS = 5 * 60_000L
        private const val GLOBAL_WINDOW_MS = 10 * 60_000L
    }
}

/** AES-256-GCM envelope used for end-to-end encryption through the (untrusted) relay. */
object RemoteCrypto {
    private val rng = SecureRandom()

    /** @return (cipherTextB64, ivB64). [aad] binds the ciphertext to its session. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<String, String> {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        val enc = Base64.getEncoder()
        return enc.encodeToString(c.doFinal(plaintext)) to enc.encodeToString(iv)
    }

    /** @return plaintext, or null when authentication fails (tampered / wrong key / wrong session). */
    fun decrypt(key: ByteArray, cipherB64: String, ivB64: String, aad: ByteArray): ByteArray? = try {
        val dec = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, dec.decode(ivB64)))
        c.updateAAD(aad)
        c.doFinal(dec.decode(cipherB64))
    } catch (e: Exception) {
        null
    }

    /** HMAC-SHA256 signature for command integrity. */
    fun hmac(key: ByteArray, data: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val result = mac.doFinal(data)
        val hexChars = "0123456789abcdef"
        val out = StringBuilder(result.size * 2)
        for (b in result) {
            val i = b.toInt() and 0xFF
            out.append(hexChars[i shr 4])
            out.append(hexChars[i and 0x0F])
        }
        return out.toString()
    }
}
