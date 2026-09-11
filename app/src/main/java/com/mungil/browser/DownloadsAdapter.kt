package com.mungil.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsAdapter(
    private val context: Context,
    private var items: List<DownloadRecord>,
    private val onDelete: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

    fun updateData(newItems: List<DownloadRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_card, parent, false)
        return DownloadViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DownloadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val ivErrorBadge: ImageView = itemView.findViewById(R.id.ivErrorBadge)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileMeta: TextView = itemView.findViewById(R.id.tvFileMeta)
        private val btnOpenFile: Button = itemView.findViewById(R.id.btnOpenFile)
        private val btnDeleteRecord: ImageButton = itemView.findViewById(R.id.btnDeleteRecord)
        private val pbDownloadProgress: ProgressBar = itemView.findViewById(R.id.pbDownloadProgress)
        private val layoutProgressStats: LinearLayout = itemView.findViewById(R.id.layoutProgressStats)
        private val tvSizeProgress: TextView = itemView.findViewById(R.id.tvSizeProgress)
        private val tvDownloadSpeed: TextView = itemView.findViewById(R.id.tvDownloadSpeed)
        private val layoutErrorBox: LinearLayout = itemView.findViewById(R.id.layoutErrorBox)
        private val tvErrorSource: TextView = itemView.findViewById(R.id.tvErrorSource)
        private val tvErrorDetails: TextView = itemView.findViewById(R.id.tvErrorDetails)

        fun bind(record: DownloadRecord) {
            tvFileName.text = record.fileName

            // Format tanggal/waktu
            val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(record.timestamp))
            val extClean = if (record.fileExtension.startsWith(".")) record.fileExtension.drop(1).uppercase() else record.fileExtension.uppercase()
            tvFileMeta.text = if (extClean.isNotEmpty()) "$extClean • $dateStr" else dateStr

            // Icon berdasarkan ekstensi
            val isAudio = record.mimeType.contains("audio") || record.fileExtension.contains("m4a") || record.fileExtension.contains("mp3")
            if (isAudio) {
                ivFileIcon.setImageResource(R.drawable.ic_audio_track)
            } else {
                ivFileIcon.setImageResource(R.drawable.ic_video_hd)
            }

            // Atur status
            when (record.status) {
                DownloadStatus.DOWNLOADING -> {
                    ivErrorBadge.visibility = View.GONE
                    layoutErrorBox.visibility = View.GONE
                    btnOpenFile.visibility = View.GONE
                    pbDownloadProgress.visibility = View.VISIBLE
                    layoutProgressStats.visibility = View.VISIBLE

                    if (record.totalBytes > 0) {
                        pbDownloadProgress.isIndeterminate = false
                        val progress = (record.downloadedBytes * 100 / record.totalBytes).toInt().coerceIn(0, 100)
                        pbDownloadProgress.progress = progress
                    } else {
                        pbDownloadProgress.isIndeterminate = true
                    }

                    tvSizeProgress.text = record.getFormattedProgress()
                    val speed = record.getFormattedSpeed()
                    if (speed.isNotEmpty()) {
                        tvDownloadSpeed.visibility = View.VISIBLE
                        tvDownloadSpeed.text = "⚡ $speed"
                    } else {
                        tvDownloadSpeed.visibility = View.GONE
                    }
                }

                DownloadStatus.COMPLETED -> {
                    pbDownloadProgress.visibility = View.GONE
                    val isCorrupt = record.isFileCorrupted()

                    if (isCorrupt) {
                        // File terindikasi rusak / 0 bytes / hilang setelah unduh
                        ivErrorBadge.visibility = View.VISIBLE
                        btnOpenFile.visibility = View.GONE
                        layoutProgressStats.visibility = View.GONE
                        layoutErrorBox.visibility = View.VISIBLE

                        tvErrorSource.text = "Asal Kendala: Integritas File (Rusak / Kosong)"
                        tvErrorDetails.text = record.errorMessage ?: "Ukuran file 0 bytes atau file tidak dapat ditemukan di penyimpanan perangkat."
                    } else {
                        ivErrorBadge.visibility = View.GONE
                        layoutErrorBox.visibility = View.GONE
                        btnOpenFile.visibility = View.VISIBLE
                        btnOpenFile.text = "Putar / Buka"
                        layoutProgressStats.visibility = View.VISIBLE

                        val finalSize = DownloadRecord.formatBytes(record.downloadedBytes)
                        tvSizeProgress.text = "Selesai • $finalSize"
                        tvDownloadSpeed.visibility = View.GONE

                        btnOpenFile.setOnClickListener {
                            openDownloadedFile(record)
                        }
                    }
                }

                DownloadStatus.FAILED -> {
                    pbDownloadProgress.visibility = View.GONE
                    btnOpenFile.visibility = View.GONE
                    layoutProgressStats.visibility = View.GONE
                    ivErrorBadge.visibility = View.VISIBLE
                    layoutErrorBox.visibility = View.VISIBLE

                    // Detail sumber error: Downloader/Jaringan vs Storage/App
                    when (record.errorSource) {
                        ErrorSource.NETWORK_OR_DOWNLOADER -> {
                            tvErrorSource.text = "Asal Kendala: Downloader / Server Jaringan"
                        }
                        ErrorSource.LOCAL_STORAGE_OR_APP -> {
                            tvErrorSource.text = "Asal Kendala: Penyimpanan HP / Izin Aplikasi"
                        }
                        ErrorSource.FILE_CORRUPTED, null -> {
                            tvErrorSource.text = "Asal Kendala: File Media Rusak"
                        }
                    }
                    tvErrorDetails.text = record.errorMessage ?: "Gagal memproses pengunduhan media."
                }
            }

            btnDeleteRecord.setOnClickListener {
                onDelete(record)
            }
        }

        private fun openDownloadedFile(record: DownloadRecord) {
            val path = record.localPath
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    try {
                        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                        } else {
                            Uri.fromFile(file)
                        }

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, record.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        return
                    } catch (e: Exception) {
                        Toast.makeText(context, "Tidak ada aplikasi pemutar yang cocok: ${e.message}", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }

            // Jika URI content resolver ada
            if (!record.targetUriString.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(record.targetUriString)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, record.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    Toast.makeText(context, "Gagal membuka media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "File fisik tidak ditemukan di folder Download.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
