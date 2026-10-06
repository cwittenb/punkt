package de.punkt.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Wecker ist fällig: alle Termine dieser Minute ausführen, dann den nächsten stellen. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val zeit = intent.getLongExtra("zeit", 0L)
        val p = Speicher.plan(ctx)
        if (p != null && zeit > 0L) {
            val tag = Instant.ofEpochMilli(zeit).atZone(ZoneId.systemDefault()).toLocalDate()
            val jetzt = Planer.termine(p, tag).filter { it.zeit == zeit }
            Protokoll.schreib(ctx, "Wecker: " + jetzt.joinToString { it.art } + (if (Laufzeit.wach) " (Übung läuft)" else ""))
            jetzt.forEach { ausfuehren(ctx, p, it) }
            Speicher.prefs(ctx).edit().putLong("erledigtBis", zeit).apply()
        }
        Planer.plane(ctx)
    }

    private fun ausfuehren(ctx: Context, p: JSONObject, t: Planer.Termin) {
        val gate = Gate.aktiv(ctx)
        when (t.art) {
            "imp" -> {
                if (Laufzeit.wach) return
                Speicher.zaehlen(ctx, "imp")
                melden(ctx, "impuls", Notif.ID_IMPULS, p.optString("frage", "Bin ich gewahr?"), p.optString("hinweis").ifEmpty { null }, "impuls", 45)
            }
            "bimp" -> {
                if (Laufzeit.wach) return
                Speicher.zaehlen(ctx, "bimp")
                melden(ctx, "still", Notif.ID_STILL, ".", null, "bimpuls", 30)
            }
            "stamm" -> {
                if (Laufzeit.wach) return
                melden(ctx, "praxis", Notif.ID_STAMM, "Stammfenster", "Hauptpraxis, jetzt.", "stamm", 90)
            }
            "schwelle" -> {
                if (Laufzeit.wach || gate) return
                val sw = p.optJSONArray("schwellen")
                var name = "Schwelle"
                if (sw != null) for (i in 0 until sw.length()) {
                    val o = sw.optJSONObject(i) ?: continue
                    if (o.optString("id") == t.arg) name = o.optString("name", name)
                }
                melden(
                    ctx, "schwelle", Notif.ID_SCHWELLE + (t.arg.hashCode() and 0xff),
                    name, "Kurz anhalten, bevor es weitergeht.", "schwelle:" + t.arg, 60
                )
            }
        }
    }

    /** Ist die App gerade vorne, geht der Impuls direkt hinein, sonst als Mitteilung. */
    private fun melden(
        ctx: Context, kanal: String, id: Int, titel: String, text: String?,
        aktion: String, ablaufMin: Long
    ) {
        val a = Laufzeit.vorne()
        if (a != null) {
            // Nimmt die Web-App den Impuls nicht an (Übung läuft), bleibt er als Mitteilung liegen
            a.aktion(aktion) { Notif.zeigen(ctx, kanal, id, titel, text, aktion, ablaufMin, mitSummen = false) }
            Notif.summen(ctx, kanal)
        } else {
            Notif.zeigen(ctx, kanal, id, titel, text, aktion, ablaufMin)
        }
    }
}

/** Nach Neustart, Update oder Zeitumstellung den Wecker neu stellen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notif.kanaele(ctx)
        Planer.plane(ctx)
    }
}
