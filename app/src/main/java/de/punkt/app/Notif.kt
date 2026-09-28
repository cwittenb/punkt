package de.punkt.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.VibratorManager

/** Mitteilungen ohne Ton. Jede Art hat ihr eigenes Vibrationsmuster. */
object Notif {
    const val ID_IMPULS = 11
    const val ID_STILL = 12
    const val ID_STAMM = 13
    const val ID_SCHWELLE = 100

    private val MUSTER = mapOf(
        "impuls" to longArrayOf(0, 60),
        "still" to longArrayOf(0, 90, 140, 90, 140, 320),
        "praxis" to longArrayOf(0, 200),
        "schwelle" to longArrayOf(0, 120, 120, 120)
    )

    fun kanaele(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        fun k(id: String, name: String, beschr: String) {
            val c = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
            c.description = beschr
            c.setSound(null, null)
            c.enableVibration(true)
            c.vibrationPattern = MUSTER[id]
            c.setShowBadge(false)
            c.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            nm.createNotificationChannel(c)
        }
        k("impuls", "Tagesimpuls", "Die Frage des Tages. Ohne Ton.")
        k("still", "Stiller Impuls", "Nur Vibration, für die Begegnung.")
        k("praxis", "Stammfenster", "Erinnerung an die Hauptpraxis.")
        k("schwelle", "Schwellen", "Zeitschwellen der Begegnung.")
    }

    fun zeigen(
        ctx: Context, kanal: String, id: Int, titel: String, text: String?,
        aktion: String, ablaufMin: Long
    ) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            summen(ctx, kanal)
            return
        }
        kanaele(ctx)
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("aktion", aktion)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, kanal)
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

    /** Nur vibrieren, etwa wenn die App gerade vorne ist. */
    fun summen(ctx: Context, kanal: String) {
        val muster = MUSTER[kanal] ?: return
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(muster, -1))
        } catch (e: Exception) {
        }
    }
}
