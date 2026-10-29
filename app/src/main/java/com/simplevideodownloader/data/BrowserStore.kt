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
        @Volatile private var instance: BrowserStore? = null

        fun get(context: Context): BrowserStore =
            instance ?: synchronized(this) { instance ?: BrowserStore(context.applicationContext).also { instance = it } }
    }
}
