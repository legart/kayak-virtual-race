package dk.kodeologi.virtualrace.audio

import java.io.BufferedInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * Plays race voice/signal WAVE audio cues and synthesized arcade countdown beeps / victory jingles.
 */
object SoundPlayer {
    var soundEnabled: Boolean = true

    enum class RaceCue(val resourcePath: String, val label: String) {
        STOP_PADDLING("/sounds/STOP_PAD.wav", "Stop paddling!"),
        START_POSITION("/sounds/START_PO.wav", "Take your start position!"),
        START_WITHIN("/sounds/START_WI.wav", "Start within 10 seconds!"),
        RACE_START("/sounds/RACE_START.wav", "GO!! (Race Start)"),
        FALSE_START("/sounds/FALSE_START.wav", "FALSE START!")
    }

    fun playCue(cue: RaceCue) {
        if (!soundEnabled) return
        thread(isDaemon = true, name = "SoundPlayer-${cue.name}") {
            try {
                val stream = SoundPlayer::class.java.getResourceAsStream(cue.resourcePath)
                if (stream != null) {
                    BufferedInputStream(stream).use { bis ->
                        val audioIn = AudioSystem.getAudioInputStream(bis)
                        val clip = AudioSystem.getClip()
                        clip.open(audioIn)
                        clip.start()
                    }
                }
            } catch (_: Throwable) {
                // Gracefully ignore if system audio device is unavailable
            }
        }
    }

    /**
     * Synthesizes a short arcade countdown beep or victory jingle.
     */
    fun playArcadeTone(freqHz: Double, durationMs: Int, volume: Double = 0.25) {
        if (!soundEnabled) return
        thread(isDaemon = true, name = "ArcadeTone") {
            try {
                val sampleRate = 22050f
                val numSamples = (durationMs * sampleRate / 1000f).toInt()
                val buf = ByteArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    val env = if (i > numSamples * 0.8) (numSamples - i).toDouble() / (numSamples * 0.2) else 1.0
                    buf[i] = (sin(angle) * 127.0 * volume * env).toInt().toByte()
                }
                val format = AudioFormat(sampleRate, 8, 1, true, false)
                val sdl = AudioSystem.getSourceDataLine(format)
                sdl.open(format)
                sdl.start()
                sdl.write(buf, 0, buf.size)
                sdl.drain()
                sdl.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun playVictoryFanfare() {
        if (!soundEnabled) return
        thread(isDaemon = true, name = "VictoryFanfare") {
            val notes = listOf(523.25 to 110, 659.25 to 110, 783.99 to 110, 1046.50 to 280)
            for ((freq, dur) in notes) {
                playArcadeTone(freq, dur, 0.22)
                Thread.sleep((dur + 20).toLong())
            }
        }
    }
}
