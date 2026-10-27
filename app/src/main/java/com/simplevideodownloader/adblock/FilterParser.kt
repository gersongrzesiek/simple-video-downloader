package com.simplevideodownloader.adblock

/**
 * Lee listas de filtros en formato Adblock Plus / uBlock / AdGuard (EasyList, EasyPrivacy, AdGuard...)
 * y archivos de tipo hosts.
 *
 * Solo entiende la parte "básica" de la sintaxis: reglas de red con sus opciones más comunes y reglas
 * de ocultación con selectores CSS. Las reglas avanzadas (scriptlets, redirecciones, CSP, selectores
 * procedimentales...) se descartan para no romper páginas por aplicarlas a medias.
 */
object FilterParser {

    sealed interface Parsed
    class Network(val rule: NetworkRule, val tokens: List<String>) : Parsed
    class Cosmetic(val rule: CosmeticRule) : Parsed
    /** Excepción de página: desactiva ocultación (`elemhide`) o solo la genérica (`generichide`). */
    class HideException(val domains: Set<String>, val genericOnly: Boolean) : Parsed

    private val unsupportedCosmeticMarkers = listOf("#?#", "#$#", "#%#", "#@?#", "#@$#", "#@%#", "##+js(", "#@#+js(", "##^", "#@#^")
    private val proceduralSelectors = listOf(
        ":has-text(", ":-abp-", ":upward(", ":xpath(", ":matches-css", ":style(", ":remove(",
        ":min-text-length(", ":watch-attr(", ":others(", ":contains(", ":matches-path(", ":matches-attr(",
        ":matches-prop(", ":remove-attr(", ":remove-class(", ":nth-ancestor(", ":if(", ":if-not(",
    )
    private val unsupportedOptions = setOf(
        "redirect", "redirect-rule", "rewrite", "csp", "removeparam", "queryprune", "replace", "header",
        "permissions", "to", "denyallow", "method", "ipaddress", "cname", "badfilter", "empty", "mp4",
        "popunder", "inline-script", "inline-font", "webrtc", "genericblock", "urlskip", "uritransform",
        "jsonprune", "hls", "network", "app", "extension", "content", "stealth", "cookie", "urltransform",
        "specifichide", "shide", "sitekey", "rewrite",
    )
    private val hostsPrefixes = listOf("0.0.0.0 ", "127.0.0.1 ", "0.0.0.0\t", "127.0.0.1\t", "::1 ", ":: ")
    private val simpleHost = Regex("^[a-z0-9_][a-z0-9._-]*[a-z0-9]$")

    fun parseLine(rawLine: String): Parsed? {
        val line = rawLine.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line.startsWith("# ") || line == "#") return null
        hostsPrefixes.firstOrNull { line.startsWith(it) }?.let { return parseHostsLine(line.substring(it.length)) }
        if (unsupportedCosmeticMarkers.any { line.contains(it) }) return null
        val hideIndex = line.indexOf("##")
        val exceptionHideIndex = line.indexOf("#@#")
        if (exceptionHideIndex >= 0 && (hideIndex < 0 || exceptionHideIndex < hideIndex)) {
            return parseCosmetic(line.substring(0, exceptionHideIndex), line.substring(exceptionHideIndex + 3), true)
        }
        if (hideIndex >= 0) {
            return parseCosmetic(line.substring(0, hideIndex), line.substring(hideIndex + 2), false)
        }
        if (line.startsWith("#")) return null
        return parseNetwork(line)
    }

    private fun parseHostsLine(rest: String): Parsed? {
        val host = rest.trim().substringBefore('#').trim().substringBefore(' ').substringBefore('\t').lowercase()
        if (host.isEmpty() || host == "localhost" || host == "0.0.0.0" || !simpleHost.matches(host)) return null
        return Network(hostRule(host, "||$host^"), listOf())
    }

    private fun parseCosmetic(domainPart: String, selector: String, isException: Boolean): Parsed? {
        val sel = selector.trim()
        if (sel.isEmpty() || proceduralSelectors.any { sel.contains(it) }) return null
        val (include, exclude) = parseDomainList(domainPart, ',') ?: return null
        return Cosmetic(CosmeticRule(sel, isException, include, exclude))
    }

    /** Devuelve null si la lista solo tenía dominios que no sabemos interpretar (p. ej. `google.*`). */
    private fun parseDomainList(text: String, separator: Char): Pair<Set<String>, Set<String>>? {
        if (text.isBlank()) return emptySet<String>() to emptySet()
        val include = HashSet<String>()
        val exclude = HashSet<String>()
        var hadIncludes = false
        for (item in text.split(separator)) {
            val d = item.trim().lowercase()
            if (d.isEmpty()) continue
            val negated = d.startsWith("~")
            val domain = if (negated) d.substring(1) else d
            if (!negated) hadIncludes = true
            if (domain.contains('*') || domain.contains('/')) continue
            if (negated) exclude += domain else include += domain
        }
        if (hadIncludes && include.isEmpty()) return null
        return include to exclude
    }

    private fun parseNetwork(line: String): Parsed? {
        var text = line
        val isException = text.startsWith("@@")
        if (isException) text = text.substring(2)

        var optionsText: String? = null
        val dollar = text.lastIndexOf('$')
        if (dollar >= 0 && !(text.startsWith("/") && text.indexOf('/', 1) > dollar)) {
            optionsText = text.substring(dollar + 1)
            text = text.substring(0, dollar)
        }

        var types: MutableSet<ResourceType>? = null
        val excludedTypes = HashSet<ResourceType>()
        var thirdParty: Boolean? = null
        var include: Set<String> = emptySet()
        var exclude: Set<String> = emptySet()
        var important = false
        var matchCase = false
        var hideException: Boolean? = null

        if (optionsText != null) {
            for (rawOption in optionsText.split(',')) {
                val option = rawOption.trim().lowercase()
                if (option.isEmpty()) continue
                val name = option.substringBefore('=')
                when {
                    name in unsupportedOptions -> return null
                    option == "third-party" || option == "3p" || option == "~first-party" || option == "~1p" || option == "strict3p" -> thirdParty = true
                    option == "~third-party" || option == "~3p" || option == "first-party" || option == "1p" || option == "strict1p" -> thirdParty = false
                    name == "domain" || name == "from" -> {
                        val parsed = parseDomainList(option.substringAfter('='), '|') ?: return null
                        include = parsed.first
                        exclude = parsed.second
                    }
                    option == "important" -> important = true
                    option == "match-case" -> matchCase = true
                    option == "all" -> types = ResourceType.entries.toMutableSet()
                    option == "elemhide" || option == "ehide" -> hideException = false
                    option == "generichide" || option == "ghide" -> hideException = true
                    option.startsWith("~") -> {
                        val type = ResourceType.fromOption(option.substring(1)) ?: return null
                        excludedTypes += type
                    }
                    else -> {
                        val type = ResourceType.fromOption(option) ?: return null
                        if (types == null) types = HashSet()
                        types!!.add(type)
                    }
                }
            }
        }

        if (hideException != null) {
            if (!isException) return null
            val host = hostFromPattern(text) ?: return null
            return HideException(include.ifEmpty { setOf(host) }, hideException)
        }

        val pattern = text.trim()
        if ((pattern.isEmpty() || pattern == "*" || pattern == "|" || pattern == "||") && include.isEmpty()) return null

        hostFromPattern(pattern)?.let { host ->
            val rule = NetworkRule(
                raw = line, isException = isException, hostname = host, substring = null, regexSource = null,
                matchCase = false, types = types, excludedTypes = excludedTypes, thirdParty = thirdParty,
                includeDomains = include, excludeDomains = exclude, important = important,
            )
            return Network(rule, listOf())
        }

        val isRegex = pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")
        val plain = !isRegex && pattern.none { it == '*' || it == '^' || it == '|' }
        val rule = NetworkRule(
            raw = line, isException = isException, hostname = null,
            substring = if (plain) pattern.lowercase() else null,
            regexSource = when {
                plain -> null
                isRegex -> pattern.substring(1, pattern.length - 1)
                else -> patternToRegex(pattern)
            },
            matchCase = matchCase, types = types, excludedTypes = excludedTypes, thirdParty = thirdParty,
            includeDomains = include, excludeDomains = exclude, important = important,
        )
        return Network(rule, if (isRegex) listOf() else tokensOf(pattern))
    }

    private fun hostRule(host: String, raw: String) = NetworkRule(
        raw = raw, isException = false, hostname = host, substring = null, regexSource = null, matchCase = false,
        types = null, excludedTypes = emptySet(), thirdParty = null, includeDomains = emptySet(),
        excludeDomains = emptySet(), important = false,
    )

    /** `||ejemplo.com^` o `||ejemplo.com` → "ejemplo.com". */
    private fun hostFromPattern(pattern: String): String? {
        if (!pattern.startsWith("||")) return null
        var host = pattern.substring(2)
        if (host.endsWith("^")) host = host.dropLast(1)
        host = host.lowercase()
        return if (simpleHost.matches(host) && host.contains('.')) host else null
    }

    fun patternToRegex(pattern: String): String {
        val sb = StringBuilder()
        var p = pattern
        when {
            p.startsWith("||") -> {
                sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?")
                p = p.substring(2)
            }
            p.startsWith("|") -> {
                sb.append('^')
                p = p.substring(1)
            }
        }
        val anchoredEnd = p.endsWith("|")
        if (anchoredEnd) p = p.dropLast(1)
        for (c in p) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_.%-]|$)")
                '|' -> sb.append("\\|")
                '.', '+', '?', '{', '}', '(', ')', '[', ']', '\\', '$' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        if (anchoredEnd) sb.append('$')
        return sb.toString()
    }

    /**
     * Fragmentos de texto que cualquier URL que cumpla el patrón contiene como "palabra" completa.
     * Sirven para indexar las reglas y no tener que probarlas todas en cada petición.
     */
    fun tokensOf(pattern: String): List<String> {
        val p = pattern.lowercase()
        val result = ArrayList<String>()
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) {
                i++
                continue
            }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val end = i
            val before = if (start == 0) null else p[start - 1]
            val after = if (end == p.length) null else p[end]
            // Un token pegado a un comodín, o al principio/final sin ancla, puede ser parte de una palabra más larga.
            val boundedLeft = before != null && before != '*'
            val boundedRight = after != null && after != '*'
            if (boundedLeft && boundedRight && end - start >= 3) result += p.substring(start, end)
        }
        return result
    }

    fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'
}
