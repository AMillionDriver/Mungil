package com.mungil.browser

import java.io.File

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED
}

enum class ErrorSource {
    NETWORK_OR_DOWNLOADER,   // HTTP 403, 404, 500, stream connection timeout, redirect limit, etc.
    LOCAL_STORAGE_OR_APP,    // MediaStore failure, permission denied, disk full, file write error
    FILE_CORRUPTED           // 0 bytes, missing EOF, unreadable
}

data class DownloadRecord(
    val id: String,
    val title: String,
    var fileName: String,
    var fileExtension: String,
    var mimeType: String,
    val streamUrl: String,
    var status: DownloadStatus = DownloadStatus.DOWNLOADING,
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    var speedBytesPerSec: Long = 0L,
    var localPath: String? = null,
    var targetUriString: String? = null,
    var errorSource: ErrorSource? = null,
    var errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Mengecek apakah file ada di storage dan dapat diputar/dilihat.
     */
    fun isFilePlayable(): Boolean {
        if (status != DownloadStatus.COMPLETED) return false
        val path = localPath ?: return false
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    /**
     * Memeriksa apakah file terindikasi rusak (misal 0 bytes setelah download selesai atau file hilang).
     */
    fun isFileCorrupted(): Boolean {
        if (status == DownloadStatus.FAILED) return true
        if (status == DownloadStatus.COMPLETED) {
            val path = localPath ?: return false
            val file = File(path)
            return !file.exists() || file.length() == 0L
        }
        return false
    }

    fun getFormattedSpeed(): String {
        if (status != DownloadStatus.DOWNLOADING || speedBytesPerSec <= 0) return ""
        val kbps = speedBytesPerSec / 1024.0
        return if (kbps >= 1024) {
            String.format("%.2f MB/s", kbps / 1024.0)
        } else {
            String.format("%.1f KB/s", kbps)
        }
    }

    fun getFormattedProgress(): String {
        val downloadedFormatted = formatBytes(downloadedBytes)
        return if (totalBytes > 0) {
            val totalFormatted = formatBytes(totalBytes)
            val percent = (downloadedBytes * 100 / totalBytes).coerceIn(0, 100)
            "$downloadedFormatted / $totalFormatted ($percent%)"
        } else {
            "$downloadedFormatted (Mengalir...)"
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}
