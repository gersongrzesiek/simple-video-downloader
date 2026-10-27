package com.simplevideodownloader.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplevideodownloader.App
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.adblock.ResourceType
import com.simplevideodownloader.databinding.ActivityMainBinding
import com.simplevideodownloader.databinding.ItemMediaBinding
import com.simplevideodownloader.databinding.SheetDetectedBinding
import com.simplevideodownloader.detect.DetectedMedia
import com.simplevideodownloader.detect.MediaClassifier
import com.simplevideodownloader.detect.MediaDetector
import com.simplevideodownloader.detect.MediaKind
import com.simplevideodownloader.download.DownloadRequest
import com.simplevideodownloader.download.Downloads
import com.simplevideodownloader.download.HlsParser
import com.simplevideodownloader.download.HlsPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class MainActivity : AppCompatActivity(), BrowserClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private val detector = MediaDetector()
    private lateinit var client: BrowserClient
    private var documentStartScript = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: DownloadRequest? = null
    private var sheet: BottomSheetDialog? = null

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingDownload
        pendingDownload = null
        if (granted && request != null) startDownload(request)
        else if (!granted) Toast.makeText(this, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupWebView()
        setupBackNavigation()

        binding.fabDownload.setOnClickListener { showDetectedSheet() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                detector.items.collect { items ->
                    binding.fabDownload.isVisible = items.isNotEmpty()
                    binding.fabDownload.text = getString(R.string.fab_videos, items.size)
                    (sheet?.findViewById<RecyclerView>(R.id.list)?.adapter as? MediaAdapter)?.submit(items)
                }
            }
        }

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else if (!handleIntent(intent)) {
            binding.webView.loadUrl(HOME_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    /** Abre enlaces compartidos desde otras apps o recibidos como "abrir con". */
    private fun handleIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value
            }
            else -> null
        } ?: return false
        binding.webView.loadUrl(url)
        return true
    }

    // --- Barra superior ---

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item -> onMenuItem(item.itemId) }

        binding.urlInput.setOnEditorActionListener { v, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                binding.webView.loadUrl(toUrl(v.text.toString()))
                hideKeyboard()
                binding.webView.requestFocus()
                true
            } else {
                false
            }
        }
        binding.blockedBadge.text = "0"
        binding.blockedBadge.setOnClickListener {
            Toast.makeText(this, getString(R.string.blocked_badge_description) + ": " + binding.blockedBadge.text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMenuItem(id: Int): Boolean {
        val web = binding.webView
        when (id) {
            R.id.action_back -> if (web.canGoBack()) web.goBack()
            R.id.action_forward -> if (web.canGoForward()) web.goForward()
            R.id.action_reload -> web.reload()
            R.id.action_home -> web.loadUrl(HOME_URL)
            R.id.action_block_here -> toggleBlockingHere()
            R.id.action_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return false
        }
        return true
    }

    private fun toggleBlockingHere() {
        val host = client.pageUrl?.let { DomainUtils.hostOf(it) } ?: return
        val currentlyAllowed = AdBlocker.isAllowlisted(host)
        AdBlocker.setAllowlisted(host, !currentlyAllowed)
        updateBlockHereItem()
        binding.webView.reload()
    }

    private fun updateBlockHereItem() {
        val item = binding.toolbar.menu.findItem(R.id.action_block_here) ?: return
        val host = client.pageUrl?.let { DomainUtils.hostOf(it) }
        item.isVisible = host != null && AdBlocker.isEnabled
        item.isChecked = host != null && !AdBlocker.isAllowlisted(host)
    }

    private fun toUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return HOME_URL
        if (URLUtil.isNetworkUrl(text)) return text
        val looksLikeDomain = !text.contains(' ') && text.contains('.') && !text.endsWith('.')
        return if (looksLikeDomain) "https://$text" else "https://duckduckgo.com/?q=" + Uri.encode(text)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        binding.urlInput.clearFocus()
    }

    // --- WebView ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        client = BrowserClient(detector, this)
        web.webViewClient = client
        web.webChromeClient = ChromeClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            startDownload(
                DownloadRequest(
                    url = url, fileName = name, isHls = false, referer = client.pageUrl, userAgent = userAgent,
                    cookies = CookieManager.getInstance().getCookie(url), mimeType = mimeType,
                )
            )
        }

        // El CSS de ocultación se pide al principio de cada documento, antes de que se pinte la página.
        web.addJavascriptInterface(CosmeticBridge(), "SVDAdBlock")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, COSMETIC_SCRIPT, setOf("*"))
            documentStartScript = true
        }
    }

    private inner class CosmeticBridge {
        @JavascriptInterface
        fun css(pageUrl: String): String = AdBlocker.cssFor(pageUrl)
    }

    override fun injectCosmetics(view: WebView, url: String) {
        // Sin soporte de "document start" lo inyectamos en cuanto podemos (el script evita duplicados).
        if (!documentStartScript && url.startsWith("http")) view.evaluateJavascript(COSMETIC_SCRIPT, null)
    }

    override fun onPageStarted(url: String) {
        if (!binding.urlInput.hasFocus()) binding.urlInput.setText(if (url == HOME_URL) "" else url)
        binding.progress.isVisible = true
        updateBlockHereItem()
    }

    override fun onPageFinished(url: String) {
        if (!binding.urlInput.hasFocus()) binding.urlInput.setText(if (url == HOME_URL) "" else url)
        binding.progress.isVisible = false
        updateBlockHereItem()
    }

    override fun onBlockedCountChanged(count: Int) {
        binding.blockedBadge.text = count.toString()
    }

    override fun openExternal(uri: Uri): Boolean {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: Exception) {
        }
        return true
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progress.setProgressCompat(newProgress, true)
            binding.progress.isVisible = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            client.pageTitle = title
        }

        /** Ventanas emergentes: solo si el usuario tocó algo y el destino no es publicidad. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (!AdBlocker.shouldBlock(target, client.pageUrl, ResourceType.POPUP)) {
                        binding.webView.loadUrl(target)
                    } else {
                        onBlockedCountChanged(binding.blockedBadge.text.toString().toIntOrNull()?.plus(1) ?: 1)
                    }
                    v.post { v.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            binding.fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            binding.fullscreenContainer.isVisible = true
            binding.browserContainer.isVisible = false
            setFullscreenMode(true)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        binding.browserContainer.isVisible = true
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setFullscreenMode(false)
    }

    private fun setFullscreenMode(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    // --- Vídeos detectados ---

    private fun showDetectedSheet() {
        val sheetBinding = SheetDetectedBinding.inflate(layoutInflater)
        val adapter = MediaAdapter { onMediaSelected(it) }
        sheetBinding.list.layoutManager = LinearLayoutManager(this)
        sheetBinding.list.adapter = adapter
        adapter.submit(detector.items.value)
        sheet = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
            setOnDismissListener { sheet = null }
            show()
        }
    }

    private fun onMediaSelected(media: DetectedMedia) {
        when (media.kind) {
            MediaKind.DASH -> Toast.makeText(this, R.string.download_unsupported_dash, Toast.LENGTH_LONG).show()
            MediaKind.HLS -> chooseHlsQuality(media)
            else -> startDownload(
                DownloadRequest(
                    url = media.url,
                    fileName = Downloads.suggestFileName(media.pageTitle ?: client.pageTitle, media.url, false, media.contentType),
                    isHls = false, referer = media.referer, userAgent = media.userAgent, cookies = media.cookies,
                    mimeType = media.contentType,
                )
            )
        }
    }

    /** Lee la lista HLS; si tiene varias calidades, deja elegir. */
    private fun chooseHlsQuality(media: DetectedMedia) {
        Toast.makeText(this, R.string.reading_playlist, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(media.url).apply {
                        media.referer?.let { header("Referer", it) }
                        media.userAgent?.let { header("User-Agent", it) }
                        media.cookies?.let { header("Cookie", it) }
                    }.build()
                    App.http.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        HlsParser.parse(response.body!!.string(), response.request.url.toString())
                    }
                }
            }
            val playlist = result.getOrElse {
                Toast.makeText(this@MainActivity, getString(R.string.playlist_error, it.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            val title = media.pageTitle ?: client.pageTitle
            fun enqueue(url: String, label: String?) = startDownload(
                DownloadRequest(
                    url = url,
                    fileName = Downloads.suggestFileName(listOfNotNull(title, label).joinToString(" "), url, true),
                    isHls = true, referer = media.referer, userAgent = media.userAgent, cookies = media.cookies,
                )
            )
            if (playlist is HlsPlaylist.Master && playlist.variants.size > 1) {
                val labels = playlist.variants.map { it.label }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.choose_quality)
                    .setItems(labels) { _, which ->
                        val variant = playlist.variants[which]
                        enqueue(variant.url, variant.resolution?.substringAfter('x')?.let { "${it}p" })
                    }
                    .show()
            } else {
                enqueue((playlist as? HlsPlaylist.Master)?.variants?.firstOrNull()?.url ?: media.url, null)
            }
        }
    }

    private fun startDownload(request: DownloadRequest) {
        if (MediaDetector.isUnsupportedSite(request.url) || MediaDetector.isUnsupportedSite(client.pageUrl)) {
            Toast.makeText(this, R.string.download_unsupported_site, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = request
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Downloads.enqueue(this, request)
        sheet?.dismiss()
        Toast.makeText(this, getString(R.string.download_started, request.fileName), Toast.LENGTH_SHORT).show()
    }

    private class MediaAdapter(private val onClick: (DetectedMedia) -> Unit) : RecyclerView.Adapter<MediaAdapter.Holder>() {
        private var items: List<DetectedMedia> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<DetectedMedia>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val media = items[position]
            val b = holder.binding
            b.kind.text = when (media.kind) {
                MediaKind.VIDEO -> MediaClassifier.extensionOf(media.url).uppercase().takeIf { it.length in 2..4 } ?: "VIDEO"
                MediaKind.AUDIO -> "AUDIO"
                MediaKind.HLS -> "HLS"
                MediaKind.DASH -> "DASH"
            }
            b.title.text = media.url.substringBefore('?').substringAfterLast('/').ifBlank { media.url }
            b.subtitle.text = listOfNotNull(
                DomainUtils.hostOf(media.url),
                media.sizeBytes.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(b.root.context, it) },
            ).joinToString(" · ")
            b.action.setOnClickListener { onClick(media) }
            b.root.setOnClickListener { onClick(media) }
        }
    }

    companion object {
        const val HOME_URL = "file:///android_asset/home.html"

        private val COSMETIC_SCRIPT = """
            (function(){
              try {
                if (window.top !== window || !/^https?:/.test(location.protocol)) return;
                if (document.getElementById('svd-adblock')) return;
                var css = window.SVDAdBlock ? SVDAdBlock.css(location.href) : '';
                if (!css) return;
                var s = document.createElement('style');
                s.id = 'svd-adblock';
                s.textContent = css;
                (document.head || document.documentElement).appendChild(s);
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
