package com.simplevideodownloader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.simplevideodownloader.App
import com.simplevideodownloader.adblock.DomainUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Collections

/**
 * Iconos de los sitios para Inicio y favoritos. Se guardan los que manda el propio WebView al visitar una
 * página; si un sitio no tiene, se pide su icono directamente a la web (sin servicios de terceros).
 */
object Favicons {
    private val memory = LruCache<String, Bitmap>(64)
    private val failed = Collections.synchronizedSet(HashSet<String>())

    private fun keyOf(url: String) = DomainUtils.hostOf(url)?.removePrefix("www.")

    private fun fileFor(context: Context, host: String) =
        File(File(context.filesDir, "favicons").apply { mkdirs() }, "$host.png")

    /** Guarda el icono que recibió el navegador, si es más grande que el que ya teníamos. */
    fun save(context: Context, pageUrl: String?, icon: Bitmap) {
        val host = pageUrl?.let(::keyOf) ?: return
        val current = memory.get(host)
        if (current != null && current.width >= icon.width) return
        memory.put(host, icon)
        failed.remove(host)
        runCatching { fileFor(context, host).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun cached(url: String): Bitmap? = keyOf(url)?.let { memory.get(it) }

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val host = keyOf(url) ?: return@withContext null
        memory.get(host)?.let { return@withContext it }
        val file = fileFor(context, host)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let {
                memory.put(host, it)
                return@withContext it
            }
        }
        if (host in failed) return@withContext null
        val origin = url.substringBefore("://", "https") + "://" + DomainUtils.hostOf(url)
        val fetched = listOf("/apple-touch-icon.png", "/favicon.ico").firstNotNullOfOrNull { path -> download(origin + path) }
        if (fetched == null) {
            failed += host
            return@withContext null
        }
        memory.put(host, fetched)
        runCatching { file.outputStream().use { fetched.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        fetched
    }

    private fun download(url: String): Bitmap? = runCatching {
        App.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            if (bytes.size > 1_000_000) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 }
        }
    }.getOrNull()
}
