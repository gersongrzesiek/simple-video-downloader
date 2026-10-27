package com.simplevideodownloader.detect

import com.simplevideodownloader.App
import com.simplevideodownloader.adblock.DomainUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.Executors

/** Un vídeo o audio encontrado en la página, con lo necesario para volver a pedirlo al descargar. */
data class DetectedMedia(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String?,
    val pageTitle: String?,
    val referer: String?,
    val userAgent: String?,
    val cookies: String?,
    val contentType: String? = null,
    val sizeBytes: Long = -1,
)

/**
 * Recoge los medios que pide la página actual. Lo alimenta el WebViewClient desde
 * `shouldInterceptRequest` (hilos de fondo) y la interfaz observa [items].
 */
class MediaDetector {
    private val lock = Any()
    private val byKey = LinkedHashMap<String, DetectedMedia>()
    private val probed = Collections.synchronizedSet(HashSet<String>())
    private val probeExecutor = Executors.newFixedThreadPool(2)

    @Volatile
    private var generation = 0

    private val _items = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val items: StateFlow<List<DetectedMedia>> = _items

    /** Se llama al empezar a cargar una página nueva. */
    fun reset() {
        synchronized(lock) {
            generation++
            byKey.clear()
            probed.clear()
            _items.value = emptyList()
        }
    }

    fun onRequest(
        url: String,
        pageUrl: String?,
        pageTitle: String?,
        headers: Map<String, String>,
        cookies: String?,
    ) {
        if (isUnsupportedSite(pageUrl) || isUnsupportedSite(url)) return
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val kind = MediaClassifier.classify(url)
        when {
            kind != null -> add(DetectedMedia(url, kind, pageUrl, pageTitle, referer, userAgent, cookies))
            MediaClassifier.shouldProbe(url) && probed.add(MediaClassifier.dedupeKey(url)) -> {
                val gen = generation
                probeExecutor.execute { probe(gen, url, pageUrl, pageTitle, referer, userAgent, cookies) }
            }
        }
    }

    /** Pregunta al servidor el tipo de contenido de una URL sin extensión clara (solo pide 1 byte). */
    private fun probe(gen: Int, url: String, pageUrl: String?, title: String?, referer: String?, ua: String?, cookies: String?) {
        try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
                referer?.let { header("Referer", it) }
                ua?.let { header("User-Agent", it) }
                cookies?.let { header("Cookie", it) }
            }.build()
            App.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful || gen != generation) return
                val type = response.header("Content-Type")
                val kind = MediaClassifier.classify(response.request.url.toString(), type) ?: return
                val size = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                add(DetectedMedia(url, kind, pageUrl, title, referer, ua, cookies, type, size))
            }
        } catch (_: Exception) {
        }
    }

    private fun add(media: DetectedMedia) {
        val key = MediaClassifier.dedupeKey(media.url)
        synchronized(lock) {
            if (byKey.containsKey(key)) return
            byKey[key] = media
            _items.value = byKey.values.toList()
        }
    }

    companion object {
        /** Sitios cuyas condiciones prohíben descargar; no ofrecemos sus vídeos. */
        private val unsupportedSites = setOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com")

        fun isUnsupportedSite(url: String?): Boolean {
            val host = url?.let { DomainUtils.hostOf(it) } ?: return false
            return DomainUtils.matchesAny(host, unsupportedSites)
        }
    }
}
