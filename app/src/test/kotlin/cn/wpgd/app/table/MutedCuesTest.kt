package cn.wpgd.app.table

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.TablePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-080: muted play stays understandable; muting removes only sound and vibration. */
class MutedCuesTest {
    private fun cue(kind: CueKind) = TableCue("e1:r5:${kind.name}:EAST", kind, Seat.EAST, 5)

    @Test fun mutedCuesKeepTheirAnnouncementAndVisuals() {
        val muted = TablePreferences(sound = false, vibration = false)
        for (kind in CueKind.entries) {
            val out = EffectRouter.route(cue(kind), muted)
            assertTrue(kind.name, out.none { it is EffectOutput.Sound || it is EffectOutput.Haptic })
            val announce = out.filterIsInstance<EffectOutput.Announce>().single()
            assertEquals(kind.messageKey, announce.messageKey)
            assertEquals(Seat.EAST, announce.seat)
            assertTrue(out.all { it.cueId == cue(kind).id })
        }
        assertTrue(EffectRouter.route(cue(CueKind.PLAY), muted).any { it is EffectOutput.Motion })
        assertTrue(EffectRouter.route(cue(CueKind.BOMB), muted).any { it is EffectOutput.Flash })
    }

    @Test fun soundAndVibrationAreSeparateSwitches() {
        val soundOnly = EffectRouter.route(cue(CueKind.BOMB), TablePreferences(sound = true, vibration = false))
        assertTrue(soundOnly.any { it is EffectOutput.Sound } && soundOnly.none { it is EffectOutput.Haptic })
        val hapticOnly = EffectRouter.route(cue(CueKind.BOMB), TablePreferences(sound = false, vibration = true))
        assertTrue(hapticOnly.none { it is EffectOutput.Sound } && hapticOnly.any { it is EffectOutput.Haptic })
        val all = EffectRouter.route(cue(CueKind.YOUR_TURN), TablePreferences(sound = true, vibration = true))
        assertEquals(setOf(EffectOutput.Announce::class, EffectOutput.Sound::class, EffectOutput.Haptic::class), all.map { it::class }.toSet())
        // Vibration is opt-in: the defaults give sound and text, no haptics.
        val defaults = EffectRouter.route(cue(CueKind.YOUR_TURN), TablePreferences())
        assertEquals(setOf(EffectOutput.Announce::class, EffectOutput.Sound::class), defaults.map { it::class }.toSet())
    }
}
