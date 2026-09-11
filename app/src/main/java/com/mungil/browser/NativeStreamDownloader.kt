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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

object NativeStreamDownloader {

    // Sanitize filename to prevent file system and scoped storage crashes,
    // and eliminate generic /main or URL path artifacts
    fun sanitizeFilename(rawTitle: String?, extension: String): String {
        var title = rawTitle?.trim() ?: ""

        // If title is URL, extract the meaningful slug or path
        if (title.startsWith("http://", ignoreCase = true) || title.startsWith("https://", ignoreCase = true)) {
            try {
                val uri = Uri.parse(title)
                val path = uri.lastPathSegment ?: ""
                title = path.substringBeforeLast('.')
            } catch(e: Exception) {
                title = ""
            }
        }

        // Remove common generic slugs that SPA feeds or domains produce
        val bannedWords = listOf("main", "index", "feed", "video", "watch", "explore", "foryou", "share", "trending", "home", "play", "app")

        val normalized = title
            .replace("[^a-zA-Z0-9_ -]".toRegex(), "_")
            .replace("_{2,}".toRegex(), "_")
            .trim('_', ' ')

        val safeBase = if (bannedWords.any { normalized.equals(it, ignoreCase = true) }) {
            "Mungil_Media"
        } else {
            normalized.take(60).ifEmpty { "Mungil_Media" }
        }

        val ext = if (extension.startsWith(".")) extension else ".$extension"
        return "${safeBase}_${System.currentTimeMillis()}$ext"
    }

    /**
     * Determine proper media extension and MIME type based on Content-Type header and URL
     */
    private fun resolveMediaFormat(contentType: String?, url: String, isAudio: Boolean): Pair<String, String> {
        val lowerType = contentType?.lowercase() ?: ""
        val lowerUrl = url.lowercase()

        return if (isAudio) {
            when {
                lowerType.contains("audio/mpeg") || lowerType.contains("audio/mp3") || lowerUrl.contains(".mp3") ->
                    Pair(".mp3", "audio/mpeg")
                lowerType.contains("audio/ogg") || lowerUrl.contains(".ogg") || lowerUrl.contains(".oga") ->
                    Pair(".ogg", "audio/ogg")
                lowerType.contains("audio/wav") || lowerUrl.contains(".wav") ->
                    Pair(".wav", "audio/wav")
                lowerType.contains("audio/flac") || lowerUrl.contains(".flac") ->
                    Pair(".flac", "audio/flac")
                else ->
                    Pair(".m4a", "audio/mp4")
            }
        } else {
            when {
                lowerType.contains("video/webm") || lowerUrl.contains(".webm") ->
                    Pair(".webm", "video/webm")
                lowerType.contains("video/ogg") || lowerUrl.contains(".ogv") ->
                    Pair(".ogv", "video/ogg")
                lowerType.contains("video/x-matroska") || lowerUrl.contains(".mkv") ->
                    Pair(".mkv", "video/x-matroska")
                lowerType.contains("video/3gpp") || lowerUrl.contains(".3gp") ->
                    Pair(".3gp", "video/3gpp")
                else ->
                    Pair(".mp4", "video/mp4")
            }
        }
    }

    /**
     * 🚀 In-App Direct Stream Downloader dengan Pelacakan Real-time & Diagnostik Error:
     * Mengunduh langsung stream video/audio menggunakan session cookies dan browser media streaming headers.
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
        val mainHandler = Handler(Looper.getMainLooper())
        val downloadId = UUID.randomUUID().toString()
        val typeLabel = if (isAudio) "Audio" else "Video"
        val defaultExt = if (isAudio) ".m4a" else ".mp4"
        val defaultMime = if (isAudio) "audio/mp4" else "video/mp4"
        val initialFileName = sanitizeFilename(title, defaultExt)

        val record = DownloadRecord(
            id = downloadId,
            title = title ?: initialFileName,
            fileName = initialFileName,
            fileExtension = defaultExt,
            mimeType = defaultMime,
            streamUrl = streamUrl,
            status = DownloadStatus.DOWNLOADING,
            timestamp = System.currentTimeMillis()
        )
        DownloadTracker.addRecord(record, context)

        mainHandler.post {
            Toast.makeText(context, "🚀 Mengunduh $typeLabel... Cek menu Unduhan", Toast.LENGTH_SHORT).show()
        }

        thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            var actualLocalPath: String? = null
            var actualUriString: String? = null
            var resolvedFileName = initialFileName
            var lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER

            try {
                var currentUrl = streamUrl
                val cookies = try {
                    CookieManager.getInstance().getCookie(streamUrl)
                } catch (e: Exception) {
                    null
                }

                // Follow redirects manually with support for relative Location header
                var redirects = 0
                val maxRedirects = 10
                while (redirects < maxRedirects) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 35000
                        readTimeout = 60000
                        instanceFollowRedirects = true

                        // Browser media playback header
                        setRequestProperty("Range", "bytes=0-")
                        if (isAudio) {
                            setRequestProperty("Accept", "audio/*,video/*;q=0.8,*/*;q=0.5")
                        } else {
                            setRequestProperty("Accept", "video/webm,video/ogg,video/*;q=0.9,audio/*;q=0.6,*/*;q=0.5")
                        }

                        if (!userAgent.isNullOrEmpty()) {
                            setRequestProperty("User-Agent", userAgent)
                        } else {
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
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
                            currentUrl = URL(URL(currentUrl), newLocation).toString()
                            redirects++
                            connection.disconnect()
                            if (redirects >= maxRedirects) {
                                lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER
                                throw IOException("Terlalu banyak redirect (>$maxRedirects). URL token expired.")
                            }
                            continue
                        }
                    }
                    break
                }

                val finalCode = connection?.responseCode ?: -1
                if (finalCode !in 200..299) {
                    lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER
                    val errorDetail = when (finalCode) {
                        403 -> "HTTP 403 Dilarang (Token stream media dibatasi atau telah kedaluwarsa)"
                        404 -> "HTTP 404 Media Tidak Ditemukan di server"
                        429 -> "HTTP 429 Kuota server streaming terlampaui"
                        else -> "Server media merespons HTTP $finalCode"
                    }
                    throw IOException(errorDetail)
                }

                val contentLength = connection?.contentLengthLong ?: -1L
                val contentType = connection?.contentType
                val (extension, mimeType) = resolveMediaFormat(contentType, currentUrl, isAudio)
                resolvedFileName = sanitizeFilename(title, extension)

                record.fileName = resolvedFileName
                record.fileExtension = extension
                record.mimeType = mimeType
                if (contentLength > 0) {
                    record.totalBytes = contentLength
                }

                val stream = connection?.inputStream
                if (stream == null) {
                    lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER
                    throw IOException("Koneksi media server ditutup atau stream tidak dapat dibaca")
                }
                inputStream = stream

                var targetUri: Uri? = null

                // Penyiapan penyimpanan di HP
                lastErrorSource = ErrorSource.LOCAL_STORAGE_OR_APP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedFileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (targetUri != null) {
                            outputStream = resolver.openOutputStream(targetUri)
                            actualUriString = targetUri.toString()
                        }
                    } catch (e: Exception) {
                        targetUri = null
                        outputStream = null
                    }
                }

                // Fallback ke penyimpanan langsung di folder Downloads
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (outputStream == null) {
                    try {
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, resolvedFileName)
                        outputStream = FileOutputStream(targetFile)
                        targetUri = Uri.fromFile(targetFile)
                        actualLocalPath = targetFile.absolutePath
                        actualUriString = targetUri.toString()
                    } catch (e: Exception) {
                        lastErrorSource = ErrorSource.LOCAL_STORAGE_OR_APP
                        throw IOException("Gagal membuat file di folder Downloads HP: ${e.message}", e)
                    }
                } else {
                    // Simpan path absolut untuk pengecekan playability
                    actualLocalPath = File(downloadsDir, resolvedFileName).absolutePath
                }

                if (outputStream == null) {
                    lastErrorSource = ErrorSource.LOCAL_STORAGE_OR_APP
                    throw IOException("Tidak dapat membuka akses tulis ke penyimpanan perangkat.")
                }

                // Mulai membaca dan melacak kecepatan
                lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L

                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastCalc = 0L
                var currentSpeed = 0L
                var lastUiUpdateTime = 0L

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (bytesRead > 0) {
                            try {
                                outputStream.write(buffer, 0, bytesRead)
                            } catch (e: IOException) {
                                lastErrorSource = ErrorSource.LOCAL_STORAGE_OR_APP
                                throw IOException("Memori HP penuh atau izin tulis terputus saat menyimpan: ${e.message}", e)
                            }

                            totalBytesRead += bytesRead
                            bytesSinceLastCalc += bytesRead

                            val now = System.currentTimeMillis()
                            val speedDelta = now - lastSpeedCalcTime
                            if (speedDelta >= 500) {
                                currentSpeed = (bytesSinceLastCalc * 1000) / speedDelta
                                lastSpeedCalcTime = now
                                bytesSinceLastCalc = 0L
                            }

                            if (now - lastUiUpdateTime >= 400) {
                                lastUiUpdateTime = now
                                DownloadTracker.updateProgress(
                                    id = downloadId,
                                    downloaded = totalBytesRead,
                                    total = if (contentLength > 0) contentLength else totalBytesRead,
                                    speed = currentSpeed
                                )
                            }
                        }
                    }
                    outputStream.flush()
                } catch (e: IOException) {
                    val kbDownloaded = totalBytesRead / 1024
                    if (lastErrorSource != ErrorSource.LOCAL_STORAGE_OR_APP) {
                        lastErrorSource = ErrorSource.NETWORK_OR_DOWNLOADER
                    }
                    throw IOException("Koneksi terputus saat unduh (${kbDownloaded}KB sudah diunduh). ${e.message}", e)
                }

                if (totalBytesRead == 0L) {
                    lastErrorSource = ErrorSource.FILE_CORRUPTED
                    throw IOException("File kosong (0 bytes). Tautan media rusak atau server mengembalikan stream kosong.")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetUri != null) {
                    val finalValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(targetUri, finalValues, null, null)
                }

                try {
                    val scanPath = actualLocalPath ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), resolvedFileName).absolutePath
                    MediaScannerConnection.scanFile(context, arrayOf(scanPath), arrayOf(mimeType), null)
                } catch (e: Exception) {}

                // Tandai selesai di DownloadTracker
                DownloadTracker.markCompleted(
                    id = downloadId,
                    localPath = actualLocalPath,
                    targetUriString = actualUriString,
                    context = context
                )

                mainHandler.post {
                    Toast.makeText(context, "✅ Unduhan selesai: $resolvedFileName", Toast.LENGTH_LONG).show()
                    onStatus?.invoke(true, resolvedFileName)
                }

            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                DownloadTracker.markFailed(
                    id = downloadId,
                    source = lastErrorSource,
                    message = errorMsg,
                    context = context
                )

                mainHandler.post {
                    Toast.makeText(context, "❌ Gagal mengunduh: $errorMsg", Toast.LENGTH_LONG).show()
                    onStatus?.invoke(false, errorMsg)
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
