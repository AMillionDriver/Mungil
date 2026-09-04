package com.mungil.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var fabDownload: ExtendedFloatingActionButton

    // Menyimpan daftar video yang tertangkap per halaman aktif
    private val detectedVideos = mutableSetOf<String>()
    private var latestVideoUrl: String? = null
    private var latestVideoTitle: String? = null

    // Sniffer ringan yang hanya membaca elemen media tanpa merusak DOM / reload
    private val snifferInjectionScript = """
        (function() {
            if (window.__mungil_sniffing) return;
            window.__mungil_sniffing = true;

            function checkMedia() {
                try {
                    document.querySelectorAll('video, audio, source').forEach(el => {
                        const src = el.src || el.currentSrc;
                        if (src && src.startsWith('http') && !src.startsWith('blob:')) {
                            if (window.AndroidDownloader) {
                                window.AndroidDownloader.onVideoFound(src, document.title || 'video');
                            }
                        }
                    });
                } catch(e) {}
            }

            // Periksa berkala tanpa blocking
            setInterval(checkMedia, 2000);
            checkMedia();
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlEditText = findViewById(R.id.urlEditText)
        progressBar = findViewById(R.id.progressBar)
        fabDownload = findViewById(R.id.fabDownload)

        val btnGo: ImageButton = findViewById(R.id.btnGo)
        val btnBack: ImageButton = findViewById(R.id.btnBack)
        val btnRefresh: ImageButton = findViewById(R.id.btnRefresh)

        setupWebView()

        btnGo.setOnClickListener {
            loadInputUrl()
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnRefresh.setOnClickListener {
            detectedVideos.clear()
            fabDownload.visibility = View.GONE
            webView.reload()
        }

        fabDownload.setOnClickListener {
            val targetUrl = latestVideoUrl
            if (!targetUrl.isNullOrEmpty()) {
                downloadVideoFile(targetUrl, latestVideoTitle)
            } else {
                Toast.makeText(this, "Belum ada link video terdeteksi", Toast.LENGTH_SHORT).show()
            }
        }

        // Tangani jika dibuka dari menu "Bagikan / Share" TikTok / YouTube
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val url = extractUrl(sharedText)
                if (url != null) {
                    urlEditText.setText(url)
                    webView.loadUrl(url)
                    return
                }
            }
        }

        // Halaman awal default
        webView.loadUrl("https://www.tiktok.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        // Akselerasi Hardware & Performa Cepat
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Cache bawaan agar loading secepat kilat
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // User Agent mobile modern standar Chrome Android (TikTok tidak akan lag/ngelag)
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        // Hubungkan Interface Native
        webView.addJavascriptInterface(AndroidBridge(), "AndroidDownloader")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // Intercept link tanpa reload loop
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // 1. URL Web HTTP/HTTPS biasa -> biarkan WebView navigasi normal
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // 2. Cegah redirect TikTok snssdk/tiktok yang memicu reload berulang
                if (url.startsWith("snssdk1180://") || url.startsWith("snssdk1233://") || url.startsWith("tiktok://")) {
                    try {
                        val uri = Uri.parse(url)
                        val fallbackUrl = uri.getQueryParameter("params_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            val decodedUrl = java.net.URLDecoder.decode(fallbackUrl, "UTF-8")
                            val currentUrl = view?.url
                            // HANYA load jika beda dengan halaman sekarang (mencegah infinity refresh loop!)
                            if (currentUrl != decodedUrl) {
                                view?.loadUrl(decodedUrl)
                            }
                            return true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return true // Block skema agar tidak crash
                }

                // 3. Skema aplikasi luar (whatsapp, intent, dll)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(intent)
                } catch (e: Exception) {}

                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                urlEditText.setText(url)
                detectedVideos.clear()
                fabDownload.visibility = View.GONE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Injeksi sniffer ringan hanya sekali saat halaman selesai load
                webView.evaluateJavascript(snifferInjectionScript, null)
            }

            // Sniffer langsung dari level Network Android (Super cepat & tanpa eval berulang)
            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url != null && isVideoMediaUrl(url)) {
                    registerDetectedVideo(url, view?.title)
                }
            }
        }
    }

    private fun isVideoMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains(".mp4") ||
                lower.contains(".m3u8") ||
                lower.contains("v16-webapp") ||
                lower.contains("v19-webapp") ||
                lower.contains("tiktokcdn.com") ||
                lower.contains("videoplayback")) && !lower.contains("favicon")
    }

    private fun registerDetectedVideo(url: String, title: String?) {
        if (detectedVideos.contains(url)) return
        detectedVideos.add(url)
        latestVideoUrl = url
        latestVideoTitle = title

        runOnUiThread {
            fabDownload.visibility = View.VISIBLE
            fabDownload.text = "Unduh Video (${detectedVideos.size})"
        }
    }

    private fun loadInputUrl() {
        var input = urlEditText.text.toString().trim()
        if (input.isEmpty()) return

        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            input = if (input.contains(".") && !input.contains(" ")) {
                "https://$input"
            } else {
                "https://www.google.com/search?q=" + Uri.encode(input)
            }
        }
        webView.loadUrl(input)
    }

    private fun extractUrl(text: String): String? {
        val parts = text.split("\\s+".toRegex())
        for (part in parts) {
            if (part.startsWith("http://") || part.startsWith("https://")) {
                return part
            }
        }
        return null
    }

    private fun downloadVideoFile(videoUrl: String, title: String?) {
        try {
            val cleanTitle = (title ?: "video")
                .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                .take(35)
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.mp4"

            val request = DownloadManager.Request(Uri.parse(videoUrl)).apply {
                setTitle("Mengunduh: $fileName")
                setDescription("Video Mungil Downloader")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                addRequestHeader("User-Agent", webView.settings.userAgentString)
                addRequestHeader("Referer", webView.url ?: "https://www.tiktok.com/")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "⬇ Mengunduh $fileName ke folder Download...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onVideoFound(videoUrl: String, title: String?) {
            registerDetectedVideo(videoUrl, title)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
