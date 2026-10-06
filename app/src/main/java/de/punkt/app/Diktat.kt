package de.punkt.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
 * Alles hier läuft auf dem Hauptthread.
 */
class Diktat(private val a: MainActivity) {
    private val hand = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var key: String? = null
    private var aktiv = false
    private var fehlerFolge = 0
    private var notAus: Runnable? = null

    /** Gibt es eine Erkennung auf dem Gerät? Einmal beim Start der Activity ermittelt. */
    val verfuegbar: Boolean by lazy {
        try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
        } catch (e: Exception) {
            false
        }
    }

    fun laeuft() = key != null

    fun start(feld: String) {
        if (key != null) stoppJetzt()
        key = feld
        aktiv = true
        fehlerFolge = 0
        Protokoll.schreib(a, "Diktat startet")
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(a)
        } catch (e: Exception) {
            null
        }
        if (r == null) return fehler("nicht")
        r.setRecognitionListener(hoerer)
        sr = r
        if (Build.VERSION.SDK_INT >= 33) sprachpaketPruefen(r) else hoeren()
    }

    /** Beendet das Diktat; das letzte Stück kommt noch als "satz", dann "ende". */
    fun stopp() {
        if (key == null) return
        aktiv = false
        try {
            sr?.stopListening()
        } catch (e: Exception) {
        }
        // Liefert die Erkennung nichts mehr, trotzdem sauber beenden
        val r = Runnable { ende() }
        notAus = r
        hand.postDelayed(r, 2500)
    }

    /** Sofort beenden, ohne auf das letzte Stück zu warten (App geht in den Hintergrund). */
    fun stoppJetzt() {
        if (key == null) return
        aktiv = false
        try {
            sr?.cancel()
        } catch (e: Exception) {
        }
        ende()
    }

    private fun absicht(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= 33) {
            // Satzzeichen und Großschreibung, soweit die Erkennung es kann
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private fun hoeren() {
        val r = sr ?: return
        if (!aktiv) return
        try {
            r.startListening(absicht())
        } catch (e: Exception) {
            fehler("nicht")
        }
    }

    /** Android 13+: Ist Deutsch auf dem Gerät installiert? Sonst Download anstoßen und melden. */
    private fun sprachpaketPruefen(r: SpeechRecognizer) {
        try {
            r.checkRecognitionSupport(absicht(), a.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    if (sr !== r) return
                    val de = { l: List<String> -> l.any { it.startsWith("de") } }
                    when {
                        de(s.installedOnDeviceLanguages) -> hoeren()
                        de(s.pendingOnDeviceLanguages) -> fehler("sprachpaket")
                        de(s.supportedOnDeviceLanguages) -> {
                            try {
                                r.triggerModelDownload(absicht())
                            } catch (e: Exception) {
                            }
                            fehler("sprachpaket")
                        }
                        // Keine Angaben (manche Dienste füllen die Listen nicht): einfach versuchen
                        else -> hoeren()
                    }
                }

                override fun onError(code: Int) {
                    if (sr === r) hoeren()
                }
            })
        } catch (e: Exception) {
            hoeren()
        }
    }

    private val hoerer = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            fehlerFolge = 0
            melde("bereit")
        }

        override fun onPartialResults(b: Bundle?) {
            val t = text(b) ?: return
            melde("teil", t)
        }

        override fun onResults(b: Bundle?) {
            text(b)?.let { melde("satz", it) }
            if (aktiv) hand.post { hoeren() } else ende()
        }

        override fun onError(code: Int) {
            when (code) {
                // Sprechpause oder nichts verstanden: weiterhören, solange nicht gestoppt
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (aktiv) hand.postDelayed({ hoeren() }, 150) else ende()
                SpeechRecognizer.ERROR_CLIENT -> if (!aktiv) ende()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fehler("mikrofon")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> fehler("sprachpaket")
                else -> {
                    fehlerFolge++
                    if (aktiv && fehlerFolge <= 3) hand.postDelayed({ hoeren() }, 400)
                    else fehler("code$code")
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buf: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(typ: Int, p: Bundle?) {}
    }

    private fun text(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifEmpty { null }

    private fun fehler(grund: String) {
        Protokoll.schreib(a, "Diktat: Fehler $grund")
        melde("fehler", grund = grund)
        aktiv = false
        ende(still = true)
    }

    private fun ende(still: Boolean = false) {
        notAus?.let { hand.removeCallbacks(it) }
        notAus = null
        val k = key ?: return
        try {
            sr?.destroy()
        } catch (e: Exception) {
        }
        sr = null
        if (!still) melde("ende", feld = k)
        key = null
        Protokoll.schreib(a, "Diktat beendet")
    }

    private fun melde(art: String, text: String? = null, grund: String? = null, feld: String? = key) {
        val o = JSONObject().put("key", feld ?: "").put("art", art)
        if (text != null) o.put("text", text)
        if (grund != null) o.put("grund", grund)
        a.js("window.punktNativ&&window.punktNativ.diktat&&window.punktNativ.diktat($o)")
    }
}
