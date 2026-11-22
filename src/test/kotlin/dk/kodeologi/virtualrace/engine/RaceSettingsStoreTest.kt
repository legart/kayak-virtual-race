package dk.kodeologi.virtualrace.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaceSettingsStoreTest {

    @Test
    fun `FileRaceSettingsStore saves, loads, and clamps lane count`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "nested/settings.properties")
        val store = FileRaceSettingsStore(file)

        assertNull(store.loadConfiguredLanesCount())

        store.saveConfiguredLanesCount(4)
        assertEquals(4, store.loadConfiguredLanesCount())

        store.saveConfiguredLanesCount(99)
        assertEquals(8, store.loadConfiguredLanesCount())

        store.saveConfiguredLanesCount(0)
        assertEquals(1, store.loadConfiguredLanesCount())
    }

    @Test
    fun `FileRaceSettingsStore ignores invalid property values`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "settings.properties")
        file.writeText("configuredLanesCount=not_a_number\n")

        val store = FileRaceSettingsStore(file)
        assertNull(store.loadConfiguredLanesCount())
    }

    @Test
    fun `RaceEngine remembers configured lane count across instances`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "settings.properties")

        val firstRun = RaceEngine(settingsStore = FileRaceSettingsStore(file))
        assertEquals(false, firstRun.uiState.value.usingEmulator)
        assertEquals(6, firstRun.uiState.value.configuredLanesCount)
        firstRun.setConfiguredLanesCount(3)
        assertEquals(3, firstRun.uiState.value.configuredLanesCount)

        val secondRun = RaceEngine(settingsStore = FileRaceSettingsStore(file))
        assertEquals(3, secondRun.uiState.value.configuredLanesCount)
        assertEquals(
            listOf(true, true, true, false, false, false, false, false),
            secondRun.emulatedTransport.units.value.map { it.pluggedIn }
        )
    }
}
