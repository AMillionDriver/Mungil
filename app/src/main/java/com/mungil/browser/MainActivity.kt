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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    private val detectedVideos = mutableSetOf<String>()
    private var latestVideoUrl: String? = null
    private var latestVideoTitle: String? = null

    // Desktop UA: Trik ampuh agar TikTok Web tidak memblokir / memaksa buka Play Store
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val snifferInjectionScript = """
        (function() {
            if (window.__mungil_sniffing) return;
            window.__mungil_sniffing = true;

            function checkMedia() {
                try {
                    document.querySelectorAll('video, source').forEach(el => {
                        const src = el.src || el.currentSrc;
                        if (src && src.startsWith('http') && !src.startsWith('blob:') && !src.includes('googlevideo.com/videoplayback?')) {
                            if (window.AndroidDownloader) {
                                window.AndroidDownloader.onVideoFound(src, document.title || 'video');
                            }
                        }
                    });
                } catch(e) {}
            }

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

        // 🎯 FITUR 1: Tekan Enter / Go di Keyboard langsung membuka URL & menutup keyboard
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_NULL
            ) {
                hideKeyboard()
                loadInputUrl()
                true
            } else {
                false
            }
        }

        btnGo.setOnClickListener {
            hideKeyboard()
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
            val currentWebUrl = webView.url ?: ""

            // 🎯 FITUR 2: Penanganan cerdas YouTube vs Direct Video
            if (isYouTubeUrl(currentWebUrl)) {
                openYouTubeDownloader(currentWebUrl)
            } else if (!targetUrl.isNullOrEmpty()) {
                downloadDirectVideo(targetUrl, latestVideoTitle)
            } else {
                Toast.makeText(this, "Belum ada link video terdeteksi", Toast.LENGTH_SHORT).show()
            }
        }

        // Tangani jika dibuka dari menu "Bagikan / Share"
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlEditText.windowToken, 0)
        urlEditText.clearFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 🎯 FITUR 3: Gunakan User Agent Desktop Linux Chrome
        // Ini memanipulasi website agar tidak menampilkan banner paksaan install aplikasi TikTok / YouTube App!
        settings.userAgentString = desktopUserAgent

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
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // Cegah deep link aplikasi agar tetap di web browser
                if (url.startsWith("snssdk1180://") || url.startsWith("snssdk1233://") || url.startsWith("tiktok://")) {
                    try {
                        val uri = Uri.parse(url)
                        val fallbackUrl = uri.getQueryParameter("params_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            val decodedUrl = java.net.URLDecoder.decode(fallbackUrl, "UTF-8")
                            val currentUrl = view?.url
                            if (currentUrl != decodedUrl) {
                                view?.loadUrl(decodedUrl)
                            }
                            return true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return true
                }

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
                webView.evaluateJavascript(snifferInjectionScript, null)

                // Jika di halaman video YouTube, otomatis aktifkan tombol download
                val currentUrl = url ?: ""
                if (isYouTubeUrl(currentUrl)) {
                    runOnUiThread {
                        fabDownload.visibility = View.VISIBLE
                        fabDownload.text = "Unduh YouTube (MP4)"
                    }
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url != null && isDirectVideoMediaUrl(url)) {
                    registerDetectedVideo(url, view?.title)
                }
            }
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        return (url.contains("youtube.com/watch") || url.contains("youtu.be/") || url.contains("youtube.com/shorts"))
    }

    // Hanya deteksi video utuh (TikTok CDN / MP4 asli), jangan tangkap fragmen split stream YouTube
    private fun isDirectVideoMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        val isNotYouTubeChunk = !lower.contains("googlevideo.com") && !lower.contains("videoplayback")
        return isNotYouTubeChunk && (
                lower.contains(".mp4") ||
                lower.contains("v16-webapp") ||
                lower.contains("v19-webapp") ||
                lower.contains("tiktokcdn.com")
        ) && !lower.contains("favicon")
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

    // Mengunduh langsung video MP4 yang utuh (seperti TikTok)
    private fun downloadDirectVideo(videoUrl: String, title: String?) {
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

            Toast.makeText(this, "⬇ Mengunduh $fileName...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Untuk YouTube: Karena YouTube menggunakan DASH adaptive split audio/video,
    // kita arahkan melalui converter/extractor clean yang menggabungkan audio+video menjadi MP4 utuh
    private fun openYouTubeDownloader(ytUrl: String) {
        val encodedUrl = Uri.encode(ytUrl)
        // Buka portal pengunduh video YouTube MP4 utuh langsung di webview Mungil
        val serviceUrl = "https://yt1s.com/en?q=$encodedUrl"
        webView.loadUrl(serviceUrl)
        Toast.makeText(this, "🚀 Menyiapkan video YouTube utuh...", Toast.LENGTH_SHORT).show()
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
