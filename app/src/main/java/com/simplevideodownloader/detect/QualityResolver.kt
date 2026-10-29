package com.simplevideodownloader.detect

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
import okhttp3.Request
import okhttp3.Response

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
) {
    val qualityLabel: String? get() = height?.let { "${it}p" }
}

/**
 * Convierte los medios detectados en una lista de opciones con resolución y tamaño,
 * preguntando al servidor lo que haga falta (cabeceras de tamaño, listas HLS).
 */
object QualityResolver {
    private fun heightFromUrl(url: String) = MediaClassifier.heightFromUrl(url)

    suspend fun resolve(items: List<DetectedMedia>, fallbackTitle: String?): List<DownloadOption> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.filter { it.kind != MediaKind.DASH }
                .map { media -> async { runCatching { optionsFor(media, fallbackTitle) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
                .distinctBy { it.request.url }
                .sortedWith(compareByDescending<DownloadOption> { it.height ?: 0 }.thenByDescending { it.sizeBytes })
        }
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
                val height = heightFromUrl(media.url)
                listOf(
                    DownloadOption(
                        request = DownloadRequest(
                            url = media.url,
                            fileName = Downloads.suggestFileName(titleWithQuality(title, height), media.url, false, media.contentType),
                            isHls = false, referer = media.referer, userAgent = media.userAgent, cookies = media.cookies,
                            mimeType = media.contentType,
                        ),
                        height = height, sizeBytes = size, sizeEstimated = false, format = ext, sourceHost = host,
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
                    DownloadOption(request(v.url, height), height, size, true, "HLS", host)
                }
            }
            is HlsPlaylist.Media -> {
                if (playlist.isLive || playlist.segments.isEmpty()) return emptyList()
                val first = playlist.segments.first()
                val segmentSize = probeSize(media, first.url)
                val size = if (segmentSize > 0) segmentSize * playlist.segments.size else -1
                val height = heightFromUrl(media.url)
                listOf(DownloadOption(request(media.url, height), height, size, true, "HLS", host))
            }
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
            media.referer?.takeIf { it.startsWith("http") }?.let { header("Referer", it) }
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
