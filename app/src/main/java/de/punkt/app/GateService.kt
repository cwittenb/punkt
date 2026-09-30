package de.punkt.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Morgen-Gate: Solange Anker, Journal und Skala offen sind, holt der Dienst die App
 * nach vorne, sobald eine andere App geöffnet wird. Er sieht nur, welche App vorne ist,
 * keine Inhalte. Telefon, Uhr, Kontakte, Notruf und Einstellungen bleiben frei.
 */
class GateService : AccessibilityService() {
    private var zuletzt = 0L
    private val merk = HashMap<String, Boolean>()
    private var heim: String? = null
    private var heimZeit = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Speicher.prefs(this).edit().putBoolean("gateWarAn", true).apply()
        Protokoll.schreib(this, "Gate-Dienst verbunden")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Gate.aktiv(this) || !sperren(pkg)) return
        val t = SystemClock.elapsedRealtime()
        if (t - zuletzt < 1200) return
        zuletzt = t
        Protokoll.schreib(this, "Gate holt die App zurück (vor: $pkg)")
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("aktion", "gate")
        try {
            startActivity(i)
        } catch (ex: Exception) {
        }
    }

    private fun sperren(pkg: String): Boolean {
        if (pkg in FREI || pkg in Gate.erlaubt(this)) return false
        if (pkg == heimAktuell()) return false
        if (pkg == tastatur()) return false
        return merk.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }
    }

    /** Der Startbildschirm kann wechseln; höchstens einmal pro Minute neu nachsehen. */
    private fun heimAktuell(): String? {
        val t = SystemClock.elapsedRealtime()
        if (heim == null || t - heimZeit > 60_000) {
            heim = heimPaket()
            heimZeit = t
        }
        return heim
    }

    private fun heimPaket(): String? = try {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    private fun tastatur(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')

    override fun onInterrupt() {}

    companion object {
        val FREI = setOf(
            // Einstellungen: bleibt frei, das Abschalten wird aber erkannt
            "com.android.settings",
            // Telefon, Anrufe, Kontakte
            "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.incallui", "com.android.incallui", "com.android.phone",
            "com.android.server.telecom", "com.samsung.android.app.contacts",
            "com.google.android.contacts", "com.android.contacts",
            // Uhr und Wecker
            "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock",
            // Notruf und Sicherheit
            "com.samsung.android.emergency", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.samsung.android.app.safetyassurance",
            // System
            "com.android.systemui", "android", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            // Smart Home: Licht und Wecker am Morgen
            "com.tuya.smartlife"
        )
    }
}
