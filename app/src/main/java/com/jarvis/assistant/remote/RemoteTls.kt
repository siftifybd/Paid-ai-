package com.jarvis.assistant.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * TLS identity for the LAN server. The private key lives in the AndroidKeyStore (never exported);
 * Android generates the self-signed certificate. Controllers must PIN the SHA-256 fingerprint
 * that is shown to the user on the phone (trust-on-first-use), exactly like SSH host keys.
 *
 * NOTE: verify on your target devices. If a device's keystore provider can't do TLS signatures,
 * fall back to a PKCS12 key generated with BouncyCastle.
 */
object RemoteTls {
    private const val ALIAS = "jarvis_remote_tls"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun sslContext(): SSLContext {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) generate()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun clientSslContext(expectedFingerprint: String? = null): SSLContext {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty certificate chain")
                val cert = chain[0]
                val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString(":") { "%02X".format(it) }
                if (!expectedFingerprint.isNullOrBlank()) {
                    val cleanExpected = expectedFingerprint.replace(":", "").uppercase()
                    val cleanFp = fp.replace(":", "").uppercase()
                    if (cleanExpected != cleanFp) {
                        throw java.security.cert.CertificateException("Server certificate fingerprint mismatch. Expected: $expectedFingerprint, Got: $fp")
                    }
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(tm), java.security.SecureRandom())
        return sslContext
    }

    /** "AB:CD:..." — show this to the user next to the PIN. */
    @Synchronized
    fun fingerprintSha256(): String {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) generate()
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    /** Forget the identity (controllers will have to re-pin). */
    @Synchronized
    fun reset() {
        keyStore().deleteEntry(ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .setCertificateSubject(X500Principal("CN=jarvis-remote"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 60_000))
            .setCertificateNotAfter(Date(now + 5L * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).run {
            initialize(spec)
            generateKeyPair()
        }
    }
}
