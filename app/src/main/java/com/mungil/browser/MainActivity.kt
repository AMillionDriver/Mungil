package com.mungil.browser

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
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
        var videoDurationSec: Int = 0,
        var isDesktopMode: Boolean = false,
        var isDevToolsActive: Boolean = false
    )

    private val tabs = mutableListOf<TabItem>()
    private var currentTabIndex = 0

    private val PREFS_NAME = "mungil_browser_prefs"
    private val KEY_PAUSE_BACKGROUND_MEDIA = "pause_background_media"
    private var isPauseBackgroundMediaEnabled = false

    // User Agents: Default ke Mobile UA agar web responsif di layar ponsel (seperti Terabox),
    // dengan opsi toggle ke Desktop UA kapan saja.
    private val mobileChromeUA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"
    private val desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Callback untuk Upload File / Gambar / Screenshot di web (seperti Google AI, ChatGPT, media upload)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult

        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val clipData = data.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    val list = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        list.add(clipData.getItemAt(i).uri)
                    }
                    results = list.toTypedArray()
                } else if (data.data != null) {
                    results = arrayOf(data.data!!)
                }
            }
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    // 🛡️ Script Sniffer Universal Cerdas v2
    // • Filter iklan pre-roll & banner VAST/VPAID web dewasa & movie streaming
    // • Ekstraksi judul & author spesifik (TikTok, Terabox, video headings)
    // • Auto-replace preroll saat video utama berdurasi panjang / aktif diputar
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
                'adkeeper', 'mgid', 'adnuntius', 'outbrain', 'taboola', 'revcontent',
                'amazon-adsystem', 'criteo', 'scorecardresearch', 'zedo', 'adroll', 'adtech',
                'trafficstars', 'chaturbate', 'stripchat', 'livejasmin', 'bongacams', 'cam4',
                'clickadu', 'ero-advertising', 'realsrv', 'tsyndicate', 'adxpansion', 'bidgear',
                'hilltopads', 'evadav', 'coinhive', 'fidelity', 'yllix', 'creative', 'promo',
                'teaser', 'zoneid', 'campaignid', 'popcash', 'creative.js', 'banner.mp4'
            ];

            function isAdUrl(url) {
                if (!url) return true;
                const lower = url.toLowerCase();
                return adKeywords.some(k => lower.includes(k));
            }

            function isAdElement(v) {
                try {
                    const parent = v.closest('[class*="ad-"], [id*="ad-"], [class*="preroll"], [id*="preroll"], [class*="vast"], [class*="vpaid"], .ima-ad-container, #player-ads, [class*="sponsor"], [id*="sponsor"], [class*="banner"], [id*="banner"]');
                    if (parent) return true;

                    // Iklan video web dewasa / streaming biasanya berdurasi pendek (5 - 65 detik)
                    const dur = v.duration || 0;
                    const isShortsPlatform = window.location.hostname.includes('tiktok') || 
                                              window.location.hostname.includes('instagram') || 
                                              window.location.href.includes('/shorts/');
                    if (!isShortsPlatform && dur > 0 && dur <= 65) {
                        return true;
                    }

                    // Elemen tersembunyi atau ukuran banner kecil
                    const rect = v.getBoundingClientRect();
                    const style = window.getComputedStyle(v);
                    if (style.display === 'none' || style.visibility === 'hidden' || rect.width < 160 || rect.height < 100) {
                        return true;
                    }
                } catch(e) {}
                return false;
            }

            // Ekstraksi judul video yang akurat dan spesifik
            function extractSpecificVideoTitle(v) {
                try {
                    // 1. TikTok: username + caption
                    if (window.location.hostname.includes('tiktok')) {
                        const desc = document.querySelector('[data-e2e="browse-video-desc"], [data-e2e="video-desc"], .video-meta-title, h1');
                        const author = document.querySelector('[data-e2e="browse-username"], [data-e2e="video-author-uniqueid"], .author-uniqueId');
                        if (desc && desc.innerText.trim()) {
                            const a = author ? author.innerText.trim() : '';
                            return (a ? a + ' - ' : '') + desc.innerText.trim();
                        }
                    }

                    // 2. Terabox: nama file asli yang sedang dibuka
                    if (window.location.hostname.includes('terabox')) {
                        const tb = document.querySelector('.file-name, .video-title, .title-text, .detail-name, h1');
                        if (tb && tb.innerText.trim()) {
                            return tb.innerText.trim();
                        }
                    }

                    // 3. Judul dari atribut elemen video
                    const attr = v.getAttribute('title') || v.getAttribute('aria-label');
                    if (attr && attr.trim()) return attr.trim();

                    // 4. Heading di dekat player
                    const h1 = document.querySelector('h1, h2.entry-title, .video-title, [itemprop="name"]');
                    if (h1 && h1.innerText.trim().length > 3 && h1.innerText.trim().length < 120) {
                        return h1.innerText.trim();
                    }

                    // 5. Meta OpenGraph
                    const og = document.querySelector('meta[property="og:title"]');
                    if (og && og.content && og.content.trim()) {
                        return og.content.trim();
                    }
                } catch(e) {}
                return document.title || '';
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
                    if (v.currentTime > 2 && !isAdElement(v)) {
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
                            const sources = v.querySelectorAll('source');
                            for (const s of sources) {
                                if (s.src && s.src.startsWith('http') && !isAdUrl(s.src)) {
                                    src = s.src;
                                    break;
                                }
                            }
                        }

                        if (!src || !src.startsWith('http') || src.startsWith('blob:') || src.includes('googlevideo.com/videoplayback')) {
                            continue;
                        }

                        if (isAdUrl(src)) continue;
                        if (isAdElement(v)) continue;

                        const dur = v.duration || 0;

                        // Jika video sedang aktif diputar oleh pengguna, prioritaskan langsung!
                        if (!v.paused && v.currentTime > 0) {
                            bestCandidate = { v, src, duration: dur };
                            break;
                        }

                        if (dur > maxDuration) {
                            maxDuration = dur;
                            bestCandidate = { v, src, duration: dur };
                        } else if (!bestCandidate) {
                            bestCandidate = { v, src, duration: dur };
                        }
                    }

                    // Fallback: jika direct stream pada elemen video adalah blob/MSE, cek tag OpenGraph / Twitter video
                    if (!bestCandidate) {
                        const ogVideo = document.querySelector('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"], meta[property="twitter:player:stream"]');
                        if (ogVideo && ogVideo.content && ogVideo.content.startsWith('http') && !isAdUrl(ogVideo.content)) {
                            bestCandidate = { v: videos[0] || document.body, src: ogVideo.content, duration: 0 };
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

                        const extractedTitle = extractSpecificVideoTitle(bestCandidate.v);

                        window.AndroidDownloader.onDirectStreamDetected(
                            bestCandidate.src,
                            canonicalLink,
                            extractedTitle,
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isPauseBackgroundMediaEnabled = prefs.getBoolean(KEY_PAUSE_BACKGROUND_MEDIA, false)

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
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Default: Mobile Chrome UA agar situs responsif di HP (Terabox, etc.)
        settings.userAgentString = mobileChromeUA

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

            // 📁 Dukungan Penuh Upload File / Lampiran / Gambar (Google AI Chat, Gemini, Facebook, form)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }

                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }

                    filePickerLauncher.launch(Intent.createChooser(intent, "Pilih File / Foto"))
                    return true
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        filePickerLauncher.launch(Intent.createChooser(fallbackIntent, "Pilih File"))
                        return true
                    } catch (err: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        Toast.makeText(this@MainActivity, "Tidak dapat membuka pemilih file", Toast.LENGTH_SHORT).show()
                        return false
                    }
                }
            }

            // 🔐 Izin Web Permissions (Clipboard, Protected Media, dll.)
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
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
                    if (!url.isNullOrEmpty() && !url.startsWith("chrome-error://") && !url.startsWith("about:blank")) {
                        urlEditText.setText(url)
                    }
                    updateNavState()
                    updateDownloadButtonState(url ?: "")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript(universalSnifferScript, null)

                // Jika DevTools aktif di tab ini, suntikkan ulang entry button
                val tab = tabs.find { it.webView == view }
                if (tab?.isDevToolsActive == true) {
                    injectDevTools(wv, forceOpen = false)
                }

                if (view == getCurrentTab()?.webView) {
                    if (!url.isNullOrEmpty() && !url.startsWith("chrome-error://") && !url.startsWith("about:blank")) {
                        urlEditText.setText(url)
                    }
                    updateNavState()
                    updateDownloadButtonState(url ?: "")
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url != null && isDirectVideoMediaUrl(url)) {
                    val tab = tabs.find { it.webView == view }
                    if (tab != null) {
                        // Jangan timpa jika sniffer DOM sudah menemukan video atau durasi video sudah valid
                        if (tab.directStreamUrl == null && tab.videoDurationSec == 0) {
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
            "amazon-adsystem", "criteo", "scorecardresearch", "zedo", "adroll", "adtech",
            "trafficstars", "chaturbate", "stripchat", "livejasmin", "bongacams", "cam4",
            "clickadu", "ero-advertising", "realsrv", "tsyndicate", "adxpansion", "bidgear",
            "hilltopads", "evadav", "coinhive", "fidelity", "yllix", "creative", "promo",
            "teaser", "zoneid", "campaignid", "popcash"
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
                lower.contains(".m3u8") ||
                lower.contains("v16-webapp") ||
                lower.contains("v19-webapp") ||
                lower.contains("tiktokcdn") ||
                lower.contains("video.twimg.com") ||
                lower.contains("fbcdn.net") ||
                lower.contains("cdninstagram.com") ||
                lower.contains("v.redd.it") ||
                lower.contains("mime=video") ||
                lower.contains("mime/video")
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
                currentUrl.contains("terabox.com") || currentUrl.contains("teraboxapp.com") -> {
                    val label = if (tab.directStreamUrl != null) "Unduh Video Terabox" else "Alat Unduh Terabox"
                    showCapsule(label)
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

        // Gunakan nama video spesifik (bukan /main atau domain)
        val effectiveTitle = when {
            !currentTab.detectedVideoTitle.isNullOrBlank() -> currentTab.detectedVideoTitle!!
            currentTab.title.isNotBlank() && currentTab.title != "Tab Baru" && !currentTab.title.contains("http") -> currentTab.title
            else -> defaultTitle
        }
        tvMediaTitle.text = effectiveTitle

        val userAgentToUse = if (currentTab.isDesktopMode) desktopChromeUA else mobileChromeUA

        // 1. Opsi HD / Direct Stream
        optVideoHd.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadDirectStreamInApp(
                    context = this,
                    streamUrl = directStream,
                    title = effectiveTitle,
                    referer = currentWebUrl,
                    userAgent = userAgentToUse,
                    isAudio = false
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, effectiveTitle, CobaltDownloader.DownloadQuality.HD)
            }
        }

        // 2. Opsi Hemat Kuota
        optVideoSaver.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadDirectStreamInApp(
                    context = this,
                    streamUrl = directStream,
                    title = effectiveTitle,
                    referer = currentWebUrl,
                    userAgent = userAgentToUse,
                    isAudio = false
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, effectiveTitle, CobaltDownloader.DownloadQuality.SAVER)
            }
        }

        // 3. Opsi Audio (M4A)
        optAudio.setOnClickListener {
            dialog.dismiss()
            if (!directStream.isNullOrEmpty()) {
                NativeStreamDownloader.downloadDirectStreamInApp(
                    context = this,
                    streamUrl = directStream,
                    title = effectiveTitle,
                    referer = currentWebUrl,
                    userAgent = userAgentToUse,
                    isAudio = true
                )
            } else {
                executeCloudResolverDownload(targetPostUrl, effectiveTitle, CobaltDownloader.DownloadQuality.AUDIO)
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

        // 5. Server Cadangan / Pengunduh Cepat di Tab Baru
        btnAltServer.setOnClickListener {
            dialog.dismiss()
            showFastDownloaderSelector(targetPostUrl)
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
            lower.contains("terabox") ->
                Pair("TERABOX CLOUD", "File Video Terabox")
            else ->
                Pair("UNIVERSAL STREAM", "Media Web Utama")
        }
    }

    private fun executeCloudResolverDownload(
        targetUrl: String,
        title: String?,
        quality: CobaltDownloader.DownloadQuality
    ) {
        val currentTab = getCurrentTab()
        val userAgentToUse = if (currentTab?.isDesktopMode == true) desktopChromeUA else mobileChromeUA

        CobaltDownloader.startDownload(
            context = this,
            mediaUrl = targetUrl,
            customTitle = title,
            quality = quality,
            userAgent = userAgentToUse,
            referer = targetUrl
        ) { success, _ ->
            if (!success) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("⚡ Antrean Cloud Padat")
                        .setMessage("Server cloud sedang sibuk / antre. Beralih ke Pengunduh Cepat (bebas antre) di Tab Baru?\n\n(Tautan otomatis disalin ke clipboard, halaman ini tetap aman)")
                        .setPositiveButton("Buka Pengunduh Cepat") { _, _ ->
                            showFastDownloaderSelector(targetUrl)
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
        }
    }

    private fun showFastDownloaderSelector(url: String) {
        val lower = url.lowercase()
        val servers: List<Pair<String, String>> = when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> listOf(
                "SaveFrom (Cepat, Tanpa Antre)" to "https://en.savefrom.net/",
                "Y2Mate (HD Video & MP3 Audio)" to "https://y2mate.is/",
                "Cobalt Tools (Universal Cloud)" to "https://cobalt.tools"
            )
            lower.contains("tiktok.com") -> listOf(
                "TikWM (Instan Tanpa Watermark)" to "https://tikwm.com/",
                "SnapTik (Server Alternatif Cepat)" to "https://snaptik.app/",
                "Cobalt Tools" to "https://cobalt.tools"
            )
            lower.contains("instagram.com") -> listOf(
                "FastDL (Instagram Reels & Post)" to "https://fastdl.app/",
                "SnapInsta (Instagram Video HD)" to "https://snapinsta.app/",
                "Cobalt Tools" to "https://cobalt.tools"
            )
            lower.contains("twitter.com") || lower.contains("x.com") -> listOf(
                "SSSTwitter (Video X / Twitter HD)" to "https://ssstwitter.com/",
                "TwitterVid (Cepat)" to "https://twittervid.com/",
                "Cobalt Tools" to "https://cobalt.tools"
            )
            lower.contains("facebook.com") || lower.contains("fb.watch") -> listOf(
                "FDown (Facebook Video HD)" to "https://fdown.net/",
                "SnapSave (Facebook Reels)" to "https://snapsave.app/",
                "Cobalt Tools" to "https://cobalt.tools"
            )
            lower.contains("terabox") -> listOf(
                "TeraBox Downloader (Direct)" to "https://teraboxdownloader.net/",
                "Cobalt Tools" to "https://cobalt.tools"
            )
            else -> listOf(
                "SaveFrom (Universal Web Video)" to "https://en.savefrom.net/",
                "Cobalt Tools (Multi-Platform)" to "https://cobalt.tools"
            )
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Media URL", url)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {}

        val labels = servers.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("⚡ Pilih Pengunduh Cepat (Bebas Antre)")
            .setItems(labels) { _, which ->
                val chosenUrl = servers[which].second
                addNewTab(chosenUrl)
                Toast.makeText(this, "📋 Tautan disalin! Tinggal tempel di kolom pengunduh.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun addNewTab(url: String) {
        val newWv = createWebView()
        val newTab = TabItem(
            id = System.currentTimeMillis(),
            title = "Tab Baru",
            url = url,
            webView = newWv,
            isDesktopMode = false
        )
        tabs.add(newTab)
        webContainer.addView(newWv)
        switchTab(tabs.size - 1)
        newWv.loadUrl(url)
        updateTabCountUI()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        val previousTabIndex = currentTabIndex
        currentTabIndex = index

        // Jika opsi jeda media aktif, jeda video/audio di tab sebelumnya
        if (isPauseBackgroundMediaEnabled && previousTabIndex != currentTabIndex && previousTabIndex in tabs.indices) {
            tabs[previousTabIndex].webView.evaluateJavascript(
                "try { document.querySelectorAll('video, audio').forEach(function(el) { el.pause(); }); } catch(e) {}",
                null
            )
        }

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

    // 🛠️ Integrasi Eruda DevTools Web Inspector (Offline & Kebal CSP)
    private var erudaSourceCode: String? = null

    private fun getErudaCode(): String {
        if (erudaSourceCode == null) {
            erudaSourceCode = try {
                assets.open("eruda.js").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }
        return erudaSourceCode ?: ""
    }

    private fun injectDevTools(wv: WebView, forceOpen: Boolean = false) {
        val checkScript = if (forceOpen) {
            "if (typeof eruda !== 'undefined') { eruda.show(); '__ALREADY__'; } else { '__NEED_LOAD__'; }"
        } else {
            "if (typeof eruda !== 'undefined') { '__ALREADY__'; } else { '__NEED_LOAD__'; }"
        }

        wv.evaluateJavascript(checkScript) { result ->
            if (result?.contains("__NEED_LOAD__") == true) {
                val code = getErudaCode()
                if (code.isNotEmpty()) {
                    wv.evaluateJavascript(code) {
                        val initScript = "try { eruda.init(); " + (if (forceOpen) "eruda.show();" else "") + " } catch(e){}"
                        wv.evaluateJavascript(initScript, null)
                    }
                }
            }
        }
    }

    private fun toggleDevTools() {
        val tab = getCurrentTab() ?: return
        tab.isDevToolsActive = !tab.isDevToolsActive

        if (tab.isDevToolsActive) {
            injectDevTools(tab.webView, forceOpen = true)
            Toast.makeText(this, "🛠️ DevTools Aktif! Panel inspeksi dibuka.", Toast.LENGTH_SHORT).show()
        } else {
            val destroyScript = "try { if (typeof eruda !== 'undefined') { eruda.destroy(); } } catch(e){}"
            tab.webView.evaluateJavascript(destroyScript, null)
            Toast.makeText(this, "DevTools dinonaktifkan", Toast.LENGTH_SHORT).show()
        }
    }

    // 🖥️ Toggle Desktop Site vs Mobile Site
    private fun toggleDesktopMode() {
        val tab = getCurrentTab() ?: return
        tab.isDesktopMode = !tab.isDesktopMode

        tab.webView.settings.userAgentString = if (tab.isDesktopMode) desktopChromeUA else mobileChromeUA
        tab.webView.reload()

        val modeLabel = if (tab.isDesktopMode) "Situs Desktop (PC)" else "Situs Seluler (Responsif)"
        Toast.makeText(this, "Beralih ke $modeLabel", Toast.LENGTH_SHORT).show()
    }

    private fun showPopupMenu(anchor: View) {
        val currentTab = getCurrentTab()
        val popup = PopupMenu(this, anchor)

        popup.menu.add(0, 1, 0, "Muat Ulang Halaman")

        val desktopText = if (currentTab?.isDesktopMode == true) "Mode Seluler (Responsif)" else "Situs Desktop (PC)"
        popup.menu.add(0, 2, 1, desktopText)

        val devToolsText = if (currentTab?.isDevToolsActive == true) "Sembunyikan DevTools" else "🛠️ DevTools Web (Inspector)"
        popup.menu.add(0, 3, 2, devToolsText)

        popup.menu.add(0, 4, 3, "Hapus Cache Browser")
        popup.menu.add(0, 6, 4, "⚙️ Pengaturan Browser")
        popup.menu.add(0, 5, 5, "Tentang Mungil Browser")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    getCurrentTab()?.webView?.reload()
                    true
                }
                2 -> {
                    toggleDesktopMode()
                    true
                }
                3 -> {
                    toggleDevTools()
                    true
                }
                4 -> {
                    clearBrowserCache()
                    true
                }
                6 -> {
                    showSettingsDialog()
                    true
                }
                5 -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val items = arrayOf(
            "Jeda media saat beralih tab\n(Matikan jika ingin mendengarkan musik di latar belakang)"
        )
        val checkedItems = booleanArrayOf(isPauseBackgroundMediaEnabled)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Pengaturan Browser")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                when (which) {
                    0 -> {
                        isPauseBackgroundMediaEnabled = isChecked
                        prefs.edit().putBoolean(KEY_PAUSE_BACKGROUND_MEDIA, isChecked).apply()
                    }
                }
            }
            .setPositiveButton("Selesai") { dialog, _ ->
                dialog.dismiss()
                val status = if (isPauseBackgroundMediaEnabled) "diaktifkan" else "dinonaktifkan"
                Toast.makeText(this, "Jeda media latar belakang $status", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun clearBrowserCache() {
        getCurrentTab()?.webView?.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, "Cache browser dibersihkan", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.6.0"
        }

        AlertDialog.Builder(this)
            .setTitle("Mungil Browser v$versionName")
            .setMessage("Browser super ramping, cepat, dan hemat kuota dengan:\n\n" +
                    "• Tampilan Studio Slate & Ergonomic Bottom Navigation\n" +
                    "• Dynamic Media Capsule Downloader (HD, Saver, MP3)\n" +
                    "• Pengunduh Bersih Bebas Iklan & Filter Pre-roll\n" +
                    "• Penamaan Otomatis Video Spesifik (Bukan /main)\n" +
                    "• Dukungan Penuh Upload File & Screenshot Paste (AI Chat Ready)\n" +
                    "• DevTools Web Inspector Offline (Eruda CSP-Safe)\n" +
                    "• Toggle Tampilan Desktop / Seluler Responsif")
            .setPositiveButton("Keren", null)
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
                    val isCurrentAd = currentTab.videoDurationSec in 1..65
                    val isNewLonger = durationSec > currentTab.videoDurationSec

                    // Gantikan jika tab belum punya stream, atau stream sebelumnya adalah iklan pre-roll, atau video baru berdurasi lebih panjang
                    if (currentTab.directStreamUrl == null || isCurrentAd || isNewLonger) {
                        currentTab.directStreamUrl = directSrc
                        currentTab.videoDurationSec = durationSec
                        if (!canonicalUrl.isNullOrEmpty()) currentTab.canonicalVideoUrl = canonicalUrl
                        if (!title.isNullOrEmpty()) {
                            currentTab.detectedVideoTitle = title
                        }
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
                    currentTab.detectedVideoTitle = null
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
