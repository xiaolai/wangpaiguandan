package cn.wpgd.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import cn.wpgd.app.table.EffectOutput
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.HapticPattern
import cn.wpgd.app.table.SoundId
import cn.wpgd.app.table.WakeLockSink

/**
 * Sound and haptics for table cues (GDN-080), with no audio assets and no permission:
 * - Sound: short tones synthesized by the platform [ToneGenerator] on the media stream.
 * - Haptics: [View.performHapticFeedback], which needs no VIBRATE permission and follows the
 *   system's touch-feedback setting. Only produced when the player opted in (the router decides).
 *
 * This sink lives as long as the activity and serves every table, so it keeps no cue history: cue
 * ids repeat across tables. The per-table guard against a cue performed twice is the table view
 * model's [cn.wpgd.app.table.CueGuard]. Visual outputs are drawn by the screen, not here. Call
 * [release] when the screen goes away.
 */
class AndroidEffectSink(private val view: View) : EffectSink {
    private var tones: ToneGenerator? = null

    override fun perform(output: EffectOutput) {
        when (output) {
            is EffectOutput.Sound -> tone(output.sound)
            is EffectOutput.Haptic -> view.performHapticFeedback(haptic(output.pattern))
            else -> Unit
        }
    }

    private fun tone(sound: SoundId) {
        val (tone, ms) = when (sound) {
            SoundId.DEAL -> ToneGenerator.TONE_PROP_ACK to 150
            SoundId.CARD_PLAY -> ToneGenerator.TONE_PROP_BEEP to 60
            SoundId.BOMB -> ToneGenerator.TONE_CDMA_HIGH_L to 300
            SoundId.PASS -> ToneGenerator.TONE_PROP_PROMPT to 60
            SoundId.YOUR_TURN -> ToneGenerator.TONE_PROP_BEEP2 to 150
            SoundId.FINISHED -> ToneGenerator.TONE_CDMA_CONFIRM to 300
            SoundId.DEAL_END -> ToneGenerator.TONE_CDMA_CONFIRM to 400
            SoundId.MATCH_END -> ToneGenerator.TONE_CDMA_CONFIRM to 600
            SoundId.ERROR -> ToneGenerator.TONE_PROP_NACK to 200
        }
        val generator = tones ?: try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { tones = it }
        } catch (e: RuntimeException) {
            // Some devices refuse a tone generator (no audio route); the cue's text and visuals still show.
            Log.w(TAG, "tone generator unavailable: ${e.message}")
            return
        }
        generator.startTone(tone, ms)
    }

    private fun haptic(p: HapticPattern): Int = when (p) {
        HapticPattern.TICK -> HapticFeedbackConstants.CLOCK_TICK
        HapticPattern.HEAVY -> HapticFeedbackConstants.LONG_PRESS
        HapticPattern.ERROR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }

    fun release() {
        tones?.release()
        tones = null
    }

    private companion object {
        const val TAG = "WpgdEffects"
        const val VOLUME = 60
    }
}

/** Keeps the screen on through the window flag only (GDN-100): no WAKE_LOCK permission. Main thread. */
class WindowWakeLockSink(private val window: Window) : WakeLockSink {
    override fun setHeld(held: Boolean) {
        if (held) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
