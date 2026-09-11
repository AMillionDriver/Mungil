package com.mungil.browser

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 📥 Halaman Pengelola Unduhan Mungil Browser
 * Desain ergonomis ala Chrome dengan kartu progres, kecepatan unduh real-time,
 * penanda file rusak / tanda seru, dan pelacak sumber error (Downloader vs Penyimpanan HP).
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var emptyDownloadView: LinearLayout
    private lateinit var btnBackFromDownloads: ImageButton
    private lateinit var btnClearAllDownloads: ImageButton
    private lateinit var adapter: DownloadsAdapter

    private val trackerListener: () -> Unit = {
        runOnUiThread {
            refreshDownloadList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        DownloadTracker.init(applicationContext)

        rvDownloads = findViewById(R.id.rvDownloads)
        emptyDownloadView = findViewById(R.id.emptyDownloadView)
        btnBackFromDownloads = findViewById(R.id.btnBackFromDownloads)
        btnClearAllDownloads = findViewById(R.id.btnClearAllDownloads)

        btnBackFromDownloads.setOnClickListener {
            finish()
        }

        btnClearAllDownloads.setOnClickListener {
            val records = DownloadTracker.getAllRecords()
            if (records.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Hapus Riwayat Unduhan")
                    .setMessage("Hapus seluruh daftar riwayat unduhan dari aplikasi? (File fisik di folder Download tetap tersimpan)")
                    .setPositiveButton("Hapus") { _, _ ->
                        DownloadTracker.clearAllRecords(this)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }

        setupRecyclerView()
        refreshDownloadList()
    }

    private fun setupRecyclerView() {
        adapter = DownloadsAdapter(
            context = this,
            items = DownloadTracker.getAllRecords(),
            onDelete = { record ->
                DownloadTracker.removeRecord(record.id, this)
            }
        )
        rvDownloads.layoutManager = LinearLayoutManager(this)
        rvDownloads.adapter = adapter
    }

    private fun refreshDownloadList() {
        val records = DownloadTracker.getAllRecords()
        adapter.updateData(records)

        if (records.isEmpty()) {
            emptyDownloadView.visibility = View.VISIBLE
            rvDownloads.visibility = View.GONE
            btnClearAllDownloads.visibility = View.GONE
        } else {
            emptyDownloadView.visibility = View.GONE
            rvDownloads.visibility = View.VISIBLE
            btnClearAllDownloads.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadTracker.addListener(trackerListener)
        refreshDownloadList()
    }

    override fun onPause() {
        super.onPause()
        DownloadTracker.removeListener(trackerListener)
    }
}
