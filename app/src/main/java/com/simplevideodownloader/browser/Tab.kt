package com.simplevideodownloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.ResourceType
import com.simplevideodownloader.detect.MediaDetector
import com.simplevideodownloader.download.DownloadRequest
import com.simplevideodownloader.ui.BrowserClient
import java.util.concurrent.atomic.AtomicLong

/**
 * Una pestaña del navegador: su WebView, su bloqueador y su propio detector de vídeos.
 * Una pestaña nueva empieza mostrando la página de inicio ([isHome]).
 */
@SuppressLint("SetJavaScriptEnabled")
class Tab(context: Context, private val host: Host) : BrowserClient.Listener {

    interface Host {
        /** Cambió algo visible de la pestaña: dirección, título, progreso o anuncios bloqueados. */
        fun onTabUpdated(tab: Tab)
        fun onTabPageFinished(tab: Tab, url: String)
        fun openInNewTab(url: String)
        fun openExternal(uri: Uri): Boolean
        fun onDownloadRequested(tab: Tab, request: DownloadRequest)
        fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun hideCustomView()
    }

    val id: Long = nextId.incrementAndGet()
    val detector = MediaDetector()
    val client = BrowserClient(detector, this)
    val webView = WebView(context)

    /** True mientras se muestra la página de inicio en lugar de la web. */
    var isHome = true
    var progress = 100
        private set
    var blockedCount = 0
        private set

    /** Última dirección pedida, aunque la página aún no haya empezado a cargar. */
    private var requestedUrl: String? = null
    /** Dirección guardada de una sesión anterior: se carga cuando se abre la pestaña. */
    private var lazyUrl: String? = null
    private var documentStartScript = false

    val url: String? get() = if (isHome) null else client.pageUrl ?: requestedUrl ?: lazyUrl
    val title: String? get() = if (isHome) null else (client.pageTitle ?: webView.title)?.takeIf { it.isNotBlank() }

    /** Si la pestaña tiene una web cargada detrás de la página de inicio. */
    val hasPage: Boolean get() = (client.pageUrl ?: requestedUrl ?: lazyUrl) != null

    init {
        webView.settings.apply {
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = client
        webView.webChromeClient = ChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            host.onDownloadRequested(
                this,
                DownloadRequest(
                    url = url, fileName = URLUtil.guessFileName(url, contentDisposition, mimeType), isHls = false,
                    referer = client.pageUrl, userAgent = userAgent,
                    cookies = CookieManager.getInstance().getCookie(url), mimeType = mimeType,
                ),
            )
        }
        // El CSS de ocultación se pide al principio de cada documento, antes de que se pinte la página.
        webView.addJavascriptInterface(CosmeticBridge(), "SVDAdBlock")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, COSMETIC_SCRIPT, setOf("*"))
            documentStartScript = true
        }
    }

    fun load(url: String) {
        isHome = false
        requestedUrl = url
        webView.loadUrl(url)
        host.onTabUpdated(this)
    }

    /** Deja la pestaña lista para cargar [url] la primera vez que se muestre. */
    fun restore(url: String) {
        lazyUrl = url
        isHome = false
    }

    fun ensureLoaded() {
        lazyUrl?.let {
            lazyUrl = null
            load(it)
        }
    }

    /** Vuelve de la página de inicio a la web que había detrás. */
    fun showPage() {
        isHome = false
        host.onTabUpdated(this)
    }

    fun showHome() {
        isHome = true
        host.onTabUpdated(this)
    }

    fun destroy() {
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private class CosmeticBridge {
        @JavascriptInterface
        fun css(pageUrl: String): String = AdBlocker.cssFor(pageUrl)
    }

    // --- BrowserClient.Listener ---

    override fun injectCosmetics(view: WebView, url: String) {
        // Sin soporte de "document start" lo inyectamos en cuanto podemos (el script evita duplicados).
        if (!documentStartScript && url.startsWith("http")) view.evaluateJavascript(COSMETIC_SCRIPT, null)
    }

    override fun onPageStarted(url: String) {
        requestedUrl = url
        progress = 0
        host.onTabUpdated(this)
    }

    override fun onPageFinished(url: String) {
        progress = 100
        host.onTabUpdated(this)
        host.onTabPageFinished(this, url)
    }

    override fun onBlockedCountChanged(count: Int) {
        blockedCount = count
        host.onTabUpdated(this)
    }

    override fun openExternal(uri: Uri): Boolean = host.openExternal(uri)

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            host.onTabUpdated(this@Tab)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            client.pageTitle = title
            host.onTabUpdated(this@Tab)
        }

        /** Ventanas emergentes: solo si el usuario tocó algo y el destino no es publicidad. Se abren en otra pestaña. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val popup = WebView(view.context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (!AdBlocker.shouldBlock(target, client.pageUrl, ResourceType.POPUP)) {
                        host.openInNewTab(target)
                    } else {
                        onBlockedCountChanged(blockedCount + 1)
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
            host.showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            host.hideCustomView()
        }
    }

    companion object {
        private val nextId = AtomicLong()

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
