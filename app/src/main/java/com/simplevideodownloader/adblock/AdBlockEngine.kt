package com.simplevideodownloader.adblock

import java.io.BufferedReader

/**
 * Motor de bloqueo: decide si una petición se bloquea y qué CSS de ocultación inyectar en cada sitio.
 *
 * Se construye una vez a partir de las listas de filtros con [Builder] y después solo se lee,
 * así que se puede consultar desde varios hilos a la vez (WebView llama a
 * `shouldInterceptRequest` desde hilos de fondo).
 */
class AdBlockEngine private constructor(
    private val blockHosts: Map<String, List<NetworkRule>>,
    private val exceptionHosts: Map<String, List<NetworkRule>>,
    private val blockTokens: Map<String, List<NetworkRule>>,
    private val blockNoToken: List<NetworkRule>,
    private val exceptionTokens: Map<String, List<NetworkRule>>,
    private val exceptionNoToken: List<NetworkRule>,
    private val genericSelectors: List<String>,
    private val genericWithExclusions: List<CosmeticRule>,
    private val specificSelectors: Map<String, List<CosmeticRule>>,
    private val cosmeticExceptions: Map<String, Set<String>>,
    private val elemHideDisabled: Set<String>,
    private val genericHideDisabled: Set<String>,
    val networkRuleCount: Int,
    val cosmeticRuleCount: Int,
) {
    data class Decision(val blocked: Boolean, val rule: String? = null)

    private val allow = Decision(false)

    private val genericCss: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { toCss(genericSelectors) }

    fun shouldBlock(url: String, pageUrl: String?, type: ResourceType): Decision {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ws")) return allow
        val urlHost = DomainUtils.hostOf(url) ?: return allow
        val pageHost = pageUrl?.let { DomainUtils.hostOf(it) }
        val thirdParty = DomainUtils.isThirdParty(urlHost, pageHost)
        val lowerUrl = url.lowercase()

        val blocking = findMatch(url, lowerUrl, urlHost, pageHost, type, thirdParty, blockHosts, blockTokens, blockNoToken)
            ?: return allow
        if (!blocking.important &&
            findMatch(url, lowerUrl, urlHost, pageHost, type, thirdParty, exceptionHosts, exceptionTokens, exceptionNoToken) != null
        ) {
            return allow
        }
        return Decision(true, blocking.raw)
    }

    /** True si una regla `@@...$document` permite la página completa. */
    fun isPageAllowed(pageUrl: String): Boolean {
        val host = DomainUtils.hostOf(pageUrl) ?: return false
        val lower = pageUrl.lowercase()
        return findMatch(pageUrl, lower, host, host, ResourceType.DOCUMENT, false, exceptionHosts, exceptionTokens, exceptionNoToken) != null
    }

    private fun findMatch(
        url: String,
        lowerUrl: String,
        urlHost: String,
        pageHost: String?,
        type: ResourceType,
        thirdParty: Boolean,
        hosts: Map<String, List<NetworkRule>>,
        tokens: Map<String, List<NetworkRule>>,
        noToken: List<NetworkRule>,
    ): NetworkRule? {
        // 1) Reglas por dominio: probamos el host y cada dominio padre.
        var h = urlHost
        while (true) {
            hosts[h]?.firstOrNull { it.matchesContext(type, thirdParty, pageHost) }?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        // 2) Reglas indexadas por los tokens de la URL.
        var i = 0
        val n = lowerUrl.length
        while (i < n) {
            if (!FilterParser.isTokenChar(lowerUrl[i])) {
                i++
                continue
            }
            val start = i
            while (i < n && FilterParser.isTokenChar(lowerUrl[i])) i++
            if (i - start < 3) continue
            val candidates = tokens[lowerUrl.substring(start, i)] ?: continue
            for (rule in candidates) {
                if (rule.matchesContext(type, thirdParty, pageHost) && rule.matchesUrl(url, lowerUrl, urlHost)) return rule
            }
        }
        // 3) Reglas sin token (pocas).
        for (rule in noToken) {
            if (rule.matchesContext(type, thirdParty, pageHost) && rule.matchesUrl(url, lowerUrl, urlHost)) return rule
        }
        return null
    }

    /** CSS que oculta los elementos publicitarios de [host]. Vacío si no hay nada que ocultar. */
    fun cssFor(host: String): String {
        val h = host.lowercase()
        if (DomainUtils.matchesAny(h, elemHideDisabled)) return ""
        val exceptions = HashSet<String>()
        forEachParent(h) { d -> cosmeticExceptions[d]?.let { exceptions.addAll(it) } }

        val sb = StringBuilder()
        if (!DomainUtils.matchesAny(h, genericHideDisabled)) {
            if (exceptions.isEmpty()) {
                sb.append(genericCss)
            } else {
                sb.append(toCss(genericSelectors.filter { it !in exceptions }))
            }
            val extra = genericWithExclusions.filter { !DomainUtils.matchesAny(h, it.excludeDomains) && it.selector !in exceptions }
            sb.append(toCss(extra.map { it.selector }))
        }
        val specific = ArrayList<String>()
        forEachParent(h) { d ->
            specificSelectors[d]?.forEach { rule ->
                if (!DomainUtils.matchesAny(h, rule.excludeDomains) && rule.selector !in exceptions) specific += rule.selector
            }
        }
        sb.append(toCss(specific))
        return sb.toString()
    }

    private inline fun forEachParent(host: String, block: (String) -> Unit) {
        var h = host
        while (true) {
            block(h)
            val dot = h.indexOf('.')
            if (dot < 0) return
            h = h.substring(dot + 1)
        }
    }

    /** Una regla CSS por selector: si un selector no es válido, el navegador descarta solo ese. */
    private fun toCss(selectors: Collection<String>): String {
        if (selectors.isEmpty()) return ""
        val sb = StringBuilder(selectors.size * 40)
        for (s in selectors) sb.append(s).append("{display:none!important}\n")
        return sb.toString()
    }

    class Builder {
        private val blockHosts = HashMap<String, MutableList<NetworkRule>>()
        private val exceptionHosts = HashMap<String, MutableList<NetworkRule>>()
        private val blockTokens = HashMap<String, MutableList<NetworkRule>>()
        private val exceptionTokens = HashMap<String, MutableList<NetworkRule>>()
        private val blockNoToken = ArrayList<NetworkRule>()
        private val exceptionNoToken = ArrayList<NetworkRule>()
        private val tokenUse = HashMap<String, Int>()
        private val pendingTokenRules = ArrayList<Pair<NetworkRule, List<String>>>()
        private val genericSelectors = LinkedHashSet<String>()
        private val genericWithExclusions = ArrayList<CosmeticRule>()
        private val specificSelectors = HashMap<String, MutableList<CosmeticRule>>()
        private val cosmeticExceptions = HashMap<String, MutableSet<String>>()
        private val genericCosmeticExceptions = HashSet<String>()
        private val elemHideDisabled = HashSet<String>()
        private val genericHideDisabled = HashSet<String>()
        private val seenNetwork = HashSet<String>()
        private var networkCount = 0
        private var cosmeticCount = 0

        fun addList(reader: BufferedReader): Builder {
            reader.forEachLine { addLine(it) }
            return this
        }

        fun addText(text: String): Builder {
            text.lineSequence().forEach { addLine(it) }
            return this
        }

        fun addLine(line: String) {
            when (val parsed = FilterParser.parseLine(line)) {
                null -> Unit
                is FilterParser.Network -> addNetwork(parsed)
                is FilterParser.Cosmetic -> addCosmetic(parsed.rule)
                is FilterParser.HideException ->
                    (if (parsed.genericOnly) genericHideDisabled else elemHideDisabled).addAll(parsed.domains)
            }
        }

        private fun addNetwork(parsed: FilterParser.Network) {
            val rule = parsed.rule
            if (!seenNetwork.add(rule.raw)) return
            networkCount++
            val host = rule.hostname
            if (host != null) {
                val map = if (rule.isException) exceptionHosts else blockHosts
                map.getOrPut(host) { ArrayList(1) }.add(rule)
                return
            }
            if (parsed.tokens.isEmpty()) {
                (if (rule.isException) exceptionNoToken else blockNoToken) += rule
            } else {
                parsed.tokens.forEach { tokenUse[it] = (tokenUse[it] ?: 0) + 1 }
                pendingTokenRules += rule to parsed.tokens
            }
        }

        private fun addCosmetic(rule: CosmeticRule) {
            cosmeticCount++
            if (rule.isException) {
                if (rule.includeDomains.isEmpty()) {
                    genericCosmeticExceptions += rule.selector
                } else {
                    rule.includeDomains.forEach { cosmeticExceptions.getOrPut(it) { HashSet() }.add(rule.selector) }
                }
                return
            }
            when {
                rule.includeDomains.isNotEmpty() ->
                    rule.includeDomains.forEach { specificSelectors.getOrPut(it) { ArrayList() }.add(rule) }
                rule.excludeDomains.isNotEmpty() -> genericWithExclusions += rule
                else -> genericSelectors += rule.selector
            }
        }

        fun build(): AdBlockEngine {
            // Indexamos cada regla por su token menos usado, para que cada cubo tenga pocas reglas.
            for ((rule, tokens) in pendingTokenRules) {
                val best = tokens.minWith(compareBy<String> { tokenUse[it] ?: 0 }.thenByDescending { it.length })
                val map = if (rule.isException) exceptionTokens else blockTokens
                map.getOrPut(best) { ArrayList(2) }.add(rule)
            }
            pendingTokenRules.clear()
            genericSelectors.removeAll(genericCosmeticExceptions)
            genericWithExclusions.removeAll { it.selector in genericCosmeticExceptions }
            return AdBlockEngine(
                blockHosts, exceptionHosts, blockTokens, blockNoToken, exceptionTokens, exceptionNoToken,
                genericSelectors.toList(), genericWithExclusions, specificSelectors, cosmeticExceptions,
                elemHideDisabled, genericHideDisabled, networkCount, cosmeticCount,
            )
        }
    }

    companion object {
        val EMPTY: AdBlockEngine = Builder().build()
    }
}
