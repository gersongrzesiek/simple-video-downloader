package com.simplevideodownloader.detect

import com.simplevideodownloader.App
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.adblock.ResourceType
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
    /** Medios descartados en esta página (gifs, clips diminutos), para no volver a añadirlos. */
    private val ignored = HashSet<String>()
    private val probed = Collections.synchronizedSet(HashSet<String>())
    private val probeExecutor = Executors.newFixedThreadPool(3)

    @Volatile
    private var generation = 0

    private val _items = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val items: StateFlow<List<DetectedMedia>> = _items

    /** Se llama al empezar a cargar una página nueva. */
    fun reset() {
        synchronized(lock) {
            generation++
            byKey.clear()
            ignored.clear()
            probed.clear()
            _items.value = emptyList()
        }
    }

    /** Una petición de red de la página (desde `shouldInterceptRequest`). */
    fun onRequest(
        url: String,
        pageUrl: String?,
        pageTitle: String?,
        headers: Map<String, String>,
        cookies: String?,
    ) {
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        consider(url, pageUrl, pageTitle, referer, userAgent, cookies, foundInPage = false)
    }

    /**
     * Un vídeo encontrado leyendo la página (etiquetas `<video>`, metadatos o scripts), sin esperar a que
     * se reproduzca. [frameUrl] es la página o iframe donde estaba.
     */
    fun onPageMedia(url: String, frameUrl: String?, pageUrl: String?, pageTitle: String?, userAgent: String?, cookies: String?, gifLike: Boolean) {
        if (gifLike) {
            ignore(MediaClassifier.dedupeKey(url))
            return
        }
        consider(url, pageUrl, pageTitle, frameUrl ?: pageUrl, userAgent, cookies, foundInPage = true)
    }

    private fun consider(url: String, pageUrl: String?, title: String?, referer: String?, ua: String?, cookies: String?, foundInPage: Boolean) {
        if (isUnsupportedSite(pageUrl) || isUnsupportedSite(url)) return
        if (isAd(url, referer, pageUrl)) return
        val key = MediaClassifier.dedupeKey(url)
        synchronized(lock) { if (key in ignored || byKey.containsKey(key)) return }
        val kind = MediaClassifier.classify(url)
        when {
            kind == MediaKind.HLS || kind == MediaKind.DASH -> add(DetectedMedia(url, kind, pageUrl, title, referer, ua, cookies))
            // Los archivos directos se consultan antes de listarlos: así sabemos su tamaño y descartamos gifs y clips mínimos.
            (kind != null || foundInPage || MediaClassifier.shouldProbe(url)) && probed.add(key) -> {
                val gen = generation
                probeExecutor.execute { probe(gen, url, kind, pageUrl, title, referer, ua, cookies) }
            }
        }
    }

    /** Anuncios: URLs con pinta de publicidad, bloqueadas por las listas o pedidas desde un iframe de publicidad. */
    private fun isAd(url: String, referer: String?, pageUrl: String?): Boolean {
        if (MediaClassifier.looksLikeAd(url)) return true
        if (AdBlocker.shouldBlock(url, pageUrl, ResourceType.MEDIA)) return true
        return referer != null && referer != pageUrl && referer.startsWith("http") &&
            (MediaClassifier.looksLikeAd(referer) || AdBlocker.shouldBlock(referer, pageUrl, ResourceType.SUBDOCUMENT))
    }

    /** Pregunta al servidor el tipo y el tamaño de un medio (solo pide 1 byte). */
    private fun probe(gen: Int, url: String, guess: MediaKind?, pageUrl: String?, title: String?, referer: String?, ua: String?, cookies: String?) {
        val key = MediaClassifier.dedupeKey(url)
        try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
                referer?.takeIf { it.startsWith("http") }?.let { header("Referer", it) }
                ua?.let { header("User-Agent", it) }
                cookies?.let { header("Cookie", it) }
            }.build()
            App.http.newCall(request).execute().use { response ->
                if (gen != generation) return
                if (!response.isSuccessful) {
                    // Algunos servidores rechazan la consulta pero sirven el vídeo al reproductor: lo listamos igualmente.
                    if (guess != null) add(DetectedMedia(url, guess, pageUrl, title, referer, ua, cookies))
                    return
                }
                val type = response.header("Content-Type")
                val kind = MediaClassifier.classify(response.request.url.toString(), type) ?: guess ?: return
                val size = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                    ?: -1
                if (kind == MediaKind.VIDEO && size in 1 until MediaClassifier.MIN_VIDEO_BYTES) {
                    ignore(key)
                    return
                }
                add(DetectedMedia(url, kind, pageUrl, title, referer, ua, cookies, type, size))
            }
        } catch (_: Exception) {
            if (gen == generation && guess != null) add(DetectedMedia(url, guess, pageUrl, title, referer, ua, cookies))
        }
    }

    private fun ignore(key: String) {
        synchronized(lock) {
            ignored += key
            if (byKey.remove(key) != null) _items.value = byKey.values.toList()
        }
    }

    private fun add(media: DetectedMedia) {
        val key = MediaClassifier.dedupeKey(media.url)
        synchronized(lock) {
            if (byKey.containsKey(key) || key in ignored) return
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
