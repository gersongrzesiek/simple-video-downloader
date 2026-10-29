package com.simplevideodownloader.data

import android.util.AtomicFile
import java.io.File

/** Lee y escribe un archivo de texto de forma atómica (si la app muere a mitad, no se corrompe). */
internal class JsonFile(file: File) {
    private val atomic = AtomicFile(file)

    fun read(): String? = runCatching { String(atomic.readFully(), Charsets.UTF_8) }.getOrNull()

    @Synchronized
    fun write(text: String) {
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
        }
    }
}
