package dk.kodeologi.virtualrace.protocol

import dk.kodeologi.virtualrace.transport.EmulatedErgoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DansprintProtocolTest {

    @Test
    fun `test checksum and host packet builders`() {
        // 1. Host Type 1 Poll Status (0x0E): 29 bytes (4B sync + 25B payload)
        val pollStatus = DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_POLL_STATUS)
        assertEquals(29, pollStatus.size)
        assertEquals(0xFF.toByte(), pollStatus[0])
        assertEquals(0xFB.toByte(), pollStatus[3])
        assertEquals(0x0E.toByte(), pollStatus[4 + 22])
        assertTrue(DansprintProtocol.verifyChecksum(pollStatus, 4, DansprintProtocol.TYPE1_PAYLOAD_LEN))

        // 2. Host Type 1 Set Distance (0x07): float(500.0) at p[0..3], p[20]=0x01, p[22]=0x07
        val setDist = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_SET_DISTANCE,
            raceDistanceMeters = 500.0f
        )
        assertEquals(29, setDist.size)
        assertEquals(0x01.toByte(), setDist[4 + 20])
        assertEquals(0x07.toByte(), setDist[4 + 22])
        assertTrue(DansprintProtocol.verifyChecksum(setDist, 4, DansprintProtocol.TYPE1_PAYLOAD_LEN))

        // 3. Host Type 2 Leader Telemetry (0xFC): 18 bytes (4B sync + 14B payload)
        val leaderPacket = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = 25_000_000L, // 50.0 seconds at 500 kHz
            speedMetersPerTick = DansprintProtocol.speedKmhToMetersPerTick(18.0f),
            distanceMeters = 250.0f
        )
        assertEquals(18, leaderPacket.size)
        assertEquals(0xFC.toByte(), leaderPacket[3])
        assertTrue(DansprintProtocol.verifyChecksum(leaderPacket, 4, DansprintProtocol.TYPE2_PAYLOAD_LEN))
    }

    @Test
    fun `test ErgoStreamParser parses 40-byte HID reports and READ marker`() {
        val parser = ErgoStreamParser()

        // 1. Build a Type 2 race telemetry frame wrapped in a 40-byte USB HID input report
        val type2Frame = DansprintProtocol.buildType2TelemetryPacket(
            ergoTicks = 14_250_000L, // 28.5 seconds
            speedMetersPerTick = DansprintProtocol.speedKmhToMetersPerTick(16.74f),
            distanceMeters = 125.0f
        )
        val hidReport = DansprintProtocol.padToHidInputReport(type2Frame)
        assertEquals(40, hidReport.size)

        val packets = parser.feedBytes(hidReport)
        assertEquals(1, packets.size)
        val parsed = packets.first() as? IncomingErgoPacket.RaceTelemetryType2
        assertNotNull(parsed)
        assertEquals(14_250_000L, parsed.ergoTicks)
        assertEquals(125.0f, parsed.distanceMeters, 0.01f)
        assertEquals(16.74f, parsed.speedKmh, 0.05f)

        // 2. Feed a "READ" power-reset ASCII marker inside a 40-byte report
        val readReport = ByteArray(40)
        readReport[0] = 'R'.code.toByte()
        readReport[1] = 'E'.code.toByte()
        readReport[2] = 'A'.code.toByte()
        readReport[3] = 'D'.code.toByte()

        val resetPackets = parser.feedBytes(readReport)
        assertTrue(resetPackets.any { it is IncomingErgoPacket.PowerReappliedRead })
    }

    @Test
    fun `test EmulatedErgoTransport full protocol handshake and race telemetry`() {
        val emulator = EmulatedErgoTransport()
        val devices = emulator.enumerateAndOpenDevices()
        assertEquals(6, devices.size) // 6 virtual ergometers plugged in by default

        // Send 0x0F Init and 0x0E Poll Status over wire to USB device 0
        assertTrue(
            emulator.writePacket(
                0,
                DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_INIT_CONNECT),
                "Init"
            )
        )
        assertTrue(
            emulator.writePacket(
                0,
                DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_POLL_STATUS),
                "Poll"
            )
        )

        // Read 40-byte HID response and parse with ErgoStreamParser
        val rxBytes = emulator.readNonBlocking(0)
        assertEquals(40, rxBytes.size)

        val parser = ErgoStreamParser()
        val frames = parser.feedBytes(rxBytes)
        assertEquals(1, frames.size)
        val statusReply = frames.first() as? IncomingErgoPacket.LaneStatusReply
        assertNotNull(statusReply)
        assertEquals(1, statusReply.laneNumber1Based)

        // Send 0x07 Set Distance (200m) and verify Type 3 Ready Ack (FF FF FF FE)
        assertTrue(
            emulator.writePacket(
                0,
                DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_SET_DISTANCE, raceDistanceMeters = 200f),
                "Set Distance"
            )
        )
        val ackBytes = emulator.readNonBlocking(0)
        assertEquals(40, ackBytes.size)
        val ackFrames = parser.feedBytes(ackBytes)
        val type3 = ackFrames.filterIsInstance<IncomingErgoPacket.ReadyActiveType3>().firstOrNull()
        assertNotNull(type3)
        assertEquals(75, type3.weightKg)

        // Enter pre-start countdown (0x1C) and trigger a manual pre-start paddle stroke
        assertTrue(
            emulator.writePacket(
                0,
                DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_START_SEQ_ACTIVE),
                "Start Seq Active"
            )
        )
        emulator.triggerManualPaddleStroke(0)
        val strokeBytes = emulator.readNonBlocking(0)
        assertEquals(40, strokeBytes.size)
        val strokeFrames = parser.feedBytes(strokeBytes)
        val stroke = strokeFrames.filterIsInstance<IncomingErgoPacket.StrokeActivity>().firstOrNull()
        assertNotNull(stroke)
        assertTrue(stroke.flywheelMetric > DansprintProtocol.FALSE_START_FLYWHEEL_THRESHOLD)
        assertTrue(stroke.msSincePulse < DansprintProtocol.FALSE_START_PULSE_AGE_MS)
    }
}
