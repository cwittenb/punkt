package de.punkt.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * Diktat ins Textfeld, nur auf dem Gerät (createOnDeviceSpeechRecognizer). Es entsteht keine
 * Audiodatei. Die Erkennung beendet sich nach einer Sprechpause von selbst; solange der Nutzer
 * nicht stoppt, wird sie neu gestartet, damit auch längere Antworten durchgehen.
 *
 * An die Web-App gehen Ereignisse window.punktNativ.diktat({key, art, text?, grund?}):
 *   art = "bereit" | "teil" (vorläufig) | "satz" (fest) | "ende" | "fehler"
 *
 * Absturzsicher: Jeder Rückruf der Erkennung läuft in sicher{}, nichts verlässt diese Klasse als
 * Ausnahme. Endet ein Diktat nicht sauber (Absturz der App), schaltet sich das Diktat bis zur
 * nächsten App-Version ab; dann nimmt die App wieder Sprachnotizen auf.
 * Alles hier läuft auf dem Hauptthread.
 */
class Diktat(private val a: MainActivity) {
    private val hand = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var key: String? = null
    private var aktiv = false
    private var fehlerFolge = 0
    private var notAus: Runnable? = null
    private var downloadAngestossen = false

    /** Gibt es eine Erkennung auf dem Gerät, und ist das Diktat nicht wegen eines Absturzes gesperrt? */
    val verfuegbar: Boolean by lazy {
        val p = Speicher.prefs(a)
        val version = versionCode(a)
        // Lief beim letzten Mal ein Diktat, als die App starb: bis zur nächsten Version gesperrt
        if (p.getBoolean("diktatLaeuft", false)) {
            Protokoll.schreib(a, "Diktat endete beim letzten Mal nicht sauber, bis zum nächsten Update aus")
            p.edit().putBoolean("diktatLaeuft", false).putLong("diktatAusBis", version).apply()
        }
        if (p.getLong("diktatAusBis", -1L) == version) return@lazy false
        try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
        } catch (e: Throwable) {
            Protokoll.fehler(a, "Diktat verfügbar?", e)
            false
        }
    }

    fun laeuft() = key != null

    fun start(feld: String) = sicher("start") {
        if (key != null) stoppJetzt()
        key = feld
        aktiv = true
        fehlerFolge = 0
        Speicher.prefs(a).edit().putBoolean("diktatLaeuft", true).commit()
        Protokoll.schreib(a, "Diktat startet")
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(a)
        r.setRecognitionListener(hoerer)
        sr = r
        hoeren()
    }

    /** Beendet das Diktat; das letzte Stück kommt noch als "satz", dann "ende". */
    fun stopp() = sicher("stopp") {
        if (key == null) return@sicher
        aktiv = false
        try {
            sr?.stopListening()
        } catch (e: Throwable) {
        }
        // Liefert die Erkennung nichts mehr, trotzdem sauber beenden
        val r = Runnable { sicher("notAus") { ende() } }
        notAus = r
        hand.postDelayed(r, 2500)
    }

    /** Sofort beenden, ohne auf das letzte Stück zu warten (App geht in den Hintergrund). */
    fun stoppJetzt() = sicher("stoppJetzt") {
        if (key == null) return@sicher
        aktiv = false
        try {
            sr?.cancel()
        } catch (e: Throwable) {
        }
        ende()
    }

    private fun absicht(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun hoeren() {
        val r = sr ?: return
        if (!aktiv) return
        try {
            r.startListening(absicht())
        } catch (e: Throwable) {
            Protokoll.fehler(a, "Diktat startListening", e)
            fehler("nicht")
        }
    }

    /** Neustart nach einer Pause oder einem Fehler, mit Abstand, damit die Erkennung frei ist. */
    private fun spaeterHoeren(ms: Long) {
        hand.postDelayed({ sicher("hoeren") { hoeren() } }, ms)
    }

    private val hoerer = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) = sicher("bereit") {
            fehlerFolge = 0
            melde("bereit")
        }

        override fun onPartialResults(b: Bundle?) = sicher("teil") {
            text(b)?.let { melde("teil", it) }
        }

        override fun onResults(b: Bundle?) = sicher("satz") {
            text(b)?.let { melde("satz", it) }
            if (aktiv) spaeterHoeren(100) else hand.post { sicher("ende") { ende() } }
        }

        override fun onError(code: Int) = sicher("fehler") {
            Protokoll.schreib(a, "Diktat: Erkennung meldet $code")
            when (code) {
                // Sprechpause oder nichts verstanden: weiterhören, solange nicht gestoppt
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (aktiv) spaeterHoeren(200) else hand.post { sicher("ende") { ende() } }
                SpeechRecognizer.ERROR_CLIENT -> if (!aktiv) hand.post { sicher("ende") { ende() } }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fehler("mikrofon")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    sprachpaketLaden()
                    fehler("sprachpaket")
                }
                else -> {
                    fehlerFolge++
                    if (aktiv && fehlerFolge <= 3) spaeterHoeren(500) else fehler("code$code")
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buf: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(typ: Int, p: Bundle?) {}
    }

    /** Android 13+: Download des Sprachpakets beim Sprachdienst anstoßen, einmal je App-Lauf. */
    private fun sprachpaketLaden() {
        if (downloadAngestossen || Build.VERSION.SDK_INT < 33) return
        downloadAngestossen = true
        try {
            sr?.triggerModelDownload(absicht())
            Protokoll.schreib(a, "Diktat: Sprachpaket angefordert")
        } catch (e: Throwable) {
            Protokoll.fehler(a, "Diktat Sprachpaket", e)
        }
    }

    private fun text(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifEmpty { null }

    private fun fehler(grund: String) {
        Protokoll.schreib(a, "Diktat: Fehler $grund")
        melde("fehler", grund = grund)
        aktiv = false
        // Nicht innerhalb des Rückrufs der Erkennung zerstören
        hand.post { sicher("ende") { ende(still = true) } }
    }

    private fun ende(still: Boolean = false) {
        notAus?.let { hand.removeCallbacks(it) }
        notAus = null
        val k = key ?: return
        key = null
        aktiv = false
        val r = sr
        sr = null
        try {
            r?.destroy()
        } catch (e: Throwable) {
            Protokoll.fehler(a, "Diktat destroy", e)
        }
        Speicher.prefs(a).edit().putBoolean("diktatLaeuft", false).apply()
        if (!still) melde("ende", feld = k)
        Protokoll.schreib(a, "Diktat beendet")
    }

    private fun melde(art: String, text: String? = null, grund: String? = null, feld: String? = key) {
        val o = JSONObject().put("key", feld ?: "").put("art", art)
        if (text != null) o.put("text", text)
        if (grund != null) o.put("grund", grund)
        a.js("window.punktNativ&&window.punktNativ.diktat&&window.punktNativ.diktat($o)")
    }

    /** Fängt alles ab: ein Fehler im Diktat darf die App nicht beenden. */
    private inline fun sicher(wo: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Protokoll.fehler(a, "Diktat $wo", e)
            val k = key
            key = null
            aktiv = false
            try {
                sr?.destroy()
            } catch (x: Throwable) {
            }
            sr = null
            Speicher.prefs(a).edit().putBoolean("diktatLaeuft", false).apply()
            if (k != null) melde("fehler", grund = "nicht", feld = k)
        }
    }

    companion object {
        fun versionCode(ctx: Context): Long = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        } catch (e: Throwable) {
            0L
        }
    }
}
