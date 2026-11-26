package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.audio.SoundPlayer
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.ErgoStreamParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaceFinishProtocolTest {

    @BeforeEach
    fun silenceAudio() {
        SoundPlayer.soundEnabled = false
    }

    @Test
    fun `physical ergometer 0x08 finish packets advance all lanes from 98m to 100m and preserve finish order`() {
        val engine = RaceEngine(defaultUseEmulator = true)
        engine.setStateForTest { state ->
            state.copy(
                raceState = RaceState.RACING,
                raceDistanceMeters = 100,
                configuredLanesCount = 3,
                isMapped = true,
                lanes = state.lanes.mapIndexed { idx, lane ->
                    if (idx < 3) {
                        lane.copy(connected = true, active = true)
                    } else {
                        lane
                    }
                }
            )
        }
        for (i in 0 until 3) {
            engine.laneRaceStartOffsetMs[i] = 2_500L
        }

        val parsers = Array(3) { ErgoStreamParser() }
        fun feedRawReport(laneIdx: Int, packet: ByteArray, nowMs: Long) {
            val frames = parsers[laneIdx].feedBytes(DansprintProtocol.padToHidInputReport(packet))
            assertEquals(1, frames.size)
            engine.handleIncomingPacket(laneIdx, laneIdx, frames.first(), nowMs)
        }

        val speedMPerTick = DansprintProtocol.speedKmhToMetersPerTick(16.0f)

        // All 3 lanes send their last pre-finish Type 2 telemetry around 98m:
        // Lane 0 leads at 98.2m, Lane 1 is at 96.5m, Lane 2 is at 95.0m
        feedRawReport(
            0,
            DansprintProtocol.buildType2TelemetryPacket((22_000L + 2_500L) * 500L, speedMPerTick, 98.2f),
            22_000L
        )
        feedRawReport(
            1,
            DansprintProtocol.buildType2TelemetryPacket((22_000L + 2_500L) * 500L, speedMPerTick, 96.5f),
            22_000L
        )
        feedRawReport(
            2,
            DansprintProtocol.buildType2TelemetryPacket((22_000L + 2_500L) * 500L, speedMPerTick, 95.0f),
            22_000L
        )
        engine.updateRacingInterpolation(22_000L, 3)

        // Lane 0 crosses 100m first and sends real hardware Type 1 Opcode 0x08 (Finish Time)
        feedRawReport(
            0,
            DansprintProtocol.buildErgoType1Packet(
                opcode = DansprintProtocol.ERGO_OPCODE_FINISH_OR_DIST_REQ,
                floatVal12 = 22_400.0f
            ),
            22_400L
        )
        // Lane 1 reaches 98.9m right after Lane 0 finishes (previously this would overtake Lane 0!)
        feedRawReport(
            1,
            DansprintProtocol.buildType2TelemetryPacket((22_600L + 2_500L) * 500L, speedMPerTick, 98.9f),
            22_600L
        )
        engine.updateRacingInterpolation(22_600L, 3)

        val midFinish = engine.uiState.value
        assertTrue(midFinish.lanes[0].isFinished)
        assertEquals(100.0f, midFinish.lanes[0].interpolatedDistanceMeters, 0.01f)
        assertEquals(1, midFinish.lanes[0].livePlace)
        assertFalse(midFinish.lanes[1].isFinished)
        assertEquals(2, midFinish.lanes[1].livePlace)
        assertEquals(3, midFinish.lanes[2].livePlace)
        assertEquals(RaceState.RACING, midFinish.raceState)

        // Lane 1 and Lane 2 finish on their physical ergometers (countdown to 0 -> send Opcode 0x08)
        feedRawReport(
            1,
            DansprintProtocol.buildErgoType1Packet(
                opcode = DansprintProtocol.ERGO_OPCODE_FINISH_OR_DIST_REQ,
                floatVal12 = 22_950.0f
            ),
            22_950L
        )
        feedRawReport(
            2,
            DansprintProtocol.buildType2TelemetryPacket((23_200L + 2_500L) * 500L, speedMPerTick, 98.1f),
            23_200L
        )
        feedRawReport(
            2,
            DansprintProtocol.buildErgoType1Packet(
                opcode = DansprintProtocol.ERGO_OPCODE_FINISH_OR_DIST_REQ,
                floatVal12 = 23_600.0f
            ),
            23_600L
        )

        engine.stepRaceStateMachine()

        val finished = engine.uiState.value
        assertEquals(RaceState.POST_FINISH, finished.raceState)
        assertEquals(100.0f, finished.lanes[0].interpolatedDistanceMeters, 0.01f)
        assertEquals(100.0f, finished.lanes[1].interpolatedDistanceMeters, 0.01f)
        assertEquals(100.0f, finished.lanes[2].interpolatedDistanceMeters, 0.01f)
        assertEquals(1, finished.lanes[0].rank)
        assertEquals(2, finished.lanes[1].rank)
        assertEquals(3, finished.lanes[2].rank)
    }

    @Test
    fun `dead reckoning interpolation advances distance smoothly between Type 2 packets`() {
        val engine = RaceEngine(defaultUseEmulator = true)
        engine.setStateForTest { state ->
            state.copy(
                raceState = RaceState.RACING,
                raceDistanceMeters = 100,
                configuredLanesCount = 2,
                isMapped = true,
                lanes = state.lanes.mapIndexed { idx, lane ->
                    if (idx == 0) lane.copy(connected = true, active = true) else lane
                }
            )
        }
        // Offset between CMD_SET_DISTANCE (0x07) and gunshot (0x0A) is 2,500 ms
        engine.laneRaceStartOffsetMs[0] = 2_500L

        // Speed = 18.0 km/h = 5.0 m/s
        val speedMPerTick = DansprintProtocol.speedKmhToMetersPerTick(18.0f)
        // At host t = 10,000 ms after gunshot, ergometer clock (started at 0x07) reads 12,500 ms = 6,250,000 ticks
        val pkt = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = (10_000L + 2_500L) * DansprintProtocol.ERGO_TICKS_PER_MS,
            speedMetersPerTick = speedMPerTick,
            distanceMeters = 50.0f
        )
        val parsed = ErgoStreamParser().feedBytes(DansprintProtocol.padToHidInputReport(pkt)).first()
        engine.handleIncomingPacket(0, 0, parsed, 10_000L)

        // 200 ms later (at 5.0 m/s), dead-reckoning should interpolate +1.0 m -> 51.0 m
        engine.updateRacingInterpolation(10_200L, 2)
        assertEquals(51.0f, engine.uiState.value.lanes[0].interpolatedDistanceMeters, 0.05f)
    }
}
