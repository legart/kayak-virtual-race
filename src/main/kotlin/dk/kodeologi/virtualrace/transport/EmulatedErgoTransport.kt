package dk.kodeologi.virtualrace.transport

import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.DansprintProtocol.toHexString
import dk.kodeologi.virtualrace.protocol.HostCommandOpcode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * AI / Player pacing profile for each emulated ergometer lane.
 */
enum class PaddlerPersona(
    val title: String,
    val baseCruiseKmh: Float,
    val sprintBurstKmh: Float,
    val strokeCadenceHz: Float,
    val description: String
) {
    BALANCED_SPRINTER("Balanced Sprinter", 16.4f, 19.2f, 2.1f, "Balanced sprint kayaker with strong finish"),
    MID_RACE_SURGER("Mid-Race Surger", 16.1f, 19.5f, 2.0f, "Smooth long strokes, surges mid-race"),
    HIGH_CADENCE("High Cadence", 15.8f, 18.8f, 2.2f, "High-cadence light glider"),
    FAST_STARTER("Fast Starter", 16.7f, 19.8f, 2.3f, "Explosive starter, fast cadence"),
    BURST_SPRINTER("Burst Sprinter", 15.5f, 20.1f, 2.5f, "Ultra-fast cadence with bursty speed"),
    POWER_PADDLER("Power Paddler", 16.9f, 18.9f, 1.85f, "High power per stroke"),
    SURGE_PACER("Surge Pacer", 16.0f, 19.4f, 1.95f, "Unpredictable mid-course surges"),
    STRONG_FINISHER("Strong Finisher", 16.5f, 19.3f, 1.9f, "Heavy water-moving powerhouse"),
    MANUAL_PLAYER("Manual (SPACE)", 0.0f, 22.0f, 2.4f, "Tap SPACEBAR to paddle yourself")
}

/**
 * State of a single emulated Dansprint kayak ergometer for UI inspection & interactive tweaking.
 */
data class EmulatedErgoUnitState(
    val usbIndex: Int,
    val pluggedIn: Boolean = true,
    val configuredHardwareLane1Based: Int = usbIndex + 1,
    val serialNumber: Long = 2060001L + usbIndex,
    val weightKg: Int = 72 + (usbIndex * 3) % 18,
    val persona: PaddlerPersona = PaddlerPersona.entries[usbIndex % 8],
    val ergoInternalMode: ErgoInternalMode = ErgoInternalMode.IDLE,
    val configuredRaceDistanceMeters: Float = 200.0f,
    val currentDistanceMeters: Float = 0.0f,
    val currentSpeedKmh: Float = 0.0f,
    val leaderDistanceFromHost: Float = 0.0f,
    val boostRemainingMs: Long = 0L,
    val spinOutRemainingMs: Long = 0L,
    val forcePreStartPaddling: Boolean = false
) {
    enum class ErgoInternalMode {
        IDLE,
        PRE_START_COUNTDOWN,
        RACING,
        FINISHED
    }
}

/**
 * Full wire-level emulator of up to 8 Dansprint USB HID kayak ergometers.
 *
 * Every emulated ergometer communicates with the host EXCLUSIVELY by parsing raw Host -> Ergometer
 * binary frames (`FF FF FF FB` and `FF FF FF FC` with 16-bit checksum verification) and queueing raw
 * 40-byte Ergometer -> Host USB HID input reports (`FF FF FF FB`, `FF FF FF FC`, `FF FF FF FE`, `"READ"`).
 */
class EmulatedErgoTransport : ErgoTransport {
    override val modeName: String = "Virtual Kayak Emulator (8 Virtual Ergometers)"
    override val isEmulated: Boolean = true

    private val _wirePacketLog = MutableStateFlow<List<WirePacketLogEntry>>(emptyList())
    override val wirePacketLog: StateFlow<List<WirePacketLogEntry>> = _wirePacketLog.asStateFlow()
    private val packetSeq = AtomicLong(0L)

    private val _units = MutableStateFlow(
        List(DansprintProtocol.MAX_LANES) { idx ->
            EmulatedErgoUnitState(
                usbIndex = idx,
                pluggedIn = idx < 6, // 6 ergometers plugged in & ready by default (lanes 1..6)
                configuredHardwareLane1Based = idx + 1,
                serialNumber = 2060101L + idx,
                weightKg = listOf(75, 68, 62, 70, 58, 88, 82, 85)[idx],
                persona = PaddlerPersona.entries[idx]
            )
        }
    )
    val units: StateFlow<List<EmulatedErgoUnitState>> = _units.asStateFlow()

    private val handleOpen = BooleanArray(DansprintProtocol.MAX_LANES)
    private val rxQueues = Array(DansprintProtocol.MAX_LANES) { ConcurrentLinkedQueue<ByteArray>() }

    // Internal simulation clocks per device
    private val ergoStartWallMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastTelemetryEmitMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastPreStartStrokeEmitMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastReadyType3EmitMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastPulseWallMs = LongArray(DansprintProtocol.MAX_LANES) { System.currentTimeMillis() - 2_000L }
    private val manualStrokeEnergyKmh = FloatArray(DansprintProtocol.MAX_LANES)
    private var lastSimTickMs: Long = System.currentTimeMillis()

    @Synchronized
    override fun enumerateAndOpenDevices(): List<DiscoveredErgoDevice> {
        val currentUnits = _units.value
        val discovered = mutableListOf<DiscoveredErgoDevice>()
        for (u in currentUnits) {
            rxQueues[u.usbIndex].clear()
            if (u.pluggedIn) {
                handleOpen[u.usbIndex] = true
                discovered.add(
                    DiscoveredErgoDevice(
                        usbIndex = u.usbIndex,
                        vendorId = DansprintProtocol.USB_VENDOR_ID,
                        productId = DansprintProtocol.USB_PRODUCT_ID,
                        manufacturer = DansprintProtocol.USB_MANUFACTURER,
                        product = DansprintProtocol.USB_PRODUCT,
                        serialNumber = u.serialNumber
                    )
                )
            } else {
                handleOpen[u.usbIndex] = false
            }
        }
        return discovered
    }

    @Synchronized
    override fun isDeviceOpen(usbIndex: Int): Boolean {
        return usbIndex in 0 until DansprintProtocol.MAX_LANES &&
            handleOpen[usbIndex] &&
            _units.value[usbIndex].pluggedIn
    }

    /**
     * Parses the actual binary packet sent by the host (verifying sync header and 16-bit checksum)
     * and triggers the corresponding ergometer firmware response!
     */
    @Synchronized
    override fun writePacket(usbIndex: Int, packet: ByteArray, description: String): Boolean {
        if (!isDeviceOpen(usbIndex)) return false
        appendLog(WirePacketLogEntry.Direction.HOST_TO_ERGO, usbIndex, packet, description)

        if (packet.size >= 4 &&
            packet[0] == 0xFF.toByte() &&
            packet[1] == 0xFF.toByte() &&
            packet[2] == 0xFF.toByte()
        ) {
            when (packet[3]) {
                0xFB.toByte() -> {
                    if (packet.size >= 4 + DansprintProtocol.TYPE1_PAYLOAD_LEN &&
                        DansprintProtocol.verifyChecksum(packet, 4, DansprintProtocol.TYPE1_PAYLOAD_LEN)
                    ) {
                        handleHostType1Command(usbIndex, packet, 4)
                    }
                }
                0xFC.toByte() -> {
                    if (packet.size >= 4 + DansprintProtocol.TYPE2_PAYLOAD_LEN &&
                        DansprintProtocol.verifyChecksum(packet, 4, DansprintProtocol.TYPE2_PAYLOAD_LEN)
                    ) {
                        handleHostType2LeaderBroadcast(usbIndex, packet, 4)
                    }
                }
            }
        }
        return true
    }

    @Synchronized
    override fun readNonBlocking(usbIndex: Int): ByteArray {
        if (!isDeviceOpen(usbIndex)) return ByteArray(0)
        stepSimulation()
        val report = rxQueues[usbIndex].poll() ?: return ByteArray(0)
        return report
    }

    @Synchronized
    override fun closeAll() {
        for (i in 0 until DansprintProtocol.MAX_LANES) {
            handleOpen[i] = false
            rxQueues[i].clear()
        }
    }

    override fun clearWireLog() {
        _wirePacketLog.value = emptyList()
    }

    // =========================================================================
    // Interactive Emulator Controls (for UI testing of all protocol scenarios)
    // =========================================================================

    @Synchronized
    fun setUnitPluggedIn(usbIndex: Int, pluggedIn: Boolean) {
        updateUnit(usbIndex) { it.copy(pluggedIn = pluggedIn) }
        handleOpen[usbIndex] = pluggedIn
        if (pluggedIn) {
            // Emit Type 3 Ready packet when newly activated
            val unit = _units.value[usbIndex]
            enqueueErgoPacket(
                usbIndex,
                DansprintProtocol.buildErgoType3ReadyPacket(unit.weightKg),
                "Ergo Ready Type3 (${unit.weightKg}kg)"
            )
        }
    }

    @Synchronized
    fun setConfiguredLane1Based(usbIndex: Int, lane1Based: Int) {
        updateUnit(usbIndex) { it.copy(configuredHardwareLane1Based = lane1Based.coerceIn(1, 8)) }
    }

    @Synchronized
    fun setUnitWeightKg(usbIndex: Int, weightKg: Int) {
        val clamped = weightKg.coerceIn(40, 130)
        updateUnit(usbIndex) { it.copy(weightKg = clamped) }
        if (isDeviceOpen(usbIndex)) {
            enqueueErgoPacket(
                usbIndex,
                DansprintProtocol.buildErgoType1Packet(opcode = 0x15, laneOrWeight = clamped),
                "Ergo Opcode 0x15 Weight Update (${clamped}kg)"
            )
        }
    }

    @Synchronized
    fun setUnitPersona(usbIndex: Int, persona: PaddlerPersona) {
        updateUnit(usbIndex) { it.copy(persona = persona) }
    }

    /**
     * Player manual stroke input (e.g., pressing SPACEBAR in the UI).
     * If any lane is set to [PaddlerPersona.MANUAL_PLAYER], or for Lane 1 if pressed during a race,
     * gives a realistic kayak paddle stroke impulse!
     */
    @Synchronized
    fun triggerManualPaddleStroke(targetUsbIndex: Int? = null) {
        val now = System.currentTimeMillis()
        val list = _units.value
        val indices = if (targetUsbIndex != null) {
            listOf(targetUsbIndex)
        } else {
            val manualIndices = list.filter { it.pluggedIn && it.persona == PaddlerPersona.MANUAL_PLAYER }.map { it.usbIndex }
            manualIndices.ifEmpty { listOf(0) }
        }

        for (idx in indices) {
            if (idx !in 0 until DansprintProtocol.MAX_LANES) continue
            lastPulseWallMs[idx] = now
            manualStrokeEnergyKmh[idx] = (manualStrokeEnergyKmh[idx] + 5.2f).coerceAtMost(23.5f)
            val u = _units.value[idx]
            if (u.ergoInternalMode == EmulatedErgoUnitState.ErgoInternalMode.PRE_START_COUNTDOWN) {
                // Paddling during pre-start countdown emits Opcode 0x04 with high flywheel metric -> triggers Stop Paddling wait or False Start!
                val periodTicks = DansprintProtocol.FLYWHEEL_METRIC_NUMERATOR / 240.0f
                enqueueErgoPacket(
                    idx,
                    DansprintProtocol.buildErgoType1Packet(
                        opcode = 0x04,
                        floatVal12 = periodTicks,
                        ticksSincePulse = 20_000L // 40 ms (< 300ms false start threshold!)
                    ),
                    "Manual Pre-Start Stroke Opcode 0x04 (Metric=240.0)"
                )
            }
        }
    }

    /**
     * Triggers a temporary turbo speed boost for a lane.
     */
    @Synchronized
    fun triggerTurboBoost(usbIndex: Int, durationMs: Long = 2_500L) {
        updateUnit(usbIndex) {
            it.copy(boostRemainingMs = max(it.boostRemainingMs, durationMs), spinOutRemainingMs = 0L)
        }
    }

    /**
     * Triggers a temporary spin-out slowdown for a lane.
     */
    @Synchronized
    fun triggerSpinOut(usbIndex: Int, durationMs: Long = 1_800L) {
        updateUnit(usbIndex) {
            it.copy(spinOutRemainingMs = max(it.spinOutRemainingMs, durationMs), boostRemainingMs = 0L)
        }
    }

    /**
     * Toggles continuous pre-start paddling on a lane (to test State 1 "Stop Paddling" hold or State 3 automatic False Start).
     */
    @Synchronized
    fun toggleForcePreStartPaddling(usbIndex: Int) {
        updateUnit(usbIndex) { it.copy(forcePreStartPaddling = !it.forcePreStartPaddling) }
    }

    /**
     * Simulates power being cycled on an ergometer by sending the 4-byte ASCII `"READ"` marker (`0x405d3f`).
     */
    @Synchronized
    fun injectPowerResetReadMarker(usbIndex: Int) {
        if (!isDeviceOpen(usbIndex)) return
        val raw = DansprintProtocol.SYNC_RESET_READ.copyOf()
        rxQueues[usbIndex].add(raw)
        appendLog(WirePacketLogEntry.Direction.ERGO_TO_HOST, usbIndex, raw, "Simulated Power Reset \"READ\"")
    }

    // =========================================================================
    // Internal Firmware Command Handling & Physics Simulation
    // =========================================================================

    private fun handleHostType1Command(usbIndex: Int, packet: ByteArray, payloadOffset: Int) {
        val opcodeByte = packet[payloadOffset + 22].toInt() and 0xFF
        val opcode = HostCommandOpcode.fromCode(opcodeByte)
        val bb = ByteBuffer.wrap(packet, payloadOffset, DansprintProtocol.TYPE1_PAYLOAD_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)
        val now = System.currentTimeMillis()
        val unit = _units.value[usbIndex]

        when (opcode) {
            HostCommandOpcode.CMD_INIT_CONNECT -> {
                // 0x0F: Initialize ergometer
                updateUnit(usbIndex) {
                    it.copy(
                        ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.IDLE,
                        currentDistanceMeters = 0f,
                        currentSpeedKmh = 0f
                    )
                }
            }

            HostCommandOpcode.CMD_POLL_STATUS -> {
                // 0x0E: Reply with Type 1 Opcode 0x0E containing 1-based Lane Number in p[8..9]
                val reply = DansprintProtocol.buildErgoType1Packet(
                    opcode = 0x0E,
                    laneOrWeight = unit.configuredHardwareLane1Based
                )
                enqueueErgoPacket(
                    usbIndex,
                    reply,
                    "Reply 0x0E (Lane=${unit.configuredHardwareLane1Based})"
                )
            }

            HostCommandOpcode.CMD_CLEAR_NEW_RACE -> {
                // 0x1A: Clear for new race, then announce readiness with Type 3 (FF FF FF FE)
                updateUnit(usbIndex) {
                    it.copy(
                        ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.IDLE,
                        currentDistanceMeters = 0f,
                        currentSpeedKmh = 0f,
                        boostRemainingMs = 0L,
                        spinOutRemainingMs = 0L
                    )
                }
                lastReadyType3EmitMs[usbIndex] = now
                enqueueErgoPacket(
                    usbIndex,
                    DansprintProtocol.buildErgoType3ReadyPacket(unit.weightKg),
                    "Reply Type3 Ready (${unit.weightKg}kg)"
                )
            }

            HostCommandOpcode.CMD_SET_DISTANCE -> {
                // 0x07: Store race distance from p[0..3] and acknowledge with Type 3 (FF FF FF FE)
                val distMeters = bb.getFloat(payloadOffset)
                updateUnit(usbIndex) {
                    it.copy(
                        configuredRaceDistanceMeters = if (distMeters > 0f) distMeters else 200f,
                        currentDistanceMeters = 0f,
                        currentSpeedKmh = 0f
                    )
                }
                enqueueErgoPacket(
                    usbIndex,
                    DansprintProtocol.buildErgoType3ReadyPacket(unit.weightKg),
                    "Ack Distance ${distMeters.toInt()}m via Type3 (${unit.weightKg}kg)"
                )
            }

            HostCommandOpcode.CMD_START_SEQ_PREP,
            HostCommandOpcode.CMD_PRE_START_LOCK,
            HostCommandOpcode.CMD_START_SEQ_ACTIVE,
            HostCommandOpcode.CMD_START_SEQ_SYNC -> {
                // 0x1D, 0x13, 0x1C, 0x10: Ergometer is in pre-start countdown sequence
                updateUnit(usbIndex) {
                    it.copy(
                        ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.PRE_START_COUNTDOWN,
                        currentDistanceMeters = 0f,
                        currentSpeedKmh = 0f
                    )
                }
            }

            HostCommandOpcode.CMD_RACE_START -> {
                // 0x0A: Race Start! Start ergometer 500 kHz race clock if not already running
                if (unit.ergoInternalMode != EmulatedErgoUnitState.ErgoInternalMode.RACING) {
                    ergoStartWallMs[usbIndex] = now
                    lastTelemetryEmitMs[usbIndex] = now
                    lastPulseWallMs[usbIndex] = now
                    updateUnit(usbIndex) {
                        it.copy(
                            ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.RACING,
                            currentDistanceMeters = 0f,
                            currentSpeedKmh = 0f
                        )
                    }
                }
            }

            HostCommandOpcode.CMD_STOP_ABORT_RACE,
            HostCommandOpcode.CMD_APP_SHUTDOWN,
            HostCommandOpcode.CMD_USB_DISCONNECT -> {
                // 0x12, 0x1B, 0x17: Stop race and return to idle
                updateUnit(usbIndex) {
                    it.copy(
                        ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.IDLE,
                        currentSpeedKmh = 0f,
                        boostRemainingMs = 0L,
                        spinOutRemainingMs = 0L
                    )
                }
            }

            else -> {}
        }
    }

    private fun handleHostType2LeaderBroadcast(usbIndex: Int, packet: ByteArray, payloadOffset: Int) {
        val bb = ByteBuffer.wrap(packet, payloadOffset, DansprintProtocol.TYPE2_PAYLOAD_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)
        val leaderDist = bb.getFloat(payloadOffset + 8)
        updateUnit(usbIndex) {
            it.copy(leaderDistanceFromHost = leaderDist)
        }
    }

    /**
     * Advances the physics and periodic telemetry packets of all 8 virtual ergometers.
     */
    private fun stepSimulation() {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastSimTickMs).coerceIn(0L, 250L)
        if (dtMs < 15L) return
        lastSimTickMs = now
        val dtSec = dtMs / 1000.0f

        val currentList = _units.value
        for (u in currentList) {
            val idx = u.usbIndex
            if (!u.pluggedIn || !handleOpen[idx]) continue

            // Decay manual stroke energy
            manualStrokeEnergyKmh[idx] = (manualStrokeEnergyKmh[idx] - dtSec * 6.5f).coerceAtLeast(0f)

            when (u.ergoInternalMode) {
                EmulatedErgoUnitState.ErgoInternalMode.IDLE -> {
                    // In Idle state, periodically emit Type 3 Ready packet every 1.5s so the host activates the lane
                    if (now - lastReadyType3EmitMs[idx] >= 1_500L) {
                        lastReadyType3EmitMs[idx] = now
                        enqueueErgoPacket(
                            idx,
                            DansprintProtocol.buildErgoType3ReadyPacket(u.weightKg),
                            "Idle Ready Heartbeat Type3 (${u.weightKg}kg)"
                        )
                    }
                }

                EmulatedErgoUnitState.ErgoInternalMode.PRE_START_COUNTDOWN -> {
                    // Every 200ms during pre-start, send Type 1 Opcode 0x04 (Stroke / Flywheel telemetry)
                    if (now - lastPreStartStrokeEmitMs[idx] >= 200L) {
                        lastPreStartStrokeEmitMs[idx] = now
                        val isPaddling = u.forcePreStartPaddling || manualStrokeEnergyKmh[idx] > 2.0f
                        val metric = if (isPaddling) 215.0f else 0.0f
                        val periodTicks = if (metric > 0f) DansprintProtocol.FLYWHEEL_METRIC_NUMERATOR / metric else 0.0f
                        val pulseAgeMs = if (isPaddling) 80L else 2_500L
                        val pulseAgeTicks = pulseAgeMs * DansprintProtocol.ERGO_TICKS_PER_MS

                        enqueueErgoPacket(
                            idx,
                            DansprintProtocol.buildErgoType1Packet(
                                opcode = 0x04,
                                floatVal12 = periodTicks,
                                ticksSincePulse = pulseAgeTicks
                            ),
                            if (isPaddling) "Pre-Start Paddling! Opcode 0x04" else "Pre-Start Stillness Opcode 0x04"
                        )
                    }
                }

                EmulatedErgoUnitState.ErgoInternalMode.RACING -> {
                    val elapsedRaceMs = (now - ergoStartWallMs[idx]).coerceAtLeast(1L)
                    val elapsedSec = elapsedRaceMs / 1000.0f
                    val progressRatio = (u.currentDistanceMeters / u.configuredRaceDistanceMeters.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)

                    // Calculate target speed based on Persona + stroke rhythm + rubber-banding + items
                    val persona = u.persona
                    val baseSpeedKmh = if (persona == PaddlerPersona.MANUAL_PLAYER) {
                        manualStrokeEnergyKmh[idx]
                    } else {
                        val strokeWave = sin(elapsedSec * persona.strokeCadenceHz * 2.0 * Math.PI).toFloat() * 0.85f
                        val startSprint = if (elapsedSec < 4.5f) (elapsedSec / 4.5f) * persona.sprintBurstKmh else persona.baseCruiseKmh
                        val finishKick = if (progressRatio > 0.78f) (persona.sprintBurstKmh - persona.baseCruiseKmh) * 0.65f else 0f
                        // Slight arcade rubber-banding relative to leader distance broadcast by host!
                        val behindLeader = (u.leaderDistanceFromHost - u.currentDistanceMeters).coerceIn(-10f, 25f)
                        val catchupBonus = if (behindLeader > 2f) min(1.4f, behindLeader * 0.08f) else 0f
                        val harmonicJitter = cos((elapsedSec * 0.9f + idx * 1.7f).toDouble()).toFloat() * 0.45f
                        (startSprint + finishKick + strokeWave + catchupBonus + harmonicJitter).coerceAtLeast(4.0f)
                    }

                    val newBoostMs = (u.boostRemainingMs - dtMs).coerceAtLeast(0L)
                    val newSpinMs = (u.spinOutRemainingMs - dtMs).coerceAtLeast(0L)

                    val effectiveSpeedKmh = when {
                        newSpinMs > 0L -> 3.2f
                        newBoostMs > 0L -> baseSpeedKmh + 6.5f
                        else -> baseSpeedKmh
                    }.coerceAtLeast(0f)

                    val speedMps = effectiveSpeedKmh / DansprintProtocol.KMH_PER_MPS
                    val newDist = (u.currentDistanceMeters + speedMps * dtSec)
                        .coerceAtMost(u.configuredRaceDistanceMeters)

                    updateUnit(idx) {
                        it.copy(
                            currentDistanceMeters = newDist,
                            currentSpeedKmh = effectiveSpeedKmh,
                            boostRemainingMs = newBoostMs,
                            spinOutRemainingMs = newSpinMs
                        )
                    }

                    // Emit Type 2 (FF FF FF FC) telemetry every ~120ms when distance changes
                    if (now - lastTelemetryEmitMs[idx] >= 120L) {
                        lastTelemetryEmitMs[idx] = now
                        val ergoTicks = elapsedRaceMs * DansprintProtocol.ERGO_TICKS_PER_MS
                        val speedMPerTick = DansprintProtocol.speedKmhToMetersPerTick(effectiveSpeedKmh)
                        enqueueErgoPacket(
                            idx,
                            DansprintProtocol.buildType2TelemetryPacket(
                                ergoTicks = ergoTicks,
                                speedMetersPerTick = speedMPerTick,
                                distanceMeters = newDist
                            ),
                            "Type2[FC] Lane ${u.configuredHardwareLane1Based}: %.1fm @ %.1fkm/h".format(
                                newDist,
                                effectiveSpeedKmh
                            )
                        )
                    }

                    // Check if kayak crossed the finish line!
                    if (newDist >= u.configuredRaceDistanceMeters) {
                        val finishTimeMs = elapsedRaceMs.toFloat() + Random.nextFloat() * 9.0f
                        updateUnit(idx) {
                            it.copy(
                                ergoInternalMode = EmulatedErgoUnitState.ErgoInternalMode.FINISHED,
                                currentDistanceMeters = u.configuredRaceDistanceMeters,
                                currentSpeedKmh = 0f,
                                boostRemainingMs = 0L,
                                spinOutRemainingMs = 0L
                            )
                        }
                        // Send official finish time packet: Type 1 Opcode 0x07 with float(finishTimeMs) at p[12..15]
                        enqueueErgoPacket(
                            idx,
                            DansprintProtocol.buildErgoType1Packet(
                                opcode = 0x07,
                                floatVal12 = finishTimeMs
                            ),
                            "FINISHED! Opcode 0x07 Time=${DansprintProtocol.formatRaceTimeMs(finishTimeMs)}"
                        )
                    }
                }

                EmulatedErgoUnitState.ErgoInternalMode.FINISHED -> {
                    // Idle after finish until reset
                }
            }
        }
    }

    private fun enqueueErgoPacket(usbIndex: Int, framedPacket: ByteArray, description: String) {
        // Pad to 40-byte USB HID input report just like physical Dansprint hardware
        val hidReport = DansprintProtocol.padToHidInputReport(framedPacket)
        rxQueues[usbIndex].add(hidReport)

        // Avoid flooding the UI log with high-frequency Type 2 packets unless the log is short or it's a control packet
        val isHighFreqType2 = framedPacket.size >= 4 && framedPacket[3] == 0xFC.toByte()
        if (!isHighFreqType2 || packetSeq.get() % 6L == 0L) {
            appendLog(WirePacketLogEntry.Direction.ERGO_TO_HOST, usbIndex, framedPacket, description)
        } else {
            packetSeq.incrementAndGet()
        }
    }

    private fun updateUnit(usbIndex: Int, transform: (EmulatedErgoUnitState) -> EmulatedErgoUnitState) {
        if (usbIndex !in 0 until DansprintProtocol.MAX_LANES) return
        val mutable = _units.value.toMutableList()
        mutable[usbIndex] = transform(mutable[usbIndex])
        _units.value = mutable
    }

    private fun appendLog(
        dir: WirePacketLogEntry.Direction,
        usbIndex: Int,
        bytes: ByteArray,
        description: String
    ) {
        val entry = WirePacketLogEntry(
            id = packetSeq.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            direction = dir,
            deviceOrLaneIndex = usbIndex,
            rawBytes = bytes,
            hexPreview = bytes.toHexString(29),
            description = description
        )
        val current = _wirePacketLog.value
        _wirePacketLog.value = (current + entry).takeLast(120)
    }
}
