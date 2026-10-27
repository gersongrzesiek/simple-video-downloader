package com.simplevideodownloader.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.ResourceType
import com.simplevideodownloader.detect.MediaClassifier
import com.simplevideodownloader.detect.MediaDetector
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * El corazón del navegador: cada petición de la página pasa por aquí. Primero se consulta al
 * bloqueador; si la petición sigue adelante, el detector mira si es un vídeo.
 */
class BrowserClient(
    private val detector: MediaDetector,
    private val listener: Listener,
) : WebViewClient() {

    interface Listener {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
        fun onBlockedCountChanged(count: Int)
        fun injectCosmetics(view: WebView, url: String)
        fun openExternal(uri: Uri): Boolean
    }

    /** URL y título de la página principal, para usarlos desde hilos de fondo. */
    @Volatile var pageUrl: String? = null
        private set
    @Volatile var pageTitle: String? = null

    private val blockedCount = AtomicInteger(0)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.isForMainFrame) return null
        val page = pageUrl

        if (AdBlocker.shouldBlock(url, page, resourceTypeOf(request, url))) {
            val count = blockedCount.incrementAndGet()
            view.post { listener.onBlockedCountChanged(count) }
            return emptyResponse()
        }

        if (!MediaClassifier.isSegment(url)) {
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            detector.onRequest(url, page, pageTitle, request.requestHeaders ?: emptyMap(), cookies)
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // intent://, market://, tel:... solo si el usuario tocó algo; así se evitan redirecciones forzadas.
            return if (request.hasGesture()) listener.openExternal(uri) else true
        }
        // Redirecciones automáticas (sin toque del usuario) hacia dominios de publicidad.
        if (request.isForMainFrame && !request.hasGesture() &&
            AdBlocker.shouldBlock(uri.toString(), pageUrl, ResourceType.POPUP)
        ) {
            val count = blockedCount.incrementAndGet()
            listener.onBlockedCountChanged(count)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageUrl = url
        pageTitle = null
        blockedCount.set(0)
        listener.onBlockedCountChanged(0)
        detector.reset()
        listener.onPageStarted(url)
        listener.injectCosmetics(view, url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        listener.injectCosmetics(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        pageUrl = url
        pageTitle = view.title
        listener.injectCosmetics(view, url)
        listener.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Webs de una sola página (SPA): la URL cambia sin recargar.
        pageUrl = url
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif")
        private val fontExt = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val mediaExt = setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "m4a", "aac", "ogg")

        /** WebView no nos dice el tipo de recurso, así que lo deducimos de la cabecera Accept y la extensión. */
        fun resourceTypeOf(request: WebResourceRequest, url: String): ResourceType {
            val accept = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
            val ext = MediaClassifier.extensionOf(url)
            return when {
                accept.startsWith("text/html") -> ResourceType.SUBDOCUMENT
                accept.startsWith("text/css") || ext == "css" -> ResourceType.STYLESHEET
                accept.startsWith("image/") || ext in imageExt -> ResourceType.IMAGE
                ext == "js" || ext == "mjs" -> ResourceType.SCRIPT
                ext in fontExt -> ResourceType.FONT
                ext in mediaExt -> ResourceType.MEDIA
                request.requestHeaders?.keys?.any { it.equals("X-Requested-With", true) } == true &&
                    accept.contains("json") -> ResourceType.XHR
                else -> ResourceType.OTHER
            }
        }
    }
}
