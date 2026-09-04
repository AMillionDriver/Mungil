package com.mungil.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var urlEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var capsuleDownload: LinearLayout
    private lateinit var tvCapsuleLabel: TextView
    private lateinit var ivSecurityStatus: ImageView
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvTabCount: TextView
    private lateinit var tabSwitcherOverlay: LinearLayout
    private lateinit var tabListContainer: LinearLayout
    private lateinit var tvTabSwitcherHeader: TextView

    data class TabItem(
        val id: Long,
        var title: String,
        var url: String,
        val webView: WebView,
        var directStreamUrl: String? = null,
        var canonicalVideoUrl: String? = null,
        var detectedVideoTitle: String? = null,
        var videoDurationSec: Int = 0
    )

    private val tabs = mutableListOf<TabItem>()
    private var currentTabIndex = 0

    // Desktop Chrome UA: Mencegah pembatasan feed scrolling & media quality lock
    private val desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // 🛡️ Script Sniffer Universal Cerdas (Anti-Iklan Pre-Roll + Auto-Replace Main Video)
    private val universalSnifferScript = """
        (function() {
            if (window.__mungil_universal_engine) return;
            window.__mungil_universal_engine = true;

            const adKeywords = [
                '/ads/', '/ad/', 'doubleclick', 'googleads', 'adservice', 'preroll', 'pre-roll',
                'midroll', 'postroll', 'vast', 'vpaid', 'popads', 'banner', 'tracking',
                'syndication', 'advertising', 'video-ads', 'spotxchange', 'aniview', 'adnxs',
                'adsystem', 'pubmatic', 'rubiconproject', 'teads', 'smartadserver', 'innovid',
                'trafficjunky', 'exoclick', 'adtrue', 'juicyads', 'propellerads', 'adsterra',
                'adkeeper', 'mgid', 'adnuntius', 'outbrain', 'taboola', 'revcontent'
            ];

            function isAdUrl(url) {
                if (!url) return true;
                const lower = url.toLowerCase();
                return adKeywords.some(k => lower.includes(k));
            }

            function isAdElement(v) {
                try {
                    const parent = v.closest('[class*="ad-"], [id*="ad-"], [class*="preroll"], [id*="preroll"], [class*="vast"], [class*="vpaid"], .ima-ad-container, #player-ads');
                    if (parent) return true;
                    if (v.duration && v.duration > 0 && v.duration <= 35) {
                        return true;
                    }
                    const rect = v.getBoundingClientRect();
                    const style = window.getComputedStyle(v);
                    if (style.display === 'none' || style.visibility === 'hidden' || rect.width < 80 || rect.height < 60) {
                        return true;
                    }
                } catch(e) {}
                return false;
            }

            const originalOpen = window.open;
            window.open = function(url, ...args) {
                if (url && (url.startsWith('snssdk') || url.startsWith('tiktok:') || url.includes('play.google.com'))) {
                    return null;
                }
                return originalOpen.apply(this, [url, ...args]);
            };

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
                setTimeout(detectBestActiveMedia, 600);
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function(...args) {
                originalReplaceState.apply(this, args);
                notifyUrlChanged();
                setTimeout(detectBestActiveMedia, 600);
            };

            window.addEventListener('popstate', () => {
                notifyUrlChanged();
                setTimeout(detectBestActiveMedia, 600);
            });

            function hookVideoElement(v) {
                if (v.__mungil_hooked) return;
                v.__mungil_hooked = true;

                const onActive = () => {
                    detectBestActiveMedia();
                };

                v.addEventListener('playing', onActive);
                v.addEventListener('loadedmetadata', onActive);
                v.addEventListener('durationchange', onActive);
                v.addEventListener('timeupdate', () => {
                    if (v.currentTime > 3 && !isAdElement(v)) {
                        detectBestActiveMedia();
                    }
                });
            }

            function detectBestActiveMedia() {
                try {
                    const currentUrl = window.location.href;
                    const pageTitle = document.title || '';

                    if (window.AndroidDownloader) {
                        window.AndroidDownloader.onMediaPageDetected(currentUrl, pageTitle);
                    }

                    const videos = Array.from(document.querySelectorAll('video'));
                    videos.forEach(hookVideoElement);

                    let bestCandidate = null;
                    let maxDuration = -1;

                    for (const v of videos) {
                        let src = v.currentSrc || v.src;
                        if (!src) {
                            const source = v.querySelector('source');
                            if (source) src = source.src;
                        }
                        if (!src || !src.startsWith('http') || src.startsWith('blob:') || src.includes('googlevideo.com/videoplayback')) {
                            continue;
                        }

                        if (isAdUrl(src)) continue;

                        const isAd = isAdElement(v);
                        const dur = v.duration || 0;

                        if (!v.paused && !isAd) {
                            bestCandidate = { v, src, duration: dur };
                            break;
                        }

                        if (!isAd && dur > maxDuration) {
                            maxDuration = dur;
                            bestCandidate = { v, src, duration: dur };
                        } else if (!bestCandidate && !isAd) {
                            bestCandidate = { v, src, duration: dur };
                        }
                    }

                    if (bestCandidate && window.AndroidDownloader) {
                        let canonicalLink = currentUrl;
                        try {
                            const parentCard = bestCandidate.v.closest('[data-e2e*="video"], article, [role="article"], .tiktok-feed-item');
                            if (parentCard) {
                                const linkElem = parentCard.querySelector('a[href*="/video/"], a[href*="/reel/"], a[href*="/status/"]');
                                if (linkElem && linkElem.href) {
                                    canonicalLink = linkElem.href;
                                }
                            }
                        } catch(err) {}

                        window.AndroidDownloader.onDirectStreamDetected(
                            bestCandidate.src,
                            canonicalLink,
                            pageTitle,
                            Math.round(bestCandidate.duration || 0)
                        );
                    }
                } catch(e) {}
            }

            try {
                const observer = new MutationObserver(() => {
                    const vids = document.querySelectorAll('video');
                    vids.forEach(hookVideoElement);
                    detectBestActiveMedia();
                });
                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'currentSrc']
                });
            } catch(e) {}

            setInterval(detectBestActiveMedia, 2000);
            detectBestActiveMedia();
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
        capsuleDownload = findViewById(R.id.capsuleDownload)
        tvCapsuleLabel = findViewById(R.id.tvCapsuleLabel)
        ivSecurityStatus = findViewById(R.id.ivSecurityStatus)

        btnNavBack = findViewById(R.id.btnNavBack)
        btnNavForward = findViewById(R.id.btnNavForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        val btnHome: ImageButton = findViewById(R.id.btnHome)
        val btnNewTabTop: ImageButton = findViewById(R.id.btnNewTabTop)
        val btnTabSwitcher: FrameLayout = findViewById(R.id.btnTabSwitcher)
        val btnMoreMenu: ImageButton = findViewById(R.id.btnMoreMenu)

        tvTabCount = findViewById(R.id.tvTabCount)
        tabSwitcherOverlay = findViewById(R.id.tabSwitcherOverlay)
        tabListContainer = findViewById(R.id.tabListContainer)
        tvTabSwitcherHeader = findViewById(R.id.tvTabSwitcherHeader)
        val btnTabSwitcherNew: Button = findViewById(R.id.btnTabSwitcherNew)
        val btnCloseTabSwitcher: ImageButton = findViewById(R.id.btnCloseTabSwitcher)

        val initialUrl = extractSharedUrl() ?: "https://www.google.com"
        addNewTab(initialUrl)

        // ⬅️ Back Navigation
        btnNavBack.setOnClickListener {
            val wv = getCurrentTab()?.webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            }
        }

        // ➡️ Forward Navigation
        btnNavForward.setOnClickListener {
            val wv = getCurrentTab()?.webView
            if (wv != null && wv.canGoForward()) {
                wv.goForward()
            }
        }

        // 🔄 Refresh / Reload
        btnRefresh.setOnClickListener {
            getCurrentTab()?.webView?.reload()
        }

        // 🏠 Home (Google)
        btnHome.setOnClickListener {
            hideKeyboard()
            closeTabSwitcher()
            getCurrentTab()?.webView?.loadUrl("https://www.google.com")
        }

        // 🔍 Enter di Omnibox
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

        // ➕ Tambah Tab Baru (+)
        btnNewTabTop.setOnClickListener {
            hideKeyboard()
            closeTabSwitcher()
            addNewTab("https://www.google.com")
            Toast.makeText(this, "Tab baru dibuka", Toast.LENGTH_SHORT).show()
        }

        btnTabSwitcherNew.setOnClickListener {
            closeTabSwitcher()
            addNewTab("https://www.google.com")
        }

        btnCloseTabSwitcher.setOnClickListener {
            closeTabSwitcher()
        }

        // 📑 Tab Switcher
        btnTabSwitcher.setOnClickListener {
            hideKeyboard()
            toggleTabSwitcher()
        }

        // ⋮ Menu Lainnya
        btnMoreMenu.setOnClickListener { v ->
            showPopupMenu(v)
        }

        // ⚡ KLIK DYNAMIC MEDIA CAPSULE: Buka Bottom Sheet Modern
        capsuleDownload.setOnClickListener {
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
                    updateNavState()
                    updateDownloadButtonState(url ?: "")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript(universalSnifferScript, null)

                if (view == getCurrentTab()?.webView) {
                    updateNavState()
                    updateDownloadButtonState(url ?: "")
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url != null && isDirectVideoMediaUrl(url)) {
                    val tab = tabs.find { it.webView == view }
                    if (tab != null) {
                        if (tab.directStreamUrl == null || tab.videoDurationSec == 0) {
                            tab.directStreamUrl = url
                            if (tab == getCurrentTab()) {
                                runOnUiThread {
                                    updateDownloadButtonState(tab.url)
                                }
                            }
                        }
                    }
                }
            }
        }

        return wv
    }

    private fun updateNavState() {
        val wv = getCurrentTab()?.webView
        val canGoBack = wv?.canGoBack() == true
        val canGoForward = wv?.canGoForward() == true

        btnNavBack.alpha = if (canGoBack) 1.0f else 0.35f
        btnNavForward.alpha = if (canGoForward) 1.0f else 0.35f

        val currentUrl = (wv?.url ?: "").lowercase()
        if (currentUrl.startsWith("https://")) {
            ivSecurityStatus.setImageResource(R.drawable.ic_lock_secure)
        } else {
            ivSecurityStatus.setImageResource(R.drawable.ic_lock_secure)
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        val adKeywords = listOf(
            "/ads/", "/ad/", "doubleclick", "googleads", "adservice", "preroll", "pre-roll",
            "midroll", "postroll", "vast", "vpaid", "popads", "banner", "tracking",
            "syndication", "advertising", "video-ads", "spotxchange", "aniview", "adnxs",
            "adsystem", "pubmatic", "rubiconproject", "teads", "smartadserver", "innovid",
            "trafficjunky", "exoclick", "adtrue", "juicyads", "propellerads", "adsterra",
            "adkeeper", "mgid", "adnuntius", "outbrain", "taboola", "revcontent",
            "amazon-adsystem", "criteo", "scorecardresearch", "zedo", "adroll", "adtech"
        )
        return adKeywords.any { lower.contains(it) }
    }

    private fun isDirectVideoMediaUrl(url: String): Boolean {
        if (isAdUrl(url)) return false
        val lower = url.lowercase()
        val isNotYouTubeChunk = !lower.contains("googlevideo.com") && !lower.contains("videoplayback")
        return isNotYouTubeChunk && (
                lower.contains(".mp4") ||
                lower.contains(".m4v") ||
                lower.contains(".webm") ||
                lower.contains("v16-webapp") ||
                lower.contains("v19-webapp") ||
                lower.contains("tiktokcdn.com") ||
                lower.contains("video.twimg.com") ||
                lower.contains("fbcdn.net")
        ) && !lower.contains("favicon")
    }

    // Identifikasi Platform dan Tampilkan Dynamic Capsule
    private fun updateDownloadButtonState(url: String) {
        val tab = getCurrentTab() ?: return
        val currentUrl = url.lowercase()

        runOnUiThread {
            when {
                currentUrl.contains("youtube.com/watch") || currentUrl.contains("youtu.be/") || currentUrl.contains("youtube.com/shorts") -> {
                    showCapsule("Unduh YouTube • HD")
                }
                currentUrl.contains("tiktok.com") -> {
                    showCapsule("Unduh TikTok • No-WM")
                }
                currentUrl.contains("instagram.com/reel") || currentUrl.contains("instagram.com/p/") -> {
                    showCapsule("Unduh Reels • HD")
                }
                currentUrl.contains("twitter.com") || currentUrl.contains("x.com") -> {
                    showCapsule("Unduh Video X")
                }
                currentUrl.contains("reddit.com") -> {
                    showCapsule("Unduh Video Reddit")
                }
                currentUrl.contains("facebook.com") || currentUrl.contains("fb.watch") -> {
                    showCapsule("Unduh Video FB")
                }
                tab.directStreamUrl != null -> {
                    val label = if (tab.videoDurationSec > 60) {
                        val min = tab.videoDurationSec / 60
                        "Unduh Video • ${min}m"
                    } else {
                        "Unduh Media • HD"
                    }
                    showCapsule(label)
                }
                else -> {
                    hideCapsule()
                }
            }
        }
    }

    private fun showCapsule(label: String) {
        tvCapsuleLabel.text = label
        if (capsuleDownload.visibility != View.VISIBLE) {
            capsuleDownload.alpha = 0f
            capsuleDownload.visibility = View.VISIBLE
            capsuleDownload.animate()
                .alpha(1f)
                .setDuration(220)
                .start()
        }
    }

    private fun hideCapsule() {
        if (capsuleDownload.visibility == View.VISIBLE) {
            capsuleDownload.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    capsuleDownload.visibility = View.GONE
                }
                .start()
        }
    }

    // 🌟 Menampilkan Modern Deep Slate Bottom Sheet
    private fun showDownloadOptionsBottomSheet() {
        val currentTab = getCurrentTab() ?: return
        val currentWebUrl = currentTab.webView.url ?: currentTab.url
        val directStream = currentTab.directStreamUrl
        val targetPostUrl = currentTab.canonicalVideoUrl ?: currentWebUrl

        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_download, null)
        dialog.setContentView(sheetView)

        val tvPlatformBadge: TextView = sheetView.findViewById(R.id.tvPlatformBadge)
        val tvMediaTitle: TextView = sheetView.findViewById(R.id.tvMediaTitle)
        val optVideoHd: LinearLayout = sheetView.findViewById(R.id.optVideoHd)
        val optVideoSaver: LinearLayout = sheetView.findViewById(R.id.optVideoSaver)
        val optAudio: LinearLayout = sheetView.findViewById(R.id.optAudio)
        val btnCopyLink: LinearLayout = sheetView.findViewById(R.id.btnCopyLink)
        val btnAltServer: LinearLayout = sheetView.findViewById(R.id.btnAltServer)

        val (platformBadge, defaultTitle) = getPlatformInfo(targetPostUrl)
        tvPlatformBadge.text = platformBadge
        tvMediaTitle.text = if (currentTab.title.isNotEmpty() && currentTab.title != "Tab Baru") {
            currentTab.title
        } else {
            defaultTitle
        }

        // 1. Opsi HD / Direct Stream
        optVideoHd.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadDirectStreamInApp(
                    context = this,
                    streamUrl = directStream,
                    title = currentTab.title,
                    referer = currentWebUrl,
                    userAgent = desktopChromeUA,
                    isAudio = false
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, currentTab.title, CobaltDownloader.DownloadQuality.HD)
            }
        }

        // 2. Opsi Hemat Kuota
        optVideoSaver.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadViaSystemManager(
                    context = this,
                    url = directStream,
                    title = currentTab.title,
                    referer = currentWebUrl,
                    userAgent = desktopChromeUA,
                    isAudio = false
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, currentTab.title, CobaltDownloader.DownloadQuality.SAVER)
            }
        }

        // 3. Opsi Audio (M4A)
        optAudio.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadDirectStreamInApp(
                    context = this,
                    streamUrl = directStream,
                    title = currentTab.title,
                    referer = currentWebUrl,
                    userAgent = desktopChromeUA,
                    isAudio = true
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, currentTab.title, CobaltDownloader.DownloadQuality.AUDIO)
            }
        }

        // 4. Salin Link
        btnCopyLink.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Media URL", targetPostUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Tautan berhasil disalin", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 5. Server Cadangan di Tab Baru
        btnAltServer.setOnClickListener {
            dialog.dismiss()
            openAlternativeServer(targetPostUrl, inNewTab = true)
        }

        dialog.show()
    }

    private fun getPlatformInfo(url: String): Pair<String, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                Pair("YOUTUBE HD", "Video YouTube")
            lower.contains("tiktok.com") ->
                Pair("TIKTOK NO-WATERMARK", "Video TikTok")
            lower.contains("instagram.com") ->
                Pair("INSTAGRAM REELS", "Media Instagram")
            lower.contains("twitter.com") || lower.contains("x.com") ->
                Pair("X / TWITTER VIDEO", "Video Postingan X")
            lower.contains("reddit.com") ->
                Pair("REDDIT MEDIA", "Video Reddit")
            lower.contains("facebook.com") || lower.contains("fb.watch") ->
                Pair("FACEBOOK VIDEO", "Video Facebook")
            else ->
                Pair("UNIVERSAL STREAM", "Media Web Utama")
        }
    }

    private fun executeCloudResolverDownload(
        targetUrl: String,
        title: String?,
        quality: CobaltDownloader.DownloadQuality
    ) {
        CobaltDownloader.startDownload(
            context = this,
            mediaUrl = targetUrl,
            customTitle = title,
            quality = quality,
            userAgent = desktopChromeUA,
            referer = targetUrl
        ) { success, _ ->
            if (!success) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Format Memerlukan Konverter Web")
                        .setMessage("Server awan sedang sibuk. Ingin membuka alat pengonversi di Tab Baru?\n\n(Halaman dan video di tab ini akan tetap aman dan tidak terganggu)")
                        .setPositiveButton("Buka Tab Baru") { _, _ ->
                            openAlternativeServer(targetUrl, inNewTab = true)
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
        }
    }

    private fun openAlternativeServer(url: String, inNewTab: Boolean = true) {
        val lower = url.lowercase()
        val altUrl = when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                "https://en.savefrom.net/248/?url=" + Uri.encode(url)
            lower.contains("tiktok.com") ->
                "https://snaptik.app"
            lower.contains("twitter.com") || lower.contains("x.com") ->
                "https://twdown.net"
            lower.contains("instagram.com") ->
                "https://snapinsta.app"
            else ->
                "https://en.savefrom.net/248/?url=" + Uri.encode(url)
        }

        if (inNewTab) {
            addNewTab(altUrl)
            Toast.makeText(this, "Membuka server cadangan di Tab Baru...", Toast.LENGTH_SHORT).show()
        } else {
            getCurrentTab()?.webView?.loadUrl(altUrl)
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
        updateNavState()
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
        tvTabSwitcherHeader.text = "Tab Aktif (${tabs.size})"
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
            val tvUrl: TextView = itemView.findViewById(R.id.tvTabUrl)
            val btnClose: ImageButton = itemView.findViewById(R.id.btnCloseTab)
            val viewDot: View = itemView.findViewById(R.id.viewActiveDot)
            val rootLayout: LinearLayout = itemView.findViewById(R.id.tabCardRoot)

            tvTitle.text = if (tab.title.isNotEmpty()) tab.title else (tab.url.ifEmpty { "Tab Baru" })
            tvUrl.text = tab.url.ifEmpty { "about:blank" }

            if (index == currentTabIndex) {
                rootLayout.setBackgroundResource(R.drawable.bg_tab_card_active_sleek)
                viewDot.visibility = View.VISIBLE
            } else {
                rootLayout.setBackgroundResource(R.drawable.bg_tab_card_sleek)
                viewDot.visibility = View.GONE
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
        popup.menu.add(0, 1, 0, "Muat Ulang Halaman")
        popup.menu.add(0, 2, 1, "Hapus Cache Browser")
        popup.menu.add(0, 3, 2, "Tentang Mungil Browser")

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
        Toast.makeText(this, "Cache browser dibersihkan", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Mungil Browser v1.4.0")
            .setMessage("Browser super ringan, cepat, dan hemat kuota dengan desain Studio Slate modern, Ergonomic Bottom Bar, Dynamic Media Capsule, dan Native Stream Downloader.")
            .setPositiveButton("Selesai", null)
            .show()
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlEditText.windowToken, 0)
        urlEditText.clearFocus()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onDirectStreamDetected(directSrc: String, canonicalUrl: String, title: String?, durationSec: Int) {
            runOnUiThread {
                val currentTab = getCurrentTab()
                if (currentTab != null) {
                    if (currentTab.directStreamUrl == null || durationSec > currentTab.videoDurationSec || currentTab.videoDurationSec <= 35) {
                        currentTab.directStreamUrl = directSrc
                        currentTab.videoDurationSec = durationSec
                        if (!canonicalUrl.isNullOrEmpty()) currentTab.canonicalVideoUrl = canonicalUrl
                        if (!title.isNullOrEmpty()) currentTab.detectedVideoTitle = title
                        updateDownloadButtonState(currentTab.url)
                    }
                }
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
                    currentTab.directStreamUrl = null
                    currentTab.videoDurationSec = 0
                    if (!title.isNullOrEmpty()) currentTab.title = title
                    urlEditText.setText(url)
                    updateNavState()
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
