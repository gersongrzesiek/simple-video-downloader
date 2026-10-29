package com.simplevideodownloader.detect

import android.media.MediaMetadataRetriever
import com.simplevideodownloader.App
import com.simplevideodownloader.download.DownloadRequest
import com.simplevideodownloader.download.Downloads
import com.simplevideodownloader.download.HlsParser
import com.simplevideodownloader.download.HlsPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/** Una opción de descarga que se muestra al usuario: resolución, tamaño y formato. */
data class DownloadOption(
    val request: DownloadRequest,
    /** Altura en píxeles (720, 1080...) o null si no se sabe. */
    val height: Int?,
    val sizeBytes: Long,
    /** True si el tamaño es una estimación (HLS) y no el real del servidor. */
    val sizeEstimated: Boolean,
    val format: String,
    val sourceHost: String?,
    /** Duración en segundos, o 0 si no se sabe. */
    val durationSec: Double = 0.0,
) {
    val qualityLabel: String? get() = height?.let { "${it}p" }
}

/**
 * Convierte los medios detectados en una lista de opciones con resolución y tamaño,
 * preguntando al servidor lo que haga falta (cabeceras de tamaño, listas HLS).
 */
object QualityResolver {
    private fun heightFromUrl(url: String) = MediaClassifier.heightFromUrl(url)
    private val audioFormats = setOf("AUDIO", "MP3", "M4A", "AAC", "OGG", "OGA", "OPUS", "WAV", "FLAC")

    /**
     * [cache] guarda lo ya consultado de cada URL (por pestaña), para no repetir peticiones cada vez que
     * la página descubre un vídeo nuevo.
     */
    suspend fun resolve(
        items: List<DetectedMedia>,
        fallbackTitle: String?,
        cache: MutableMap<String, List<DownloadOption>> = java.util.concurrent.ConcurrentHashMap(),
    ): List<DownloadOption> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.filter { it.kind != MediaKind.DASH }
                .map { media ->
                    async {
                        cache[media.url] ?: runCatching { optionsFor(media, fallbackTitle) }.getOrDefault(emptyList())
                            .also { cache[media.url] = it }
                    }
                }
                .awaitAll()
                .flatten()
                .distinctBy { it.request.url }
                .let(::tidy)
        }
    }

    /**
     * Deja una opción por resolución. La página suele tener, además del vídeo, avances de otros vídeos
     * (gifs, clips de segundos) y el mismo vídeo en MP4 y HLS: nos quedamos con el archivo más grande de
     * cada altura y quitamos lo diminuto en comparación con el vídeo principal.
     */
    private fun tidy(options: List<DownloadOption>): List<DownloadOption> {
        val largest = options.maxOfOrNull { it.sizeBytes } ?: return options
        val real = options.filterNot { o ->
            val isAudio = o.format in audioFormats
            val tiny = !isAudio && o.sizeBytes in 1 until MediaClassifier.MIN_VIDEO_BYTES
            val preview = !isAudio && largest > 0 && o.sizeBytes in 1 until largest / 30
            tiny || preview
        }
        val best = compareBy<DownloadOption>({ it.sizeBytes > 0 && !it.sizeEstimated }, { it.sizeBytes })
        val byHeight = real.filter { it.height != null }.groupBy { it.height }.values.map { group -> group.maxWith(best) }
        // Sin resolución conocida: si es un archivo distinto (otro tamaño) a los ya listados. Las que tampoco
        // tienen tamaño solo se muestran si no hay nada mejor.
        val anySized = real.any { it.sizeBytes > 0 }
        val unknown = real.filter { it.height == null }.filter { o ->
            if (o.sizeBytes <= 0) byHeight.isEmpty() && !anySized
            else byHeight.none { abs(it.sizeBytes - o.sizeBytes) < o.sizeBytes / 20 }
        }
        return (byHeight + unknown).sortedWith(compareByDescending<DownloadOption> { it.height ?: 0 }.thenByDescending { it.sizeBytes })
    }

    private fun optionsFor(media: DetectedMedia, fallbackTitle: String?): List<DownloadOption> {
        val title = media.pageTitle ?: fallbackTitle
        val host = com.simplevideodownloader.adblock.DomainUtils.hostOf(media.url)
        return when (media.kind) {
            MediaKind.HLS -> hlsOptions(media, title, host)
            else -> {
                val size = if (media.sizeBytes > 0) media.sizeBytes else probeSize(media, media.url)
                val ext = MediaClassifier.extensionOf(media.url).uppercase().takeIf { it.length in 2..4 }
                    ?: if (media.kind == MediaKind.AUDIO) "AUDIO" else "MP4"
                // Si la URL no dice la resolución, se la preguntamos al propio archivo (lee solo la cabecera del vídeo).
                val info = if (media.kind == MediaKind.VIDEO) heightFromUrl(media.url)?.let { VideoInfo(it, 0.0) } ?: readVideoInfo(media)
                    else null
                val height = info?.height
                listOf(
                    DownloadOption(
                        request = DownloadRequest(
                            url = media.url,
                            fileName = Downloads.suggestFileName(titleWithQuality(title, height), media.url, false, media.contentType),
                            isHls = false, referer = media.referer, userAgent = media.userAgent, cookies = media.cookies,
                            mimeType = media.contentType,
                        ),
                        height = height, sizeBytes = size, sizeEstimated = false, format = ext, sourceHost = host,
                        durationSec = info?.durationSec ?: 0.0,
                    )
                )
            }
        }
    }

    private fun hlsOptions(media: DetectedMedia, title: String?, host: String?): List<DownloadOption> {
        val playlist = fetch(media, media.url).use { HlsParser.parse(it.body!!.string(), it.request.url.toString()) }
        fun request(url: String, height: Int?) = DownloadRequest(
            url = url,
            fileName = Downloads.suggestFileName(titleWithQuality(title, height), url, true),
            isHls = true, referer = media.referer, userAgent = media.userAgent, cookies = media.cookies,
        )
        return when (playlist) {
            is HlsPlaylist.Master -> {
                if (playlist.variants.isEmpty()) return emptyList()
                // Todas las calidades duran lo mismo: leemos la duración de una sola.
                val duration = runCatching {
                    val first = playlist.variants.first()
                    fetch(media, first.url).use { (HlsParser.parse(it.body!!.string(), first.url) as HlsPlaylist.Media).durationSec }
                }.getOrDefault(0.0)
                playlist.variants.map { v ->
                    val height = v.resolution?.substringAfter('x')?.toIntOrNull() ?: heightFromUrl(v.url)
                    val size = if (v.bandwidth > 0 && duration > 0) (v.bandwidth / 8.0 * duration).toLong() else -1
                    DownloadOption(request(v.url, height), height, size, true, "HLS", host, duration)
                }
            }
            is HlsPlaylist.Media -> {
                if (playlist.isLive || playlist.segments.isEmpty()) return emptyList()
                val first = playlist.segments.first()
                val segmentSize = probeSize(media, first.url)
                val size = if (segmentSize > 0) segmentSize * playlist.segments.size else -1
                val height = heightFromUrl(media.url)
                listOf(DownloadOption(request(media.url, height), height, size, true, "HLS", host, playlist.durationSec))
            }
        }
    }

    private class VideoInfo(val height: Int?, val durationSec: Double)

    private val metadataExecutor = Executors.newCachedThreadPool()

    /** Resolución y duración leídas del archivo con MediaMetadataRetriever (máximo 10 s, por si el servidor no responde). */
    private fun readVideoInfo(media: DetectedMedia): VideoInfo? {
        val task = metadataExecutor.submit<VideoInfo?> {
            val retriever = MediaMetadataRetriever()
            try {
                val headers = HashMap<String, String>()
                media.referer?.takeIf { it.startsWith("http") }?.let { headers["Referer"] = it }
                media.userAgent?.let { headers["User-Agent"] = it }
                media.cookies?.let { headers["Cookie"] = it }
                retriever.setDataSource(media.url, headers)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0) ?: 0.0
                // "720p" se refiere al lado corto, también en vídeos verticales.
                VideoInfo(if (w > 0 && h > 0) min(w, h) else null, duration)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        return try {
            task.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            null
        }
    }

    private fun titleWithQuality(title: String?, height: Int?) =
        listOfNotNull(title?.takeIf { it.isNotBlank() }, height?.let { "${it}p" }).joinToString(" ").ifBlank { null }

    /** Tamaño total del recurso pidiendo solo 1 byte (cabecera Content-Range), o -1. */
    private fun probeSize(media: DetectedMedia, url: String): Long = runCatching {
        fetch(media, url, "bytes=0-0").use { response ->
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: -1L
        }
    }.getOrDefault(-1L)

    private fun fetch(media: DetectedMedia, url: String, range: String? = null): Response {
        val request = Request.Builder().url(url).apply {
            media.referer?.takeIf { it.startsWith("http") }?.let { referer ->
                header("Referer", referer)
                referer.toHttpUrlOrNull()?.let { header("Origin", "${it.scheme}://${it.host}") }
            }
            media.userAgent?.let { header("User-Agent", it) }
            media.cookies?.let { header("Cookie", it) }
            range?.let { header("Range", it) }
        }.build()
        val response = App.http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code}")
        }
        return response
    }
}
