package com.mungil.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
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
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    // Desktop Chrome UA: Ampuh mencegah situs seperti TikTok memblokir feed scrolling
    private val desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // 🛡️ Script Sakti Sniffer Universal & SPA Observer
    private val universalSnifferScript = """
        (function() {
            if (window.__mungil_universal_engine) return;
            window.__mungil_universal_engine = true;

            // 1. Blokir script paksaan redirect ke aplikasi native
            const originalOpen = window.open;
            window.open = function(url, ...args) {
                if (url && (url.startsWith('snssdk') || url.startsWith('tiktok:') || url.includes('play.google.com'))) {
                    return null;
                }
                return originalOpen.apply(this, [url, ...args]);
            };

            // 2. Pantau perubahan URL SPA (YouTube, TikTok, Twitter, IG, Reddit)
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
                setTimeout(detectMedia, 600);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(...args) {
                originalReplaceState.apply(this, args);
                notifyUrlChanged();
                setTimeout(detectMedia, 600);
            };

            window.addEventListener('popstate', () => {
                notifyUrlChanged();
                setTimeout(detectMedia, 600);
            });

            // 3. Deteksi media dalam viewport yang sedang diputar
            function detectMedia() {
                try {
                    const currentUrl = window.location.href;
                    if (window.AndroidDownloader) {
                        window.AndroidDownloader.onMediaPageDetected(currentUrl, document.title || '');
                    }

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

            // MutationObserver untuk memantau feed scroll
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

        val initialUrl = extractSharedUrl() ?: "https://www.google.com"
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

        // ⬇️ ⚡ KLIK TOMBOL DOWNLOAD UNIVERSAL: BUKA BOTTOM SHEET PILIHAN FORMAT
        fabDownload.setOnClickListener {
            showDownloadOptionsBottomSheet()
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

        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

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

                // Blokir skema eksternal paksaan buka aplikasi
                if (url.startsWith("snssdk1180://") ||
                    url.startsWith("snssdk1233://") ||
                    url.startsWith("tiktok://") ||
                    url.startsWith("market://") ||
                    url.contains("play.google.com")
                ) {
                    return true
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
                    updateDownloadButtonState(url ?: "")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript(universalSnifferScript, null)

                if (view == getCurrentTab()?.webView) {
                    updateDownloadButtonState(url ?: "")
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

    // Identifikasi Platform dan Atur Tombol Download
    private fun updateDownloadButtonState(url: String) {
        val tab = getCurrentTab() ?: return
        val currentUrl = url.lowercase()

        runOnUiThread {
            when {
                currentUrl.contains("youtube.com/watch") || currentUrl.contains("youtu.be/") || currentUrl.contains("youtube.com/shorts") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh YouTube"
                }
                currentUrl.contains("tiktok.com") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh TikTok (No-WM)"
                }
                currentUrl.contains("instagram.com/reel") || currentUrl.contains("instagram.com/p/") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh Reels / IG"
                }
                currentUrl.contains("twitter.com") || currentUrl.contains("x.com") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh Video X"
                }
                currentUrl.contains("reddit.com") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh Video Reddit"
                }
                currentUrl.contains("facebook.com") || currentUrl.contains("fb.watch") -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "⚡ Unduh Video FB"
                }
                tab.detectedVideos.isNotEmpty() -> {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = "Unduh Media (${tab.detectedVideos.size})"
                }
                else -> {
                    fabDownload.visibility = View.GONE
                }
            }
        }
    }

    // 🌟 Menampilkan Bottom Sheet Pilihan Format Download
    private fun showDownloadOptionsBottomSheet() {
        val currentTab = getCurrentTab() ?: return
        val currentWebUrl = currentTab.webView.url ?: currentTab.url
        val directDetectedUrl = currentTab.latestVideoUrl

        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_download, null)
        dialog.setContentView(sheetView)

        val tvPlatformBadge: TextView = sheetView.findViewById(R.id.tvPlatformBadge)
        val tvMediaTitle: TextView = sheetView.findViewById(R.id.tvMediaTitle)
        val optVideoHd: LinearLayout = sheetView.findViewById(R.id.optVideoHd)
        val optVideoSaver: LinearLayout = sheetView.findViewById(R.id.optVideoSaver)
        val optAudio: LinearLayout = sheetView.findViewById(R.id.optAudio)
        val btnCopyLink: Button = sheetView.findViewById(R.id.btnCopyLink)
        val btnAltServer: Button = sheetView.findViewById(R.id.btnAltServer)

        // Set Platform Badge & Judul
        val (platformBadge, defaultTitle) = getPlatformInfo(currentWebUrl)
        tvPlatformBadge.text = platformBadge
        tvMediaTitle.text = if (currentTab.title.isNotEmpty() && currentTab.title != "Tab Baru") {
            currentTab.title
        } else {
            defaultTitle
        }

        // 1. Opsi HD
        optVideoHd.setOnClickListener {
            dialog.dismiss()
            executeUniversalDownload(currentWebUrl, directDetectedUrl, currentTab.title, CobaltDownloader.DownloadQuality.HD)
        }

        // 2. Opsi Hemat Kuota
        optVideoSaver.setOnClickListener {
            dialog.dismiss()
            executeUniversalDownload(currentWebUrl, directDetectedUrl, currentTab.title, CobaltDownloader.DownloadQuality.SAVER)
        }

        // 3. Opsi Audio MP3
        optAudio.setOnClickListener {
            dialog.dismiss()
            executeUniversalDownload(currentWebUrl, directDetectedUrl, currentTab.title, CobaltDownloader.DownloadQuality.AUDIO)
        }

        // 4. Salin Link
        btnCopyLink.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Media URL", currentWebUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "📋 Tautan berhasil disalin!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 5. Server Cadangan (Alternative Converter)
        btnAltServer.setOnClickListener {
            dialog.dismiss()
            openAlternativeServer(currentWebUrl)
        }

        dialog.show()
    }

    private fun getPlatformInfo(url: String): Pair<String, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                Pair("⚡ YOUTUBE DOWNLOADER", "Video YouTube")
            lower.contains("tiktok.com") ->
                Pair("⚡ TIKTOK NO-WATERMARK", "Video TikTok")
            lower.contains("instagram.com") ->
                Pair("⚡ INSTAGRAM REELS", "Media Instagram")
            lower.contains("twitter.com") || lower.contains("x.com") ->
                Pair("⚡ X / TWITTER VIDEO", "Video Postingan X")
            lower.contains("reddit.com") ->
                Pair("⚡ REDDIT MEDIA", "Video Reddit")
            lower.contains("facebook.com") || lower.contains("fb.watch") ->
                Pair("⚡ FACEBOOK VIDEO", "Video Facebook")
            else ->
                Pair("⚡ UNIVERSAL DOWNLOADER", "Media Web Terdeteksi")
        }
    }

    private fun executeUniversalDownload(
        webUrl: String,
        directMediaUrl: String?,
        title: String?,
        quality: CobaltDownloader.DownloadQuality
    ) {
        // Coba via Cobalt Universal API terlebih dahulu
        CobaltDownloader.startDownload(
            context = this,
            mediaUrl = webUrl,
            customTitle = title,
            quality = quality,
            userAgent = desktopChromeUA,
            referer = webUrl
        ) { success, _ ->
            // Jika Cobalt gagal dan ada link direct media terdeteksi (Web umum / CDN), jalankan fallback
            if (!success && !directMediaUrl.isNullOrEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Menggunakan jalur direct stream...", Toast.LENGTH_SHORT).show()
                    downloadDirectVideo(directMediaUrl, title)
                }
            }
        }
    }

    private fun openAlternativeServer(url: String) {
        val currentWv = getCurrentTab()?.webView
        val lower = url.lowercase()

        val altUrl = when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> {
                url.replace("m.youtube.com", "www.youtubepp.com")
                    .replace("www.youtube.com", "www.youtubepp.com")
                    .replace("youtu.be/", "www.youtubepp.com/watch?v=")
            }
            lower.contains("tiktok.com") -> {
                "https://snaptik.app"
            }
            else -> {
                "https://en.savefrom.net/248/?url=" + Uri.encode(url)
            }
        }

        currentWv?.loadUrl(altUrl)
        Toast.makeText(this, "Membuka server alternatif...", Toast.LENGTH_SHORT).show()
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
        updateDownloadButtonState(currentUrl)
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
            .setTitle("Mungil Browser v1.2.0")
            .setMessage("Browser super ringan, cepat, dan hemat kuota dengan dukungan Multi-Tab Chrome-style dan Universal Downloader Engine terintegrasi.\n\nUnduh YouTube, TikTok No-WM, IG Reels, Twitter/X, dan web media lainnya dengan pilihan HD, Hemat Kuota, atau MP3!")
            .setPositiveButton("Keren Banget!", null)
            .show()
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
                addRequestHeader("User-Agent", desktopChromeUA)
                addRequestHeader("Referer", getCurrentTab()?.webView?.url ?: "https://www.google.com/")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "⬇ Mengunduh $fileName...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh direct stream: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

        @JavascriptInterface
        fun onMediaPageDetected(url: String, title: String?) {
            runOnUiThread {
                val currentTab = getCurrentTab()
                if (currentTab != null) {
                    if (!title.isNullOrEmpty()) currentTab.title = title
                    updateDownloadButtonState(url)
                }
            }
        }

        @JavascriptInterface
        fun onUrlChanged(url: String, title: String?) {
            runOnUiThread {
                val currentTab = getCurrentTab()
                if (currentTab != null) {
                    currentTab.url = url
                    if (!title.isNullOrEmpty()) currentTab.title = title
                    urlEditText.setText(url)
                    updateDownloadButtonState(url)
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
