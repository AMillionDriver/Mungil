package com.mungil.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object CobaltDownloader {

    enum class DownloadQuality {
        HD,      // 1080p / 720p
        SAVER,   // 480p / 360p
        AUDIO    // MP3 Only
    }

    // Instans Cobalt publik aktif & andal dengan failover cepat
    private val COBALT_INSTANCES = listOf(
        "https://api.cobalt.tools",
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.stuff.solutions",
        "https://cobalt.xy24.eu.org",
        "https://dl.khub.win"
    )

    fun startDownload(
        context: Context,
        mediaUrl: String,
        customTitle: String? = null,
        quality: DownloadQuality = DownloadQuality.HD,
        userAgent: String? = null,
        referer: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isEmpty()) {
            Toast.makeText(context, "URL media tidak valid", Toast.LENGTH_SHORT).show()
            onComplete?.invoke(false, "URL kosong")
            return
        }

        val qualityLabel = when (quality) {
            DownloadQuality.HD -> "Video HD (1080p/720p)"
            DownloadQuality.SAVER -> "Video Hemat (480p)"
            DownloadQuality.AUDIO -> "Audio MP3"
        }

        Toast.makeText(context, "🚀 Menyiapkan $qualityLabel...", Toast.LENGTH_SHORT).show()

        thread {
            var directDownloadUrl: String? = null
            var responseFilename: String? = null
            var lastError = "Semua server Cobalt sedang sibuk"

            for (instance in COBALT_INSTANCES) {
                try {
                    val result = fetchStreamFromCobalt(instance, cleanUrl, quality)
                    if (result != null && result.first.isNotEmpty()) {
                        directDownloadUrl = result.first
                        responseFilename = result.second
                        break
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Koneksi terputus"
                }
            }

            Handler(Looper.getMainLooper()).post {
                if (!directDownloadUrl.isNullOrEmpty()) {
                    val isAudio = quality == DownloadQuality.AUDIO
                    val defaultExt = if (isAudio) ".mp3" else ".mp4"

                    val safeTitle = (responseFilename ?: customTitle ?: "Mungil_Media")
                        .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                        .take(40)

                    val fileName = if (safeTitle.endsWith(".mp4") || safeTitle.endsWith(".mp3")) {
                        safeTitle
                    } else {
                        "${safeTitle}_${System.currentTimeMillis()}$defaultExt"
                    }

                    try {
                        val request = DownloadManager.Request(Uri.parse(directDownloadUrl)).apply {
                            setTitle(fileName)
                            setDescription("Unduhan Mungil Universal")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            
                            // Suntikkan User-Agent, Referer, dan Cookie agar anti 403 Forbidden
                            if (!userAgent.isNullOrEmpty()) {
                                addRequestHeader("User-Agent", userAgent)
                            }
                            if (!referer.isNullOrEmpty()) {
                                addRequestHeader("Referer", referer)
                            }
                            try {
                                val cookies = CookieManager.getInstance().getCookie(directDownloadUrl)
                                if (!cookies.isNullOrEmpty()) {
                                    addRequestHeader("Cookie", cookies)
                                }
                            } catch (e: Exception) {}
                        }

                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)

                        Toast.makeText(context, "⬇ Mulai mengunduh: $fileName", Toast.LENGTH_LONG).show()
                        onComplete?.invoke(true, fileName)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Gagal memulai unduhan: ${e.message}", Toast.LENGTH_LONG).show()
                        onComplete?.invoke(false, e.message ?: "Gagal DownloadManager")
                    }
                } else {
                    Toast.makeText(context, "❌ Gagal memproses media ($lastError)", Toast.LENGTH_LONG).show()
                    onComplete?.invoke(false, lastError)
                }
            }
        }
    }

    private fun fetchStreamFromCobalt(
        apiBaseUrl: String,
        targetVideoUrl: String,
        quality: DownloadQuality
    ): Pair<String, String?>? {
        val endpoint = if (apiBaseUrl.endsWith("/")) "${apiBaseUrl}api/json" else "$apiBaseUrl/api/json"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 12000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }

        val jsonBody = JSONObject().apply {
            put("url", targetVideoUrl)
            put("filenamePattern", "basic")
            put("isNoWatermark", true)

            when (quality) {
                DownloadQuality.HD -> {
                    put("vQuality", "1080")
                    put("isAudioOnly", false)
                }
                DownloadQuality.SAVER -> {
                    put("vQuality", "480")
                    put("isAudioOnly", false)
                }
                DownloadQuality.AUDIO -> {
                    put("isAudioOnly", true)
                    put("aFormat", "mp3")
                }
            }
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val jsonResponse = JSONObject(responseText)
            val status = jsonResponse.optString("status")

            if (status == "stream" || status == "redirect" || status == "success") {
                val downloadUrl = jsonResponse.optString("url")
                val filename = jsonResponse.optString("filename", null)
                if (downloadUrl.isNotEmpty()) {
                    conn.disconnect()
                    return Pair(downloadUrl, filename)
                }
            }

            if (jsonResponse.has("url")) {
                val downloadUrl = jsonResponse.getString("url")
                val filename = jsonResponse.optString("filename", null)
                conn.disconnect()
                return Pair(downloadUrl, filename)
            }

            // Penanganan Picker (misal TikTok multi-slide / Carousel)
            if (status == "picker" && jsonResponse.has("picker")) {
                val pickerArray = jsonResponse.getJSONArray("picker")
                if (pickerArray.length() > 0) {
                    val firstItem = pickerArray.getJSONObject(0)
                    val downloadUrl = firstItem.optString("url")
                    if (downloadUrl.isNotEmpty()) {
                        conn.disconnect()
                        return Pair(downloadUrl, null)
                    }
                }
            }
        } else {
            conn.disconnect()
            return fetchStreamFromCobaltV10(apiBaseUrl, targetVideoUrl, quality)
        }

        conn.disconnect()
        return null
    }

    // Dukungan API Cobalt modern v10
    private fun fetchStreamFromCobaltV10(
        apiBaseUrl: String,
        targetVideoUrl: String,
        quality: DownloadQuality
    ): Pair<String, String?>? {
        val endpoint = if (apiBaseUrl.endsWith("/")) apiBaseUrl else "$apiBaseUrl/"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 12000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val jsonBody = JSONObject().apply {
            put("url", targetVideoUrl)
            when (quality) {
                DownloadQuality.HD -> put("videoQuality", "1080")
                DownloadQuality.SAVER -> put("videoQuality", "480")
                DownloadQuality.AUDIO -> {
                    put("downloadMode", "audio")
                    put("audioFormat", "mp3")
                }
            }
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

        if (conn.responseCode in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val jsonResponse = JSONObject(responseText)
            val downloadUrl = jsonResponse.optString("url")
            val filename = jsonResponse.optString("filename", null)
            if (downloadUrl.isNotEmpty()) {
                conn.disconnect()
                return Pair(downloadUrl, filename)
            }
        }
        conn.disconnect()
        return null
    }
}
