package de.punkt.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Brücke zur Web-App (window.PunktNative). Die Methoden laufen auf dem Brücken-Thread,
 * alles an der Oberfläche geht über runOnUiThread.
 */
class Bruecke(private val a: MainActivity) {

    @JavascriptInterface
    fun zustandLaden(): String? = try {
        Speicher.zustandLaden(a)
    } catch (e: Exception) {
        null
    }

    @JavascriptInterface
    fun zustandSichern(json: String): Boolean = try {
        Speicher.zustandSichern(a, json)
        true
    } catch (e: Exception) {
        false
    }

    @JavascriptInterface
    fun plan(json: String): Boolean {
        val alt = Speicher.plan(a)?.optString("fertig") ?: ""
        Speicher.planSetzen(a, json)
        val neu = try { JSONObject(json).optString("fertig") } catch (e: Exception) { "" }
        if (neu != alt) Protokoll.schreib(a, if (neu == Speicher.heute()) "Gate für heute frei" else "Gate-Stand: " + (neu.ifEmpty { "offen" }))
        Planer.plane(a)
        return true
    }

    @JavascriptInterface
    fun zaehler(tag: String): String = Speicher.zaehler(a, tag)

    @JavascriptInterface
    fun gateAusGemeldet(): Boolean = Gate.ausGemeldet(a)

    @JavascriptInterface
    fun gateStatus(): String = JSONObject()
        .put("an", Gate.dienstAn(a))
        .put("aktiv", Gate.aktiv(a))
        .put("frei", Speicher.plan(a)?.optString("fertig") == Speicher.heute())
        .toString()

    @JavascriptInterface
    fun gateEinstellungen(): Boolean {
        a.runOnUiThread {
            try {
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
            }
        }
        return true
    }

    @JavascriptInterface
    fun appInfo(): Boolean {
        a.runOnUiThread {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", a.packageName, null))
                )
            } catch (e: Exception) {
            }
        }
        return true
    }

    @JavascriptInterface
    fun wach(an: Boolean): Boolean {
        a.wach(an)
        return true
    }

    @JavascriptInterface
    fun aufnahme(name: String): Boolean {
        if (!a.mikrofonFrei()) return false
        return a.recorder.start(name)
    }

    @JavascriptInterface
    fun aufnahmeStopp(): Int = a.recorder.stopp()

    /** Datei nach Downloads/Punkt schreiben. */
    @JavascriptInterface
    fun speichere(name: String, daten: String): Boolean = try {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/markdown")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Punkt")
        }
        val cr = a.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) false else {
            cr.openOutputStream(uri)?.use { it.write(daten.toByteArray(Charsets.UTF_8)) }
            true
        }
    } catch (e: Exception) {
        false
    }

    @JavascriptInterface
    fun kopiere(text: String): Boolean {
        a.runOnUiThread {
            a.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Punkt", text))
        }
        return true
    }

    @JavascriptInterface
    fun telefon(): Boolean {
        a.runOnUiThread {
            try {
                a.startActivity(Intent(Intent.ACTION_DIAL))
            } catch (e: Exception) {
            }
        }
        return true
    }

    @JavascriptInterface
    fun sprich(wort: String): Boolean = a.sprich(wort)

    @JavascriptInterface
    fun sprichStopp(): Boolean {
        a.sprichStopp()
        return true
    }

    /** CSS-Farbe wie "rgb(30, 30, 30)" für die Systemleisten. */
    @JavascriptInterface
    fun leiste(css: String): Boolean {
        val m = Regex("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").find(css) ?: return false
        val (r, g, b) = m.destructured.toList().map { it.toInt() }
        val hell = 0.299 * r + 0.587 * g + 0.114 * b > 150
        a.runOnUiThread { a.leiste(Color.rgb(r, g, b), hell) }
        return true
    }

    @JavascriptInterface
    fun dunkel(): Boolean = a.dunkel()

    /** Installierte Apps mit Startsymbol, für die Auswahl „Im Gate erlaubt“. */
    @JavascriptInterface
    fun apps(): String {
        val pm = a.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val arr = org.json.JSONArray()
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != a.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .forEach { arr.put(JSONObject().put("p", it.first).put("n", it.second)) }
        return arr.toString()
    }

    /** Kurz vibrieren, wenn der Gong im Hintergrund nicht zu hören ist. */
    @JavascriptInterface
    fun summen(): Boolean {
        Notif.summen(a, "praxis")
        return true
    }

    @JavascriptInterface
    fun musikWaehlen(): Boolean {
        a.runOnUiThread { a.musikWaehlen() }
        return true
    }

    @JavascriptInterface
    fun musikStart(vol: Double): Boolean {
        a.runOnUiThread { a.musik.start(vol.toFloat()) }
        return true
    }

    @JavascriptInterface
    fun musikStopp(fadeMs: Double): Boolean {
        a.runOnUiThread { a.musik.stopp(fadeMs.toLong()) }
        return true
    }

    @JavascriptInterface
    fun log(text: String): Boolean {
        Protokoll.schreib(a, "Web: " + text.take(300))
        return true
    }

    @JavascriptInterface
    fun protokoll(): String = Protokoll.lesen(a)
}
