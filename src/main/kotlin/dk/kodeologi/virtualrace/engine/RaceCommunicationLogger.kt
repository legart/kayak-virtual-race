package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.DansprintProtocol.toHexString
import dk.kodeologi.virtualrace.protocol.ErgoStreamParseResult
import dk.kodeologi.virtualrace.protocol.IncomingErgoPacket
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RaceLogLevel {
    OK,
    WARN,
    ERR,
    FAIL
}

data class RaceLogLaneMappingEntry(
    val laneNumber1Based: Int,
    val usbIndex: Int,
    val serialNumber: Long?,
    val connected: Boolean,
    val active: Boolean,
    val weightKg: Int
)

data class RaceLogMetadata(
    val raceNumber: Int,
    val raceDistanceMeters: Int,
    val configuredLanesCount: Int,
    val transportModeName: String,
    val isEmulated: Boolean,
    val isMapped: Boolean,
    val laneMappings: List<RaceLogLaneMappingEntry>
)

/**
 * Per-race bidirectional wire communication and diagnostic logger.
 *
 * Captures 100% of Host -> Ergometer (`TX`) and Ergometer -> Host (`RX`) traffic with
 * both decoded protocol fields and complete raw hex dumps, plus state-machine and
 * diagnostic events (`EVT`) so issues such as a stalled boat mid-race can be debugged.
 */
interface RaceCommunicationLogger {
    val currentLogFile: File?
    val isSessionActive: Boolean

    fun startRaceSession(metadata: RaceLogMetadata): File?

    fun logTx(
        stateTimerMs: Long,
        raceState: RaceState,
        usbIndex: Int,
        laneNumber1Based: Int?,
        packet: ByteArray,
        ok: Boolean,
        description: String = "",
        errorDetail: String? = null
    )

    fun logRx(
        stateTimerMs: Long,
        raceState: RaceState,
        usbIndex: Int,
        laneNumber1Based: Int?,
        rawBytes: ByteArray,
        parseResult: ErgoStreamParseResult
    )

    fun logEvent(
        stateTimerMs: Long,
        raceState: RaceState,
        level: RaceLogLevel = RaceLogLevel.OK,
        usbIndex: Int? = null,
        laneNumber1Based: Int? = null,
        message: String
    )

    fun endRaceSession(
        reason: String,
        finalLanes: List<LaneTelemetryState>,
        configuredLanesCount: Int
    )
}

/**
 * File-backed [RaceCommunicationLogger] that writes one structured plain-text `.log` file
 * per race into [logDir] (default `./logs/`).
 *
 * Format:
 * - `#`-prefixed metadata header and summary footer
 * - Pipe-delimited (`|`) columnar rows for every `TX`, `RX`, and `EVT` entry, easy to scan
 *   by eye, filter with `grep`, or parse programmatically.
 * - Flushes after every entry so mid-race crashes or power cuts never lose tail packets.
 */
class FileRaceCommunicationLogger(
    val logDir: File = defaultLogDir()
) : RaceCommunicationLogger {

    private var writer: BufferedWriter? = null
    private var activeFile: File? = null
    private var sessionStartWallMs: Long = 0L

    private var txPackets: Long = 0L
    private var txBytes: Long = 0L
    private var txErrors: Long = 0L
    private var rxPackets: Long = 0L
    private var rxBytes: Long = 0L
    private var rxFramesDecoded: Long = 0L
    private var parseWarnings: Long = 0L
    private var eventCount: Long = 0L

    override val currentLogFile: File?
        @Synchronized get() = activeFile

    override val isSessionActive: Boolean
        @Synchronized get() = writer != null

    @Synchronized
    override fun startRaceSession(metadata: RaceLogMetadata): File? {
        if (writer != null) {
            closeWriterInternal(
                reason = "SUPERSEDED_BY_NEW_RACE",
                finalLanes = emptyList(),
                configuredLanesCount = 0
            )
        }

        return try {
            logDir.mkdirs()
            val nowZdt = ZonedDateTime.now()
            sessionStartWallMs = nowZdt.toInstant().toEpochMilli()
            resetCounters()

            val file = allocateUniqueLogFile(nowZdt, metadata.raceNumber, metadata.raceDistanceMeters)
            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8))
            writer = bw
            activeFile = file

            bw.write("# ==============================================================================\n")
            bw.write("# KAYAK VIRTUAL RACE - ERGOMETER COMMUNICATION LOG\n")
            bw.write("# ==============================================================================\n")
            bw.write("# FormatVersion:   1\n")
            bw.write("# StartedAt:       ${nowZdt.format(ISO_ZONED_FMT)}\n")
            bw.write("# RaceNumber:      ${metadata.raceNumber}\n")
            bw.write("# DistanceM:       ${metadata.raceDistanceMeters}\n")
            bw.write("# ConfiguredLanes: ${metadata.configuredLanesCount}\n")
            bw.write("# Transport:       ${metadata.transportModeName} (emulated=${metadata.isEmulated})\n")
            bw.write("# Mapped:          ${metadata.isMapped}\n")
            val mappingSummary = if (metadata.laneMappings.isEmpty()) {
                "(none)"
            } else {
                metadata.laneMappings.joinToString(", ") { m ->
                    val sn = m.serialNumber?.toString() ?: "n/a"
                    "L${m.laneNumber1Based}<-usb${m.usbIndex}(sn=$sn,conn=${m.connected},active=${m.active},w=${m.weightKg}kg)"
                }
            }
            bw.write("# LaneMapping:     $mappingSummary\n")
            bw.write("# Columns:         ISO_TIME | LOG_MS | TIMER_MS | STATE | DIR | USB | LANE | LEN | STATUS | DECODED | HEX\n")
            bw.write("# ==============================================================================\n")
            bw.flush()
            file
        } catch (_: Throwable) {
            closeQuietly()
            null
        }
    }

    @Synchronized
    override fun logTx(
        stateTimerMs: Long,
        raceState: RaceState,
        usbIndex: Int,
        laneNumber1Based: Int?,
        packet: ByteArray,
        ok: Boolean,
        description: String,
        errorDetail: String?
    ) {
        val bw = writer ?: return
        txPackets++
        txBytes += packet.size
        if (!ok) txErrors++

        val decodedWire = DansprintProtocol.decodeHostPacketSummary(packet)
        val decoded = buildString {
            append(decodedWire)
            if (description.isNotBlank() && !decodedWire.contains(description, ignoreCase = true)) {
                append(" (").append(description).append(")")
            }
            if (!ok) {
                append(" [TX FAILED")
                if (!errorDetail.isNullOrBlank()) {
                    append(": ").append(errorDetail)
                }
                append("]")
            }
        }

        writeRow(
            bw = bw,
            stateTimerMs = stateTimerMs,
            raceState = raceState,
            dir = "TX",
            usbIndex = usbIndex,
            laneNumber1Based = laneNumber1Based,
            length = packet.size,
            level = if (ok) RaceLogLevel.OK else RaceLogLevel.FAIL,
            decoded = decoded,
            hex = packet.toHexString()
        )
    }

    @Synchronized
    override fun logRx(
        stateTimerMs: Long,
        raceState: RaceState,
        usbIndex: Int,
        laneNumber1Based: Int?,
        rawBytes: ByteArray,
        parseResult: ErgoStreamParseResult
    ) {
        val bw = writer ?: return
        rxPackets++
        rxBytes += rawBytes.size
        rxFramesDecoded += parseResult.packets.size

        val notes = mutableListOf<String>()
        if (parseResult.packets.isNotEmpty()) {
            notes.add(parseResult.packets.joinToString("; ") { it.debugSummary })
        }
        if (parseResult.checksumFailures.isNotEmpty()) {
            parseWarnings += parseResult.checksumFailures.size
            notes.add("CHECKSUM_ERROR: ${parseResult.checksumFailures.joinToString("; ")}")
        }
        if (parseResult.bufferOverflowCount > 0) {
            parseWarnings += parseResult.bufferOverflowCount
            notes.add("BUFFER_OVERFLOW(x${parseResult.bufferOverflowCount})")
        }
        if (parseResult.discardedGarbageBytes > 0) {
            parseWarnings++
            notes.add("DISCARDED_BYTES=${parseResult.discardedGarbageBytes}B")
        }
        if (parseResult.packets.isEmpty() && notes.isEmpty()) {
            if (parseResult.bufferedBytesRemaining > 0) {
                notes.add("Partial frame buffered (${parseResult.bufferedBytesRemaining}B pending)")
            } else {
                notes.add("No complete frame extracted")
            }
        } else if (parseResult.bufferedBytesRemaining > 0) {
            notes.add("buffered=${parseResult.bufferedBytesRemaining}B")
        }

        val hasAlertPacket = parseResult.packets.any {
            it is IncomingErgoPacket.PowerReappliedRead || it is IncomingErgoPacket.ConnectResetReply
        }
        val level = when {
            parseResult.checksumFailures.isNotEmpty() || parseResult.bufferOverflowCount > 0 -> RaceLogLevel.ERR
            hasAlertPacket || parseResult.discardedGarbageBytes > 0 -> RaceLogLevel.WARN
            else -> RaceLogLevel.OK
        }

        writeRow(
            bw = bw,
            stateTimerMs = stateTimerMs,
            raceState = raceState,
            dir = "RX",
            usbIndex = usbIndex,
            laneNumber1Based = laneNumber1Based,
            length = rawBytes.size,
            level = level,
            decoded = notes.joinToString("; "),
            hex = rawBytes.toHexString()
        )
    }

    @Synchronized
    override fun logEvent(
        stateTimerMs: Long,
        raceState: RaceState,
        level: RaceLogLevel,
        usbIndex: Int?,
        laneNumber1Based: Int?,
        message: String
    ) {
        val bw = writer ?: return
        eventCount++
        writeRow(
            bw = bw,
            stateTimerMs = stateTimerMs,
            raceState = raceState,
            dir = "EVT",
            usbIndex = usbIndex,
            laneNumber1Based = laneNumber1Based,
            length = 0,
            level = level,
            decoded = message,
            hex = ""
        )
    }

    @Synchronized
    override fun endRaceSession(
        reason: String,
        finalLanes: List<LaneTelemetryState>,
        configuredLanesCount: Int
    ) {
        if (writer == null) return
        closeWriterInternal(reason, finalLanes, configuredLanesCount)
    }

    private fun closeWriterInternal(
        reason: String,
        finalLanes: List<LaneTelemetryState>,
        configuredLanesCount: Int
    ) {
        val bw = writer ?: return
        try {
            val endedZdt = ZonedDateTime.now()
            val durationMs = (endedZdt.toInstant().toEpochMilli() - sessionStartWallMs).coerceAtLeast(0L)
            bw.write("# ==============================================================================\n")
            bw.write("# RaceEndReason: $reason\n")
            bw.write("# EndedAt:       ${endedZdt.format(ISO_ZONED_FMT)}\n")
            bw.write("# DurationMs:    $durationMs\n")
            bw.write(
                "# Summary:       txPackets=$txPackets (${txBytes}B), txErrors=$txErrors, " +
                    "rxPackets=$rxPackets (${rxBytes}B, decodedFrames=$rxFramesDecoded), " +
                    "parseWarnings=$parseWarnings, events=$eventCount\n"
            )
            if (finalLanes.isNotEmpty() && configuredLanesCount > 0) {
                bw.write("# FinalLanes:\n")
                for (lane in finalLanes.take(configuredLanesCount)) {
                    if (!lane.active && !lane.connected && !lane.disqualified && !lane.isFinished) continue
                    bw.write(
                        String.format(
                            Locale.US,
                            "#   L%d: rank=%d, finish=%s (%.2fms), rawDist=%.2fm, interpDist=%.2fm, " +
                                "speed=%.2fkm/h, active=%s, conn=%s, dsq=%s, falseStarts=%d, box=%s\n",
                            lane.laneNumber1Based,
                            lane.rank,
                            lane.formattedFinishTime,
                            lane.finishTimeMs,
                            lane.rawDistanceMeters,
                            lane.interpolatedDistanceMeters,
                            lane.displayedSpeedKmh,
                            lane.active,
                            lane.connected,
                            lane.disqualified,
                            lane.falseStartCount,
                            lane.boxStatus.name
                        )
                    )
                }
            }
            bw.write("# ==============================================================================\n")
            bw.flush()
        } catch (_: Throwable) {
            // Never crash the race engine on disk logging errors
        } finally {
            closeQuietly()
        }
    }

    private fun writeRow(
        bw: BufferedWriter,
        stateTimerMs: Long,
        raceState: RaceState,
        dir: String,
        usbIndex: Int?,
        laneNumber1Based: Int?,
        length: Int,
        level: RaceLogLevel,
        decoded: String,
        hex: String
    ) {
        try {
            val nowMs = System.currentTimeMillis()
            val logElapsedMs = (nowMs - sessionStartWallMs).coerceAtLeast(0L)
            val isoTime = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).format(ROW_TIME_FMT)
            val usbCol = if (usbIndex != null) "usb$usbIndex" else "-"
            val laneCol = if (laneNumber1Based != null) "L$laneNumber1Based" else "-"
            val safeDecoded = sanitizeField(decoded)
            val line = String.format(
                Locale.US,
                "%s | %7d | %7d | %-17s | %-3s | %-4s | %-4s | %3d | %-4s | %s | %s\n",
                isoTime,
                logElapsedMs,
                stateTimerMs,
                raceState.name,
                dir,
                usbCol,
                laneCol,
                length,
                level.name,
                safeDecoded,
                hex
            )
            bw.write(line)
            bw.flush()
        } catch (_: Throwable) {
            // Never crash the race engine on disk logging errors
        }
    }

    private fun allocateUniqueLogFile(now: ZonedDateTime, raceNumber: Int, distanceMeters: Int): File {
        val stamp = now.format(FILE_STAMP_FMT)
        val baseName = "Race_${stamp}_No${raceNumber}_${distanceMeters}m"
        var candidate = File(logDir, "$baseName.log")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(logDir, "${baseName}_$suffix.log")
            suffix++
        }
        return candidate
    }

    private fun resetCounters() {
        txPackets = 0L
        txBytes = 0L
        txErrors = 0L
        rxPackets = 0L
        rxBytes = 0L
        rxFramesDecoded = 0L
        parseWarnings = 0L
        eventCount = 0L
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }

    companion object {
        private val FILE_STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH-mm-ss")
        private val ROW_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        private val ISO_ZONED_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        fun defaultLogDir(): File = File("logs")

        internal fun sanitizeField(value: String): String =
            value.replace("\r", " ").replace("\n", " ").replace("|", "/")
    }
}
