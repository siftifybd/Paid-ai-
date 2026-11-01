package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * AppUpdater utility handling downloading, SHA-256 checksum verification,
 * package name validation, and system package installation via FileProvider.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Downloads an APK to cache, validates SHA-256 and package name, and launches the install prompt.
     *
     * @param context Application or Activity context.
     * @param downloadUrl The direct HTTPS URL of the update APK.
     * @param expectedSha256 Optional SHA-256 checksum (hex string) to validate against.
     * @param expectedPackageName Optional package name (defaults to current app package name).
     * @param onProgress Callback invoked during download with (progressPercent 0..100, downloadedBytes, totalBytes).
     * @return Result<File> containing the downloaded APK file on success, or exception on failure.
     */
    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        expectedSha256: String? = null,
        expectedPackageName: String? = context.packageName,
        onProgress: ((progressPercent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(downloadUrl.isNotBlank()) { "Download URL cannot be blank" }
            if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException("Only secure HTTPS URLs are allowed for app updates")
            }

            Log.d(TAG, "Initiating download from: $downloadUrl")

            // Prepare target APK file in cache directory
            val updateFolder = File(context.cacheDir, "updates").apply {
                if (!exists()) mkdirs()
            }
            val apkFile = File(updateFolder, "jarvis_update_${System.currentTimeMillis()}.apk")
            if (apkFile.exists()) apkFile.delete()

            // 1. Download file using OkHttp
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "JARVIS-NEXUS-Updater/${context.packageName}")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("Server returned HTTP error code ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from server")
            val totalBytes = body.contentLength()

            body.byteStream().use { inputStream ->
                FileOutputStream(apkFile).use { outputStream ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var downloadedBytes = 0L
                    var lastProgress = -1

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress?.invoke(progress, downloadedBytes, totalBytes)
                            }
                        } else {
                            onProgress?.invoke(-1, downloadedBytes, totalBytes)
                        }
                    }
                    outputStream.flush()
                }
            }

            Log.d(TAG, "Download finished. Size: ${apkFile.length()} bytes")

            // 2. Validate SHA-256 hash if provided
            if (!expectedSha256.isNullOrBlank()) {
                val computedHash = calculateSha256(apkFile)
                val targetHash = expectedSha256.trim().lowercase()
                if (!computedHash.equals(targetHash, ignoreCase = true)) {
                    apkFile.delete()
                    throw SecurityException("SHA-256 checksum mismatch. Expected: $targetHash, Computed: $computedHash")
                }
                Log.d(TAG, "SHA-256 checksum verified: $computedHash")
            }

            // 3. Verify Package Name from APK Archive Info
            val targetPackage = expectedPackageName ?: context.packageName
            val packageInfo = getPackageArchiveInfo(context, apkFile)
                ?: run {
                    apkFile.delete()
                    throw SecurityException("Unable to parse package archive metadata from downloaded file")
                }

            if (packageInfo.packageName != targetPackage) {
                apkFile.delete()
                throw SecurityException("Package name mismatch! Expected: $targetPackage, Found: ${packageInfo.packageName}")
            }
            Log.d(TAG, "Package name verified: ${packageInfo.packageName}")

            // 4. Trigger Android system installation prompt via FileProvider
            installApk(context, apkFile)

            apkFile
        }
    }

    /**
     * Calculates the SHA-256 hex checksum of a file.
     */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Retrieves package archive info for a file, handling API level differences.
     */
    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfo(context: Context, apkFile: File) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
    }

    /**
     * Constructs FileProvider URI and opens installation activity intent.
     */
    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }
}
