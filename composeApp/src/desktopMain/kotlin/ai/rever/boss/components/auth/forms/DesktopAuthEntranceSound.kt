package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import kotlin.math.PI
import kotlin.math.sin

private val logger = BossLogger.forComponent("AuthEntrySound")

/** Environment variable that turns the theme off. */
private const val AUTH_SOUND_KEY = "BOSS_AUTH_SOUND"

/** System property equivalent, so `-Dboss.auth.sound=false` works as well as the env var. */
private const val AUTH_SOUND_PROPERTY = "boss.auth.sound"

private const val SAMPLE_RATE = 44_100

/** Length of the one-shot. */
private const val ENTRY_SECONDS = 4.0

/**
 * Once per process.
 *
 * `AuthScaffold` frames all four auth screens, so moving from the login form to the passkey or magic-link
 * step re-composes it - without this latch the swell would fire again on every step, which is the opposite
 * of an arrival. It does mean signing out and back in within one session is silent; that is the trade.
 */
private val played =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

/**
 * Peak amplitude as a fraction of full scale.
 *
 * Low frequencies read quieter than mid ones at equal amplitude, so this sits above what the high version
 * needed - but it is still background on a screen someone may reach in an open office or while on a call.
 */
private const val PEAK = 0.17

/** Fade applied when the screen goes before the swell has finished. */
private const val FADE_MS = 450L

private val lock = Any()
private var clip: Clip? = null

internal actual fun startAuthTheme() {
    if (!soundEnabled()) return
    if (!played.compareAndSet(false, true)) return
    // Daemon, and off the composition thread: synthesis is millions of multiplications. Daemon so it can
    // never hold the app open at shutdown.
    Thread({ renderAndPlay() }, "auth-entry-sound")
        .apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
}

internal actual fun stopAuthTheme() {
    val running =
        synchronized(lock) {
            val current = clip ?: return
            clip = null
            current
        }
    Thread({ fadeOutAndClose(running) }, "auth-entry-sound-stop").apply { isDaemon = true }.start()
}

/**
 * Opt-out, matching the vocabulary of the other flags: `0`, `false`, `no` and `off` all disable it.
 *
 * Default on, since it was asked for, but a deployment that does not want its login screen making noise
 * needs one variable rather than a rebuild.
 */
private fun soundEnabled(): Boolean {
    val env = System.getenv(AUTH_SOUND_KEY)?.takeIf { it.isNotBlank() }
    return !FluckEngine.isFalsyFlag(env ?: System.getProperty(AUTH_SOUND_PROPERTY))
}

/**
 * Broad catch on purpose: a machine with no mixer, an exclusive-mode device or a sandbox without audio
 * must produce silence and nothing else. This is decoration on the sign-in screen.
 */
@Suppress("TooGenericExceptionCaught")
private fun renderAndPlay() {
    try {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val pcm = renderEntry()
        val stream = AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / 2).toLong())
        val opened = AudioSystem.getClip()
        opened.open(stream)

        val keep =
            synchronized(lock) {
                // stopAuthTheme may have run while this was rendering - a saved session can sign in before
                // four seconds are up. If so, discard rather than playing over the app that follows.
                if (clip != null) {
                    false
                } else {
                    clip = opened
                    true
                }
            }
        if (!keep) {
            opened.close()
            return
        }

        // One pass. A LineListener clears the reference when it finishes on its own, so `stop` afterwards
        // has nothing to do and the device is not held for the life of the app.
        opened.addLineListener { event ->
            if (event.type == LineEvent.Type.STOP) {
                synchronized(lock) { if (clip === opened) clip = null }
                runCatching { opened.close() }
            }
        }
        opened.start()
    } catch (e: Exception) {
        logger.debug(
            LogCategory.SYSTEM,
            "Auth entry sound unavailable",
            mapOf("reason" to (e.message ?: e::class.simpleName ?: "unknown")),
        )
    }
}

@Suppress("TooGenericExceptionCaught")
private fun fadeOutAndClose(target: Clip) {
    try {
        // MASTER_GAIN is in decibels and is not guaranteed to exist on every line, so the fade is
        // best-effort and the stop is not.
        val gain =
            runCatching { target.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl }.getOrNull()
        if (gain != null) {
            val steps = 18
            val from = gain.value
            for (step in 1..steps) {
                val level = from + (gain.minimum - from) * (step.toDouble() / steps).toFloat()
                gain.value = level.coerceIn(gain.minimum, gain.maximum)
                Thread.sleep(FADE_MS / steps)
            }
        }
        target.stop()
        target.close()
    } catch (e: Exception) {
        logger.debug(LogCategory.SYSTEM, "Auth entry sound stop failed", mapOf("reason" to (e.message ?: "unknown")))
    }
}

/** One voice of the swell: a pitch and its level in the mix. */
private data class Voice(
    val hz: Double,
    val level: Double,
)

/**
 * Renders the entry sound: a deep swell that rises, blooms and is gone in four seconds.
 *
 * **A one-shot is written differently from the loop this replaced, and it is simpler for it.** A loop had to
 * hold every frequency on a `1 / duration` grid so the waveform was exactly periodic - anything else clicked
 * at the seam every twenty seconds. Nothing here comes back round, so the pitches are free and the shape can
 * be a plain swell that begins and ends at silence.
 *
 * The envelope is `sin(pi * progress)`: zero at both ends by construction, peaking halfway, with no corner
 * anywhere. That matters more than it sounds - a linear fade in and out has a discontinuity in its slope at
 * the peak, which on a sustained low tone is audible as a small bump.
 *
 * **The pitches deliberately do not go as low as they could.** The 41Hz sub is there for headphones and real
 * speakers, but laptop speakers roll off well above it and reproduce nothing at all - so the weight the ear
 * actually hears sits at ~82Hz and ~123Hz, with a thin partial at ~247Hz for definition. Pitched purely at
 * the bottom, this would be silent on the machine most people sign in from.
 */
private fun renderEntry(): ByteArray {
    val frames = (SAMPLE_RATE * ENTRY_SECONDS).toInt()
    val mix = DoubleArray(frames)

    val voices =
        listOf(
            // Sub: felt rather than heard, and only on output that can reach it.
            Voice(hz = 41.2, level = 0.6),
            // The body of the sound: E2 with a partner half a hertz away, so the two beat slowly against
            // each other across the four seconds rather than sitting still.
            Voice(hz = 82.4, level = 0.55),
            Voice(hz = 82.9, level = 0.5),
            // Fifth above, which gives it a key rather than leaving it a rumble.
            Voice(hz = 123.5, level = 0.22),
            // Octave and a distant partial, for definition on small speakers.
            Voice(hz = 164.8, level = 0.12),
            Voice(hz = 246.9, level = 0.05),
        )

    val phases = DoubleArray(voices.size)

    for (i in 0 until frames) {
        val progress = i.toDouble() / frames
        val envelope = sin(PI * progress)

        var sample = 0.0
        for (v in voices.indices) {
            // A slight downward drift over the swell, a couple of percent: it reads as something settling
            // into place rather than a held note.
            val hz = voices[v].hz * (1.0 - 0.025 * progress)
            phases[v] += 2.0 * PI * hz / SAMPLE_RATE
            sample += sin(phases[v]) * voices[v].level
        }

        mix[i] = sample * envelope
    }

    // Normalise once, at the end: six voices beating against each other make the true maximum hard to
    // predict, and scaling each one by guesswork either clips or leaves it inaudible.
    var loudest = 0.0
    for (value in mix) {
        val magnitude = if (value < 0) -value else value
        if (magnitude > loudest) loudest = magnitude
    }
    val scale = if (loudest > 0) PEAK / loudest else 0.0

    val bytes = ByteArray(frames * 2)
    for (i in 0 until frames) {
        val pcm = ((mix[i] * scale).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        bytes[i * 2] = (pcm.toInt() and 0xFF).toByte()
        bytes[i * 2 + 1] = ((pcm.toInt() shr 8) and 0xFF).toByte()
    }
    return bytes
}
