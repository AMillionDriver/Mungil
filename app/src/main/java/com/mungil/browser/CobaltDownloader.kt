package com.mungil.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object CobaltDownloader {

    // Daftar instance Cobalt publik yang aktif & cepat (dengan fallback otomatis)
    private val COBALT_INSTANCES = listOf(
        "https://api.cobalt.tools",
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.stuff.solutions",
        "https://cobalt.xy24.eu.org"
    )

    fun downloadMedia(
        context: Context,
        mediaUrl: String,
        customTitle: String? = null,
        userAgent: String? = null
    ) {
        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isEmpty()) {
            Toast.makeText(context, "URL media tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "🚀 Menghubungi mesin Cobalt...", Toast.LENGTH_SHORT).show()

        thread {
            var directDownloadUrl: String? = null
            var finalFilename: String? = null
            var lastErrorMessage: String = "Server sedang sibuk"

            for (instance in COBALT_INSTANCES) {
                try {
                    val result = requestCobaltStream(instance, cleanUrl)
                    if (result != null) {
                        directDownloadUrl = result.first
                        finalFilename = result.second
                        break
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message ?: "Koneksi gagal"
                }
            }

            Handler(Looper.getMainLooper()).post {
                if (!directDownloadUrl.isNullOrEmpty()) {
                    val title = (finalFilename ?: customTitle ?: "Mungil_Video")
                        .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                        .take(40)
                    val fileNameWithExt = if (title.endsWith(".mp4")) title else "${title}_${System.currentTimeMillis()}.mp4"

                    try {
                        val request = DownloadManager.Request(Uri.parse(directDownloadUrl)).apply {
                            setTitle(fileNameWithExt)
                            setDescription("Unduhan Cobalt Mungil")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileNameWithExt)
                            if (!userAgent.isNullOrEmpty()) {
                                addRequestHeader("User-Agent", userAgent)
                            }
                        }

                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)

                        Toast.makeText(context, "⬇ Mengunduh via Cobalt: $fileNameWithExt", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Gagal memulai Download Manager: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "❌ Gagal memproses video: $lastErrorMessage", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestCobaltStream(apiBaseUrl: String, targetVideoUrl: String): Pair<String, String?>? {
        val endpoint = if (apiBaseUrl.endsWith("/")) "${apiBaseUrl}api/json" else "$apiBaseUrl/api/json"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MungilBrowser/1.0")
        }

        val jsonBody = JSONObject().apply {
            put("url", targetVideoUrl)
            put("vQuality", "720")
            put("filenamePattern", "basic")
            put("isNoWatermark", true)
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
                    return Pair(downloadUrl, filename)
                }
            }
            if (jsonResponse.has("url")) {
                val downloadUrl = jsonResponse.getString("url")
                val filename = jsonResponse.optString("filename", null)
                return Pair(downloadUrl, filename)
            }
        } else {
            // Coba v10/modern endpoint jika ada
            conn.disconnect()
            return requestCobaltV10(apiBaseUrl, targetVideoUrl)
        }

        conn.disconnect()
        return null
    }

    private fun requestCobaltV10(apiBaseUrl: String, targetVideoUrl: String): Pair<String, String?>? {
        val endpoint = if (apiBaseUrl.endsWith("/")) apiBaseUrl else "$apiBaseUrl/"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val jsonBody = JSONObject().apply {
            put("url", targetVideoUrl)
            put("videoQuality", "720")
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
