package com.mungil.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object NativeStreamDownloader {

    // Sanitize filename to prevent file system and scoped storage crashes
    fun sanitizeFilename(rawTitle: String?, extension: String): String {
        val safeBase = (rawTitle ?: "Mungil_Media")
            .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            .replace("_{2,}".toRegex(), "_")
            .trim('_')
            .take(40)
            .ifEmpty { "Mungil_Media" }

        val ext = if (extension.startsWith(".")) extension else ".$extension"
        return "${safeBase}_${System.currentTimeMillis()}$ext"
    }

    /**
     * 🚀 In-App Direct Stream Downloader:
     * Mengunduh langsung stream video/audio menggunakan session cookies dan browser media streaming headers.
     * Menggunakan Range: bytes=0- dan Accept media standar agar CDN tidak memutus sesi pemutar video di WebView.
     */
    fun downloadDirectStreamInApp(
        context: Context,
        streamUrl: String,
        title: String?,
        referer: String?,
        userAgent: String?,
        isAudio: Boolean = false,
        onStatus: ((Boolean, String) -> Unit)? = null
    ) {
        // Untuk audio dari direct stream video, gunakan format .m4a / .mp3 yang ramah player Android
        val extension = if (isAudio) ".m4a" else ".mp4"
        val mimeType = if (isAudio) "audio/mp4" else "video/mp4"
        val fileName = sanitizeFilename(title, extension)

        val mainHandler = Handler(Looper.getMainLooper())
        val typeLabel = if (isAudio) "Audio (M4A)" else "Video (MP4)"
        mainHandler.post {
            Toast.makeText(context, "🚀 Memulai unduhan $typeLabel: $fileName", Toast.LENGTH_SHORT).show()
        }

        thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null

            try {
                var currentUrl = streamUrl
                var redirects = 0
                val cookies = try {
                    CookieManager.getInstance().getCookie(currentUrl)
                } catch (e: Exception) {
                    null
                }

                while (redirects < 6) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 25000
                        readTimeout = 40000
                        instanceFollowRedirects = true

                        // Header standar pemutar media browser (mencegah CDN mendeteksi bot rip dan memutus video di web)
                        setRequestProperty("Range", "bytes=0-")
                        if (isAudio) {
                            setRequestProperty("Accept", "audio/*,video/*;q=0.8,*/*;q=0.5")
                            setRequestProperty("Sec-Fetch-Dest", "audio")
                        } else {
                            setRequestProperty("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                            setRequestProperty("Sec-Fetch-Dest", "video")
                        }
                        setRequestProperty("Sec-Fetch-Mode", "no-cors")
                        setRequestProperty("Sec-Fetch-Site", "cross-site")

                        if (!userAgent.isNullOrEmpty()) {
                            setRequestProperty("User-Agent", userAgent)
                        } else {
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0 Safari/537.36")
                        }
                        if (!referer.isNullOrEmpty()) {
                            setRequestProperty("Referer", referer)
                        }
                        if (!cookies.isNullOrEmpty()) {
                            setRequestProperty("Cookie", cookies)
                        }
                    }

                    val code = connection.responseCode
                    if (code in 301..308) {
                        val newLocation = connection.getHeaderField("Location")
                        if (!newLocation.isNullOrEmpty()) {
                            currentUrl = newLocation
                            redirects++
                            connection.disconnect()
                            continue
                        }
                    }
                    break
                }

                val finalCode = connection?.responseCode ?: -1
                // 200 OK atau 206 Partial Content (karena Range: bytes=0-)
                if (finalCode !in 200..299) {
                    throw Exception("Server media merespons HTTP $finalCode")
                }

                inputStream = connection?.inputStream ?: throw Exception("Stream data kosong")

                var targetUri: Uri? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ Scoped Storage via MediaStore
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw Exception("Gagal membuat entri penyimpanan MediaStore")

                    outputStream = resolver.openOutputStream(targetUri)
                } else {
                    // Android 9 kebawah via DIRECTORY_DOWNLOADS
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, fileName)
                    outputStream = FileOutputStream(targetFile)
                    targetUri = Uri.fromFile(targetFile)
                }

                if (outputStream == null) {
                    throw Exception("Tidak dapat membuka file penyimpanan")
                }

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                }
                outputStream.flush()

                // Finalize MediaStore pending flag
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetUri != null) {
                    val finalValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(targetUri, finalValues, null, null)
                }

                // Beritahu MediaScanner agar media langsung muncul di Galeri / Pemutar Musik
                try {
                    val path = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName).absolutePath
                    } else {
                        null
                    }
                    if (path != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
                    }
                } catch (e: Exception) {}

                mainHandler.post {
                    Toast.makeText(context, "✅ Unduhan selesai: $fileName", Toast.LENGTH_LONG).show()
                    onStatus?.invoke(true, fileName)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "❌ Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
                    onStatus?.invoke(false, e.message ?: "Unknown error")
                }
            } finally {
                try { outputStream?.close() } catch (e: Exception) {}
                try { inputStream?.close() } catch (e: Exception) {}
                try { connection?.disconnect() } catch (e: Exception) {}
            }
        }
    }

    /**
     * 📱 Download via System DownloadManager dengan hardening
     */
    fun downloadViaSystemManager(
        context: Context,
        url: String,
        title: String?,
        referer: String?,
        userAgent: String?,
        isAudio: Boolean = false
    ): Boolean {
        return try {
            val extension = if (isAudio) ".m4a" else ".mp4"
            val mimeType = if (isAudio) "audio/mp4" else "video/mp4"
            val fileName = sanitizeFilename(title, extension)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Mungil Browser Downloader")
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                if (!userAgent.isNullOrEmpty()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                if (!referer.isNullOrEmpty()) {
                    addRequestHeader("Referer", referer)
                }
                try {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrEmpty()) {
                        addRequestHeader("Cookie", cookies)
                    }
                } catch (e: Exception) {}
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "⬇ Mengunduh via Download Manager: $fileName", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Download Manager dialihkan ke unduhan langsung...", Toast.LENGTH_SHORT).show()
            downloadDirectStreamInApp(context, url, title, referer, userAgent, isAudio)
            false
        }
    }
}
