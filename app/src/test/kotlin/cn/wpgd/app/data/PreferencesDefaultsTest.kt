package cn.wpgd.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vibration is opt-in (WI-08b); the four-colour suit choice (L9) persists like every other preference. */
class PreferencesDefaultsTest {
    @Test fun vibrationIsOffUntilChosen() {
        assertFalse(TablePreferences().vibration)
        assertFalse(PreferencesRepository(InMemoryKeyValueStore()).preferences.value.vibration)
    }

    @Test fun fourColourSuitsPersist() {
        val store = InMemoryKeyValueStore()
        assertFalse(PreferencesRepository(store).preferences.value.fourColorSuits)
        PreferencesRepository(store).update { it.copy(fourColorSuits = true, vibration = true) }
        val again = PreferencesRepository(store).preferences.value
        assertTrue(again.fourColorSuits)
        assertTrue(again.vibration)
        assertEquals("true", store.get("four_color_suits"))
    }
}
