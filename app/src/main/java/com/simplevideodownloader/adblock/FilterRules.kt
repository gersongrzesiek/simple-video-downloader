package com.simplevideodownloader.adblock

/** Tipo de recurso de una petición, en el sentido de las opciones de las listas de filtros. */
enum class ResourceType(val option: String) {
    DOCUMENT("document"),
    SUBDOCUMENT("subdocument"),
    SCRIPT("script"),
    STYLESHEET("stylesheet"),
    IMAGE("image"),
    MEDIA("media"),
    FONT("font"),
    XHR("xmlhttprequest"),
    WEBSOCKET("websocket"),
    PING("ping"),
    POPUP("popup"),
    OTHER("other");

    companion object {
        private val byOption = entries.associateBy { it.option } + mapOf(
            "xhr" to XHR,
            "frame" to SUBDOCUMENT,
            "css" to STYLESHEET,
            "doc" to DOCUMENT,
            "object" to OTHER,
            "object-subrequest" to OTHER,
            "beacon" to PING,
        )

        fun fromOption(name: String): ResourceType? = byOption[name]
    }
}

/** Una regla de red (bloqueo o excepción) ya compilada. */
class NetworkRule(
    val raw: String,
    val isException: Boolean,
    /** Dominio para reglas del tipo `||dominio^`. */
    val hostname: String?,
    /** Texto literal (en minúsculas) que debe aparecer en la URL, para reglas sin comodines. */
    val substring: String?,
    /** Expresión regular equivalente; se compila la primera vez que hace falta. */
    private val regexSource: String?,
    private val matchCase: Boolean,
    /** Tipos permitidos; null significa "todos menos documento y popup". */
    val types: Set<ResourceType>?,
    /** Tipos excluidos con `~tipo`. */
    val excludedTypes: Set<ResourceType>,
    /** null = cualquiera; true = solo terceros; false = solo mismo sitio. */
    val thirdParty: Boolean?,
    val includeDomains: Set<String>,
    val excludeDomains: Set<String>,
    val important: Boolean,
) {
    fun matchesContext(type: ResourceType, isThirdParty: Boolean, pageHost: String?): Boolean {
        if (types != null) {
            if (type !in types) return false
        } else if (type == ResourceType.DOCUMENT || type == ResourceType.POPUP) {
            // Sin tipos explícitos una regla no bloquea páginas completas ni ventanas emergentes.
            return false
        }
        if (type in excludedTypes) return false
        if (thirdParty != null && thirdParty != isThirdParty) return false
        if (includeDomains.isNotEmpty() || excludeDomains.isNotEmpty()) {
            val host = pageHost ?: return includeDomains.isEmpty()
            if (DomainUtils.matchesAny(host, excludeDomains)) return false
            if (includeDomains.isNotEmpty() && !DomainUtils.matchesAny(host, includeDomains)) return false
        }
        return true
    }

    private val regex: Regex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        regexSource?.let {
            runCatching { if (matchCase) Regex(it) else Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
    }

    /** [lowerUrl] es la URL en minúsculas; [url] la original, para reglas `match-case`. */
    fun matchesUrl(url: String, lowerUrl: String, urlHost: String): Boolean = when {
        hostname != null -> DomainUtils.isSameOrSubdomain(urlHost, hostname)
        substring != null -> lowerUrl.contains(substring)
        else -> regex?.containsMatchIn(url) ?: false
    }
}

/** Regla de ocultación de elementos (`##selector`), con sus dominios. */
class CosmeticRule(
    val selector: String,
    val isException: Boolean,
    val includeDomains: Set<String>,
    val excludeDomains: Set<String>,
)

object DomainUtils {
    /** Sufijos de segundo nivel frecuentes, para calcular el "sitio" sin la lista pública completa. */
    private val secondLevelSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.ar", "com.br", "com.mx", "com.co", "com.pe", "com.ve",
        "com.uy", "com.py", "com.bo", "com.ec", "com.au", "net.au", "co.jp", "ne.jp", "co.kr", "co.in",
        "co.za", "co.nz", "com.tr", "com.cn", "com.hk", "com.tw", "com.sg", "com.my", "com.ph", "com.pk",
        "com.ng", "com.eg", "com.sa", "com.ua", "co.id", "or.jp", "gob.mx", "gob.ar", "gov.br", "blogspot.com",
        "github.io", "appspot.com", "cloudfront.net", "herokuapp.com",
    )

    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        var start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        var host = url.substring(start, end)
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            return if (close > 0) host.substring(0, close + 1).lowercase() else null
        }
        val colon = host.indexOf(':')
        if (colon >= 0) host = host.substring(0, colon)
        return host.trimEnd('.').lowercase().ifEmpty { null }
    }

    /** Dominio registrable aproximado ("eTLD+1"). */
    fun siteOf(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in secondLevelSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun isThirdParty(requestHost: String, pageHost: String?): Boolean {
        if (pageHost == null) return false
        return siteOf(requestHost) != siteOf(pageHost)
    }

    fun isSameOrSubdomain(host: String, domain: String): Boolean =
        host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')

    fun matchesAny(host: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var h = host
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
