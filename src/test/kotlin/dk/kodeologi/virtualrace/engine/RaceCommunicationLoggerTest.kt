package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.audio.SoundPlayer
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.ErgoStreamParser
import dk.kodeologi.virtualrace.protocol.HostCommandOpcode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaceCommunicationLoggerTest {

    @BeforeEach
    fun silenceAudio() {
        SoundPlayer.soundEnabled = false
    }

    @Test
    fun `protocol host decoder and detailed parser expose full diagnostics`() {
        val setDistPkt = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_SET_DISTANCE,
            raceDistanceMeters = 500.0f
        )
        val setDistSummary = DansprintProtocol.decodeHostPacketSummary(setDistPkt)
        assertTrue(setDistSummary.contains("CMD_SET_DISTANCE"), setDistSummary)
        assertTrue(setDistSummary.contains("500.00m"), setDistSummary)

        val startPkt = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_RACE_START,
            startTimerMs = 1234.5f
        )
        val startSummary = DansprintProtocol.decodeHostPacketSummary(startPkt)
        assertTrue(startSummary.contains("CMD_RACE_START"), startSummary)
        assertTrue(startSummary.contains("1234.5ms"), startSummary)

        val leaderPkt = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = 10_000_000L,
            speedMetersPerTick = DansprintProtocol.speedKmhToMetersPerTick(18.0f),
            distanceMeters = 123.45f
        )
        val leaderSummary = DansprintProtocol.decodeHostPacketSummary(leaderPkt)
        assertTrue(leaderSummary.contains("LeaderBroadcast"), leaderSummary)
        assertTrue(leaderSummary.contains("123.45m"), leaderSummary)
        assertTrue(leaderSummary.contains("18.00km/h"), leaderSummary)

        // Checksum corruption detection
        val parser = ErgoStreamParser()
        val badType2 = DansprintProtocol.padToHidInputReport(leaderPkt).also {
            it[it.size - 1] = 0x00 // keep pad
            it[4 + DansprintProtocol.TYPE2_PAYLOAD_LEN - 1] =
                (it[4 + DansprintProtocol.TYPE2_PAYLOAD_LEN - 1].toInt() xor 0xFF).toByte()
        }
        val badRes = parser.feedBytesDetailed(badType2)
        assertTrue(badRes.packets.isEmpty())
        assertEquals(1, badRes.checksumFailures.size)
        assertTrue(badRes.checksumFailures.first().contains("Type2(FC) checksum mismatch"))

        // Partial frame buffered diagnostics
        val parser2 = ErgoStreamParser()
        val partial = leaderPkt.copyOfRange(0, 10)
        val partialRes = parser2.feedBytesDetailed(partial)
        assertTrue(partialRes.packets.isEmpty())
        assertEquals(10, partialRes.bufferedBytesRemaining)

        // Buffer overflow diagnostics (> 200 bytes without sync)
        val parser3 = ErgoStreamParser()
        val overflowRes = parser3.feedBytesDetailed(ByteArray(210) { 0x55 })
        assertTrue(overflowRes.bufferOverflowCount >= 1)
    }

    @Test
    fun `FileRaceCommunicationLogger writes structured 11-column log with full hex and unique per-race files`(
        @TempDir tempDir: Path
    ) {
        val logger = FileRaceCommunicationLogger(tempDir.toFile())
        val meta = RaceLogMetadata(
            raceNumber = 3,
            raceDistanceMeters = 200,
            configuredLanesCount = 2,
            transportModeName = "Physical USB HID (16C0:1503)",
            isEmulated = false,
            isMapped = true,
            laneMappings = listOf(
                RaceLogLaneMappingEntry(1, 0, 2060101L, connected = true, active = true, weightKg = 75),
                RaceLogLaneMappingEntry(2, 1, 2060102L, connected = true, active = true, weightKg = 68)
            )
        )

        val file1 = logger.startRaceSession(meta)
        assertNotNull(file1)
        assertTrue(file1.isFile)
        assertTrue(logger.isSessionActive)

        val txPkt = DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_SET_DISTANCE, 200f)
        logger.logTx(
            stateTimerMs = 1000L,
            raceState = RaceState.STOP_PADDLING,
            usbIndex = 0,
            laneNumber1Based = 1,
            packet = txPkt,
            ok = true,
            description = "CMD_SET_DISTANCE (200m)"
        )
        logger.logTx(
            stateTimerMs = 1005L,
            raceState = RaceState.STOP_PADDLING,
            usbIndex = 1,
            laneNumber1Based = 2,
            packet = txPkt,
            ok = false,
            description = "CMD_SET_DISTANCE (200m)",
            errorDetail = "hidraw write failed (errno=19) on /dev/hidraw2"
        )

        val rxFrame = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = 5_000_000L,
            speedMetersPerTick = DansprintProtocol.speedKmhToMetersPerTick(16.2f),
            distanceMeters = 42.5f
        )
        val rawReport = DansprintProtocol.padToHidInputReport(rxFrame)
        val parseRes = ErgoStreamParser().feedBytesDetailed(rawReport)
        logger.logRx(
            stateTimerMs = 8500L,
            raceState = RaceState.RACING,
            usbIndex = 0,
            laneNumber1Based = 1,
            rawBytes = rawReport,
            parseResult = parseRes
        )

        // Pipe / newline sanitization check
        logger.logEvent(
            stateTimerMs = 9000L,
            raceState = RaceState.RACING,
            level = RaceLogLevel.WARN,
            usbIndex = 1,
            laneNumber1Based = 2,
            message = "Test pipe | and\nnewline"
        )

        val finalLanes = listOf(
            LaneTelemetryState(
                laneIndex = 0,
                connected = true,
                active = true,
                rawDistanceMeters = 200f,
                interpolatedDistanceMeters = 200f,
                finishTimeMs = 42_150f,
                rank = 1
            ),
            LaneTelemetryState(
                laneIndex = 1,
                connected = false,
                active = true,
                rawDistanceMeters = 91.5f,
                interpolatedDistanceMeters = 91.5f,
                boxStatus = BoxStatusColor.YELLOW_HANDLE_CLOSED
            )
        )
        logger.endRaceSession("FINISHED_EARLY", finalLanes, 2)
        assertFalse(logger.isSessionActive)

        val content = file1.readText()
        assertTrue(content.contains("# RaceNumber:      3"))
        assertTrue(content.contains("# DistanceM:       200"))
        assertTrue(content.contains("L1<-usb0(sn=2060101"))
        assertTrue(content.contains("hidraw write failed (errno=19)"))
        assertTrue(content.contains("dist=42.50m"))
        assertTrue(content.contains("# RaceEndReason: FINISHED_EARLY"))
        assertTrue(content.contains("txPackets=2 (58B), txErrors=1, rxPackets=1 (40B, decodedFrames=1)"))
        assertTrue(content.contains("L2: rank=0, finish=--:--.-- (0.00ms), rawDist=91.50m"))

        val dataRows = content.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        assertEquals(4, dataRows.size)
        for (row in dataRows) {
            val cols = row.split("|")
            assertEquals(11, cols.size, "Expected 11 columns in row: $row")
        }

        // Verify RX raw hex is NOT truncated (all 40 bytes = 40 hex pairs)
        val rxRow = dataRows.first { it.contains("| RX  |") }
        val hexCol = rxRow.split("|").last().trim()
        assertEquals(40, hexCol.split(" ").size, "Expected full 40-byte HID report hex dump")

        // Verify TX raw hex is NOT truncated (all 29 bytes)
        val txRow = dataRows.first { it.contains("| TX  |") }
        val txHexCol = txRow.split("|").last().trim()
        assertEquals(29, txHexCol.split(" ").size, "Expected full 29-byte Type 1 TX hex dump")

        // Starting a second race immediately must allocate a distinct file (never overwrite)
        val file2 = logger.startRaceSession(meta)
        assertNotNull(file2)
        assertNotEquals(file1.name, file2.name)
        logger.endRaceSession("ABORTED", emptyList(), 0)
    }

    @Test
    fun `RaceEngine logs bidirectional traffic and creates a separate log file per race`(@TempDir tempDir: Path) {
        val logger = FileRaceCommunicationLogger(tempDir.toFile())
        val engine = RaceEngine(
            defaultUseEmulator = true,
            raceCommLogger = logger
        )
        engine.start()
        try {
            val deadline = System.currentTimeMillis() + 3_000L
            while (!engine.uiState.value.isMapped && System.currentTimeMillis() < deadline) {
                Thread.sleep(30)
            }
            assertTrue(engine.uiState.value.isMapped, "Emulator should map lanes")

            // Race 1: Start -> wait briefly to capture TX and RX traffic -> Abort
            engine.startRace()
            assertEquals(RaceState.STOP_PADDLING, engine.uiState.value.raceState)
            val race1Path = engine.uiState.value.lastRaceLogPath
            assertNotNull(race1Path)

            // Wait ~400ms so pre-start 0x10 stroke packets (sent every 200ms) arrive from virtual ergos
            Thread.sleep(450)
            engine.restartOrAbortRace()
            assertFalse(logger.isSessionActive)

            val logFile1 = File(race1Path)
            assertTrue(logFile1.isFile)
            val text1 = logFile1.readText()
            assertTrue(text1.contains("| TX  |"), "Expected TX packets in race 1 log:\n$text1")
            assertTrue(text1.contains("CMD_START_SEQ_PREP"), "Expected 0x1D command in log")
            assertTrue(text1.contains("CMD_PRE_START_LOCK"), "Expected 0x13 command in log")
            assertTrue(text1.contains("CMD_START_SEQ_ACTIVE"), "Expected 0x1C command in log")
            assertTrue(text1.contains("CMD_STOP_ABORT_RACE"), "Expected 0x12 abort command in log")
            assertTrue(text1.contains("| RX  |"), "Expected RX packets from ergometers in race 1 log:\n$text1")
            assertTrue(text1.contains("Type1[0x10 Stroke]"), "Expected decoded pre-start stroke RX packets")
            assertTrue(text1.contains("# RaceEndReason: ABORTED"), "Expected ABORTED end reason")

            // Race 2: Start a second race and verify a second distinct log file is created
            engine.setRaceNumber(2)
            engine.startRace()
            val race2Path = engine.uiState.value.lastRaceLogPath
            assertNotNull(race2Path)
            assertNotEquals(race1Path, race2Path)
            Thread.sleep(150)
            engine.newRace()
            assertFalse(logger.isSessionActive)

            val logFile2 = File(race2Path)
            assertTrue(logFile2.isFile)
            val text2 = logFile2.readText()
            assertTrue(text2.contains("# RaceNumber:      2"))
            assertTrue(text2.contains("# RaceEndReason: RESET_NEW_RACE"))
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `RaceEngine logs stalled-boat diagnostics including stale timeout, unchanged distance, out-of-range ticks, and unexpected mid-race packets`(
        @TempDir tempDir: Path
    ) {
        val logger = FileRaceCommunicationLogger(tempDir.toFile())
        val engine = RaceEngine(
            defaultUseEmulator = true,
            raceCommLogger = logger
        )
        // Discover and map lanes cleanly, then stop background loop so we can drive deterministic packets directly
        engine.start()
        try {
            val deadline = System.currentTimeMillis() + 3_000L
            while (!engine.uiState.value.isMapped && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(engine.uiState.value.isMapped)
        } finally {
            engine.stop()
        }

        engine.setStateForTest { state ->
            state.copy(
                raceState = RaceState.IDLE_PRE_RACE,
                raceDistanceMeters = 100,
                configuredLanesCount = 2,
                isMapped = true,
                lanes = state.lanes.mapIndexed { idx, l ->
                    if (idx < 2) l.copy(connected = true, active = true) else l
                }
            )
        }
        // Start logger session cleanly directly via logger & drive engine diagnostics
        val logFile = logger.startRaceSession(
            RaceLogMetadata(
                raceNumber = 7,
                raceDistanceMeters = 100,
                configuredLanesCount = 2,
                transportModeName = engine.activeTransport.modeName,
                isEmulated = true,
                isMapped = true,
                laneMappings = listOf(
                    RaceLogLaneMappingEntry(1, 0, 2060101L, true, true, 75),
                    RaceLogLaneMappingEntry(2, 1, 2060102L, true, true, 68)
                )
            )
        )
        assertNotNull(logFile)

        engine.setStateForTest { it.copy(raceState = RaceState.RACING) }
        engine.laneRaceStartOffsetMs[0] = 2_000L
        engine.laneRaceStartOffsetMs[1] = 2_000L

        val speedMPerTick = DansprintProtocol.speedKmhToMetersPerTick(16.0f)

        // 1. Lane 0 receives a normal Type 2 telemetry packet at t=10,000ms (50.0m)
        val pkt1 = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = (10_000L + 2_000L) * 500L,
            speedMetersPerTick = speedMPerTick,
            distanceMeters = 50.0f
        )
        val parsed1 = ErgoStreamParser().feedBytes(DansprintProtocol.padToHidInputReport(pkt1)).first()
        engine.handleIncomingPacket(0, 0, parsed1, 10_000L)

        // 2. Duplicate Type 2 with identical distance (50.0m) -> should log WARN unchanged distance
        engine.handleIncomingPacket(0, 0, parsed1, 10_150L)

        // 3. Stale telemetry timeout (> 2,000ms later with no new Type 2 packet) -> logged ONCE even across multiple ticks
        engine.updateRacingInterpolation(12_500L, 2)
        engine.updateRacingInterpolation(12_525L, 2)
        engine.updateRacingInterpolation(12_550L, 2)

        // 4. Telemetry resumes on Lane 0 at 52.0m -> should log Telemetry resumed
        val pkt2 = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = (12_600L + 2_000L) * 500L,
            speedMetersPerTick = speedMPerTick,
            distanceMeters = 52.0f
        )
        val parsed2 = ErgoStreamParser().feedBytes(DansprintProtocol.padToHidInputReport(pkt2)).first()
        engine.handleIncomingPacket(0, 0, parsed2, 12_600L)

        // 5. Out-of-range dead-reckoning ticks on Lane 1 (ergoTicks wildly ahead -> negative deltaTicks)
        val badTicksPkt = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = (50_000L + 2_000L) * 500L,
            speedMetersPerTick = speedMPerTick,
            distanceMeters = 40.0f
        )
        val parsedBadTicks = ErgoStreamParser().feedBytes(DansprintProtocol.padToHidInputReport(badTicksPkt)).first()
        engine.handleIncomingPacket(1, 1, parsedBadTicks, 12_600L)
        engine.updateRacingInterpolation(12_625L, 2)
        engine.updateRacingInterpolation(12_650L, 2)

        // 6. Unexpected Type 3 Ready packet and READ power-reset during RACING
        val type3 = ErgoStreamParser().feedBytes(
            DansprintProtocol.padToHidInputReport(DansprintProtocol.buildErgoType3ReadyPacket(68))
        ).first()
        engine.handleIncomingPacket(1, 1, type3, 12_700L)

        val readReset = ErgoStreamParser().feedBytes(
            DansprintProtocol.padToHidInputReport(DansprintProtocol.SYNC_RESET_READ)
        ).first()
        engine.handleIncomingPacket(1, 1, readReset, 12_750L)

        // Finish early -> closes log cleanly
        engine.finishRaceEarly()
        assertFalse(logger.isSessionActive)

        val logText = logFile.readText()
        assertTrue(logText.contains("Type2 telemetry ignored: unchanged distance=50.00m"), logText)

        // Stale telemetry warning must appear ONCE (de-duplicated across 3 ticks)
        val staleCount = logText.lineSequence().count { it.contains("Stale telemetry timeout:") }
        assertEquals(1, staleCount, "Stale telemetry warning should not spam every 25ms tick")
        assertTrue(logText.contains("Telemetry resumed after"), logText)

        // Out-of-range deltaTicks warning must appear ONCE
        val deltaWarnCount = logText.lineSequence().count { it.contains("Dead-reckoning deltaTicks out of range:") }
        assertEquals(1, deltaWarnCount, "Out-of-range deltaTicks warning should be de-duplicated")

        // Unexpected Type 3 & READ power reapplied mid-race must be logged
        assertTrue(logText.contains("Unexpected Type3[FE] received during RACING"), logText)
        assertTrue(logText.contains("Power reapplied (\"READ\")"), logText)
        assertTrue(logText.contains("# RaceEndReason: FINISHED_EARLY"), logText)
    }
}
