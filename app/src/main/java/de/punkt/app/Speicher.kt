package de.punkt.app

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Kleiner Speicher für Plan, Zähler und Zustand. Alles liegt im privaten App-Ordner. */
object Speicher {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("punkt", Context.MODE_PRIVATE)

    /** Datum im Format der Web-App (JJJJ-MM-TT). */
    fun heute(): String = LocalDate.now().toString()

    // Der Gate-Dienst fragt den Plan bei jedem Fensterwechsel ab: einmal parsen, nicht jedes Mal.
    @Volatile private var planCache: Pair<String, JSONObject?>? = null

    fun plan(ctx: Context): JSONObject? {
        val t = prefs(ctx).getString("plan", null) ?: return null
        planCache?.let { if (it.first == t) return it.second }
        val o = try {
            JSONObject(t)
        } catch (e: Exception) {
            null
        }
        planCache = t to o
        return o
    }

    fun planSetzen(ctx: Context, json: String) {
        prefs(ctx).edit().putString("plan", json).apply()
        planCache = null
    }

    fun zaehlen(ctx: Context, art: String) {
        val p = prefs(ctx)
        val k = "z_${art}_${heute()}"
        p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
        aufraeumen(p)
    }

    fun zaehler(ctx: Context, tag: String): String {
        val p = prefs(ctx)
        val o = JSONObject()
        o.put("imp", p.getInt("z_imp_$tag", 0))
        o.put("bimp", p.getInt("z_bimp_$tag", 0))
        return o.toString()
    }

    private fun aufraeumen(p: SharedPreferences) {
        val grenze = LocalDate.now().minusDays(21).toString()
        val weg = p.all.keys.filter { k ->
            k.startsWith("z_") && k.substringAfterLast('_') < grenze
        }
        if (weg.isNotEmpty()) {
            val e = p.edit()
            weg.forEach { e.remove(it) }
            e.apply()
        }
    }

    // Zustand der Web-App als Datei, zusätzlich zum localStorage der WebView.
    private fun zustandDatei(ctx: Context) = File(ctx.filesDir, "zustand.json")

    fun zustandLaden(ctx: Context): String? {
        val f = zustandDatei(ctx)
        return if (f.exists()) f.readText() else null
    }

    fun zustandSichern(ctx: Context, json: String) {
        val f = zustandDatei(ctx)
        val tmp = File(ctx.filesDir, "zustand.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            f.writeText(json)
            tmp.delete()
        }
    }
}

object Gate {
    /** Gate greift ab der Morgenstunde, bis Anker, Journal und Skala erledigt sind oder der Tag frei ist. */
    fun aktiv(ctx: Context): Boolean {
        val p = Speicher.plan(ctx) ?: return false
        if (!p.optBoolean("angelegt")) return false
        if (LocalTime.now().hour < p.optInt("morgen", 5)) return false
        return p.optString("fertig") != Speicher.heute()
    }

    /** Apps, die der Nutzer im Gate erlaubt hat. */
    fun erlaubt(ctx: Context): Set<String> {
        val a = Speicher.plan(ctx)?.optJSONArray("erlaubt") ?: return emptySet()
        return (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }.toSet()
    }

    fun dienstAn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(ctx, GateService::class.java)
        val lang = cn.flattenToString()
        val kurz = cn.flattenToShortString()
        return s.split(':').any { it.equals(lang, true) || it.equals(kurz, true) }
    }

    /** true, wenn das Gate vorher an war und jetzt aus ist. Merkt sich den neuen Stand. */
    fun ausGemeldet(ctx: Context): Boolean {
        val an = dienstAn(ctx)
        val p = Speicher.prefs(ctx)
        val war = p.getBoolean("gateWarAn", false)
        p.edit().putBoolean("gateWarAn", an).apply()
        return war && !an && Speicher.plan(ctx)?.optBoolean("angelegt") == true
    }
}

/** Laufzeitzustand, nur solange der Prozess lebt. */
object Laufzeit {
    @Volatile
    var wach = false

    @Volatile
    var aktivitaet: java.lang.ref.WeakReference<MainActivity>? = null

    fun vorne(): MainActivity? = aktivitaet?.get()?.takeIf { it.istVorne }
}
