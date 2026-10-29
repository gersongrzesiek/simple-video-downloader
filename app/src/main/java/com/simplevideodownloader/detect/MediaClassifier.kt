package com.simplevideodownloader.detect

/** Tipo de medio detectado en una página. */
enum class MediaKind { VIDEO, AUDIO, HLS, DASH }

/**
 * Decide, solo con la URL (y el Content-Type si lo tenemos), si una petición es un vídeo o audio
 * que merezca la pena ofrecer para descargar.
 */
object MediaClassifier {
    private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "avi", "wmv", "mpeg", "mpg")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val segmentExtensions = setOf("ts", "m4s", "cmfv", "cmfa", "m4f", "fmp4")
    private val ignoredExtensions = setOf(
        "js", "css", "json", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf",
        "otf", "html", "htm", "php", "xml", "txt", "wasm", "map", "vtt", "srt",
    )
    private val probeHints = listOf("video", "media", "stream", "playlist", "manifest", "/hls", "/dash", "play", "mp4", "m3u8")

    /** Extensión del último segmento de la ruta, en minúsculas, o "" si no tiene. */
    fun extensionOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot < 0 || dot == lastSegment.length - 1) return ""
        return lastSegment.substring(dot + 1).lowercase()
    }

    fun classify(url: String, contentType: String? = null): MediaKind? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null) {
            when {
                type.contains("mpegurl") -> return MediaKind.HLS
                type == "application/dash+xml" -> return MediaKind.DASH
                type == "video/mp2t" || type == "video/iso.segment" -> return null
                type.startsWith("video/") -> return MediaKind.VIDEO
                type.startsWith("audio/") -> return MediaKind.AUDIO
            }
        }
        val ext = extensionOf(url)
        return when {
            ext == "m3u8" || ext == "m3u" -> MediaKind.HLS
            ext == "mpd" -> MediaKind.DASH
            ext in videoExtensions -> MediaKind.VIDEO
            ext in audioExtensions -> MediaKind.AUDIO
            else -> null
        }
    }

    fun isSegment(url: String) = extensionOf(url) in segmentExtensions

    /**
     * True si la URL no tiene una extensión clara pero "huele" a medio, y vale la pena preguntar
     * al servidor su Content-Type.
     */
    fun shouldProbe(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val ext = extensionOf(url)
        if (ext.isNotEmpty() && ext.length <= 5) return false
        if (ext in ignoredExtensions || ext in segmentExtensions) return false
        val lower = url.lowercase()
        return probeHints.any { lower.contains(it) }
    }

    private val heightP = Regex("(?<![0-9])(\\d{3,4})[pP](?![a-zA-Z])")
    private val dimensions = Regex("(?<![0-9])\\d{3,4}[xX](\\d{3,4})(?![0-9])")
    private val validHeights = 120..4320

    /** Altura deducida de la URL (p. ej. `video_720p.mp4` o `1280x720`), o null. */
    fun heightFromUrl(url: String): Int? {
        val path = url.substringBefore('#')
        dimensions.findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in validHeights }?.let { return it }
        return heightP.findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in validHeights }
    }

    /** Clave para no listar dos veces el mismo vídeo pedido por rangos (`?range=`, `&bytes=`...). */
    fun dedupeKey(url: String): String {
        val noFragment = url.substringBefore('#')
        val q = noFragment.indexOf('?')
        if (q < 0) return noFragment
        val params = noFragment.substring(q + 1).split('&')
            .filterNot { p ->
                val name = p.substringBefore('=').lowercase()
                name == "range" || name == "bytes" || name == "rn" || name == "rbuf" || name == "_" || name == "t"
            }
        return noFragment.substring(0, q) + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}
