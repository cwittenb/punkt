package de.punkt.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Mitteilungen ohne Ton und ohne eigene Kanal-Vibration. Die Vibration kommt direkt
 * mit der Nutzung „Wecker“, damit sie auch im Lautlos-Modus spürbar ist.
 */
object Notif {
    const val ID_IMPULS = 11
    const val ID_STILL = 12
    const val ID_STAMM = 13
    const val ID_SCHWELLE = 100

    // Kanal-IDs mit Version: Kanäle lassen sich nach dem Anlegen nicht mehr ändern
    private const val V = "2"
    private val ALT = listOf("impuls", "still", "praxis", "schwelle")

    private val MUSTER = mapOf(
        "impuls" to longArrayOf(0, 220),
        "still" to longArrayOf(0, 90, 140, 90, 140, 320),
        "praxis" to longArrayOf(0, 400, 200, 400),
        "schwelle" to longArrayOf(0, 120, 120, 120, 120, 120)
    )

    @Volatile private var kanaeleAngelegt = false

    fun kanaele(ctx: Context) {
        if (kanaeleAngelegt) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        kanaeleAngelegt = true
        ALT.forEach { if (nm.getNotificationChannel(it) != null) nm.deleteNotificationChannel(it) }
        fun k(id: String, name: String, beschr: String) {
            val c = NotificationChannel(id + V, name, NotificationManager.IMPORTANCE_DEFAULT)
            c.description = beschr
            c.setSound(null, null)
            c.enableVibration(false)
            c.setShowBadge(false)
            c.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            nm.createNotificationChannel(c)
        }
        k("impuls", "Tagesimpuls", "Die Frage des Tages. Ohne Ton, eine kurze Vibration.")
        k("still", "Stiller Impuls", "Nur Vibration, für die Begegnung.")
        k("praxis", "Stammfenster", "Erinnerung an die Hauptpraxis.")
        k("schwelle", "Schwellen", "Zeitschwellen der Begegnung.")
    }

    fun zeigen(
        ctx: Context, kanal: String, id: Int, titel: String, text: String?,
        aktion: String, ablaufMin: Long, mitSummen: Boolean = true
    ) {
        if (mitSummen) summen(ctx, kanal)
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        kanaele(ctx)
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("aktion", aktion)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, kanal + V)
            .setSmallIcon(R.drawable.ic_punkt)
            .setContentTitle(titel)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(0xFFB8175A.toInt())
        if (text != null) b.setContentText(text)
        if (ablaufMin > 0) b.setTimeoutAfter(ablaufMin * 60_000L)
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
    }

    /** Vibration als Wecker: kommt auch im Lautlos-Modus durch, nicht aber bei „Nicht stören“ ohne Wecker. */
    fun summen(ctx: Context, kanal: String) {
        val muster = MUSTER[kanal] ?: return
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!v.hasVibrator()) return
            val effekt = VibrationEffect.createWaveform(muster, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effekt, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effekt, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            Protokoll.schreib(ctx, "Vibration: $kanal")
        } catch (e: Exception) {
            Protokoll.schreib(ctx, "Vibration fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }
}
