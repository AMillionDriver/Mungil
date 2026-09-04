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

    // Sistem Manajemen Multi-Tab
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

    // Desktop UA: Trik agar TikTok / YouTube tidak memaksa download aplikasi resmi
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

        // Buat tab pertama
        val initialUrl = extractSharedUrl() ?: "https://www.google.com"
        addNewTab(initialUrl)

        // 🏠 1. Tombol Home (Kembali ke Beranda / Google)
        btnHome.setOnClickListener {
            hideKeyboard()
            closeTabSwitcher()
            getCurrentTab()?.webView?.loadUrl("https://www.google.com")
        }

        // 🔍 2. Enter di Keyboard untuk Navigasi URL
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

        // ➕ 3. Tombol Tambah Tab Baru (+)
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

        // 📑 4. Tombol Tab Switcher (Kotak Angka Tab)
        btnTabSwitcher.setOnClickListener {
            hideKeyboard()
            toggleTabSwitcher()
        }

        // ⋮ 5. Tombol Menu Tiga Titik (Settings, About, Clear Data, Desktop Mode)
        btnMoreMenu.setOnClickListener { v ->
            showPopupMenu(v)
        }

        // ⬇️ Tombol Download Video
        fabDownload.setOnClickListener {
            val currentTab = getCurrentTab() ?: return@setOnClickListener
            val targetUrl = currentTab.latestVideoUrl
            val currentWebUrl = currentTab.webView.url ?: ""

            if (isYouTubeUrl(currentWebUrl)) {
                openYouTubeDownloader(currentWebUrl)
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
        settings.userAgentString = desktopUserAgent

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
                super.onPageStarted(view, url, favicon)
                val tab = tabs.find { it.webView == view }
                tab?.url = url ?: ""
                tab?.detectedVideos?.clear()

                if (view == getCurrentTab()?.webView) {
                    urlEditText.setText(url)
                    fabDownload.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript(snifferInjectionScript, null)

                if (view == getCurrentTab()?.webView) {
                    val currentUrl = url ?: ""
                    if (isYouTubeUrl(currentUrl)) {
                        runOnUiThread {
                            fabDownload.visibility = View.VISIBLE
                            fabDownload.text = "Unduh YouTube (MP4)"
                        }
                    }
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
        urlEditText.setText(activeTab.webView.url ?: activeTab.url)

        if (isYouTubeUrl(activeTab.webView.url ?: "")) {
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
            // Jika hanya tinggal 1 tab, buat tab baru bersih
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

    // Popup Menu titik 3 (Settings, About, Refresh, Clear Data)
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

    private fun isYouTubeUrl(url: String): Boolean {
        return (url.contains("youtube.com/watch") || url.contains("youtu.be/") || url.contains("youtube.com/shorts"))
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
                addRequestHeader("User-Agent", getCurrentTab()?.webView?.settings?.userAgentString ?: desktopUserAgent)
                addRequestHeader("Referer", getCurrentTab()?.webView?.url ?: "https://www.tiktok.com/")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "⬇ Mengunduh $fileName...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openYouTubeDownloader(ytUrl: String) {
        val encodedUrl = Uri.encode(ytUrl)
        val serviceUrl = "https://yt1s.com/en?q=$encodedUrl"
        getCurrentTab()?.webView?.loadUrl(serviceUrl)
        Toast.makeText(this, "🚀 Menyiapkan video YouTube utuh...", Toast.LENGTH_SHORT).show()
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
