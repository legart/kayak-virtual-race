package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.audio.SoundPlayer
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.ErgoStreamParser
import dk.kodeologi.virtualrace.protocol.HostCommandOpcode
import dk.kodeologi.virtualrace.protocol.IncomingErgoPacket
import dk.kodeologi.virtualrace.transport.EmulatedErgoTransport
import dk.kodeologi.virtualrace.transport.ErgoTransport
import dk.kodeologi.virtualrace.transport.UsbHidErgoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Exact 10 states of the Dansprint Virtual Race state machine (`0x485d70`).
 */
enum class RaceState(
    val code: Int,
    val title: String,
    val bannerText: String,
    val trafficLightStage: Int // 0 = off/idle, 1 = red (stop/pos), 2 = yellow (ready), 3 = green (go!), 4 = hazard (false start)
) {
    IDLE_PRE_RACE(0, "Pre-Race Lobby", "READY FOR NEW RACE", 0),
    STOP_PADDLING(1, "Stop Paddling", "STOP PADDLING!", 1),
    START_POSITION(2, "Start Position", "TAKE START POSITION!", 1),
    READY_COUNTDOWN(3, "Start Within 10s", "READY... HOLD STILL!", 2),
    RACE_START_HOLD(4, "Race Start!", "GO!! GO!! GO!!", 3),
    FALSE_START(5, "False Start", "FALSE START! RETURN TO LINE!", 4),
    RACING(6, "Racing", "SPRINT TO THE FINISH!", 3),
    POST_FINISH(7, "Finishing", "ALL BOATS FINISHED!", 3),
    RACE_COMPLETE(8, "Race Results", "OFFICIAL RACE RESULTS!", 0),
    STARTUP_DISCOVERY(9, "Connecting Ergometers", "SCANNING USB LANES...", 0)
}

/**
 * Dansprint Box Status indicator colors (matching `0x4063e0` `Box_status1..8` TShape colors).
 */
enum class BoxStatusColor(val label: String) {
    RED_DISCONNECTED("Disconnected / No Ergo"),
    GREEN_CONNECTED("Connected & Mapped"),
    BLUE_DUPLICATE_LANE("Duplicate Lane Number Conflict!"),
    PURPLE_POWER_REAPPLIED("Power Reapplied (\"READ\") - Reconnect!"),
    YELLOW_HANDLE_CLOSED("USB Handle Closed")
}

/**
 * Immutable snapshot of a single race lane (`0..7`, displayed as Lane `1..8`).
 */
data class LaneTelemetryState(
    val laneIndex: Int, // 0..7
    val laneNumber1Based: Int = laneIndex + 1,
    val connected: Boolean = false,
    val active: Boolean = false,
    val readyAck: Boolean = false,
    val powerReapplied: Boolean = false,
    val boxStatus: BoxStatusColor = BoxStatusColor.RED_DISCONNECTED,
    val weightKg: Int = 75,
    val interpolatedDistanceMeters: Float = 0.0f,
    val rawDistanceMeters: Float = 0.0f,
    val displayedSpeedKmh: Float = 0.0f,
    val speedMetersPerTick: Float = 0.0f,
    val ergoTicks: Long = 0L,
    val flywheelMetric: Float = 0.0f,
    val msSincePulse: Long = 9_999L,
    val falseStartFlag: Boolean = false,
    val falseStartCount: Int = 0,
    val disqualified: Boolean = false,
    val finishTimeMs: Float = 0.0f,
    val rank: Int = 0, // 0 = unranked, 1..8 = place, 999 = Disqualified (0x3E7)
    val livePlace: Int = laneIndex + 1,
    val isBoosting: Boolean = false,
    val boostIntensity: Float = 0.0f
) {
    val isFinished: Boolean get() = finishTimeMs > 0.0f
    val formattedFinishTime: String
        get() = when {
            disqualified || rank == 999 -> "DSQ"
            finishTimeMs > 0.0f -> DansprintProtocol.formatRaceTimeMs(finishTimeMs)
            else -> "--:--.--"
        }

    /**
     * Whether this lane should currently be shown in the race track & status UI:
     * dynamically visible when its ergometer is connected (or reporting a hardware alert),
     * or while actively participating / finished / disqualified in a race.
     */
    fun isVisibleInRaceUi(raceState: RaceState): Boolean {
        if (connected) return true
        if (boxStatus == BoxStatusColor.BLUE_DUPLICATE_LANE ||
            boxStatus == BoxStatusColor.PURPLE_POWER_REAPPLIED
        ) {
            return true
        }
        val inRaceOrResults = raceState != RaceState.IDLE_PRE_RACE &&
            raceState != RaceState.STARTUP_DISCOVERY
        return inRaceOrResults && (active || disqualified || isFinished)
    }
}

/**
 * Complete UI snapshot of the Race Engine.
 */
data class RaceUiState(
    val usingEmulator: Boolean = false,
    val transportModeName: String = "",
    val raceState: dk.kodeologi.virtualrace.engine.RaceState = RaceState.IDLE_PRE_RACE,
    val raceNumber: Int = 1,
    val configuredLanesCount: Int = 6,
    val raceDistanceMeters: Int = 200,
    val viewCourseSpanMeters: Float = 200.0f,
    val viewScaleFactor: Float = 1.0f,
    val isMapped: Boolean = false,
    val usbTestActive: Boolean = false,
    val displayWeight: Boolean = true,
    val elapsedRaceSeconds: Long = 0L,
    val elapsedTimerMs: Long = 0L,
    val leaderLaneIndex: Int = 0,
    val maxBoatDistanceMeters: Float = 0.0f,
    val lanes: List<dk.kodeologi.virtualrace.engine.LaneTelemetryState> = List(DansprintProtocol.MAX_LANES) {
        LaneTelemetryState(
            it
        )
    },
    val alertDialogMessage: String? = null,
    val statusToastMessage: String = "Welcome to Kayak Virtual Race!"
) {
    /**
     * Returns only the configured lanes whose ergometers are currently connected (or active in-race),
     * so disconnected lanes dynamically hide and re-appear on connect/disconnect.
     */
    fun visibleLanes(): List<dk.kodeologi.virtualrace.engine.LaneTelemetryState> =
        lanes.take(configuredLanesCount.coerceIn(1, DansprintProtocol.MAX_LANES))
            .filter { it.isVisibleInRaceUi(raceState) }
}

/**
 * Core engine implementing `Control`, `Com_thread`, `Communication`, and `Calc_race` from
 * `DansprintVirtualRaceUSB.exe`.
 */
class RaceEngine(
    val usbTransport: UsbHidErgoTransport = UsbHidErgoTransport(),
    val emulatedTransport: EmulatedErgoTransport = EmulatedErgoTransport(),
    private val settingsStore: RaceSettingsStore? = null,
    defaultUseEmulator: Boolean = false
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val initialConfiguredLanesCount: Int =
        settingsStore?.loadConfiguredLanesCount()?.coerceIn(1, DansprintProtocol.MAX_LANES)
            ?: DEFAULT_CONFIGURED_LANES_COUNT

    var activeTransport: ErgoTransport = if (defaultUseEmulator) emulatedTransport else usbTransport
        private set

    private val _uiState = MutableStateFlow(
        RaceUiState(
            usingEmulator = defaultUseEmulator,
            transportModeName = activeTransport.modeName,
            configuredLanesCount = initialConfiguredLanesCount
        )
    )
    val uiState: StateFlow<RaceUiState> = _uiState.asStateFlow()

    init {
        for (i in 0 until DansprintProtocol.MAX_LANES) {
            emulatedTransport.setUnitPluggedIn(i, i < initialConfiguredLanesCount)
        }
    }

    // Per-device 200-byte stream parsers (`0x4883d4`)
    private val streamParsers = Array(DansprintProtocol.MAX_LANES) { ErgoStreamParser() }

    // Two-phase USB <-> Lane mapping tables (`0x486a5b`, `0x486a63`, `0x486a7b`)
    private var isMapped: Boolean = false
    private val laneToUsb = IntArray(DansprintProtocol.MAX_LANES) { it }
    private val usbToLane = IntArray(DansprintProtocol.MAX_LANES) { it }
    private val discoveredLaneByUsb = IntArray(DansprintProtocol.MAX_LANES) { -1 }
    private var currentDiscoveryUsbIdx: Int = 0

    // Per-lane internal mutable state (`0x485aac` .. `0x485d79`)
    private val speedFifo = Array(DansprintProtocol.MAX_LANES) { FloatArray(4) }
    private val speedFifoCount = IntArray(DansprintProtocol.MAX_LANES)
    private val boostTrackers = Array(DansprintProtocol.MAX_LANES) { DynamicBoostTracker() }
    private val prevType2Distance = FloatArray(DansprintProtocol.MAX_LANES) { -1f }
    private val lastType2RxMs = LongArray(DansprintProtocol.MAX_LANES)
    private val prevType2RxMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastRxWallMs = LongArray(DansprintProtocol.MAX_LANES)
    private val lastDistance0x07SentMs = LongArray(DansprintProtocol.MAX_LANES)
    private val laneRaceStartOffsetMs = LongArray(DansprintProtocol.MAX_LANES)

    // State machine timers (`0x488a1c`, `0x4882d8` .. `0x4882fa`)
    private var timerOriginWallMs: Long = System.currentTimeMillis()
    private var state1SentInitial0x07: Boolean = false
    private var state1LastSyncMs: Long = 0L
    private var state1StillnessCount: Int = 0
    private var state2LastSyncMs: Long = 0L
    private var state3RandDelayMs: Long = 1_000L
    private var state3Sent500ms0x07: Boolean = false
    private var state3LastSyncMs: Long = 0L
    private var state3StartSignalSubstate: Int = 0
    private var state5LastSyncMs: Long = 0L
    private var lastLeaderBroadcastMs: Long = 0L
    private var leaderBroadcastRoundRobinLane: Int = 0
    private var leaderFinishedBroadcastTicks: Int = 0
    private var postConnectSend0x1AAtMs: Long = 0L
    private var currentPollDeviceIdx: Int = 0

    private var engineJob: Job? = null

    fun start() {
        if (engineJob != null) return
        resetTimer()
        connectAndDiscoverErgometers(initialStartup = true)

        engineJob = scope.launch {
            var lastBoxStatusCheckMs = System.currentTimeMillis()
            var lastUsbTestPollMs = System.currentTimeMillis()
            var lastHotplugScanMs = System.currentTimeMillis()

            while (isActive) {
                val nowWall = System.currentTimeMillis()

                // 1. Poll incoming bytes from all 8 devices (TRead thread 0x405770)
                pollAllIncomingStreams()

                // 2. Run main race state machine tick (Timer1Timer -> 0x404748 -> 0x40480c)
                stepRaceStateMachine()

                // 3. Every 500ms: Run Box_status_timerTimer (0x401d64 / 0x4063e0)
                if (nowWall - lastBoxStatusCheckMs >= 500L) {
                    lastBoxStatusCheckMs = nowWall
                    updateBoxStatusAndMapping()
                }

                // 4. Every 1000ms in Physical USB mode: Detect hotplug plug-in / unplug
                if (!_uiState.value.usingEmulator && nowWall - lastHotplugScanMs >= 1_000L) {
                    lastHotplugScanMs = nowWall
                    when (usbTransport.pollHotplugChanges()) {
                        UsbHidErgoTransport.HotplugCheckResult.NEW_DEVICES_DETECTED -> {
                            val st = _uiState.value.raceState
                            if (st == RaceState.IDLE_PRE_RACE ||
                                st == RaceState.STARTUP_DISCOVERY ||
                                st == RaceState.RACE_COMPLETE
                            ) {
                                connectAndDiscoverErgometers(initialStartup = false)
                            }
                        }
                        UsbHidErgoTransport.HotplugCheckResult.DEVICES_REMOVED -> {
                            updateBoxStatusAndMapping()
                        }
                        UsbHidErgoTransport.HotplugCheckResult.NO_CHANGE -> {}
                    }
                }

                // 5. Optional USB Test polling every 100ms (Timer2Timer 0x4028d0)
                if (_uiState.value.usbTestActive && nowWall - lastUsbTestPollMs >= 100L) {
                    lastUsbTestPollMs = nowWall
                    sendType1ToSingle(currentPollDeviceIdx, HostCommandOpcode.CMD_POLL_STATUS)
                    currentPollDeviceIdx = (currentPollDeviceIdx + 1) % _uiState.value.configuredLanesCount
                }

                delay(25L) // 40 Hz main loop
            }
        }
    }

    fun stop() {
        // Matches FormDestroy (0x401c2c): send 0x12, 0x1B, then 0x17 and close handles
        broadcastToConnectedLanes(HostCommandOpcode.CMD_STOP_ABORT_RACE)
        broadcastToActiveLanes(HostCommandOpcode.CMD_APP_SHUTDOWN)
        disconnectErgometers()
        engineJob?.cancel()
        engineJob = null
    }

    /**
     * Switches at runtime between the built-in Virtual Kayak Emulator and Physical USB HID Ergometers.
     */
    @Synchronized
    fun setUseEmulator(useEmulator: Boolean) {
        if (_uiState.value.usingEmulator == useEmulator) return
        abortAndResetRaceInternal()
        disconnectErgometers()

        activeTransport = if (useEmulator) emulatedTransport else usbTransport
        if (useEmulator) {
            val configuredCount = _uiState.value.configuredLanesCount
            for (i in 0 until DansprintProtocol.MAX_LANES) {
                emulatedTransport.setUnitPluggedIn(i, i < configuredCount)
            }
        }
        _uiState.value = _uiState.value.copy(
            usingEmulator = useEmulator,
            transportModeName = activeTransport.modeName,
            statusToastMessage = if (useEmulator) {
                "Switched to Emulator Mode (8 Virtual Dansprint Ergometers active!)"
            } else {
                "Switched to Physical USB HID Mode (Scanning VID=0x16C0 PID=0x1503...)"
            }
        )
        connectAndDiscoverErgometers(initialStartup = false)
    }

    /**
     * Options -> Connect (`0x4025f4` / `0x40bfa8`):
     * Enumerates USB devices, resets mapping to unmapped mode (`isMapped = false`), and sends `0x0F` + `0x0E`.
     */
    @Synchronized
    fun connectAndDiscoverErgometers(initialStartup: Boolean = false) {
        isMapped = false
        postConnectSend0x1AAtMs = 0L
        for (i in 0 until DansprintProtocol.MAX_LANES) {
            laneToUsb[i] = i
            usbToLane[i] = i
            discoveredLaneByUsb[i] = -1
            streamParsers[i].reset()
        }

        val discovered = activeTransport.enumerateAndOpenDevices()
        val nextState = if (initialStartup) RaceState.STARTUP_DISCOVERY else RaceState.IDLE_PRE_RACE

        updateLanes { idx, lane ->
            lane.copy(
                connected = false,
                active = false,
                readyAck = false,
                powerReapplied = false,
                boxStatus = if (activeTransport.isDeviceOpen(idx)) {
                    BoxStatusColor.GREEN_CONNECTED
                } else {
                    BoxStatusColor.RED_DISCONNECTED
                }
            )
        }

        val openCount = (0 until DansprintProtocol.MAX_LANES).count { activeTransport.isDeviceOpen(it) }
        val statusMsg = when {
            !_uiState.value.usingEmulator && (openCount == 0 || openCount < discovered.size) ->
                usbTransport.lastStatusMessage
            discovered.isEmpty() ->
                "No USB ergometers found. Toggle Emulator Mode to race with virtual kayaks!"
            else ->
                "Opened $openCount ergometer(s). Discovering lane numbers (0x0F -> 0x0E)..."
        }
        val permissionAlert = if (!_uiState.value.usingEmulator) usbTransport.lastPermissionError else null

        _uiState.value = _uiState.value.copy(
            raceState = nextState,
            isMapped = false,
            alertDialogMessage = permissionAlert,
            statusToastMessage = statusMsg
        )

        // Send 0x0F (Init) and 0x0E (Query Lane) to each opened USB device (matching 0x405814)
        initAllDevicesHandshake()
        updateBoxStatusAndMapping()
    }

    /**
     * Disconnects all USB devices (`0x40dd60`), sending `0x17` (`CMD_USB_DISCONNECT`) before closing.
     */
    @Synchronized
    fun disconnectErgometers() {
        for (u in 0 until DansprintProtocol.MAX_LANES) {
            if (activeTransport.isDeviceOpen(u)) {
                val pkt = DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_USB_DISCONNECT)
                activeTransport.writePacket(u, pkt, "CMD_USB_DISCONNECT (0x17)")
            }
        }
        activeTransport.closeAll()
    }

    // =========================================================================
    // User / Race Control Actions (Controls Menu & Keyboard Shortcuts)
    // =========================================================================

    /**
     * Controls -> New Race (`Ctrl+Alt+C`, `0x40c6e8` -> `0x404748(0)`):
     * If previous race finished (State 8), increments race number. Resets all lane telemetry and sends `0x12` & `0x1A`.
     */
    @Synchronized
    fun newRace() {
        val current = _uiState.value
        val nextRaceNo = if (current.raceState == RaceState.RACE_COMPLETE) {
            current.raceNumber + 1
        } else {
            current.raceNumber
        }

        // Clear all active flags and reset race state (0x4052d8 -> 0x4052f8)
        updateLanes { idx, lane ->
            speedFifoCount[idx] = 0
            prevType2Distance[idx] = -1f
            boostTrackers[idx].reset()
            lane.copy(
                active = (current.usingEmulator && lane.connected),
                readyAck = false,
                interpolatedDistanceMeters = 0f,
                rawDistanceMeters = 0f,
                displayedSpeedKmh = 0f,
                speedMetersPerTick = 0f,
                ergoTicks = 0L,
                falseStartFlag = false,
                falseStartCount = 0,
                disqualified = false,
                finishTimeMs = 0f,
                rank = 0,
                livePlace = idx + 1,
                isBoosting = false,
                boostIntensity = 0f
            )
        }

        recalculateCourseViewZoom(current.raceDistanceMeters)
        broadcastToConnectedLanes(HostCommandOpcode.CMD_STOP_ABORT_RACE) // 0x12
        broadcastToConnectedLanes(HostCommandOpcode.CMD_CLEAR_NEW_RACE)  // 0x1A

        _uiState.value = _uiState.value.copy(
            raceState = RaceState.IDLE_PRE_RACE,
            raceNumber = nextRaceNo,
            elapsedRaceSeconds = 0L,
            elapsedTimerMs = 0L,
            maxBoatDistanceMeters = 0f,
            alertDialogMessage = null,
            statusToastMessage = "Race #$nextRaceNo ready (${current.raceDistanceMeters}m)!"
        )
    }

    /**
     * Controls -> Start Race (`Ctrl+Alt+Z`, `0x40c808`):
     * Validates connection state, sends `0x1D`, transitions to State 1 (`STOP_PADDLING`), plays `STOP_PAD`,
     * and sends `0x13` and `0x1C` to active lanes.
     */
    @Synchronized
    fun startRace() {
        val current = _uiState.value
        val activeLanes = current.lanes.take(current.configuredLanesCount).filter { it.active }

        // 1. Check if any active lane had power reapplied ("READ") (0x40c826)
        if (activeLanes.any { it.powerReapplied }) {
            _uiState.value = current.copy(
                alertDialogMessage = "Power has been reapplied on one or more ergo computers. Please connect computer again in Options -> Connect..."
            )
            return
        }

        // 2. Check duplicate lane numbers / unmapped state (0x40c87a)
        if (!isMapped) {
            _uiState.value = current.copy(
                alertDialogMessage = "Two or more ergometers have the same lane number (or USB discovery is still in progress)!"
            )
            return
        }

        // 3. Check at least one active paddler (0x40c8bc)
        if (activeLanes.isEmpty()) {
            _uiState.value = current.copy(
                alertDialogMessage = "No active paddlers! Activate lanes with Shift+1..8 or click a lane card."
            )
            return
        }

        // 4. Execute exact StartRace sequence (0x40c8fe - 0x40c924)
        broadcastToConnectedLanes(HostCommandOpcode.CMD_START_SEQ_PREP) // 0x1D
        resetTimer()
        state1SentInitial0x07 = false
        state1LastSyncMs = 0L
        state1StillnessCount = 0

        _uiState.value = current.copy(
            raceState = RaceState.STOP_PADDLING,
            alertDialogMessage = null,
            statusToastMessage = "START SEQUENCE: Stop paddling and wait for start position!"
        )

        SoundPlayer.playCue(SoundPlayer.RaceCue.STOP_PADDLING)
        SoundPlayer.playArcadeTone(440.0, 140)

        broadcastToActiveLanes(HostCommandOpcode.CMD_PRE_START_LOCK)   // 0x13
        broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_ACTIVE) // 0x1C
    }

    /**
     * Controls -> Restart / Abort Race (`Ctrl+Alt+K`, `0x40c938` -> `0x4052f8`):
     * Sends `0x12` (`CMD_STOP_ABORT_RACE`), clears distances/speeds/false starts, and returns to State 0 (`IDLE_PRE_RACE`).
     */
    @Synchronized
    fun restartOrAbortRace() {
        abortAndResetRaceInternal()
        _uiState.value = _uiState.value.copy(
            statusToastMessage = "Race aborted and reset to Pre-Race Lobby."
        )
    }

    /**
     * Controls -> False Start (`Ctrl+Alt+N`, `0x40c9a8` -> `0x405560`):
     * Manually triggers State 5 (`FALSE_START`) and plays `FALSE_START.wav`.
     */
    @Synchronized
    fun triggerManualFalseStart() {
        val current = _uiState.value
        if (current.raceState !in listOf(
                RaceState.STOP_PADDLING,
                RaceState.START_POSITION,
                RaceState.READY_COUNTDOWN,
                RaceState.RACE_START_HOLD,
                RaceState.RACING
            )
        ) {
            return
        }
        resetTimer()
        state5LastSyncMs = 0L

        updateLanes { idx, lane ->
            speedFifoCount[idx] = 0
            boostTrackers[idx].reset()
            lane.copy(
                interpolatedDistanceMeters = 0f,
                rawDistanceMeters = 0f,
                displayedSpeedKmh = 0f,
                speedMetersPerTick = 0f,
                finishTimeMs = 0f,
                rank = 0,
                isBoosting = false,
                boostIntensity = 0f
            )
        }

        _uiState.value = _uiState.value.copy(
            raceState = RaceState.FALSE_START,
            maxBoatDistanceMeters = 0f,
            statusToastMessage = "FALSE START CALLED! Return to start line (State 5)..."
        )
        SoundPlayer.playCue(SoundPlayer.RaceCue.FALSE_START)
    }

    /**
     * Controls -> End Race Early (`Ctrl+Alt+M`, `0x40c940`):
     * If at least one active lane has finished (`finishTimeMs > 0`), finalizes rankings, saves results,
     * sends `0x12`, and enters State 8 (`RACE_COMPLETE`).
     */
    @Synchronized
    fun finishRaceEarly() {
        val current = _uiState.value
        val anyFinished = current.lanes.take(current.configuredLanesCount).any { it.active && it.finishTimeMs > 0f }
        if (!anyFinished && current.raceState != RaceState.RACING) return

        // Even if forced during racing before anyone crossed, rank by current distance or finish time
        calculateFinalRankings()
        saveRaceResultsToDisk()
        broadcastToConnectedLanes(HostCommandOpcode.CMD_STOP_ABORT_RACE) // 0x12
        resetTimer()

        _uiState.value = _uiState.value.copy(
            raceState = RaceState.RACE_COMPLETE,
            statusToastMessage = "Race #${current.raceNumber} ended! Results recorded."
        )
        SoundPlayer.playVictoryFanfare()
    }

    /**
     * Toggles whether a lane (`0..7`) is active in State 0 (`Shift+1..8` activates, `Ctrl+1..8` deactivates and sends `0x1A`).
     */
    @Synchronized
    fun setLaneActive(laneIndex: Int, active: Boolean) {
        if (laneIndex !in 0 until DansprintProtocol.MAX_LANES) return
        val current = _uiState.value
        if (current.raceState != RaceState.IDLE_PRE_RACE && current.raceState != RaceState.STARTUP_DISCOVERY) return

        val lane = current.lanes[laneIndex]
        if (active && !lane.connected) return

        updateSingleLane(laneIndex) { it.copy(active = active) }
        if (!active) {
            sendType1ToSingle(laneIndex, HostCommandOpcode.CMD_CLEAR_NEW_RACE) // 0x40c6c5 sends 0x1A
        } else {
            sendSetDistanceToSingle(laneIndex)
        }
    }

    @Synchronized
    fun setRaceDistanceMeters(distanceMeters: Int) {
        val clamped = distanceMeters.coerceIn(50, 42_000)
        recalculateCourseViewZoom(clamped)
        _uiState.value = _uiState.value.copy(
            raceDistanceMeters = clamped,
            statusToastMessage = "Race distance set to ${clamped}m (sent Opcode 0x07 to ergometers)"
        )
        // Matches DistanceEditExit / 0x401d00: send 0x07 to all connected lanes
        val distFloat = clamped.toFloat()
        val pkt = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_SET_DISTANCE,
            raceDistanceMeters = distFloat
        )
        val lanes = _uiState.value.lanes
        for (i in 0 until _uiState.value.configuredLanesCount) {
            if (lanes[i].connected) {
                writeToLogicalLane(i, pkt, "CMD_SET_DISTANCE (${clamped}m)")
            }
        }
    }

    @Synchronized
    fun setConfiguredLanesCount(count: Int) {
        val clamped = count.coerceIn(1, DansprintProtocol.MAX_LANES)
        settingsStore?.saveConfiguredLanesCount(clamped)
        _uiState.value = _uiState.value.copy(
            configuredLanesCount = clamped,
            statusToastMessage = "Configured for $clamped race ${if (clamped == 1) "lane" else "lanes"}."
        )
        if (_uiState.value.usingEmulator) {
            for (i in 0 until DansprintProtocol.MAX_LANES) {
                emulatedTransport.setUnitPluggedIn(i, i < clamped)
            }
        }
        connectAndDiscoverErgometers(initialStartup = false)
    }

    @Synchronized
    fun setRaceNumber(raceNo: Int) {
        _uiState.value = _uiState.value.copy(raceNumber = raceNo.coerceIn(1, 9999))
    }

    @Synchronized
    fun zoomInCourseView() {
        // Matches 0x404772 (Calc_race case 2): halves viewCourseSpanMeters down to min 13m
        val curSpan = _uiState.value.viewCourseSpanMeters
        if (curSpan >= 13.0f) {
            _uiState.value = _uiState.value.copy(
                viewScaleFactor = _uiState.value.viewScaleFactor * 2.0f,
                viewCourseSpanMeters = curSpan * 0.5f
            )
        }
    }

    @Synchronized
    fun zoomOutCourseView() {
        // Matches 0x4047ad (Calc_race case 3): doubles viewCourseSpanMeters up to raceDistanceMeters
        val curSpan = _uiState.value.viewCourseSpanMeters
        val maxSpan = max(_uiState.value.raceDistanceMeters.toFloat(), 100.0f)
        if (curSpan < maxSpan && curSpan < 3200.0f) {
            _uiState.value = _uiState.value.copy(
                viewScaleFactor = _uiState.value.viewScaleFactor * 0.5f,
                viewCourseSpanMeters = curSpan * 2.0f
            )
        }
    }

    @Synchronized
    fun toggleDisplayWeight() {
        _uiState.value = _uiState.value.copy(displayWeight = !_uiState.value.displayWeight)
    }

    @Synchronized
    fun toggleUsbTestMode() {
        val next = !_uiState.value.usbTestActive
        _uiState.value = _uiState.value.copy(
            usbTestActive = next,
            statusToastMessage = if (next) "USB Test Mode started (polling 0x0E every 100ms)" else "USB Test Mode stopped"
        )
    }

    @Synchronized
    fun dismissAlertDialog() {
        _uiState.value = _uiState.value.copy(alertDialogMessage = null)
    }

    // =========================================================================
    // Internal State Machine & Packet Processing (`Calc_race` & `Communication`)
    // =========================================================================

    private fun abortAndResetRaceInternal() {
        broadcastToConnectedLanes(HostCommandOpcode.CMD_STOP_ABORT_RACE) // 0x12
        recalculateCourseViewZoom(_uiState.value.raceDistanceMeters)

        updateLanes { idx, lane ->
            speedFifoCount[idx] = 0
            prevType2Distance[idx] = -1f
            boostTrackers[idx].reset()
            lane.copy(
                interpolatedDistanceMeters = 0f,
                rawDistanceMeters = 0f,
                displayedSpeedKmh = 0f,
                speedMetersPerTick = 0f,
                ergoTicks = 0L,
                falseStartFlag = false,
                falseStartCount = 0,
                disqualified = false,
                finishTimeMs = 0f,
                rank = 0,
                livePlace = idx + 1,
                isBoosting = false,
                boostIntensity = 0f
            )
        }

        _uiState.value = _uiState.value.copy(
            raceState = RaceState.IDLE_PRE_RACE,
            elapsedRaceSeconds = 0L,
            elapsedTimerMs = 0L,
            maxBoatDistanceMeters = 0f
        )
    }

    private fun recalculateCourseViewZoom(raceDistanceMeters: Int) {
        // Matches 0x405315 - 0x405348: starts from 100m, halves until <= raceDistanceMeters
        var scale = 1.0f
        var span = 100.0f
        while (raceDistanceMeters.toFloat() < span && span > 12.5f) {
            scale *= 2.0f
            span *= 0.5f
        }
        // Show full race course by default or zoomed window
        _uiState.value = _uiState.value.copy(
            viewScaleFactor = scale,
            viewCourseSpanMeters = raceDistanceMeters.toFloat().coerceAtLeast(50f)
        )
    }

    private fun initAllDevicesHandshake() {
        // Matches 0x405814: for each index 0..7, if handle open, send 0x0F then 0x0E and poll
        for (i in 0 until DansprintProtocol.MAX_LANES) {
            val usbIdx = if (isMapped) laneToUsb[i] else i
            if (activeTransport.isDeviceOpen(usbIdx)) {
                currentDiscoveryUsbIdx = i
                sendType1ToSingle(i, HostCommandOpcode.CMD_INIT_CONNECT)
                sendType1ToSingle(i, HostCommandOpcode.CMD_POLL_STATUS)
                pollIncomingForDevice(i)
            }
        }
    }

    private fun pollAllIncomingStreams() {
        for (usbIdx in 0 until DansprintProtocol.MAX_LANES) {
            if (activeTransport.isDeviceOpen(usbIdx)) {
                pollIncomingForDevice(usbIdx)
            }
        }
    }

    private fun pollIncomingForDevice(usbIdx: Int) {
        // Drain up to 8 queued HID input reports per 25ms tick so bursts never lag behind
        for (reads in 0 until 8) {
            val bytes = activeTransport.readNonBlocking(usbIdx)
            if (bytes.isEmpty()) return

            // Translate USB device index -> Logical Lane index when mapped (0x405c3c)
            val laneIdx = if (isMapped) usbToLane[usbIdx].coerceIn(0, 7) else usbIdx
            val packets = streamParsers[laneIdx].feedBytes(bytes)
            val nowMs = elapsedTimerMs()

            for (pkt in packets) {
                lastRxWallMs[laneIdx] = System.currentTimeMillis()
                handleIncomingPacket(usbIdx, laneIdx, pkt, nowMs)
            }
        }
    }

    private fun handleIncomingPacket(
        usbIdx: Int,
        laneIdx: Int,
        pkt: IncomingErgoPacket,
        nowTimerMs: Long
    ) {
        val state = _uiState.value.raceState

        when (pkt) {
            is IncomingErgoPacket.PowerReappliedRead -> {
                // 0x405d3f: "READ" received -> power was cycled on ergometer
                updateSingleLane(laneIdx) {
                    it.copy(
                        powerReapplied = true,
                        connected = false,
                        active = false,
                        boxStatus = BoxStatusColor.PURPLE_POWER_REAPPLIED
                    )
                }
            }

            is IncomingErgoPacket.ConnectResetReply -> {
                // 0x405a6f: Opcode 0x0F reply
                updateSingleLane(laneIdx) {
                    it.copy(
                        powerReapplied = true,
                        connected = false,
                        active = false
                    )
                }
            }

            is IncomingErgoPacket.LaneStatusReply -> {
                // 0x405b5d: Opcode 0x0E reply with 1-based Lane Number in p[8..9]
                if (isMapped && state in listOf(
                        RaceState.STARTUP_DISCOVERY,
                        RaceState.IDLE_PRE_RACE,
                        RaceState.STOP_PADDLING,
                        RaceState.RACE_COMPLETE
                    )
                ) {
                    updateSingleLane(laneIdx) {
                        it.copy(connected = true, powerReapplied = false)
                    }
                }
                if (state == RaceState.IDLE_PRE_RACE || state == RaceState.STARTUP_DISCOVERY) {
                    val reportedLaneIdx = (pkt.laneNumber1Based - 1).coerceIn(0, 7)
                    // 0x40d910: Map USB device -> reported lane
                    laneToUsb[reportedLaneIdx] = usbIdx
                    usbToLane[usbIdx] = reportedLaneIdx
                    discoveredLaneByUsb[usbIdx] = reportedLaneIdx
                }
            }

            is IncomingErgoPacket.StrokeActivity -> {
                // 0x405a94: Opcode 0x04 flywheel & pulse age telemetry
                updateSingleLane(laneIdx) {
                    it.copy(
                        flywheelMetric = pkt.flywheelMetric,
                        msSincePulse = pkt.msSincePulse
                    )
                }
            }

            is IncomingErgoPacket.FinishOrDistanceAck -> {
                // 0x405b00: Opcode 0x07
                if (state == RaceState.STOP_PADDLING || state == RaceState.START_POSITION) {
                    sendSetDistanceToSingle(laneIdx)
                }
                if (state == RaceState.RACING) {
                    val raceDist = _uiState.value.raceDistanceMeters.toFloat()
                    speedFifoCount[laneIdx] = 0
                    boostTrackers[laneIdx].reset()
                    updateSingleLane(laneIdx) {
                        it.copy(
                            finishTimeMs = pkt.finishTimeMs,
                            rawDistanceMeters = raceDist,
                            interpolatedDistanceMeters = raceDist,
                            speedMetersPerTick = 0f,
                            displayedSpeedKmh = 0f,
                            isBoosting = false,
                            boostIntensity = 0f
                        )
                    }
                    SoundPlayer.playArcadeTone(880.0, 90)
                }
            }

            is IncomingErgoPacket.WeightReport -> {
                // 0x405ba8 / 0x405bb9: Opcodes 0x14 & 0x15
                updateSingleLane(laneIdx) {
                    it.copy(weightKg = pkt.weightKg)
                }
            }

            is IncomingErgoPacket.ReadyActiveType3 -> {
                // 0x405ec7: Type 3 (FF FF FF FE) Ready / Distance-Ack / Active
                if (isMapped) {
                    if (state == RaceState.IDLE_PRE_RACE || state == RaceState.STARTUP_DISCOVERY) {
                        val currentLane = _uiState.value.lanes[laneIdx]
                        if (!currentLane.active || nowTimerMs - lastDistance0x07SentMs[laneIdx] > 1_000L) {
                            sendSetDistanceToSingle(laneIdx)
                        }
                        updateSingleLane(laneIdx) {
                            it.copy(
                                active = true,
                                connected = true,
                                weightKg = if (pkt.weightKg > 0) pkt.weightKg else it.weightKg
                            )
                        }
                    }
                    updateSingleLane(laneIdx) {
                        it.copy(
                            rawDistanceMeters = 0f,
                            speedMetersPerTick = 0f,
                            readyAck = true
                        )
                    }
                }
            }

            is IncomingErgoPacket.RaceTelemetryType2 -> {
                // 0x405d6e: Type 2 (FF FF FF FC) Real-Time Race Telemetry
                if (state == RaceState.RACE_START_HOLD || state == RaceState.RACING) {
                    if (pkt.distanceMeters != prevType2Distance[laneIdx]) {
                        prevType2Distance[laneIdx] = pkt.distanceMeters
                        prevType2RxMs[laneIdx] = lastType2RxMs[laneIdx]
                        lastType2RxMs[laneIdx] = nowTimerMs

                        val avgSpeedKmh = updateMovingAverageSpeedKmh(laneIdx, pkt.speedMetersPerTick)
                        val boost = boostTrackers[laneIdx].onSpeedSample(nowTimerMs, avgSpeedKmh, pkt.distanceMeters)
                        updateSingleLane(laneIdx) {
                            if (it.isFinished) {
                                it
                            } else {
                                it.copy(
                                    ergoTicks = pkt.ergoTicks,
                                    speedMetersPerTick = pkt.speedMetersPerTick,
                                    rawDistanceMeters = pkt.distanceMeters,
                                    displayedSpeedKmh = avgSpeedKmh,
                                    isBoosting = boost.active,
                                    boostIntensity = boost.intensity
                                )
                            }
                        }
                    }
                }
            }

            is IncomingErgoPacket.UnknownType1 -> {}
        }
    }

    /**
     * Implements `0x405444`: Converts `speed_m_per_tick` to `km/h` (`* 500000.0 * 3.6`) and filters
     * through a 4-sample moving average FIFO.
     */
    private fun updateMovingAverageSpeedKmh(laneIdx: Int, speedMetersPerTick: Float): Float {
        val instantKmh = DansprintProtocol.speedMetersPerTickToKmh(speedMetersPerTick)
        if (instantKmh <= 0.0f) {
            speedFifoCount[laneIdx] = 0
            return 0.0f
        }
        val fifo = speedFifo[laneIdx]
        val count = min(4, speedFifoCount[laneIdx] + 1)
        speedFifoCount[laneIdx] = count
        // Shift samples and append
        for (i in 0 until count - 1) {
            fifo[i] = fifo[i + 1]
        }
        fifo[count - 1] = instantKmh
        var sum = 0.0f
        for (i in 0 until count) {
            sum += fifo[i]
        }
        return sum / count.toFloat()
    }

    /**
     * Implements `0x4063e0` (`UpdateBoxStatusAndMapping`):
     * Validates discovered lane numbers, sets Box Status LEDs, and transitions from unmapped (`isMapped = false`)
     * to mapped (`isMapped = true`) mode once no duplicate lane numbers exist.
     */
    private fun updateBoxStatusAndMapping() {
        val current = _uiState.value
        var noDuplicates = true
        var anySingleMatched = false

        for (laneIdx in 0 until DansprintProtocol.MAX_LANES) {
            val matchCount = (0 until DansprintProtocol.MAX_LANES).count { u ->
                activeTransport.isDeviceOpen(u) && discoveredLaneByUsb[u] == laneIdx
            }

            if (isMapped) {
                if (matchCount > 1) {
                    // Duplicate lane conflict detected! Reset to unmapped (0x40643e)
                    isMapped = false
                    for (i in 0 until DansprintProtocol.MAX_LANES) {
                        laneToUsb[i] = i
                        usbToLane[i] = i
                    }
                    initAllDevicesHandshake()
                    return
                }

                val usbIdx = laneToUsb[laneIdx]
                val handleOpen = activeTransport.isDeviceOpen(usbIdx)
                val laneState = current.lanes[laneIdx]
                val color = when {
                    matchCount == 0 -> BoxStatusColor.RED_DISCONNECTED
                    !handleOpen -> BoxStatusColor.YELLOW_HANDLE_CLOSED
                    laneState.powerReapplied -> BoxStatusColor.PURPLE_POWER_REAPPLIED
                    else -> BoxStatusColor.GREEN_CONNECTED
                }
                val isConn = matchCount == 1 && handleOpen && !laneState.powerReapplied
                val inPreRaceLobby = current.raceState == RaceState.IDLE_PRE_RACE ||
                    current.raceState == RaceState.STARTUP_DISCOVERY
                updateSingleLane(laneIdx) {
                    it.copy(
                        connected = isConn,
                        active = if (!isConn && inPreRaceLobby) false else it.active,
                        boxStatus = color
                    )
                }
            } else {
                val color = when {
                    matchCount > 1 -> {
                        noDuplicates = false
                        BoxStatusColor.BLUE_DUPLICATE_LANE
                    }
                    matchCount == 1 -> {
                        anySingleMatched = true
                        BoxStatusColor.GREEN_CONNECTED
                    }
                    else -> BoxStatusColor.RED_DISCONNECTED
                }
                updateSingleLane(laneIdx) {
                    it.copy(
                        connected = matchCount == 1,
                        active = (matchCount == 1 && current.usingEmulator),
                        boxStatus = color
                    )
                }
            }
        }

        // Re-poll any opened physical USB device that has not reported its lane number yet
        if (current.raceState == RaceState.IDLE_PRE_RACE || current.raceState == RaceState.STARTUP_DISCOVERY) {
            for (u in 0 until DansprintProtocol.MAX_LANES) {
                if (activeTransport.isDeviceOpen(u) && discoveredLaneByUsb[u] < 0) {
                    val pkt = DansprintProtocol.buildHostType1Command(HostCommandOpcode.CMD_POLL_STATUS)
                    activeTransport.writePacket(u, pkt, "CMD_POLL_STATUS (discovery retry)")
                }
            }
        }

        // Transition from unmapped -> mapped when valid (0x406674)
        if (!isMapped && noDuplicates && anySingleMatched) {
            isMapped = true
            initAllDevicesHandshake()
            postConnectSend0x1AAtMs = elapsedTimerMs() + 800L
            _uiState.value = _uiState.value.copy(
                isMapped = true,
                raceState = if (_uiState.value.raceState == RaceState.STARTUP_DISCOVERY) {
                    RaceState.IDLE_PRE_RACE
                } else {
                    _uiState.value.raceState
                },
                statusToastMessage = "All ergometer lanes mapped & ready!"
            )
        }

        // 800ms after mapping completes, broadcast 0x1A (CMD_CLEAR_NEW_RACE) (0x4066f6 - 0x406728)
        if (postConnectSend0x1AAtMs > 0L && elapsedTimerMs() > postConnectSend0x1AAtMs) {
            postConnectSend0x1AAtMs = 0L
            broadcastToConnectedLanes(HostCommandOpcode.CMD_CLEAR_NEW_RACE)
        }
    }

    /**
     * Main Race State Machine dispatcher (`0x40480c` in `Calc_race`).
     */
    private fun stepRaceStateMachine() {
        val current = _uiState.value
        val nowMs = elapsedTimerMs()
        val numLanes = current.configuredLanesCount

        when (current.raceState) {
            RaceState.IDLE_PRE_RACE,
            RaceState.STARTUP_DISCOVERY,
            RaceState.RACE_COMPLETE -> {
                // Idle / Lobby / Results view
            }

            RaceState.STOP_PADDLING -> {
                // State 1 (`0x4049c8`): Wait 1s -> send 0x07; after 3s -> every 500ms send 0x10 and check stillness
                if (nowMs < 1_000L) {
                    state1SentInitial0x07 = false
                    return
                }
                if (!state1SentInitial0x07) {
                    broadcastSetDistanceToActiveLanes()
                    state1SentInitial0x07 = true
                }
                if (nowMs < 3_000L) {
                    state1LastSyncMs = 0L
                    return
                }
                if (nowMs >= state1LastSyncMs + 500L) {
                    state1LastSyncMs = nowMs
                    broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_SYNC) // 0x10

                    val lanes = _uiState.value.lanes.take(numLanes)
                    var anyMoving = false
                    for (lane in lanes) {
                        if (!lane.active) continue
                        if (lane.rawDistanceMeters == current.raceDistanceMeters.toFloat()) {
                            sendSetDistanceToSingle(lane.laneIndex)
                            state1StillnessCount = 0
                            anyMoving = true
                            break
                        }
                        if (lane.flywheelMetric > DansprintProtocol.STILLNESS_FLYWHEEL_THRESHOLD &&
                            lane.msSincePulse < DansprintProtocol.STILLNESS_PULSE_AGE_MS
                        ) {
                            state1StillnessCount = 0
                            anyMoving = true
                            break
                        }
                    }

                    if (!anyMoving) {
                        state1StillnessCount++
                        if (state1StillnessCount > 2) {
                            // Transition State 1 -> State 2 (`START_POSITION`) (0x404ae4)
                            state1StillnessCount = 0
                            state2LastSyncMs = 0L
                            resetTimer()
                            _uiState.value = _uiState.value.copy(
                                raceState = RaceState.START_POSITION,
                                statusToastMessage = "TAKE YOUR START POSITION! Hold paddle ready..."
                            )
                            SoundPlayer.playCue(SoundPlayer.RaceCue.START_POSITION)
                            SoundPlayer.playArcadeTone(523.25, 160)
                            broadcastSetDistanceToActiveLanes()
                        }
                    }
                }
            }

            RaceState.START_POSITION -> {
                // State 2 (`0x404b14`): Every 500ms after 1s send 0x10; after 3s check all active lanes have msSincePulse >= 500
                if (nowMs < 1_000L) {
                    state2LastSyncMs = 0L
                    return
                }
                if (nowMs >= state2LastSyncMs + 500L) {
                    state2LastSyncMs = nowMs
                    broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_SYNC) // 0x10
                }
                if (nowMs <= 3_000L) return

                val activeLanes = _uiState.value.lanes.take(numLanes).filter { it.active }
                if (activeLanes.any { it.msSincePulse < DansprintProtocol.STILLNESS_PULSE_AGE_MS }) {
                    return
                }

                // Transition State 2 -> State 3 (`READY_COUNTDOWN`) (0x404ba3 - 0x404bf6)
                state3RandDelayMs = Random.nextLong(0L, 2_000L) // rand() % 2000
                state3StartSignalSubstate = 0
                state3Sent500ms0x07 = false
                state3LastSyncMs = 0L

                updateLanes { _, lane -> lane.copy(readyAck = false) }
                resetTimer()
                _uiState.value = _uiState.value.copy(
                    raceState = RaceState.READY_COUNTDOWN,
                    statusToastMessage = "READY... Start within 10 seconds! (Watch for False Starts!)"
                )
                SoundPlayer.playCue(SoundPlayer.RaceCue.START_WITHIN)
                SoundPlayer.playArcadeTone(659.25, 180)
                broadcastSetDistanceToActiveLanes()
            }

            RaceState.READY_COUNTDOWN -> {
                // State 3 (`0x404c04`): Armed for False Start & Random Start Signal Gate
                if (nowMs < 500L && state3StartSignalSubstate == 0) {
                    state3LastSyncMs = 0L
                    state3Sent500ms0x07 = false
                    return
                }
                if (!state3Sent500ms0x07) {
                    state3Sent500ms0x07 = true
                    for (lane in _uiState.value.lanes.take(numLanes)) {
                        if (lane.active && !lane.readyAck) {
                            sendSetDistanceToSingle(lane.laneIndex)
                        }
                    }
                }
                if (nowMs < 800L && state3StartSignalSubstate == 0) {
                    state3LastSyncMs = 0L
                    return
                }

                // False Start Check (`0x404dfc`)
                if (checkAndTriggerFalseStart(numLanes)) {
                    return
                }

                // Race Start Trigger (`0x404cc6 - 0x404d5d`)
                if (state3StartSignalSubstate > 0) {
                    if (state3StartSignalSubstate == 1) {
                        state3StartSignalSubstate = 2
                    } else {
                        // Fire Race Start signal and enter State 4!
                        SoundPlayer.playCue(SoundPlayer.RaceCue.RACE_START)
                        _uiState.value = _uiState.value.copy(
                            raceState = RaceState.RACE_START_HOLD,
                            statusToastMessage = "GO!! GO!! GO!! Sprint to ${current.raceDistanceMeters}m!"
                        )
                        broadcastRaceStart0x0A()
                        state3StartSignalSubstate = 0
                        return
                    }
                }

                if (nowMs >= state3RandDelayMs + 1_800L && state3StartSignalSubstate == 0) {
                    broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_SYNC) // 0x10
                    // Capture per-lane time offset (`0x404dc4`)
                    for (i in 0 until numLanes) {
                        if (_uiState.value.lanes[i].active) {
                            laneRaceStartOffsetMs[i] = (nowMs - lastDistance0x07SentMs[i]).coerceAtLeast(0L)
                        }
                    }
                    resetTimer()
                    broadcastRaceStart0x0A()
                    state3StartSignalSubstate = 2
                    return
                }

                if (nowMs >= state3LastSyncMs + 100L && state3StartSignalSubstate == 0) {
                    state3LastSyncMs = nowMs
                    broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_SYNC) // 0x10 every 100ms
                }
            }

            RaceState.RACE_START_HOLD -> {
                // State 4 (`0x404f90`): Hold for 1500ms after race start signal, then enter State 6 (`RACING`)
                updateRacingInterpolation(nowMs, numLanes)
                if (nowMs > 1_500L) {
                    _uiState.value = _uiState.value.copy(
                        raceState = RaceState.RACING,
                        elapsedTimerMs = nowMs,
                        elapsedRaceSeconds = nowMs / 1_000L
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        elapsedTimerMs = nowMs,
                        elapsedRaceSeconds = nowMs / 1_000L
                    )
                }
            }

            RaceState.FALSE_START -> {
                // State 5 (`0x404e64`): False start recall & 2-strike disqualification
                if (nowMs < 4_000L) {
                    state5LastSyncMs = 0L
                    return
                }
                if (nowMs >= state5LastSyncMs + 500L) {
                    state5LastSyncMs = nowMs
                    broadcastToActiveLanes(HostCommandOpcode.CMD_START_SEQ_SYNC) // 0x10
                }
                if (nowMs <= 6_000L) return

                broadcastSetDistanceToActiveLanes()
                var nextState = RaceState.START_POSITION

                updateLanes { _, lane ->
                    if (!lane.active) {
                        lane.copy(falseStartFlag = false)
                    } else if (lane.falseStartCount > 1) {
                        // 2nd false start -> Disqualified! (`0x404ef4`: active = 0, rank = 999 / 0x3E7)
                        lane.copy(
                            active = false,
                            disqualified = true,
                            rank = 999,
                            falseStartFlag = false
                        )
                    } else {
                        if (lane.flywheelMetric > DansprintProtocol.STILLNESS_FLYWHEEL_THRESHOLD &&
                            lane.msSincePulse < DansprintProtocol.STILLNESS_PULSE_AGE_MS
                        ) {
                            nextState = RaceState.STOP_PADDLING
                        }
                        lane.copy(falseStartFlag = false)
                    }
                }

                val remainingActive = _uiState.value.lanes.take(numLanes).any { it.active }
                if (!remainingActive) {
                    abortAndResetRaceInternal()
                    _uiState.value = _uiState.value.copy(
                        statusToastMessage = "All active lanes disqualified after 2 false starts."
                    )
                    return
                }

                resetTimer()
                _uiState.value = _uiState.value.copy(
                    raceState = nextState,
                    statusToastMessage = if (nextState == RaceState.STOP_PADDLING) {
                        "Restarting sequence: Stop paddling!"
                    } else {
                        "Restarting sequence: Take your start position!"
                    }
                )
                if (nextState == RaceState.STOP_PADDLING) {
                    SoundPlayer.playCue(SoundPlayer.RaceCue.STOP_PADDLING)
                } else {
                    SoundPlayer.playCue(SoundPlayer.RaceCue.START_POSITION)
                }
            }

            RaceState.RACING -> {
                // State 6 (`0x404867` -> `0x404898` & `0x404fc0`)
                updateRacingInterpolation(nowMs, numLanes)
                broadcastLeaderProgressIfDue(nowMs, numLanes)

                // Check if all active lanes have reported an official finish time (`0x404fc0`)
                val activeLanes = _uiState.value.lanes.take(numLanes).filter { it.active }
                if (activeLanes.isNotEmpty() && activeLanes.all { it.finishTimeMs > 0.0f }) {
                    calculateFinalRankings()
                    saveRaceResultsToDisk()
                    resetTimer()
                    _uiState.value = _uiState.value.copy(
                        raceState = RaceState.POST_FINISH,
                        statusToastMessage = "ALL KAYAKS FINISHED! Computing final standings..."
                    )
                    SoundPlayer.playVictoryFanfare()
                }
            }

            RaceState.POST_FINISH -> {
                // State 7 (`0x405018`): Continue leader broadcast for 2000ms, then send 0x12 and enter State 8
                broadcastLeaderProgressIfDue(nowMs, numLanes)
                if (nowMs > 2_000L) {
                    broadcastToConnectedLanes(HostCommandOpcode.CMD_STOP_ABORT_RACE) // 0x12
                    _uiState.value = _uiState.value.copy(
                        raceState = RaceState.RACE_COMPLETE,
                        statusToastMessage = "Race #${current.raceNumber} Complete! Press New Race for the next heat."
                    )
                }
            }
        }
    }

    /**
     * Checks if any active lane jumped the start in State 3 (`0x404dfc`):
     * `msSincePulse < 300 && flywheelMetric > 50.0f`.
     */
    private fun checkAndTriggerFalseStart(numLanes: Int): Boolean {
        val currentLanes = _uiState.value.lanes
        var offenderLane1Based: Int? = null

        val updated = currentLanes.mapIndexed { idx, lane ->
            if (idx < numLanes &&
                lane.active &&
                lane.msSincePulse < DansprintProtocol.FALSE_START_PULSE_AGE_MS &&
                lane.flywheelMetric > DansprintProtocol.FALSE_START_FLYWHEEL_THRESHOLD
            ) {
                offenderLane1Based = lane.laneNumber1Based
                lane.copy(
                    falseStartCount = lane.falseStartCount + 1,
                    falseStartFlag = true
                )
            } else {
                lane
            }
        }

        if (offenderLane1Based != null) {
            resetTimer()
            state5LastSyncMs = 0L
            _uiState.value = _uiState.value.copy(
                raceState = RaceState.FALSE_START,
                lanes = updated,
                statusToastMessage = "FALSE START ON LANE $offenderLane1Based!"
            )
            SoundPlayer.playCue(SoundPlayer.RaceCue.FALSE_START)
            return true
        }
        return false
    }

    /**
     * Implements `0x404898`, `0x40504c`:
     * Dead-reckoning distance interpolation between Type 2 packets, 2-second stale speed timeout,
     * live position ordering, and leader tracking.
     */
    private fun updateRacingInterpolation(nowMs: Long, numLanes: Int) {
        val raceDist = _uiState.value.raceDistanceMeters.toFloat()
        var maxDist = _uiState.value.maxBoatDistanceMeters

        val interpolatedLanes = _uiState.value.lanes.mapIndexed { idx, lane ->
            if (idx >= numLanes || !lane.active) {
                lane
            } else if (lane.isFinished) {
                maxDist = max(maxDist, raceDist)
                boostTrackers[idx].reset()
                lane.copy(
                    interpolatedDistanceMeters = raceDist,
                    displayedSpeedKmh = 0f,
                    isBoosting = false,
                    boostIntensity = 0f
                )
            } else {
                // 2000ms stale telemetry timeout (`0x4048f0`)
                val timeSinceLastRx = nowMs - lastType2RxMs[idx]
                val packetInterval = (lastType2RxMs[idx] - prevType2RxMs[idx]).coerceAtLeast(50L)
                val staleSpeed = timeSinceLastRx > packetInterval * 4L && timeSinceLastRx > 2_000L

                val effectiveSpeedMPerTick = if (staleSpeed) 0f else lane.speedMetersPerTick
                val effectiveDisplayedKmh = if (staleSpeed) {
                    speedFifoCount[idx] = 0
                    boostTrackers[idx].reset()
                    0f
                } else {
                    lane.displayedSpeedKmh
                }
                val boost = if (staleSpeed) {
                    DynamicBoostTracker.BoostSnapshot(false, 0f)
                } else {
                    boostTrackers[idx].evaluateAt(nowMs, effectiveDisplayedKmh)
                }

                // Dead-reckoning interpolation (`0x404924`)
                val hostTicks = (nowMs - laneRaceStartOffsetMs[idx]).coerceAtLeast(0L) * DansprintProtocol.ERGO_TICKS_PER_MS
                val deltaTicks = (hostTicks - lane.ergoTicks).coerceAtLeast(0L)
                val interpDist = if (deltaTicks < 1_500_000L) {
                    lane.rawDistanceMeters + deltaTicks.toFloat() * effectiveSpeedMPerTick
                } else {
                    lane.rawDistanceMeters
                }.coerceIn(0f, raceDist)

                maxDist = max(maxDist, interpDist)
                lane.copy(
                    interpolatedDistanceMeters = interpDist,
                    speedMetersPerTick = effectiveSpeedMPerTick,
                    displayedSpeedKmh = effectiveDisplayedKmh,
                    isBoosting = boost.active,
                    boostIntensity = boost.intensity
                )
            }
        }

        // Compute live race places (1st..8th) based on finish time (if finished) or interpolated distance
        val activeIndices = interpolatedLanes.take(numLanes).filter { it.active }.sortedWith(
            compareByDescending<LaneTelemetryState> { it.isFinished }
                .thenBy { if (it.isFinished) it.finishTimeMs else Float.MAX_VALUE }
                .thenByDescending { it.interpolatedDistanceMeters }
        )
        val placeMap = activeIndices.mapIndexed { rankIdx, l -> l.laneIndex to (rankIdx + 1) }.toMap()
        val leaderIdx = activeIndices.firstOrNull()?.laneIndex ?: _uiState.value.leaderLaneIndex

        val withLivePlaces = interpolatedLanes.map { l ->
            l.copy(livePlace = placeMap[l.laneIndex] ?: l.laneNumber1Based)
        }

        _uiState.value = _uiState.value.copy(
            lanes = withLivePlaces,
            leaderLaneIndex = leaderIdx,
            maxBoatDistanceMeters = maxDist,
            elapsedTimerMs = nowMs,
            elapsedRaceSeconds = nowMs / 1_000L
        )
    }

    /**
     * Implements `0x4050a8` & `0x406380`:
     * Every 1000ms during State 6 and State 7, broadcasts the leading paddler's Type 2 (`FF FF FF FC`)
     * telemetry packet to active lanes.
     */
    private fun broadcastLeaderProgressIfDue(nowMs: Long, numLanes: Int) {
        if (nowMs < 1_000L) {
            lastLeaderBroadcastMs = 0L
            leaderBroadcastRoundRobinLane = 0
            leaderFinishedBroadcastTicks = 0
            return
        }
        if (nowMs < lastLeaderBroadcastMs + 1_000L) return

        val current = _uiState.value
        val leaderLane = current.lanes.getOrNull(current.leaderLaneIndex) ?: return
        if (leaderLane.finishTimeMs > 0f) {
            leaderFinishedBroadcastTicks++
        }
        if (leaderFinishedBroadcastTicks > 16) return

        val targetLaneIdx = leaderBroadcastRoundRobinLane.coerceIn(0, numLanes - 1)
        val targetLane = current.lanes[targetLaneIdx]
        if (targetLane.active) {
            val pkt = DansprintProtocol.buildType2TelemetryPacket(
                ergoTicks = leaderLane.ergoTicks,
                speedMetersPerTick = leaderLane.speedMetersPerTick,
                distanceMeters = leaderLane.rawDistanceMeters
            )
            writeToLogicalLane(
                targetLaneIdx,
                pkt,
                "Leader Broadcast Type2[FC] (Leader=Lane ${leaderLane.laneNumber1Based}, ${leaderLane.rawDistanceMeters.toInt()}m)"
            )
        }

        leaderBroadcastRoundRobinLane++
        if (leaderBroadcastRoundRobinLane >= numLanes) {
            lastLeaderBroadcastMs = nowMs
            leaderBroadcastRoundRobinLane = 0
        }
    }

    /**
     * Implements `0x404618` (`CalculateRankings`):
     * Computes 1-based finishing positions (`1..8`) from `finishTimeMs`, handling ties and disqualifications (`999`).
     */
    private fun calculateFinalRankings() {
        val current = _uiState.value
        val numLanes = current.configuredLanesCount
        val lanes = current.lanes

        val ranked = lanes.mapIndexed { idx, lane ->
            if (idx >= numLanes) {
                lane.copy(rank = 0)
            } else if (lane.disqualified || lane.rank == 999) {
                lane.copy(rank = 999)
            } else if (lane.active && lane.finishTimeMs > 0.0f) {
                // Count how many active finished lanes have a faster or equal time (matching 0x40469d)
                val betterOrEqual = lanes.take(numLanes).count { other ->
                    other.active && other.finishTimeMs > 0.0f && other.finishTimeMs < lane.finishTimeMs
                }
                val place = betterOrEqual + 1
                lane.copy(rank = place, livePlace = place)
            } else {
                lane.copy(rank = 0)
            }
        }

        _uiState.value = current.copy(lanes = ranked)
    }

    /**
     * Saves race results to `./results/Results_<date>.txt` matching the `File` unit (`0x406b78`).
     */
    private fun saveRaceResultsToDisk() {
        try {
            val state = _uiState.value
            val resultsDir = File("results")
            resultsDir.mkdirs()
            val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
            val timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
            val outFile = File(resultsDir, "Results_$dateStr.txt")

            val sb = StringBuilder()
            sb.appendLine("============================================================")
            sb.appendLine("Virtual Race Result - $timestampStr")
            sb.appendLine("Results for race no. ${state.raceNumber}   Distance: ${state.raceDistanceMeters} meter")
            sb.appendLine("------------------------------------------------------------")
            sb.appendLine("Position    Lane          Time              Weight")

            val activeOrDsq = state.lanes.take(state.configuredLanesCount)
                .filter { it.active || it.disqualified }
                .sortedBy { if (it.rank in 1..8) it.rank else 999 }

            for (l in activeOrDsq) {
                val posStr = when {
                    l.disqualified || l.rank == 999 -> "DSQ"
                    l.rank > 0 -> l.rank.toString()
                    else -> "DNF"
                }
                sb.appendLine(
                    "   %-8s    %-6d     %-15s   %3d Kg".format(
                        posStr,
                        l.laneNumber1Based,
                        l.formattedFinishTime,
                        l.weightKg
                    )
                )
            }
            sb.appendLine()
            outFile.appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // Low-Level Packet Dispatch Helpers (`0x40614c` .. `0x406938`)
    // =========================================================================

    private fun broadcastSetDistanceToActiveLanes() {
        val current = _uiState.value
        val distFloat = current.raceDistanceMeters.toFloat()
        val pkt = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_SET_DISTANCE,
            raceDistanceMeters = distFloat
        )
        val nowMs = elapsedTimerMs()
        for (i in 0 until current.configuredLanesCount) {
            if (current.lanes[i].active) {
                writeToLogicalLane(i, pkt, "CMD_SET_DISTANCE (${current.raceDistanceMeters}m)")
                lastDistance0x07SentMs[i] = nowMs
            }
        }
    }

    private fun sendSetDistanceToSingle(laneIndex: Int) {
        val distInt = _uiState.value.raceDistanceMeters
        val pkt = DansprintProtocol.buildHostType1Command(
            opcode = HostCommandOpcode.CMD_SET_DISTANCE,
            raceDistanceMeters = distInt.toFloat()
        )
        writeToLogicalLane(laneIndex, pkt, "CMD_SET_DISTANCE (${distInt}m)")
        lastDistance0x07SentMs[laneIndex] = elapsedTimerMs()
    }

    private fun broadcastRaceStart0x0A() {
        val current = _uiState.value
        for (i in 0 until current.configuredLanesCount) {
            if (current.lanes[i].active) {
                val timerMs = elapsedTimerMs().toFloat()
                val pkt = DansprintProtocol.buildHostType1Command(
                    opcode = HostCommandOpcode.CMD_RACE_START,
                    startTimerMs = timerMs
                )
                writeToLogicalLane(i, pkt, "CMD_RACE_START (0x0A, t=${timerMs.toInt()}ms)")
            }
        }
    }

    private fun broadcastToActiveLanes(opcode: HostCommandOpcode) {
        val current = _uiState.value
        val pkt = DansprintProtocol.buildHostType1Command(opcode)
        for (i in 0 until current.configuredLanesCount) {
            if (current.lanes[i].active) {
                writeToLogicalLane(i, pkt, opcode.label)
            }
        }
    }

    private fun broadcastToConnectedLanes(opcode: HostCommandOpcode) {
        val current = _uiState.value
        val pkt = DansprintProtocol.buildHostType1Command(opcode)
        for (i in 0 until current.configuredLanesCount) {
            if (current.lanes[i].connected) {
                writeToLogicalLane(i, pkt, opcode.label)
            }
        }
    }

    private fun sendType1ToSingle(laneOrUsbIndex: Int, opcode: HostCommandOpcode) {
        val pkt = DansprintProtocol.buildHostType1Command(opcode)
        writeToLogicalLane(laneOrUsbIndex, pkt, opcode.label)
    }

    private fun writeToLogicalLane(laneIndex: Int, packet: ByteArray, description: String) {
        if (laneIndex !in 0 until DansprintProtocol.MAX_LANES) return
        // Matches 0x40e0cc: when isMapped == true, translate logical lane -> USB index via laneToUsb
        val usbIdx = if (isMapped) laneToUsb[laneIndex] else laneIndex
        val ok = activeTransport.writePacket(usbIdx, packet, description)
        if (!ok && isMapped) {
            updateSingleLane(laneIndex) { it.copy(connected = false) }
        }
    }

    private fun resetTimer() {
        timerOriginWallMs = System.currentTimeMillis()
    }

    private fun elapsedTimerMs(): Long {
        return (System.currentTimeMillis() - timerOriginWallMs).coerceAtLeast(0L)
    }

    private fun updateSingleLane(laneIndex: Int, transform: (LaneTelemetryState) -> LaneTelemetryState) {
        if (laneIndex !in 0 until DansprintProtocol.MAX_LANES) return
        val cur = _uiState.value
        val mutable = cur.lanes.toMutableList()
        mutable[laneIndex] = transform(mutable[laneIndex])
        _uiState.value = cur.copy(lanes = mutable)
    }

    private fun updateLanes(transform: (Int, LaneTelemetryState) -> LaneTelemetryState) {
        val cur = _uiState.value
        val updated = cur.lanes.mapIndexed(transform)
        _uiState.value = cur.copy(lanes = updated)
    }

    companion object {
        const val DEFAULT_CONFIGURED_LANES_COUNT = 6
    }
}
