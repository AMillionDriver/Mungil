package com.mungil.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    // Daftar server resolver publik (dibersihkan dari host yang mati/offline)
    private val RESOLVER_INSTANCES = listOf(
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.stuff.solutions",
        "https://cobalt.xy24.eu.org",
        "https://api.cobalt.tools"
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

        Toast.makeText(context, "🚀 Menghubungkan ke pengonversi $qualityLabel...", Toast.LENGTH_SHORT).show()

        thread {
            var directDownloadUrl: String? = null
            var responseFilename: String? = null
            var lastError = "Server cloud sedang sibuk atau URL dilindungi"

            for (instance in RESOLVER_INSTANCES) {
                try {
                    val result = fetchStreamFromCobalt(instance, cleanUrl, quality)
                    if (result != null && result.first.isNotEmpty()) {
                        directDownloadUrl = result.first
                        responseFilename = result.second
                        break
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    lastError = when {
                        msg.contains("timeout", ignoreCase = true) -> "Waktu koneksi habis"
                        msg.contains("429") -> "Batas kuota server tercapai"
                        msg.contains("403") -> "Akses media dibatasi hak cipta"
                        else -> "Server pengonversi sedang antre"
                    }
                }
            }

            Handler(Looper.getMainLooper()).post {
                if (!directDownloadUrl.isNullOrEmpty()) {
                    val isAudio = quality == DownloadQuality.AUDIO
                    val chosenTitle = responseFilename ?: customTitle

                    // Gunakan NativeStreamDownloader untuk mengunduh dengan andal
                    NativeStreamDownloader.downloadDirectStreamInApp(
                        context = context,
                        streamUrl = directDownloadUrl,
                        title = chosenTitle,
                        referer = referer ?: cleanUrl,
                        userAgent = userAgent,
                        isAudio = isAudio,
                        onStatus = onComplete
                    )
                } else {
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
            connectTimeout = 20000
            readTimeout = 30000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0 Safari/537.36")
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
        }

        conn.disconnect()
        return null
    }
}
