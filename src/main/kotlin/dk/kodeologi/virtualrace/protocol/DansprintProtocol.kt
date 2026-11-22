package dk.kodeologi.virtualrace.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Complete wire-level implementation of the reverse-engineered Dansprint Virtual Race USB HID protocol.
 *
 * Reference binary: DansprintVirtualRaceUSB.exe (ErgoRace.exe)
 * - VID = 0x16C0 (5824), PID = 0x1503 (5379)
 * - Manufacturer = "Moebius", Product = "Dansprint"
 * - Internal ergometer hardware clock: 500 kHz (1 tick = 2 microseconds = 0.002 ms)
 */
object DansprintProtocol {
    const val USB_VENDOR_ID: Int = 0x16C0
    const val USB_PRODUCT_ID: Int = 0x1503
    const val USB_MANUFACTURER: String = "Moebius"
    const val USB_PRODUCT: String = "Dansprint"

    const val MAX_LANES: Int = 8
    const val STREAM_BUFFER_CAPACITY: Int = 200
    const val HID_INPUT_REPORT_CONSUME_BYTES: Int = 40
    const val HID_OUTPUT_REPORT_LENGTH: Int = 41 // 1-byte Report ID (0x00) + 40-byte HID payload

    // 500 kHz hardware clock on the ergometer microcontroller (1 tick = 2 us = 0.002 ms)
    const val ERGO_CLOCK_HZ: Float = 500_000.0f
    const val ERGO_TICKS_PER_MS: Long = 500L
    const val MS_PER_ERGO_TICK: Double = 0.002
    const val FLYWHEEL_METRIC_NUMERATOR: Float = 7_500_000.0f
    const val KMH_PER_MPS: Float = 3.6f

    // Movement and false-start detection thresholds (from Calc_race 0x404a97, 0x404dfc, 0x404efd)
    const val STILLNESS_FLYWHEEL_THRESHOLD: Float = 160.0f
    const val STILLNESS_PULSE_AGE_MS: Long = 500L
    const val FALSE_START_FLYWHEEL_THRESHOLD: Float = 50.0f
    const val FALSE_START_PULSE_AGE_MS: Long = 300L

    // Payload lengths (including the trailing 2-byte little-endian checksum, excluding the 4-byte sync header)
    const val TYPE1_PAYLOAD_LEN: Int = 25 // 0x19 -> Total frame = 29 bytes
    const val TYPE2_PAYLOAD_LEN: Int = 14 // 0x0E -> Total frame = 18 bytes
    const val TYPE3_PAYLOAD_LEN: Int = 6  // 0x06 -> Total frame = 10 bytes
    const val RESET_MARKER_LEN: Int = 4   // "READ" -> Total frame = 4 bytes

    val SYNC_TYPE1: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFB.toByte())
    val SYNC_TYPE2: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFC.toByte())
    val SYNC_TYPE3: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte())
    val SYNC_RESET_READ: ByteArray = byteArrayOf(0x52, 0x45, 0x41, 0x44) // ASCII "READ"

    /**
     * Calculates the 16-bit unsigned little-endian checksum over payload[0 until payloadLen - 2]
     * exactly as implemented at 0x0040611c in DansprintVirtualRaceUSB.exe.
     */
    fun calculateChecksum(payload: ByteArray, offset: Int = 0, payloadLen: Int = payload.size): Int {
        var sum = 0
        val dataEnd = offset + payloadLen - 2
        for (i in offset until dataEnd) {
            sum = (sum + (payload[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /**
     * Verifies that the trailing 2-byte little-endian checksum in [payload] matches [calculateChecksum].
     */
    fun verifyChecksum(payload: ByteArray, offset: Int = 0, payloadLen: Int = payload.size): Boolean {
        if (offset + payloadLen > payload.size || payloadLen < 2) return false
        val expected = calculateChecksum(payload, offset, payloadLen)
        val low = payload[offset + payloadLen - 2].toInt() and 0xFF
        val high = payload[offset + payloadLen - 1].toInt() and 0xFF
        val actual = low or (high shl 8)
        return expected == actual
    }

    /**
     * Builds a Host -> Ergometer Type 1 Command Packet (29 bytes: FF FF FF FB + 25-byte payload).
     */
    fun buildHostType1Command(
        opcode: HostCommandOpcode,
        raceDistanceMeters: Float = 0f,
        startTimerMs: Float = 0f
    ): ByteArray {
        val payload = ByteArray(TYPE1_PAYLOAD_LEN)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        when (opcode) {
            HostCommandOpcode.CMD_SET_DISTANCE -> {
                // 0x4061f0 / 0x406264: p[0..3] = float(raceDistanceMeters), p[20] = 0x01, p[22] = 0x07
                buf.putFloat(0, raceDistanceMeters)
                payload[20] = 0x01
            }
            HostCommandOpcode.CMD_RACE_START -> {
                // 0x406314: p[12..15] = float(currentTimerMs), p[22] = 0x0A
                buf.putFloat(12, startTimerMs)
            }
            else -> {
                // All other Type 1 host commands have 0x00 in p[0..21]
            }
        }

        payload[22] = opcode.code.toByte()
        val checksum = calculateChecksum(payload, 0, TYPE1_PAYLOAD_LEN)
        buf.putShort(23, checksum.toShort())

        return SYNC_TYPE1 + payload
    }

    /**
     * Builds a Type 2 Real-Time Race Progress / Leader Broadcast Packet (18 bytes: FF FF FF FC + 14-byte payload).
     * Used both by Host -> Ergometer (leader broadcast at 0x406380) and Ergometer -> Host (live race telemetry).
     */
    fun buildType2TelemetryPacket(
        ergoTicks: Long,
        speedMetersPerTick: Float,
        distanceMeters: Float
    ): ByteArray {
        val payload = ByteArray(TYPE2_PAYLOAD_LEN)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, (ergoTicks and 0xFFFFFFFFL).toInt())
        buf.putFloat(4, speedMetersPerTick)
        buf.putFloat(8, distanceMeters)
        val checksum = calculateChecksum(payload, 0, TYPE2_PAYLOAD_LEN)
        buf.putShort(12, checksum.toShort())
        return SYNC_TYPE2 + payload
    }

    /**
     * Builds an Ergometer -> Host Type 1 Packet (29 bytes: FF FF FF FB + 25-byte payload).
     * Used by the built-in ergometer emulator and unit tests.
     */
    fun buildErgoType1Packet(
        opcode: Int,
        laneOrWeight: Int = 0,
        floatVal12: Float = 0f,
        ticksSincePulse: Long = 0L
    ): ByteArray {
        val payload = ByteArray(TYPE1_PAYLOAD_LEN)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(8, (laneOrWeight and 0xFFFF).toShort())
        buf.putFloat(12, floatVal12)
        buf.putInt(16, (ticksSincePulse and 0xFFFFFFFFL).toInt())
        payload[22] = (opcode and 0xFF).toByte()
        val checksum = calculateChecksum(payload, 0, TYPE1_PAYLOAD_LEN)
        buf.putShort(23, checksum.toShort())
        return SYNC_TYPE1 + payload
    }

    /**
     * Builds an Ergometer -> Host Type 3 Ready / Distance-Ack / Active Packet (10 bytes: FF FF FF FE + 6-byte payload).
     */
    fun buildErgoType3ReadyPacket(weightKg: Int, statusWord: Int = 1): ByteArray {
        val payload = ByteArray(TYPE3_PAYLOAD_LEN)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, (statusWord and 0xFFFF).toShort())
        buf.putShort(2, (weightKg and 0xFFFF).toShort())
        val checksum = calculateChecksum(payload, 0, TYPE3_PAYLOAD_LEN)
        buf.putShort(4, checksum.toShort())
        return SYNC_TYPE3 + payload
    }

    /**
     * Wraps a framed packet into a 40-byte USB HID input report (as sent by ergometer firmware over USB HID).
     */
    fun padToHidInputReport(packet: ByteArray): ByteArray {
        val report = ByteArray(HID_INPUT_REPORT_CONSUME_BYTES)
        System.arraycopy(packet, 0, report, 0, min(packet.size, report.size))
        return report
    }

    /**
     * Converts instantaneous speed in meters per 2us tick to kilometers per hour (0x405444).
     */
    fun speedMetersPerTickToKmh(speedMetersPerTick: Float): Float {
        return speedMetersPerTick * ERGO_CLOCK_HZ * KMH_PER_MPS
    }

    /**
     * Converts kilometers per hour to meters per 2us tick.
     */
    fun speedKmhToMetersPerTick(speedKmh: Float): Float {
        return speedKmh / (ERGO_CLOCK_HZ * KMH_PER_MPS)
    }

    /**
     * Formats milliseconds as H:MM:SS.CC (matching 0x404577 in Graphic unit).
     */
    fun formatRaceTimeMs(timeMs: Float): String {
        val totalMs = timeMs.roundToLong().coerceAtLeast(0L)
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val hundredths = (totalMs % 1_000L) / 10L
        return if (hours > 0) {
            "%d:%02d:%02d.%02d".format(hours, minutes, seconds, hundredths)
        } else {
            "%02d:%02d.%02d".format(minutes, seconds, hundredths)
        }
    }

    fun ByteArray.toHexString(maxBytes: Int = this.size): String {
        val count = min(this.size, maxBytes)
        return (0 until count).joinToString(" ") { "%02X".format(this[it].toInt() and 0xFF) } +
            if (this.size > maxBytes) " ..." else ""
    }
}

/**
 * Host -> Ergometer Type 1 Command Opcodes (stored at p[22] / offset 0x16 of payload).
 */
enum class HostCommandOpcode(val code: Int, val label: String) {
    CMD_ACCEPT_LEGACY(0x03, "Legacy Accept (0x03)"),
    CMD_SET_DISTANCE(0x07, "Set Race Distance (0x07)"),
    CMD_RACE_START(0x0A, "Start Race Signal (0x0A)"),
    CMD_POLL_STATUS(0x0E, "Poll Lane & Status (0x0E)"),
    CMD_INIT_CONNECT(0x0F, "Init / Connect Ergo (0x0F)"),
    CMD_START_SEQ_SYNC(0x10, "Pre-Start Sync Keepalive (0x10)"),
    CMD_STOP_ABORT_RACE(0x12, "Stop / Abort Race (0x12)"),
    CMD_PRE_START_LOCK(0x13, "Pre-Start Clear/Lock (0x13)"),
    CMD_USB_DISCONNECT(0x17, "USB Disconnect (0x17)"),
    CMD_CLEAR_NEW_RACE(0x1A, "Clear / New Race (0x1A)"),
    CMD_APP_SHUTDOWN(0x1B, "App Shutdown (0x1B)"),
    CMD_START_SEQ_ACTIVE(0x1C, "Enter Start Sequence Active (0x1C)"),
    CMD_START_SEQ_PREP(0x1D, "Prepare Start Sequence All (0x1D)");

    companion object {
        fun fromCode(code: Int): HostCommandOpcode? = entries.find { it.code == (code and 0xFF) }
    }
}

/**
 * Decoded Ergometer -> Host packets emitted by [ErgoStreamParser].
 */
sealed interface IncomingErgoPacket {
    val summary: String

    /** Type 1 Opcode 0x04: Flywheel period & time since last pulse (0x405a94) */
    data class StrokeActivity(
        val flywheelPeriodTicks: Float,
        val flywheelMetric: Float,
        val ticksSincePulse: Long,
        val msSincePulse: Long
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type1[0x04 Stroke] metric=%.1f, pulseAge=%dms".format(flywheelMetric, msSincePulse)
    }

    /** Type 1 Opcode 0x07: Official finish time in ms (in State 6) or distance request (in States 1/2) (0x405b00) */
    data class FinishOrDistanceAck(
        val finishTimeMs: Float
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type1[0x07 Finish/Ack] finishTime=%.1fms (%s)".format(
                finishTimeMs,
                DansprintProtocol.formatRaceTimeMs(finishTimeMs)
            )
    }

    /** Type 1 Opcode 0x0E: 1-based lane number (1..8) and connection status reply (0x405b5d) */
    data class LaneStatusReply(
        val laneNumber1Based: Int
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type1[0x0E Status] Lane=$laneNumber1Based"
    }

    /** Type 1 Opcode 0x0F: Ergometer init/reset reply (0x405a6f) */
    data object ConnectResetReply : IncomingErgoPacket {
        override val summary: String = "Type1[0x0F Reset/Init Reply]"
    }

    /** Type 1 Opcode 0x14 or 0x15: Paddler weight in kg (0x405ba8, 0x405bb9) */
    data class WeightReport(
        val weightKg: Int,
        val opcode: Int
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type1[0x%02X Weight] %d kg".format(opcode, weightKg)
    }

    /** Type 1 with any other opcode */
    data class UnknownType1(
        val opcode: Int
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type1[0x%02X Unknown]".format(opcode)
    }

    /** Type 2 (FF FF FF FC): Real-time race telemetry (0x405d6e) */
    data class RaceTelemetryType2(
        val ergoTicks: Long,
        val speedMetersPerTick: Float,
        val distanceMeters: Float
    ) : IncomingErgoPacket {
        val speedKmh: Float
            get() = DansprintProtocol.speedMetersPerTickToKmh(speedMetersPerTick)

        override val summary: String
            get() = "Type2[FC Telemetry] dist=%.1fm, speed=%.1fkm/h, ticks=%d".format(
                distanceMeters,
                speedKmh,
                ergoTicks
            )
    }

    /** Type 3 (FF FF FF FE): Ergometer Ready / Distance Ack / Active (0x405ec7) */
    data class ReadyActiveType3(
        val statusWord: Int,
        val weightKg: Int
    ) : IncomingErgoPacket {
        override val summary: String
            get() = "Type3[FE Ready/Active] weight=${weightKg}kg"
    }

    /** Raw 4-byte ASCII "READ" power-reapplied marker (0x405d3f) */
    data object PowerReappliedRead : IncomingErgoPacket {
        override val summary: String = "Marker[\"READ\" Power Reapplied]"
    }
}

/**
 * Per-device 200-byte stream buffer and frame parser faithfully reproducing `0x00405be0` (`ParseIncomingStream`)
 * in `DansprintVirtualRaceUSB.exe`.
 */
class ErgoStreamParser {
    private val buffer = ByteArray(DansprintProtocol.STREAM_BUFFER_CAPACITY)
    var bufferedLength: Int = 0
        private set

    fun reset() {
        bufferedLength = 0
    }

    /**
     * Appends incoming USB HID bytes to the 200-byte stream buffer and parses all complete frames.
     */
    fun feedBytes(incoming: ByteArray, length: Int = incoming.size): List<IncomingErgoPacket> {
        val parsed = mutableListOf<IncomingErgoPacket>()
        for (i in 0 until min(length, incoming.size)) {
            if (bufferedLength < DansprintProtocol.STREAM_BUFFER_CAPACITY) {
                buffer[bufferedLength++] = incoming[i]
            } else {
                // Matches 0x405c89: if buffer reaches 200 bytes without being drained, reset index to 0
                bufferedLength = 0
            }
        }

        // Loop just like 0x405be0 until no more complete packets can be extracted
        while (true) {
            val limit = bufferedLength - 4
            if (limit <= 0) break

            var foundType = 0 // 1 = FB, 2 = FC, 3 = FE, 4 = "READ"
            var syncOffset = 0
            var needMoreBytes = false

            while (syncOffset < limit) {
                val b0 = buffer[syncOffset]
                val b1 = buffer[syncOffset + 1]
                val b2 = buffer[syncOffset + 2]
                val b3 = buffer[syncOffset + 3]

                if (b0 == 0xFF.toByte() && b1 == 0xFF.toByte() && b2 == 0xFF.toByte()) {
                    when (b3) {
                        0xFB.toByte() -> {
                            foundType = 1
                            if (limit < DansprintProtocol.TYPE1_PAYLOAD_LEN) needMoreBytes = true
                            break
                        }
                        0xFC.toByte() -> {
                            foundType = 2
                            if (limit < DansprintProtocol.TYPE2_PAYLOAD_LEN) needMoreBytes = true
                            break
                        }
                        0xFE.toByte() -> {
                            foundType = 3
                            if (limit < DansprintProtocol.TYPE3_PAYLOAD_LEN) needMoreBytes = true
                            break
                        }
                    }
                } else if (
                    b0 == DansprintProtocol.SYNC_RESET_READ[0] &&
                    b1 == DansprintProtocol.SYNC_RESET_READ[1] &&
                    b2 == DansprintProtocol.SYNC_RESET_READ[2] &&
                    b3 == DansprintProtocol.SYNC_RESET_READ[3]
                ) {
                    foundType = 4
                    break
                }
                syncOffset++
            }

            if (needMoreBytes) {
                break
            }

            var consumeBytes = 0
            val payloadStart = syncOffset + 4
            val remainingPayloadBytes = limit - syncOffset

            when (foundType) {
                4 -> {
                    parsed.add(IncomingErgoPacket.PowerReappliedRead)
                    consumeBytes = syncOffset + 4
                }
                2 -> {
                    if (remainingPayloadBytes >= DansprintProtocol.TYPE2_PAYLOAD_LEN) {
                        if (DansprintProtocol.verifyChecksum(buffer, payloadStart, DansprintProtocol.TYPE2_PAYLOAD_LEN)) {
                            val bb = ByteBuffer.wrap(buffer, payloadStart, DansprintProtocol.TYPE2_PAYLOAD_LEN)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val ticks = bb.getInt(payloadStart).toLong() and 0xFFFFFFFFL
                            val speed = bb.getFloat(payloadStart + 4)
                            val dist = bb.getFloat(payloadStart + 8)
                            parsed.add(IncomingErgoPacket.RaceTelemetryType2(ticks, speed, dist))
                            consumeBytes = syncOffset + DansprintProtocol.HID_INPUT_REPORT_CONSUME_BYTES
                        } else {
                            consumeBytes = syncOffset + 4
                        }
                    }
                }
                1 -> {
                    if (remainingPayloadBytes >= DansprintProtocol.TYPE1_PAYLOAD_LEN) {
                        if (DansprintProtocol.verifyChecksum(buffer, payloadStart, DansprintProtocol.TYPE1_PAYLOAD_LEN)) {
                            parsed.add(decodeType1Payload(buffer, payloadStart))
                            consumeBytes = syncOffset + DansprintProtocol.HID_INPUT_REPORT_CONSUME_BYTES
                        } else {
                            consumeBytes = syncOffset + 4
                        }
                    }
                }
                3 -> {
                    if (remainingPayloadBytes >= DansprintProtocol.TYPE3_PAYLOAD_LEN) {
                        if (DansprintProtocol.verifyChecksum(buffer, payloadStart, DansprintProtocol.TYPE3_PAYLOAD_LEN)) {
                            val bb = ByteBuffer.wrap(buffer, payloadStart, DansprintProtocol.TYPE3_PAYLOAD_LEN)
                                .order(ByteOrder.LITTLE_ENDIAN)
                            val statusWord = bb.getShort(payloadStart).toInt() and 0xFFFF
                            val weightKg = bb.getShort(payloadStart + 2).toInt() and 0xFFFF
                            parsed.add(IncomingErgoPacket.ReadyActiveType3(statusWord, weightKg))
                            consumeBytes = syncOffset + DansprintProtocol.HID_INPUT_REPORT_CONSUME_BYTES
                        } else {
                            consumeBytes = syncOffset + 4
                        }
                    }
                }
                0 -> {
                    if (bufferedLength > 4) {
                        consumeBytes = bufferedLength - 4
                    }
                }
            }

            if (consumeBytes <= 0) {
                break
            }

            val actualConsume = min(bufferedLength, consumeBytes)
            val remaining = bufferedLength - actualConsume
            if (remaining > 0) {
                System.arraycopy(buffer, actualConsume, buffer, 0, remaining)
            }
            bufferedLength = remaining

            if (bufferedLength < 10) {
                break
            }
        }

        return parsed
    }

    private fun decodeType1Payload(buf: ByteArray, offset: Int): IncomingErgoPacket {
        val bb = ByteBuffer.wrap(buf, offset, DansprintProtocol.TYPE1_PAYLOAD_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buf[offset + 22].toInt() and 0xFF
        return when (opcode) {
            0x04 -> {
                val periodTicks = bb.getFloat(offset + 12)
                val metric = if (periodTicks == 0.0f) 0.0f else DansprintProtocol.FLYWHEEL_METRIC_NUMERATOR / periodTicks
                val ticksSincePulse = bb.getInt(offset + 16).toLong() and 0xFFFFFFFFL
                val msSincePulse = (ticksSincePulse.toDouble() * DansprintProtocol.MS_PER_ERGO_TICK).roundToLong()
                IncomingErgoPacket.StrokeActivity(periodTicks, metric, ticksSincePulse, msSincePulse)
            }
            0x07 -> {
                val finishTimeMs = bb.getFloat(offset + 12)
                IncomingErgoPacket.FinishOrDistanceAck(finishTimeMs)
            }
            0x0E -> {
                val lane1Based = bb.getShort(offset + 8).toInt() and 0xFFFF
                IncomingErgoPacket.LaneStatusReply(lane1Based)
            }
            0x0F -> IncomingErgoPacket.ConnectResetReply
            0x14, 0x15 -> {
                val weightKg = bb.getShort(offset + 8).toInt() and 0xFFFF
                IncomingErgoPacket.WeightReport(weightKg, opcode)
            }
            else -> IncomingErgoPacket.UnknownType1(opcode)
        }
    }
}
