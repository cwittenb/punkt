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

    // Grob geschätzte Zeilenzahl seit dem letzten Kürzen; genau zählen muss niemand
    private var seitKuerzen = -1

    @Synchronized
    fun schreib(ctx: Context, text: String) {
        try {
            val f = datei(ctx)
            val neu = LocalDateTime.now().format(format) + "  " + text.replace('\n', ' ') + "\n"
            f.appendText(neu)
            if (seitKuerzen < 0) seitKuerzen = if (f.exists()) f.readLines().size else 0 else seitKuerzen++
            if (seitKuerzen > MAX + 100) {
                f.writeText(f.readLines().takeLast(MAX).joinToString("\n") + "\n")
                seitKuerzen = MAX
            }
        } catch (e: Exception) {
        }
    }

    /** Ausnahme mit den obersten Zeilen des Stacktraces, inklusive Ursache. */
    fun fehler(ctx: Context, wo: String, e: Throwable) {
        val sb = StringBuilder("FEHLER $wo: ").append(e.javaClass.name).append(": ").append(e.message ?: "")
        var t: Throwable? = e
        var tiefe = 0
        while (t != null && tiefe < 3) {
            if (tiefe > 0) sb.append(" | Ursache: ").append(t.javaClass.name).append(": ").append(t.message ?: "")
            t.stackTrace.take(8).forEach { sb.append(" | at ").append(it.className.substringAfterLast('.')).append('.').append(it.methodName).append(':').append(it.lineNumber) }
            t = t.cause
            tiefe++
        }
        schreib(ctx, sb.toString())
    }

    @Volatile private var eingerichtet = false

    /** Schreibt jeden Absturz der App ins Protokoll, bevor Android sie beendet. Einmal je Prozess. */
    fun absturzFangen(ctx: Context) {
        if (eingerichtet) return
        eingerichtet = true
        val app = ctx.applicationContext
        val vorher = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                fehler(app, "ABSTURZ im Thread ${th.name}", e)
            } catch (x: Throwable) {
            }
            vorher?.uncaughtException(th, e)
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
