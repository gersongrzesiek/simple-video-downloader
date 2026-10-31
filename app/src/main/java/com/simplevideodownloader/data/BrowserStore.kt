package com.simplevideodownloader.data

import android.content.Context
import com.simplevideodownloader.adblock.DomainUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un sitio guardado o visitado. */
data class Site(val url: String, val title: String)

/**
 * Favoritos y sitios más visitados. Se guardan en un JSON privado de la app.
 * Las visitas se cuentan por dominio, quedándonos con la última URL y título de cada uno.
 */
class BrowserStore private constructor(context: Context) {
    private val file = JsonFile(File(context.filesDir, "browser.json"))
    private val favoritesList = ArrayList<Site>()
    private val visits = LinkedHashMap<String, Visit>()
    /** Páginas visitadas (la más reciente primero), para sugerir mientras se escribe la dirección. */
    private val history = ArrayList<Site>()

    private data class Visit(val host: String, var url: String, var title: String, var count: Int, var last: Long)

    private val _favorites = MutableStateFlow<List<Site>>(emptyList())
    val favorites: StateFlow<List<Site>> = _favorites

    private val _topSites = MutableStateFlow<List<Site>>(emptyList())
    val topSites: StateFlow<List<Site>> = _topSites

    init {
        load()
        publish()
    }

    @Synchronized
    fun isFavorite(url: String?): Boolean = url != null && favoritesList.any { sameUrl(it.url, url) }

    @Synchronized
    fun toggleFavorite(url: String, title: String?): Boolean {
        val existing = favoritesList.indexOfFirst { sameUrl(it.url, url) }
        val nowFavorite = if (existing >= 0) {
            favoritesList.removeAt(existing)
            false
        } else {
            favoritesList.add(0, Site(url, title?.takeIf { it.isNotBlank() } ?: DomainUtils.hostOf(url) ?: url))
            true
        }
        save()
        return nowFavorite
    }

    @Synchronized
    fun removeFavorite(url: String) {
        favoritesList.removeAll { sameUrl(it.url, url) }
        save()
    }

    @Synchronized
    fun recordVisit(url: String, title: String?) {
        if (!url.startsWith("http")) return
        val host = DomainUtils.hostOf(url)?.removePrefix("www.") ?: return
        val now = System.currentTimeMillis()
        val visit = visits.getOrPut(host) { Visit(host, url, host, 0, now) }
        visit.count++
        visit.last = now
        // Para el acceso directo preferimos la portada del sitio, no la página concreta.
        visit.url = url.substringBefore('?').substringBefore('#').let { full ->
            val schemeEnd = full.indexOf("://") + 3
            val slash = full.indexOf('/', schemeEnd)
            if (slash > 0) full.substring(0, slash + 1) else "$full/"
        }
        if (!title.isNullOrBlank() && visit.count <= 1) visit.title = title
        history.removeAll { sameUrl(it.url, url) }
        history.add(0, Site(url, title?.takeIf { it.isNotBlank() } ?: host))
        if (history.size > MAX_HISTORY) history.subList(MAX_HISTORY, history.size).clear()
        save()
    }

    /**
     * Dominio con el que completar lo escrito en la barra (el más visitado que empiece así), sin "www.".
     * Ejemplo: "you" → "youtube.com". Null si no hay ninguno.
     */
    @Synchronized
    fun completeHost(typed: String): String? {
        val t = typed.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (t.isEmpty() || t.contains('/') || t.contains(' ')) return null
        return visits.values.sortedByDescending { it.count }.map { it.host }.firstOrNull { it.startsWith(t) && it != t }
    }

    /** Sugerencias para lo escrito: favoritos, sitios e historial cuya dirección o título lo contengan. */
    @Synchronized
    fun suggest(typed: String, limit: Int = 6): List<Site> {
        val t = typed.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (t.isEmpty()) return emptyList()
        fun bare(url: String) = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        fun score(site: Site): Int {
            val u = bare(site.url)
            return when {
                u.startsWith(t) -> 0
                u.contains(t) -> 1
                site.title.lowercase().contains(t) -> 2
                else -> -1
            }
        }
        val candidates = favoritesList + visits.values.sortedByDescending { it.count }.map { Site(it.url, it.title) } + history
        return candidates.map { it to score(it) }.filter { it.second >= 0 }
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { bare(it.url).trimEnd('/') }
            .take(limit)
    }

    /** Páginas visitadas, la más reciente primero. */
    @Synchronized
    fun history(): List<Site> = history.toList()

    @Synchronized
    fun clearHistory() {
        history.clear()
        save()
    }

    @Synchronized
    fun removeTopSite(url: String) {
        DomainUtils.hostOf(url)?.removePrefix("www.")?.let { visits.remove(it) }
        save()
    }

    private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    private fun load() {
        val text = file.read() ?: return
        runCatching {
            val json = JSONObject(text)
            json.optJSONArray("favorites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    favoritesList += Site(o.getString("url"), o.optString("title"))
                }
            }
            json.optJSONArray("history")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    history += Site(o.getString("url"), o.optString("title"))
                }
            }
            json.optJSONArray("visits")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val v = Visit(o.getString("host"), o.getString("url"), o.optString("title"), o.optInt("count"), o.optLong("last"))
                    visits[v.host] = v
                }
            }
        }
    }

    private fun save() {
        val json = JSONObject()
        json.put("favorites", JSONArray().apply {
            favoritesList.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) }
        })
        json.put("history", JSONArray().apply {
            history.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) }
        })
        // Guardamos solo los 100 dominios más visitados.
        json.put("visits", JSONArray().apply {
            visits.values.sortedByDescending { it.count }.take(100).forEach {
                put(JSONObject().put("host", it.host).put("url", it.url).put("title", it.title).put("count", it.count).put("last", it.last))
            }
        })
        file.write(json.toString())
        publish()
    }

    private fun publish() {
        _favorites.value = favoritesList.toList()
        _topSites.value = visits.values
            .filter { v -> favoritesList.none { DomainUtils.hostOf(it.url)?.removePrefix("www.") == v.host } }
            .sortedWith(compareByDescending<Visit> { it.count }.thenByDescending { it.last })
            .take(8)
            .map { Site(it.url, it.title) }
    }

    companion object {
        private const val MAX_HISTORY = 300
        @Volatile private var instance: BrowserStore? = null

        fun get(context: Context): BrowserStore =
            instance ?: synchronized(this) { instance ?: BrowserStore(context.applicationContext).also { instance = it } }
    }
}
