package com.mungil.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var fabDownload: ExtendedFloatingActionButton
    private lateinit var tvTabCount: TextView
    private lateinit var tabSwitcherOverlay: LinearLayout
    private lateinit var tabListContainer: LinearLayout
    private lateinit var tvTabSwitcherHeader: TextView

    data class TabItem(
        val id: Long,
        var title: String,
        var url: String,
        val webView: WebView,
        val detectedVideos: MutableSet<String> = mutableSetOf(),
        var latestVideoUrl: String? = null,
        var latestVideoTitle: String? = null
    )

    private val tabs = mutableListOf<TabItem>()
    private var currentTabIndex = 0

    // User-Agent Desktop modern standar Chrome Windows/Linux:
    // Mencegah script paksaan download aplikasi di TikTok & Instagram
    private val desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // 🛡️ Script Sakti yang Disuntikkan:
    // 1. Matikan window.open & location redirect yang mencoba memaksa buka app TikTok
    // 2. Sniff URL video aktif secara berkala dan perhatikan perubahan SPA (Single Page App)
    // 3. Patch pushState & replaceState agar URL Bar Mungil otomatis terupdate saat navigasi tanpa reload
    private val superSnifferAndBypassScript = """
        (function() {
            // Mencegah eksekusi ganda
            if (window.__mungil_engine_active) return;
            window.__mungil_engine_active = true;

            // 1. Blokir script TikTok yang mencoba membuka deep link aplikasi
            const originalOpen = window.open;
            window.open = function(url, ...args) {
                if (url && (url.startsWith('snssdk') || url.startsWith('tiktok:') || url.includes('play.google.com'))) {
                    console.log('Blocked app redirect:', url);
                    return null;
                }
                return originalOpen.apply(this, [url, ...args]);
            };

            // 2. Pantau perubahan URL Single Page App (SPA) seperti di YouTube & TikTok
            function notifyUrlChanged() {
                try {
                    if (window.AndroidDownloader) {
                        window.AndroidDownloader.onUrlChanged(window.location.href, document.title || '');
                    }
                } catch(e) {}
            }

            const originalPushState = history.pushState;
            history.pushState = function(...args) {
                originalPushState.apply(this, args);
                notifyUrlChanged();
                setTimeout(detectMedia, 500);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(...args) {
                originalReplaceState.apply(this, args);
                notifyUrlChanged();
                setTimeout(detectMedia, 500);
            };

            window.addEventListener('popstate', () => {
                notifyUrlChanged();
                setTimeout(detectMedia, 500);
            });

            // 3. Sniffing video aktif
            function detectMedia() {
                try {
                    // Cek jika sedang di YouTube
                    if (window.location.hostname.includes('youtube.com') || window.location.hostname.includes('youtu.be')) {
                        if (window.AndroidDownloader) {
                            window.AndroidDownloader.onYouTubeDetected(window.location.href, document.title || 'YouTube Video');
                        }
                    }

                    // Cek elemen video DOM (TikTok, Twitter/X, FB, Web Umum)
                    const videos = Array.from(document.querySelectorAll('video'));
                    for (const v of videos) {
                        let src = v.currentSrc || v.src;
                        if (!src) {
                            const source = v.querySelector('source');
                            if (source) src = source.src;
                        }
                        if (src && src.startsWith('http') && !src.startsWith('blob:') && !src.includes('googlevideo.com/videoplayback?')) {
                            if (window.AndroidDownloader) {
                                window.AndroidDownloader.onVideoFound(src, document.title || 'Video');
                            }
                        }
                    }
                } catch(e) {}
            }

            // MutationObserver untuk mendeteksi video saat scroll feed TikTok
            try {
                const observer = new MutationObserver(() => {
                    detectMedia();
                });
                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'currentSrc']
                });
            } catch(e) {}

            setInterval(detectMedia, 1500);
            detectMedia();
            notifyUrlChanged();
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webContainer = findViewById(R.id.webContainer)
        urlEditText = findViewById(R.id.urlEditText)
        progressBar = findViewById(R.id.progressBar)
        fabDownload = findViewById(R.id.fabDownload)
        tvTabCount = findViewById(R.id.tvTabCount)
        tabSwitcherOverlay = findViewById(R.id.tabSwitcherOverlay)
        tabListContainer = findViewById(R.id.tabListContainer)
        tvTabSwitcherHeader = findViewById(R.id.tvTabSwitcherHeader)

        val btnHome: ImageButton = findViewById(R.id.btnHome)
        val btnNewTab: ImageButton = findViewById(R.id.btnNewTab)
        val btnTabSwitcher: FrameLayout = findViewById(R.id.btnTabSwitcher)
        val btnMoreMenu: ImageButton = findViewById(R.id.btnMoreMenu)
        val btnTabSwitcherNew: Button = findViewById(R.id.btnTabSwitcherNew)

        val initialUrl = extractSharedUrl() ?: "https://www.tiktok.com"
        addNewTab(initialUrl)

        // 🏠 1. Tombol Home (Google)
        btnHome.setOnClickListener {
            hideKeyboard()
            closeTabSwitcher()
            getCurrentTab()?.webView?.loadUrl("https://www.google.com")
        }

        // 🔍 2. Enter di Keyboard Virtual
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_NULL
            ) {
                hideKeyboard()
                closeTabSwitcher()
                loadInputUrl()
                true
            } else {
                false
            }
        }

        // ➕ 3. Tambah Tab Baru (+)
        btnNewTab.setOnClickListener {
            hideKeyboard()
            closeTabSwitcher()
            addNewTab("https://www.google.com")
            Toast.makeText(this, "Tab baru dibuka", Toast.LENGTH_SHORT).show()
        }

        btnTabSwitcherNew.setOnClickListener {
            closeTabSwitcher()
            addNewTab("https://www.google.com")
        }

        // 📑 4. Tab Switcher
        btnTabSwitcher.setOnClickListener {
            hideKeyboard()
            toggleTabSwitcher()
        }

        // ⋮ 5. Menu Titik Tiga
        btnMoreMenu.setOnClickListener { v ->
            showPopupMenu(v)
        }

        // ⬇️ Tombol Download Video
        fabDownload.setOnClickListener {
            val currentTab = getCurrentTab() ?: return@setOnClickListener
            val currentWebUrl = currentTab.webView.url ?: currentTab.url
            val targetUrl = currentTab.latestVideoUrl

            if (isYouTubeVideoUrl(currentWebUrl)) {
                openYouTubeDownloadDialog(currentWebUrl)
            } else if (!targetUrl.isNullOrEmpty()) {
                downloadDirectVideo(targetUrl, currentTab.latestVideoTitle)
            } else {
                Toast.makeText(this, "Belum ada link video terdeteksi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCurrentTab(): TabItem? {
        if (tabs.isEmpty() || currentTabIndex !in tabs.indices) return null
        return tabs[currentTabIndex]
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Dukungan Zoom
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // User-Agent: Desktop Chrome agar TikTok tidak memblokir scroll feed di ponsel
        settings.userAgentString = desktopChromeUA

        wv.addJavascriptInterface(AndroidBridge(), "AndroidDownloader")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == getCurrentTab()?.webView) {
                    if (newProgress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val tab = tabs.find { it.webView == view }
                if (tab != null && !title.isNullOrEmpty()) {
                    tab.title = title
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // 🛑 BLOKIR TOTAL skema aplikasi eksternal (TikTok, Snssdk, Play Store)
                // agar pengalaman scroll TikTok web tidak pernah terputus oleh paksaan install aplikasi
                if (url.startsWith("snssdk1180://") ||
                    url.startsWith("snssdk1233://") ||
                    url.startsWith("tiktok://") ||
                    url.startsWith("market://") ||
                    url.contains("play.google.com")
                ) {
                    return true // Telan redirect, biarkan web tetap berjalan
                }

                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(intent)
                } catch (e: Exception) {}

                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val tab = tabs.find { it.webView == view }
                tab?.url = url ?: ""

                if (view == getCurrentTab()?.webView) {
                    urlEditText.setText(url)
                    checkAndShowYouTubeButton(url ?: "")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript(superSnifferAndBypassScript, null)

                if (view == getCurrentTab()?.webView) {
                    checkAndShowYouTubeButton(url ?: "")
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url != null && isDirectVideoMediaUrl(url)) {
                    val tab = tabs.find { it.webView == view }
                    if (tab != null) {
                        registerDetectedVideoForTab(tab, url, view?.title)
                    }
                }
            }
        }

        return wv
    }

    private fun checkAndShowYouTubeButton(url: String) {
        if (isYouTubeVideoUrl(url)) {
            runOnUiThread {
                fabDownload.visibility = View.VISIBLE
                fabDownload.text = "Unduh YouTube (MP4)"
            }
        }
    }

    private fun addNewTab(url: String) {
        val newWv = createWebView()
        val newTab = TabItem(
            id = System.currentTimeMillis(),
            title = "Tab Baru",
            url = url,
            webView = newWv
        )
        tabs.add(newTab)
        webContainer.addView(newWv)
        switchTab(tabs.size - 1)
        newWv.loadUrl(url)
        updateTabCountUI()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index

        for (i in tabs.indices) {
            tabs[i].webView.visibility = if (i == currentTabIndex) View.VISIBLE else View.GONE
        }

        val activeTab = tabs[currentTabIndex]
        val currentUrl = activeTab.webView.url ?: activeTab.url
        urlEditText.setText(currentUrl)

        if (isYouTubeVideoUrl(currentUrl)) {
            fabDownload.visibility = View.VISIBLE
            fabDownload.text = "Unduh YouTube (MP4)"
        } else if (activeTab.detectedVideos.isNotEmpty()) {
            fabDownload.visibility = View.VISIBLE
            fabDownload.text = "Unduh Video (${activeTab.detectedVideos.size})"
        } else {
            fabDownload.visibility = View.GONE
        }

        updateTabCountUI()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            val current = tabs[0]
            webContainer.removeView(current.webView)
            current.webView.destroy()
            tabs.clear()
            addNewTab("https://www.google.com")
            renderTabList()
            return
        }

        val removedTab = tabs.removeAt(index)
        webContainer.removeView(removedTab.webView)
        removedTab.webView.destroy()

        if (currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        }
        switchTab(currentTabIndex)
        updateTabCountUI()
        renderTabList()
    }

    private fun updateTabCountUI() {
        tvTabCount.text = tabs.size.toString()
        tvTabSwitcherHeader.text = "Tab Terbuka (${tabs.size})"
    }

    private fun toggleTabSwitcher() {
        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            closeTabSwitcher()
        } else {
            renderTabList()
            tabSwitcherOverlay.visibility = View.VISIBLE
        }
    }

    private fun closeTabSwitcher() {
        tabSwitcherOverlay.visibility = View.GONE
    }

    private fun renderTabList() {
        tabListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        tabs.forEachIndexed { index, tab ->
            val itemView = inflater.inflate(R.layout.item_tab_card, tabListContainer, false)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTabTitle)
            val btnClose: ImageButton = itemView.findViewById(R.id.btnCloseTab)
            val rootLayout: LinearLayout = itemView.findViewById(R.id.tabCardRoot)

            tvTitle.text = if (tab.title.isNotEmpty()) tab.title else (tab.url.ifEmpty { "Tab Baru" })

            if (index == currentTabIndex) {
                rootLayout.setBackgroundResource(R.drawable.bg_tab_card_active)
            } else {
                rootLayout.setBackgroundResource(R.drawable.bg_tab_card)
            }

            rootLayout.setOnClickListener {
                switchTab(index)
                closeTabSwitcher()
            }

            btnClose.setOnClickListener {
                closeTab(index)
            }

            tabListContainer.addView(itemView)
        }
    }

    private fun showPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "🔄 Muat Ulang Halaman")
        popup.menu.add(0, 2, 1, "🗑️ Hapus Cache Browser")
        popup.menu.add(0, 3, 2, "ℹ️ Tentang Mungil Browser")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    getCurrentTab()?.webView?.reload()
                    true
                }
                2 -> {
                    clearBrowserCache()
                    true
                }
                3 -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun clearBrowserCache() {
        getCurrentTab()?.webView?.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, "Cache browser berhasil dibersihkan!", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Mungil Browser v1.0.0")
            .setMessage("Browser super ringan, cepat, dan hemat kuota dengan dukungan Multi-Tab Chrome-style dan Video Sniffer terintegrasi.\n\nDibuat khusus untuk kenyamanan berselancar tanpa batas!")
            .setPositiveButton("Keren!", null)
            .show()
    }

    // Mendeteksi link video YouTube (watch, shorts, share link)
    private fun isYouTubeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("youtube.com/watch") ||
                lower.contains("youtu.be/") ||
                lower.contains("youtube.com/shorts"))
    }

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

    private fun registerDetectedVideoForTab(tab: TabItem, url: String, title: String?) {
        if (tab.detectedVideos.contains(url)) return
        tab.detectedVideos.add(url)
        tab.latestVideoUrl = url
        tab.latestVideoTitle = title

        if (tab == getCurrentTab()) {
            runOnUiThread {
                fabDownload.visibility = View.VISIBLE
                fabDownload.text = "Unduh Video (${tab.detectedVideos.size})"
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
        getCurrentTab()?.webView?.loadUrl(input)
    }

    private fun extractSharedUrl(): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                val parts = sharedText.split("\\s+".toRegex())
                for (part in parts) {
                    if (part.startsWith("http://") || part.startsWith("https://")) {
                        return part
                    }
                }
            }
        }
        return null
    }

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
                addRequestHeader("User-Agent", getCurrentTab()?.webView?.settings?.userAgentString ?: desktopChromeUA)
                addRequestHeader("Referer", getCurrentTab()?.webView?.url ?: "https://www.tiktok.com/")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "⬇ Mengunduh $fileName...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 🎯 Solusi Anti-Gagal YouTube:
    // Menampilkan pilihan server download langsung di dalam dialog tanpa resiko error 404
    private fun openYouTubeDownloadDialog(ytUrl: String) {
        val options = arrayOf(
            "⚡ Server 1 (Y2Mate Quick MP4/MP3)",
            "🌐 Server 2 (SSYouTube Web)",
            "📋 Salin Tautan Video"
        )

        AlertDialog.Builder(this)
            .setTitle("Unduh Video YouTube")
            .setItems(options) { _, which ->
                val currentWv = getCurrentTab()?.webView
                when (which) {
                    0 -> {
                        // Trik menyisipkan 'pp' pada youtube.com menjadi youtubepp.com (Metode Y2Mate paling stabil di dunia)
                        val y2mateUrl = ytUrl
                            .replace("m.youtube.com", "www.youtubepp.com")
                            .replace("www.youtube.com", "www.youtubepp.com")
                            .replace("youtu.be/", "www.youtubepp.com/watch?v=")
                        currentWv?.loadUrl(y2mateUrl)
                        Toast.makeText(this, "Membuka server pengunduh...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        // Trik menyisipkan 'ss' pada youtube.com menjadi ssyoutube.com
                        val ssUrl = ytUrl
                            .replace("m.youtube.com", "ssyoutube.com")
                            .replace("www.youtube.com", "ssyoutube.com")
                            .replace("youtu.be/", "ssyoutube.com/watch?v=")
                        currentWv?.loadUrl(ssUrl)
                        Toast.makeText(this, "Membuka server pengunduh...", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("YouTube URL", ytUrl)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Tautan berhasil disalin!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlEditText.windowToken, 0)
        urlEditText.clearFocus()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onVideoFound(videoUrl: String, title: String?) {
            val currentTab = getCurrentTab()
            if (currentTab != null) {
                registerDetectedVideoForTab(currentTab, videoUrl, title)
            }
        }

        // Listener saat user berpindah video di YouTube (SPA Navigation)
        @JavascriptInterface
        fun onYouTubeDetected(url: String, title: String?) {
            runOnUiThread {
                val currentTab = getCurrentTab()
                if (currentTab != null && isYouTubeVideoUrl(url)) {
                    urlEditText.setText(url)
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "Unduh YouTube (MP4)"
                }
            }
        }

        // Sinkronkan URL Bar secara otomatis saat single page app mengubah path URL
        @JavascriptInterface
        fun onUrlChanged(url: String, title: String?) {
            runOnUiThread {
                val currentTab = getCurrentTab()
                if (currentTab != null) {
                    currentTab.url = url
                    if (!title.isNullOrEmpty()) currentTab.title = title
                    urlEditText.setText(url)
                    if (isYouTubeVideoUrl(url)) {
                        fabDownload.visibility = View.VISIBLE
                        fabDownload.text = "Unduh YouTube (MP4)"
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        if (tabSwitcherOverlay.visibility == View.VISIBLE) {
            closeTabSwitcher()
            return
        }

        val currentWv = getCurrentTab()?.webView
        if (currentWv != null && currentWv.canGoBack()) {
            currentWv.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
