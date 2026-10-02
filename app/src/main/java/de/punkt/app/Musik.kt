package de.punkt.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File

/** Hintergrundmusik fürs Breathwork: eine Datei, kopiert in den App-Ordner, in Schleife, mit Ausblenden. */
class Musik(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    private val hand = Handler(Looper.getMainLooper())
    private var fade: Runnable? = null
    private val datei get() = File(ctx.filesDir, "musik/aktuell")

    /** Kopiert die gewählte Datei in den App-Ordner und gibt ihren Anzeigenamen zurück. */
    fun uebernehmen(uri: Uri): String? = try {
        val f = datei
        f.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        var name = "Musik"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        Protokoll.schreib(ctx, "Musik übernommen: $name (${f.length() / 1024} kB)")
        name.substringBeforeLast('.')
    } catch (e: Exception) {
        Protokoll.schreib(ctx, "Musik übernehmen fehlgeschlagen: ${e.javaClass.simpleName}")
        null
    }

    fun start(vol: Float) {
        stopp(0)
        if (!datei.exists()) return
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            m.setDataSource(datei.absolutePath)
            m.isLooping = true
            m.setVolume(0f, 0f)
            m.prepare()
            m.start()
            mp = m
            // Einblenden über 6 Sekunden
            blende(0f, vol.coerceIn(0f, 1f), 6000L, null)
        } catch (e: Exception) {
            Protokoll.schreib(ctx, "Musik start fehlgeschlagen: ${e.javaClass.simpleName}")
            mp = null
        }
    }

    fun stopp(fadeMs: Long) {
        val m = mp ?: return
        fade?.let { hand.removeCallbacks(it) }
        if (fadeMs <= 0) {
            mp = null
            try { m.stop(); m.release() } catch (e: Exception) {}
            return
        }
        blende(lautAktuell, 0f, fadeMs) {
            if (mp === m) mp = null
            try { m.stop(); m.release() } catch (e: Exception) {}
        }
    }

    private var lautAktuell = 0f

    private fun blende(von: Float, bis: Float, dauer: Long, danach: (() -> Unit)?) {
        fade?.let { hand.removeCallbacks(it) }
        val start = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val m = mp ?: return
                val t = ((System.currentTimeMillis() - start).toFloat() / dauer).coerceIn(0f, 1f)
                lautAktuell = von + (bis - von) * t
                try { m.setVolume(lautAktuell, lautAktuell) } catch (e: Exception) {}
                if (t < 1f) hand.postDelayed(this, 100) else danach?.invoke()
            }
        }
        fade = r
        hand.post(r)
    }
}
