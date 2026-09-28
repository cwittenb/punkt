package de.punkt.app

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Kurzes Protokoll im App-Ordner, für die Fehlersuche. Sichtbar in den Optionen im Testmodus. */
object Protokoll {
    private const val MAX = 300
    private val format = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss")

    private fun datei(ctx: Context) = File(ctx.filesDir, "protokoll.txt")

    @Synchronized
    fun schreib(ctx: Context, text: String) {
        try {
            val f = datei(ctx)
            val zeilen = if (f.exists()) f.readLines().takeLast(MAX - 1) else emptyList()
            val neu = LocalDateTime.now().format(format) + "  " + text.replace('\n', ' ')
            f.writeText((zeilen + neu).joinToString("\n") + "\n")
        } catch (e: Exception) {
        }
    }

    /** Neueste Zeilen zuerst. */
    fun lesen(ctx: Context, n: Int = 80): String = try {
        val f = datei(ctx)
        if (f.exists()) f.readLines().takeLast(n).reversed().joinToString("\n") else ""
    } catch (e: Exception) {
        ""
    }
}
