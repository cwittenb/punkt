package de.punkt.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/**
 * Berechnet die Termine eines Tages aus dem Plan der Web-App und stellt
 * immer genau einen Wecker auf den nächsten Termin.
 */
object Planer {
    data class Termin(val zeit: Long, val art: String, val arg: String)

    // Tagesimpulse verteilen sich über dieses Fenster (Minuten ab Mitternacht).
    private const val IMPULS_ENDE = 21 * 60

    fun termine(p: JSONObject, tag: LocalDate): List<Termin> {
        val zone = ZoneId.systemDefault()
        fun um(min: Int): Long =
            tag.atTime(min / 60, min % 60).atZone(zone).toInstant().toEpochMilli()

        val out = ArrayList<Termin>()

        // Stammfenster: Wochentage 0 = Montag … 6 = Sonntag, wie in der Web-App
        val st = p.optJSONObject("stamm")
        if (st != null && st.optBoolean("aktiv")) {
            val wt = tag.dayOfWeek.value - 1
            val tage = st.optJSONArray("tage")
            var drin = false
            if (tage != null) for (i in 0 until tage.length()) if (tage.optInt(i, -1) == wt) drin = true
            val m = minuten(st.optString("zeit"))
            if (drin && m != null) out.add(Termin(um(m), "stamm", ""))
        }

        // Schwellen mit Uhrzeit, täglich
        val sw = p.optJSONArray("schwellen")
        if (sw != null) for (i in 0 until sw.length()) {
            val o = sw.optJSONObject(i) ?: continue
            val m = minuten(o.optString("zeit")) ?: continue
            out.add(Termin(um(m), "schwelle", o.optString("id")))
        }

        // Tagesimpulse: ab drei Stunden nach der Morgenstunde, frühestens 8 Uhr, bis 21 Uhr
        val nImp = p.optInt("impulse", 0)
        val impVon = maxOf(8, p.optInt("morgen", 5) + 3) * 60
        verteilt(tag, nImp, impVon, IMPULS_ENDE, 11).forEach { out.add(Termin(um(it), "imp", "")) }

        // Stille Impulse der Begegnung
        val b = p.optJSONObject("bimp")
        if (b != null) {
            verteilt(tag, b.optInt("n", 0), b.optInt("von", 8) * 60, b.optInt("bis", 17) * 60, 23)
                .forEach { out.add(Termin(um(it), "bimp", "")) }
        }
        return out.sortedBy { it.zeit }
    }

    /** Geschichtete Zufallszeiten, für denselben Tag immer gleich. */
    private fun verteilt(tag: LocalDate, n: Int, von: Int, bis: Int, salz: Int): List<Int> {
        if (n <= 0 || bis <= von) return emptyList()
        val r = Random(tag.toEpochDay() * 7919L + salz)
        val slot = (bis - von).toDouble() / n
        return (0 until n).map { i -> (von + slot * i + slot * (0.1 + 0.8 * r.nextDouble())).toInt() }
    }

    private fun minuten(hm: String?): Int? {
        if (hm.isNullOrBlank()) return null
        val t = hm.split(':')
        if (t.size < 2) return null
        val h = t[0].toIntOrNull() ?: return null
        val m = t[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun weckerIntent(ctx: Context, t: Termin?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        if (t != null) i.putExtra("zeit", t.zeit)
        return PendingIntent.getBroadcast(
            ctx, 7, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // So weit voraus wird gesucht: ein Stammfenster nur montags darf übers Wochenende nicht verloren gehen
    private const val HORIZONT_TAGE = 8L

    /** Stellt den Wecker auf den nächsten offenen Termin. Synchronisiert: Brücke und Empfänger rufen gleichzeitig. */
    @Synchronized
    fun plane(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = Speicher.plan(ctx)
        val jetzt = System.currentTimeMillis()
        val erledigt = Speicher.prefs(ctx).getLong("erledigtBis", 0L)
        val naechster = if (p == null || !p.optBoolean("angelegt")) null else {
            val h = LocalDate.now()
            (0 until HORIZONT_TAGE).asSequence()
                .flatMap { termine(p, h.plusDays(it)).asSequence() }
                .filter { it.zeit > erledigt && it.zeit > jetzt - 5 * 60_000L }
                .minByOrNull { it.zeit }
        }
        if (naechster == null) {
            am.cancel(weckerIntent(ctx, null))
            return
        }
        val wann = maxOf(naechster.zeit, jetzt + 1000)
        val pi = weckerIntent(ctx, naechster)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wann, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wann, pi)
        }
    }
}
