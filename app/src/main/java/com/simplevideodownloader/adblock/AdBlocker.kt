package com.simplevideodownloader.adblock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.simplevideodownloader.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Punto de entrada del bloqueador: guarda las listas descargadas, construye el [AdBlockEngine]
 * y recuerda los ajustes (activado, listas elegidas, sitios permitidos).
 */
object AdBlocker {
    private const val TAG = "AdBlocker"
    const val PREF_ENABLED = "adblock_enabled"
    const val PREF_COSMETIC = "adblock_cosmetic"
    const val PREF_LISTS = "adblock_lists"
    private const val PREF_ALLOWLIST = "adblock_allowlist"
    private const val PREF_LAST_UPDATE = "adblock_last_update"

    data class Status(val ready: Boolean, val networkRules: Int, val cosmeticRules: Int, val lastUpdate: Long)

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buildMutex = Mutex()

    @Volatile
    var engine: AdBlockEngine = AdBlockEngine.EMPTY
        private set

    private val _status = MutableStateFlow(Status(false, 0, 0, 0))
    val status: StateFlow<Status> = _status

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        scope.launch {
            rebuild()
            // Primera vez: aún no hay listas descargadas, así que las bajamos ya.
            if (selectedLists().none { cacheFile(it.id).exists() }) updateLists()
        }
    }

    val isEnabled: Boolean get() = prefs.getBoolean(PREF_ENABLED, true)
    val isCosmeticEnabled: Boolean get() = prefs.getBoolean(PREF_COSMETIC, true)

    fun selectedLists(): List<FilterList> {
        val ids = prefs.getStringSet(PREF_LISTS, null) ?: FilterLists.defaultIds
        return FilterLists.all.filter { it.id in ids }
    }

    // --- Sitios en los que el usuario desactivó el bloqueo ---

    fun allowlist(): Set<String> = prefs.getStringSet(PREF_ALLOWLIST, emptySet())!!.toSet()

    fun isAllowlisted(host: String?): Boolean {
        if (host == null) return false
        return DomainUtils.matchesAny(host.lowercase().removePrefix("www."), allowlist())
    }

    fun setAllowlisted(host: String, allowed: Boolean) {
        val site = host.lowercase().removePrefix("www.")
        val set = allowlist().toMutableSet()
        if (allowed) set += site else set -= site
        prefs.edit().putStringSet(PREF_ALLOWLIST, set).apply()
    }

    fun clearAllowlist() = prefs.edit().remove(PREF_ALLOWLIST).apply()

    // --- Consultas desde el navegador ---

    /** Decide si bloquear una petición hecha por la página [pageUrl]. */
    fun shouldBlock(url: String, pageUrl: String?, type: ResourceType): Boolean {
        if (!isEnabled) return false
        val pageHost = pageUrl?.let { DomainUtils.hostOf(it) }
        if (isAllowlisted(pageHost)) return false
        val e = engine
        if (pageUrl != null && e.isPageAllowed(pageUrl)) return false
        return e.shouldBlock(url, pageUrl, type).blocked
    }

    fun cssFor(pageUrl: String?): String {
        if (!isEnabled || !isCosmeticEnabled || pageUrl == null) return ""
        val host = DomainUtils.hostOf(pageUrl) ?: return ""
        if (isAllowlisted(host)) return ""
        return engine.cssFor(host)
    }

    // --- Listas ---

    private fun cacheFile(id: String) = File(File(appContext.filesDir, "filters").apply { mkdirs() }, "$id.txt")

    /** Descarga las listas elegidas y reconstruye el motor. Devuelve cuántas listas se actualizaron. */
    suspend fun updateLists(): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (list in selectedLists()) {
            try {
                val request = Request.Builder().url(list.url).header("User-Agent", "SimpleVideoDownloader").build()
                App.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val tmp = File(cacheFile(list.id).path + ".tmp")
                    tmp.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                    if (tmp.length() < 100) error("Lista vacía")
                    tmp.renameTo(cacheFile(list.id))
                    updated++
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo descargar ${list.id}: ${e.message}")
            }
        }
        if (updated > 0) prefs.edit().putLong(PREF_LAST_UPDATE, System.currentTimeMillis()).apply()
        rebuild()
        updated
    }

    /** Vuelve a construir el motor con las listas guardadas (se llama también al cambiar la selección). */
    suspend fun rebuild() = buildMutex.withLock {
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val builder = AdBlockEngine.Builder()
            // Lista mínima incluida en la app, para bloquear lo más común incluso sin conexión.
            appContext.assets.open("adblock/base.txt").bufferedReader().use { builder.addList(it) }
            for (list in selectedLists()) {
                val file = cacheFile(list.id)
                if (file.exists()) file.bufferedReader().use { builder.addList(it) }
            }
            val built = builder.build()
            engine = built
            _status.value = Status(true, built.networkRuleCount, built.cosmeticRuleCount, prefs.getLong(PREF_LAST_UPDATE, 0))
            Log.i(TAG, "Motor listo: ${built.networkRuleCount} reglas de red, ${built.cosmeticRuleCount} de ocultación, ${System.currentTimeMillis() - start} ms")
        }
    }

    fun rebuildAsync() {
        scope.launch { rebuild() }
    }
}
