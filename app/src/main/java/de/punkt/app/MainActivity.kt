package de.punkt.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/** Hülle um die Web-App. Die Oberfläche liegt komplett in assets/web/index.html. */
class MainActivity : Activity() {
    lateinit var web: WebView
        private set
    var istVorne = false
        private set

    private var geladen = false
    private var konfig: Configuration? = null
    private var offeneAktion: String? = null
    private var dateiRueckruf: ValueCallback<Array<Uri>>? = null
    val recorder by lazy { Recorder(this) }
    val musik by lazy { Musik(this) }
    val diktat by lazy { Diktat(this) }

    fun musikWaehlen() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_MUSIK)
        } catch (e: Exception) {
        }
    }

    // Sprachbegleitung
    private var tts: TextToSpeech? = null
    private var ttsBereit = false

    // Lage des Handys während der Praxis
    private var sensoren: SensorManager? = null
    private var lage: Boolean? = null
    private val lageHoerer = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val z = e.values[2]
            val alt = lage
            val unten = if (alt == true) z < -5f else z < -7.5f
            if (unten != alt) {
                lage = unten
                helligkeit(unten)
                js("window.punktNativ&&window.punktNativ.lage($unten)")
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Protokoll.absturzFangen(this)
        Protokoll.schreib(this, "App startet" + if (savedInstanceState != null) " (neu aufgebaut)" else "")
        konfig = Configuration(resources.configuration)
        Laufzeit.aktivitaet = WeakReference(this)
        Notif.kanaele(this)

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bruecke(this), "PunktNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                geladen = true
                offeneAktion?.let {
                    offeneAktion = null
                    aktion(it)
                }
            }

            // Stirbt die Web-Oberfläche, die Seite neu aufbauen statt die App abstürzen zu lassen
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Protokoll.schreib(this@MainActivity, "Web-Oberfläche beendet (" + (if (detail.didCrash()) "Absturz" else "vom System") + "), baue neu auf")
                geladen = false
                offeneAktion?.let { Speicher.prefs(this@MainActivity).edit().putString("offeneAktion", it).apply() }
                recreate()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "file") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Fehler der Web-Oberfläche ins Protokoll, damit sie sich am Handy finden lassen
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    Protokoll.schreib(this@MainActivity, "JS-Fehler: " + m.message().take(200) + " (Zeile " + m.lineNumber() + ")")
                }
                return false
            }

            override fun onShowFileChooser(
                view: WebView, rueckruf: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                dateiRueckruf?.onReceiveValue(null)
                dateiRueckruf = rueckruf
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), REQ_DATEI)
                    true
                } catch (e: Exception) {
                    dateiRueckruf = null
                    false
                }
            }
        }

        diktat.verfuegbar
        if (savedInstanceState == null) verarbeite(intent)
        else Speicher.prefs(this).getString("offeneAktion", null)?.let {
            Speicher.prefs(this).edit().remove("offeneAktion").apply()
            offeneAktion = it
        }
        web.loadUrl("file:///android_asset/web/index.html")
        mitteilungenErlauben()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        verarbeite(intent)
    }

    /** Aktion aus Mitteilung, Gate oder NFC-Aufkleber an die Web-App geben. */
    private fun verarbeite(i: Intent?) {
        if (i == null) return
        val d = i.data
        Protokoll.schreib(this, "Aufruf: " + (i.getStringExtra("aktion") ?: d?.toString() ?: i.action ?: "–"))
        val a = when {
            d != null && d.scheme == "punkt" -> if (d.host == "anker") "nfc" else d.host
            else -> i.getStringExtra("aktion")
        } ?: return
        i.removeExtra("aktion")
        i.data = null
        if (geladen) aktion(a) else offeneAktion = a
    }

    /** Aktion an die Web-App geben. Nimmt sie sie nicht an (z. B. mitten in einer Übung), läuft `sonst`. */
    fun aktion(a: String, sonst: (() -> Unit)? = null) {
        runOnUiThread {
            if (!geladen) {
                sonst?.invoke()
                return@runOnUiThread
            }
            web.evaluateJavascript("window.punktNativ&&window.punktNativ.aktion(" + JSONObject.quote(a) + ")===true?'1':'0'") { r ->
                if (r != "\"1\"") sonst?.invoke()
            }
        }
    }

    fun js(code: String) {
        runOnUiThread {
            if (geladen) web.evaluateJavascript(code, null)
        }
    }

    override fun onResume() {
        super.onResume()
        Protokoll.schreib(this, "vorne")
        istVorne = true
        Laufzeit.aktivitaet = WeakReference(this)
        js("window.punktNativ&&window.punktNativ.resume()")
    }

    override fun onPause() {
        istVorne = false
        Protokoll.schreib(this, "im Hintergrund")
        js("window.punktNativ&&window.punktNativ.hinten&&window.punktNativ.hinten()")
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
    }

    // Im Hintergrund schweigt die Begleitung; die Web-Oberfläche meldet sich als verborgen
    override fun onStop() {
        sprichStopp()
        // Im Hintergrund darf und soll nicht mitgehört werden
        if (diktat.laeuft()) diktat.stoppJetzt()
        web.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        geladen = false
        if (Laufzeit.aktivitaet?.get() === this) Laufzeit.aktivitaet = null
        Protokoll.schreib(this, "App beendet" + (if (isFinishing) " (geschlossen)" else "") + (if (isChangingConfigurations) " (Konfiguration)" else ""))
        wach(false)
        recorder.stopp()
        diktat.stoppJetzt()
        musik.stopp(0)
        tts?.shutdown()
        tts = null
        // Die Web-Oberfläche wirklich beenden: Der Prozess lebt wegen des Gate-Dienstes weiter,
        // sonst liefen Takt und Begleitung ohne Bildschirm weiter.
        try {
            web.removeJavascriptInterface("PunktNative")
            web.stopLoading()
            web.loadUrl("about:blank")
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    @Deprecated("Zurück-Taste an die Web-App geben")
    override fun onBackPressed() {
        if (!geladen) {
            moveTaskToBack(true)
            return
        }
        web.evaluateJavascript("(window.punktNativ&&window.punktNativ.zurueck())?'1':'0'") { r ->
            if (r != "\"1\"") moveTaskToBack(true)
        }
    }

    override fun onConfigurationChanged(neu: Configuration) {
        super.onConfigurationChanged(neu)
        Protokoll.schreib(this, "Konfiguration geändert: 0x" + Integer.toHexString(konfig?.diff(neu) ?: 0))
        konfig = Configuration(neu)
        // Dunkles Systemthema wechselt: Web-App neu zeichnen lassen
        js("typeof render==='function'&&render()")
    }

    @Deprecated("Dateiauswahl für den Import")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DATEI) {
            dateiRueckruf?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            dateiRueckruf = null
        }
        if (requestCode == REQ_MUSIK && resultCode == RESULT_OK && data?.data != null) {
            val name = musik.uebernehmen(data.data!!)
            js("window.punktNativ&&window.punktNativ.musik(" + JSONObject.quote(name ?: "") + ")")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIKRO) {
            val frei = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Protokoll.schreib(this, if (frei) "Mikrofon freigegeben" else "Mikrofon abgelehnt")
            js("window.punktNativ&&window.punktNativ.mikro&&window.punktNativ.mikro($frei)")
        }
    }

    private fun mitteilungenErlauben() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = Speicher.prefs(this)
        if (p.getBoolean("mitteilungGefragt", false)) return
        p.edit().putBoolean("mitteilungGefragt", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_MITTEILUNG)
    }

    fun mikrofonFrei(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIKRO) }
        return false
    }

    /** Während einer Übung: Display anlassen, Lage messen, Sprache vorbereiten. */
    fun wach(an: Boolean) {
        if (an != Laufzeit.wach) Protokoll.schreib(this, if (an) "Übung: Display bleibt an" else "Übung vorbei")
        Laufzeit.wach = an
        runOnUiThread {
            if (an) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                lage = null
                val sm = sensoren ?: getSystemService(SensorManager::class.java)
                sensoren = sm
                sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(lageHoerer, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                ttsVorbereiten()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                sensoren?.unregisterListener(lageHoerer)
                lage = null
                helligkeit(false)
            }
        }
    }

    /** Liegt das Display unten, wird es fast dunkel. */
    private fun helligkeit(dunkel: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (dunkel) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // Ansagen, die vor der Bereitschaft der Sprachausgabe kamen; sonst ginge die erste verloren
    private val ttsOffen = ArrayList<String>()

    private fun ttsVorbereiten() {
        if (tts != null) return
        tts = TextToSpeech(this) { st ->
            runOnUiThread {
                val t = tts ?: return@runOnUiThread
                if (st == TextToSpeech.SUCCESS) {
                    t.language = Locale.GERMANY
                    t.setSpeechRate(0.8f)
                    ttsBereit = true
                    ttsOffen.forEach { t.speak(it, TextToSpeech.QUEUE_ADD, null, "punkt") }
                } else {
                    Protokoll.schreib(this, "Sprachausgabe nicht verfügbar")
                }
                ttsOffen.clear()
            }
        }
    }

    /** Reiht die Ansage ein. Die Web-Engine spricht nacheinander, deshalb QUEUE_ADD. */
    fun sprich(w: String): Boolean {
        runOnUiThread {
            val t = tts
            if (t != null && ttsBereit) t.speak(w, TextToSpeech.QUEUE_ADD, null, "punkt")
            else {
                if (ttsOffen.size < 3) ttsOffen.add(w)
                ttsVorbereiten()
            }
        }
        return true
    }

    fun sprichStopp() {
        runOnUiThread {
            ttsOffen.clear()
            tts?.stop()
        }
    }

    /** Statusleiste und Navigationsleiste in der Farbe des Bildschirms. */
    fun leiste(farbe: Int, hell: Boolean) {
        web.setBackgroundColor(farbe)
        window.statusBarColor = farbe
        window.navigationBarColor = farbe
        val maske = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (hell) maske else 0, maske)
    }

    fun dunkel(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) Protokoll.schreib(this, "Speicher knapp: Stufe $level")
    }

    companion object {
        /** Intent, der die App mit einer Aktion für die Web-App nach vorne holt (Mitteilung, Gate). */
        fun aktionIntent(ctx: android.content.Context, aktion: String): Intent =
            Intent(ctx, MainActivity::class.java)
                .putExtra("aktion", aktion)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        const val REQ_DATEI = 41
        const val REQ_MITTEILUNG = 42
        const val REQ_MIKRO = 43
        const val REQ_MUSIK = 44
    }
}
