package com.simplevideodownloader.download

import java.net.URI

/** Variante de un "master playlist" HLS (una calidad). */
data class HlsVariant(val url: String, val bandwidth: Long, val resolution: String?, val name: String?) {
    val label: String
        get() = buildString {
            append(resolution?.substringAfter('x')?.let { "${it}p" } ?: name ?: "Calidad")
            if (bandwidth > 0) append(" · ").append(String.format("%.1f", bandwidth / 1_000_000.0)).append(" Mbps")
        }
}

/** Clave de cifrado de un segmento (solo AES-128 está soportado). */
data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?)

data class HlsSegment(val url: String, val sequence: Long, val key: HlsKey?, val byteRange: LongRange?)

sealed interface HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist
    data class Media(val segments: List<HlsSegment>, val initSegment: HlsSegment?, val isLive: Boolean) : HlsPlaylist {
        val isEncryptedUnsupported: Boolean
            get() = segments.any { it.key != null && it.key.method != "AES-128" && it.key.method != "NONE" }
    }
}

/** Lector mínimo de listas HLS (RFC 8216): calidades, segmentos, EXT-X-KEY, EXT-X-MAP y BYTERANGE. */
object HlsParser {

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull()?.startsWith("#EXTM3U") == true) { "No es una lista HLS" }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsPlaylist.Master {
        val variants = ArrayList<HlsVariant>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#") -> Unit
                pending != null -> {
                    variants += HlsVariant(
                        url = resolve(baseUrl, line),
                        bandwidth = pending["BANDWIDTH"]?.toLongOrNull() ?: pending["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0,
                        resolution = pending["RESOLUTION"],
                        name = pending["NAME"],
                    )
                    pending = null
                }
            }
        }
        return HlsPlaylist.Master(variants.distinctBy { it.url }.sortedByDescending { it.bandwidth })
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsPlaylist.Media {
        val segments = ArrayList<HlsSegment>()
        var sequence = 0L
        var key: HlsKey? = null
        var init: HlsSegment? = null
        var range: LongRange? = null
        var lastRangeEnd = 0L
        var ended = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else HlsKey(method, a["URI"]?.let { resolve(baseUrl, it) }, a["IV"]?.let(::hexToBytes))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    a["URI"]?.let { init = HlsSegment(resolve(baseUrl, it), -1, null, a["BYTERANGE"]?.let { r -> byteRange(r, 0) }) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    range = byteRange(line.substringAfter(':'), lastRangeEnd)
                    lastRangeEnd = range!!.last + 1
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#") -> Unit
                else -> {
                    segments += HlsSegment(resolve(baseUrl, line), sequence++, key, range)
                    range = null
                }
            }
        }
        return HlsPlaylist.Media(segments, init, isLive = !ended)
    }

    /** `RESOLUTION=1280x720,CODECS="a,b",URI="x"` → mapa (respeta comas dentro de comillas). */
    fun attributes(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val name = text.substring(i, eq).trim().trimStart(',')
            var j = eq + 1
            val value: String
            if (j < text.length && text[j] == '"') {
                val close = text.indexOf('"', j + 1).let { if (it < 0) text.length else it }
                value = text.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = text.indexOf(',', j).let { if (it < 0) text.length else it }
                value = text.substring(j, comma)
                j = comma
            }
            result[name] = value
            i = j + 1
        }
        return result
    }

    private fun byteRange(text: String, defaultOffset: Long): LongRange {
        val length = text.substringBefore('@').trim().toLong()
        val offset = text.substringAfter('@', "").trim().toLongOrNull() ?: defaultOffset
        return offset until offset + length
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun resolve(base: String, relative: String): String =
        runCatching { URI(base).resolve(relative.replace(" ", "%20")).toString() }.getOrDefault(relative)
}
