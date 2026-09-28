package de.punkt.app

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/** Sprachaufnahmen fürs Journal. Dateien bleiben im privaten App-Ordner (aufnahmen/). */
class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    private var start = 0L

    @Synchronized
    fun start(name: String): Boolean {
        stopp()
        val dir = File(ctx.filesDir, "aufnahmen")
        dir.mkdirs()
        val sicher = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val f = File(dir, "$sicher.m4a")
        val m = MediaRecorder(ctx)
        return try {
            m.setAudioSource(MediaRecorder.AudioSource.MIC)
            m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            m.setAudioChannels(1)
            m.setAudioSamplingRate(44100)
            m.setAudioEncodingBitRate(64000)
            m.setOutputFile(f)
            m.prepare()
            m.start()
            mr = m
            start = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            m.release()
            f.delete()
            false
        }
    }

    /** Beendet die Aufnahme und gibt die Dauer in Sekunden zurück. */
    @Synchronized
    fun stopp(): Int {
        val m = mr ?: return 0
        mr = null
        val sek = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
        try {
            m.stop()
        } catch (e: Exception) {
        }
        m.release()
        return sek
    }
}
