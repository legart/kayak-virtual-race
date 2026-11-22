package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicLaneVisibilityTest {

    @Test
    fun `visibleLanes only includes connected lanes in pre-race lobby`() {
        val lanes = List(DansprintProtocol.MAX_LANES) { idx ->
            LaneTelemetryState(
                laneIndex = idx,
                connected = (idx == 1 || idx == 4),
                boxStatus = if (idx == 1 || idx == 4) BoxStatusColor.GREEN_CONNECTED else BoxStatusColor.RED_DISCONNECTED
            )
        }
        val state = RaceUiState(
            raceState = RaceState.IDLE_PRE_RACE,
            configuredLanesCount = 6,
            lanes = lanes
        )

        assertEquals(listOf(1, 4), state.visibleLanes().map { it.laneIndex })
    }

    @Test
    fun `visibleLanes keeps hardware alerts and in-race participants visible`() {
        val lanes = List(DansprintProtocol.MAX_LANES) { idx ->
            when (idx) {
                0 -> LaneTelemetryState(laneIndex = 0, connected = true, active = true, boxStatus = BoxStatusColor.GREEN_CONNECTED)
                1 -> LaneTelemetryState(laneIndex = 1, connected = false, active = true, boxStatus = BoxStatusColor.YELLOW_HANDLE_CLOSED)
                2 -> LaneTelemetryState(laneIndex = 2, connected = false, active = false, boxStatus = BoxStatusColor.BLUE_DUPLICATE_LANE)
                3 -> LaneTelemetryState(laneIndex = 3, connected = false, active = false, boxStatus = BoxStatusColor.RED_DISCONNECTED)
                else -> LaneTelemetryState(laneIndex = idx, connected = false, active = false, boxStatus = BoxStatusColor.RED_DISCONNECTED)
            }
        }

        val lobbyState = RaceUiState(
            raceState = RaceState.IDLE_PRE_RACE,
            configuredLanesCount = 6,
            lanes = lanes
        )
        // In lobby: connected (0) and duplicate-lane alert (2) are visible; disconnected (1, 3) are hidden
        assertEquals(listOf(0, 2), lobbyState.visibleLanes().map { it.laneIndex })

        val racingState = lobbyState.copy(raceState = RaceState.RACING)
        // During a race: active participant (1) stays visible even if cable blips; idle disconnected (3) remains hidden
        assertEquals(listOf(0, 1, 2), racingState.visibleLanes().map { it.laneIndex })
    }

    @Test
    fun `RaceEngine dynamically hides and shows lanes on ergometer unplug and replug`() {
        val engine = RaceEngine(defaultUseEmulator = true)
        engine.start()
        try {
            // Wait for initial discovery & mapping of all 6 emulator lanes
            val mapDeadline = System.currentTimeMillis() + 3_000L
            while (
                (!engine.uiState.value.isMapped || engine.uiState.value.visibleLanes().size != 6) &&
                System.currentTimeMillis() < mapDeadline
            ) {
                Thread.sleep(40)
            }
            assertEquals(listOf(0, 1, 2, 3, 4, 5), engine.uiState.value.visibleLanes().map { it.laneIndex })

            // Unplug ergometer on Lane 3 (index 2) -> lane should dynamically hide
            engine.emulatedTransport.setUnitPluggedIn(2, false)
            val hideDeadline = System.currentTimeMillis() + 2_000L
            while (
                engine.uiState.value.visibleLanes().any { it.laneIndex == 2 } &&
                System.currentTimeMillis() < hideDeadline
            ) {
                Thread.sleep(40)
            }
            assertEquals(listOf(0, 1, 3, 4, 5), engine.uiState.value.visibleLanes().map { it.laneIndex })
            assertFalse(engine.uiState.value.lanes[2].connected)
            assertFalse(engine.uiState.value.lanes[2].active)

            // Re-plug ergometer on Lane 3 (index 2) -> lane should dynamically reappear
            engine.emulatedTransport.setUnitPluggedIn(2, true)
            val showDeadline = System.currentTimeMillis() + 2_000L
            while (
                engine.uiState.value.visibleLanes().none { it.laneIndex == 2 } &&
                System.currentTimeMillis() < showDeadline
            ) {
                Thread.sleep(40)
            }
            assertEquals(listOf(0, 1, 2, 3, 4, 5), engine.uiState.value.visibleLanes().map { it.laneIndex })
            assertTrue(engine.uiState.value.lanes[2].connected)
        } finally {
            engine.stop()
        }
    }
}
