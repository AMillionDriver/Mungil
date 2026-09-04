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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar

    // Script Halo Sniffer versi injected (otomatis menyaring & mendownload)
    private val snifferInjectionScript = """
        (function() {
            if (window.__halo_injected) return;
            window.__halo_injected = true;

            console.log('[Mungil Browser] Halo Sniffer Active');

            // Floating indicator
            const badge = document.createElement('div');
            badge.id = 'mungil-badge';
            badge.innerText = '⚡ Mungil Sniffer';
            badge.style.position = 'fixed';
            badge.style.bottom = '20px';
            badge.style.right = '20px';
            badge.style.backgroundColor = '#10b981';
            badge.style.color = '#000';
            badge.style.padding = '8px 14px';
            badge.style.borderRadius = '20px';
            badge.style.fontSize = '12px';
            badge.style.fontWeight = 'bold';
            badge.style.zIndex = '999999';
            badge.style.boxShadow = '0 4px 15px rgba(0,0,0,0.4)';
            badge.style.display = 'none';
            badge.style.cursor = 'pointer';
            document.body.appendChild(badge);

            const detectedUrls = new Set();

            function notifyNative(url, title) {
                if (!url || detectedUrls.has(url)) return;
                detectedUrls.add(url);
                badge.style.display = 'block';
                badge.innerText = '⬇ Unduh Video (' + detectedUrls.size + ')';
                badge.onclick = function() {
                    if (window.AndroidDownloader) {
                        window.AndroidDownloader.downloadVideo(url, title || document.title);
                    }
                };
            }

            // Monitor media elements
            setInterval(() => {
                document.querySelectorAll('video, audio, source').forEach(el => {
                    const src = el.src || el.currentSrc;
                    if (src && src.startsWith('http')) {
                        notifyNative(src, document.title);
                    }
                });
            }, 1000);

            // Hook fetch
            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                if (url && (url.includes('.mp4') || url.includes('.m3u8') || url.includes('v16-webapp') || url.includes('videoplayback') || url.includes('/pass_md5/'))) {
                    notifyNative(url, document.title);
                }
                return origFetch.apply(this, args);
            };
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlEditText = findViewById(R.id.urlEditText)
        progressBar = findViewById(R.id.progressBar)

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
            webView.reload()
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
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // User Agent desktop/mobile modern
        settings.userAgentString = settings.userAgentString + " MungilBrowser/1.0"

        // Hubungkan Interface Java/Kotlin ke JavaScript
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
            // 🛡️ Menahan & membelokkan deep link TikTok agar tidak error ERR_UNKNOWN_URL_SCHEME
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Jika link web biasa (https:// atau http://), biarkan dibuka di dalam webview
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // Jika TikTok mencoba membuka aplikasi resmi (snssdk1180:// atau tiktok://)
                if (url.startsWith("snssdk1180://") || url.startsWith("snssdk1233://") || url.startsWith("tiktok://")) {
                    try {
                        val uri = Uri.parse(url)
                        val fallbackUrl = uri.getQueryParameter("params_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            val decodedUrl = java.net.URLDecoder.decode(fallbackUrl, "UTF-8")
                            view?.loadUrl(decodedUrl)
                            return true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Diamkan agar tidak menampilkan halaman error
                    return true
                }

                // Untuk link luar lainnya (misal: intent:, whatsapp:, mailto:)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(intent)
                } catch (e: Exception) {
                    // Abaikan jika tidak ada aplikasi
                }

                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                urlEditText.setText(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Suntikkan sniffer otomatis saat web selesai memuat
                webView.evaluateJavascript(snifferInjectionScript, null)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                // Sniffing stream video dari network resource
                if (url != null && (url.contains(".mp4") || url.contains(".m3u8") || url.contains("v16-webapp") || url.contains("videoplayback"))) {
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.__halo_injected) { notifyNative('$url', document.title); }",
                            null
                        )
                    }
                }
            }
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

    inner class AndroidBridge {
        @JavascriptInterface
        fun downloadVideo(videoUrl: String, title: String?) {
            runOnUiThread {
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

                    Toast.makeText(this@MainActivity, "⬇ Mengunduh $fileName ke folder Download...", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
