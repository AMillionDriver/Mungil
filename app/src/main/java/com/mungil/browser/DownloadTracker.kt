package com.mungil.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object DownloadTracker {
    private const val PREFS_NAME = "mungil_downloads_v1"
    private const val KEY_RECORDS = "download_records"

    private val records = CopyOnWriteArrayList<DownloadRecord>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadFromPrefs(context)
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {}
            }
        }
    }

    fun getAllRecords(): List<DownloadRecord> {
        return records.toList()
    }

    fun addRecord(record: DownloadRecord, context: Context? = null) {
        records.add(0, record)
        notifyListeners()
        context?.let { saveToPrefs(it) }
    }

    fun updateProgress(
        id: String,
        downloaded: Long,
        total: Long,
        speed: Long
    ) {
        val item = records.find { it.id == id } ?: return
        item.downloadedBytes = downloaded
        item.totalBytes = total
        item.speedBytesPerSec = speed
        notifyListeners()
    }

    fun markCompleted(
        id: String,
        localPath: String?,
        targetUriString: String?,
        context: Context? = null
    ) {
        val item = records.find { it.id == id } ?: return
        item.status = DownloadStatus.COMPLETED
        item.localPath = localPath
        item.targetUriString = targetUriString
        item.speedBytesPerSec = 0L

        // Cek apakah file benar-benar ada dan tidak kosong
        if (localPath != null) {
            val file = File(localPath)
            if (!file.exists() || file.length() == 0L) {
                item.errorSource = ErrorSource.FILE_CORRUPTED
                item.errorMessage = "File hasil unduh rusak atau 0 bytes saat disimpan."
            } else {
                item.downloadedBytes = file.length()
                if (item.totalBytes <= 0) item.totalBytes = file.length()
            }
        }

        notifyListeners()
        context?.let { saveToPrefs(it) }
    }

    fun markFailed(
        id: String,
        source: ErrorSource,
        message: String,
        context: Context? = null
    ) {
        val item = records.find { it.id == id } ?: return
        item.status = DownloadStatus.FAILED
        item.errorSource = source
        item.errorMessage = message
        item.speedBytesPerSec = 0L
        notifyListeners()
        context?.let { saveToPrefs(it) }
    }

    fun removeRecord(id: String, context: Context? = null) {
        records.removeAll { it.id == id }
        notifyListeners()
        context?.let { saveToPrefs(it) }
    }

    fun clearAllRecords(context: Context? = null) {
        records.clear()
        notifyListeners()
        context?.let { saveToPrefs(it) }
    }

    private fun saveToPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            for (r in records.take(50)) { // Simpan hingga 50 record terbaru
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("fileName", r.fileName)
                    put("fileExtension", r.fileExtension)
                    put("mimeType", r.mimeType)
                    put("streamUrl", r.streamUrl)
                    put("status", r.status.name)
                    put("totalBytes", r.totalBytes)
                    put("downloadedBytes", r.downloadedBytes)
                    put("localPath", r.localPath ?: "")
                    put("targetUriString", r.targetUriString ?: "")
                    put("errorSource", r.errorSource?.name ?: "")
                    put("errorMessage", r.errorMessage ?: "")
                    put("timestamp", r.timestamp)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_RECORDS, jsonArray.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun loadFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_RECORDS, null) ?: return
            val jsonArray = JSONArray(jsonString)
            records.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val statusStr = obj.optString("status", DownloadStatus.COMPLETED.name)
                val status = try { DownloadStatus.valueOf(statusStr) } catch(e: Exception) { DownloadStatus.COMPLETED }
                val errorSourceStr = obj.optString("errorSource", "")
                val errorSource = if (errorSourceStr.isNotEmpty()) {
                    try { ErrorSource.valueOf(errorSourceStr) } catch (e: Exception) { null }
                } else null

                val record = DownloadRecord(
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    title = obj.optString("title", "Unduhan"),
                    fileName = obj.optString("fileName", "media"),
                    fileExtension = obj.optString("fileExtension", ""),
                    mimeType = obj.optString("mimeType", "*/*"),
                    streamUrl = obj.optString("streamUrl", ""),
                    status = if (status == DownloadStatus.DOWNLOADING) DownloadStatus.FAILED else status,
                    totalBytes = obj.optLong("totalBytes", 0L),
                    downloadedBytes = obj.optLong("downloadedBytes", 0L),
                    localPath = obj.optString("localPath").ifEmpty { null },
                    targetUriString = obj.optString("targetUriString").ifEmpty { null },
                    errorSource = if (status == DownloadStatus.DOWNLOADING) ErrorSource.NETWORK_OR_DOWNLOADER else errorSource,
                    errorMessage = if (status == DownloadStatus.DOWNLOADING) "Unduhan terhenti saat aplikasi ditutup" else obj.optString("errorMessage").ifEmpty { null },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
                records.add(record)
            }
        } catch (e: Exception) {}
    }
}
