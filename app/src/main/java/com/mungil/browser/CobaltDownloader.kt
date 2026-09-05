package com.mungil.browser

import android.content.Context
import android.net.Uri
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

            // 1. Ekstraktor langsung berkecepatan tinggi untuk platform TikTok (No Watermark & Audio)
            if (cleanUrl.contains("tiktok.com", ignoreCase = true)) {
                try {
                    val isAudio = quality == DownloadQuality.AUDIO
                    val tikwmResult = fetchStreamFromTikWM(cleanUrl, isAudio)
                    if (tikwmResult != null && tikwmResult.first.isNotEmpty()) {
                        directDownloadUrl = tikwmResult.first
                        responseFilename = tikwmResult.second
                    }
                } catch (e: Exception) {}
            }

            // 2. Jika belum ditemukan, gunakan resolver Cobalt (v10 / v7)
            if (directDownloadUrl.isNullOrEmpty()) {
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

    private fun fetchStreamFromTikWM(
        targetUrl: String,
        isAudio: Boolean
    ): Pair<String, String?>? {
        val encoded = Uri.encode(targetUrl)
        val endpoint = "https://www.tikwm.com/api/?url=$encoded"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(responseText)
                if (json.optInt("code") == 0) {
                    val data = json.optJSONObject("data")
                    if (data != null) {
                        val playUrl = if (isAudio) {
                            data.optString("music").ifEmpty { data.optString("play") }
                        } else {
                            data.optString("play")
                        }
                        val title = data.optString("title")
                        if (playUrl.isNotEmpty()) {
                            return Pair(playUrl, if (title.isNotEmpty()) title else null)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return null
    }

    private fun fetchStreamFromCobalt(
        apiBaseUrl: String,
        targetVideoUrl: String,
        quality: DownloadQuality
    ): Pair<String, String?>? {
        val base = if (apiBaseUrl.endsWith("/")) apiBaseUrl.dropLast(1) else apiBaseUrl
        // Dukung skema Cobalt v10 (POST /) dan legacy v7 (POST /api/json)
        val endpoints = listOf(base, "$base/api/json")

        for (endpoint in endpoints) {
            try {
                val result = executeCobaltPost(endpoint, targetVideoUrl, quality)
                if (result != null) return result
            } catch (e: Exception) {}
        }
        return null
    }

    private fun executeCobaltPost(
        endpoint: String,
        targetVideoUrl: String,
        quality: DownloadQuality
    ): Pair<String, String?>? {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }

        val jsonBody = JSONObject().apply {
            put("url", targetVideoUrl)
            put("filenamePattern", "basic")
            put("isNoWatermark", true)

            when (quality) {
                DownloadQuality.HD -> {
                    put("videoQuality", "1080")
                    put("vQuality", "1080")
                    put("downloadMode", "auto")
                    put("isAudioOnly", false)
                }
                DownloadQuality.SAVER -> {
                    put("videoQuality", "480")
                    put("vQuality", "480")
                    put("downloadMode", "auto")
                    put("isAudioOnly", false)
                }
                DownloadQuality.AUDIO -> {
                    put("downloadMode", "audio")
                    put("audioFormat", "mp3")
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

            if (status == "stream" || status == "redirect" || status == "success" || status == "tunnel") {
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
