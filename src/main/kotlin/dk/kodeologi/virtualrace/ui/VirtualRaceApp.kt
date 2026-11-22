package dk.kodeologi.virtualrace.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.kodeologi.virtualrace.engine.*
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.transport.EmulatedErgoUnitState
import dk.kodeologi.virtualrace.transport.PaddlerPersona
import dk.kodeologi.virtualrace.transport.WirePacketLogEntry
import dk.kodeologi.virtualrace.web.RaceWebServer
import dk.kodeologi.virtualrace.web.WebServerInfo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun VirtualRaceApp(
    engine: RaceEngine,
    webServer: RaceWebServer? = null
) {
    val uiState by engine.uiState.collectAsState()
    val emulatorUnits by engine.emulatedTransport.units.collectAsState()
    val wireLog by engine.activeTransport.wirePacketLog.collectAsState()
    val webInfo = webServer?.info?.collectAsState()?.value

    var showEmulatorLab by remember { mutableStateOf(uiState.usingEmulator) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var showResultsOverlay by remember { mutableStateOf(false) }
    var showRemoteDialog by remember { mutableStateOf(false) }

    // Auto-hide Emulator Lab & Wire Hex panel in Physical USB mode to maximize track space
    LaunchedEffect(uiState.usingEmulator) {
        showEmulatorLab = uiState.usingEmulator
    }

    // Auto-pop results overlay when entering State 8 (RACE_COMPLETE)
    LaunchedEffect(uiState.raceState) {
        if (uiState.raceState == RaceState.RACE_COMPLETE) {
            showResultsOverlay = true
        }
    }

    MaterialTheme(
        colors = darkColors(
            primary = RegattaTheme.ArcadeBorderGold,
            background = RegattaTheme.DeepNavyBg,
            surface = RegattaTheme.ArcadePanelBg
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0A1429),
                            RegattaTheme.DeepNavyBg,
                            Color(0xFF081124)
                        )
                    )
                )
                .padding(12.dp)
        ) {
            val isRaceRunning = uiState.raceState in listOf(
                RaceState.STOP_PADDLING,
                RaceState.START_POSITION,
                RaceState.READY_COUNTDOWN,
                RaceState.RACE_START_HOLD,
                RaceState.FALSE_START,
                RaceState.RACING,
                RaceState.POST_FINISH
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Top Arcade Header & Transport Toggle Bar (hidden while a race is running to maximize track space)
                if (!isRaceRunning) {
                    TopArcadeHeaderBar(
                        uiState = uiState,
                        engine = engine,
                        webInfo = webInfo,
                        showEmulatorLab = showEmulatorLab,
                        onToggleEmulatorLab = { showEmulatorLab = !showEmulatorLab },
                        onOpenRemoteDialog = { showRemoteDialog = true }
                    )
                }

                // 2. Starting Traffic Lights & Control Strip
                ControlAndTrafficLightBar(
                    uiState = uiState,
                    engine = engine,
                    onOpenSetup = { showSetupDialog = true },
                    onTogglePodium = { showResultsOverlay = !showResultsOverlay }
                )

                // 3. Main Split Area: Regatta Track Canvas + Optional Emulator & Wire Protocol Lab
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KayakRaceTrackCanvas(
                        uiState = uiState,
                        emulatorUnits = emulatorUnits,
                        onToggleLaneActive = { laneIndex ->
                            val lane = uiState.lanes[laneIndex]
                            engine.setLaneActive(laneIndex, !lane.active)
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )

                    if (showEmulatorLab) {
                        EmulatorAndWireLabPanel(
                            uiState = uiState,
                            emulatorUnits = emulatorUnits,
                            wireLog = wireLog,
                            engine = engine,
                            modifier = Modifier.width(400.dp).fillMaxHeight()
                        )
                    }
                }
            }

            // 5. Alert Dialog (from original TForm1::StartRace1Click validation checks)
            if (uiState.alertDialogMessage != null) {
                AlertModalOverlay(
                    message = uiState.alertDialogMessage ?: "",
                    onDismiss = { engine.dismissAlertDialog() },
                    onReconnect = {
                        engine.dismissAlertDialog()
                        engine.connectAndDiscoverErgometers(initialStartup = false)
                    }
                )
            }

            // 6. Setup Modal Dialog
            if (showSetupDialog) {
                SetupRaceDialog(
                    uiState = uiState,
                    emulatorUnits = emulatorUnits,
                    engine = engine,
                    onDismiss = { showSetupDialog = false }
                )
            }

            // 7. Race Podium Results Modal Overlay
            if (showResultsOverlay) {
                RaceResultsOverlay(
                    uiState = uiState,
                    onDismiss = { showResultsOverlay = false },
                    onNewRace = {
                        showResultsOverlay = false
                        engine.newRace()
                    }
                )
            }

            // 8. Mobile Web Remote Info Modal Overlay
            if (showRemoteDialog && webInfo != null) {
                MobileRemoteInfoDialog(
                    webInfo = webInfo,
                    onDismiss = { showRemoteDialog = false }
                )
            }
        }
    }
}

@Composable
private fun TopArcadeHeaderBar(
    uiState: RaceUiState,
    engine: RaceEngine,
    webInfo: WebServerInfo?,
    showEmulatorLab: Boolean,
    onToggleEmulatorLab: () -> Unit,
    onOpenRemoteDialog: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(RegattaTheme.ArcadePanelBg, RegattaTheme.ArcadePanelLight, RegattaTheme.ArcadePanelBg)
                )
            )
            .border(2.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Title & Trophy Emblem
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFFFEA00), Color(0xFFFF6D00))
                        )
                    )
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🚣", fontSize = 20.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "VIRTUAL REGATTA",
                        color = RegattaTheme.ArcadeBorderGold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    ArcadeBadge(
                        text = "RACE #${uiState.raceNumber}",
                        bgColor = Color(0xFF0D47A1),
                        fontSize = 13.sp,
                        horizontalPadding = 10.dp,
                        verticalPadding = 4.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    ArcadeBadge(
                        text = uiState.raceState.bannerText,
                        bgColor = when (uiState.raceState) {
                            RaceState.RACING, RaceState.RACE_START_HOLD -> Color(0xFF00C853)
                            RaceState.FALSE_START -> Color(0xFFD50000)
                            RaceState.STOP_PADDLING, RaceState.START_POSITION, RaceState.READY_COUNTDOWN -> Color(0xFFFF8F00)
                            else -> Color(0xFF283593)
                        },
                        textColor = Color.White,
                        fontSize = 15.sp,
                        horizontalPadding = 12.dp,
                        verticalPadding = 4.dp
                    )
                }
                Text(
                    text = uiState.statusToastMessage,
                    color = Color(0xFFB3E5FC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Transport Mode Toggle (Emulator vs Physical USB HID) + Reconnect + Lab Drawer Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Transport Segmented Switch
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF091224))
                    .border(1.5.dp, Color(0xFF4FC3F7), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isEmu = uiState.usingEmulator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEmu) RegattaTheme.SuccessGreen else Color.Transparent)
                        .clickable { if (!isEmu) engine.setUseEmulator(true) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "🎮 EMULATOR (VIRTUAL ERGOS)",
                        color = if (isEmu) Color(0xFF07210D) else Color(0xFF90CAF9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isEmu) RegattaTheme.AccentBlue else Color.Transparent)
                        .clickable { if (isEmu) engine.setUseEmulator(false) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "🔌 PHYSICAL USB HID",
                        color = if (!isEmu) Color.White else Color(0xFF90CAF9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            ArcadeButton(
                text = "🔄 Connect",
                onClick = { engine.connectAndDiscoverErgometers(initialStartup = false) },
                topColor = Color(0xFF26C6DA),
                bottomColor = Color(0xFF00838F),
                textColor = Color.White,
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )

            if (webInfo != null) {
                ArcadeButton(
                    text = if (webInfo.running) "📱 :${webInfo.port}" else "📱 Remote",
                    onClick = onOpenRemoteDialog,
                    topColor = if (webInfo.running) Color(0xFF26A69A) else Color(0xFF78909C),
                    bottomColor = if (webInfo.running) Color(0xFF00695C) else Color(0xFF455A64),
                    textColor = Color.White,
                    fontSize = 11.sp,
                    horizontalPadding = 10.dp
                )
            }

            ArcadeButton(
                text = if (showEmulatorLab) "🔬 Hide Wire Lab" else "🔬 Show Wire Lab",
                onClick = onToggleEmulatorLab,
                topColor = if (showEmulatorLab) Color(0xFFAB47BC) else Color(0xFF42A5F5),
                bottomColor = if (showEmulatorLab) Color(0xFF7B1FA2) else Color(0xFF1565C0),
                textColor = Color.White,
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )
        }
    }
}

@Composable
private fun ControlAndTrafficLightBar(
    uiState: RaceUiState,
    engine: RaceEngine,
    onOpenSetup: () -> Unit,
    onTogglePodium: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RegattaTheme.ArcadePanelBg)
            .border(1.5.dp, Color(0xFF395B9E), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Main Race Action Buttons (matching original Controls menu: New / Start / Abort / FalseStart / Finish / Setup)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canStart = uiState.raceState in listOf(
                RaceState.IDLE_PRE_RACE,
                RaceState.RACE_COMPLETE,
                RaceState.STARTUP_DISCOVERY
            )
            val isSequenceOrRacing = uiState.raceState in listOf(
                RaceState.STOP_PADDLING,
                RaceState.START_POSITION,
                RaceState.READY_COUNTDOWN,
                RaceState.RACE_START_HOLD,
                RaceState.RACING,
                RaceState.FALSE_START
            )

            ArcadeButton(
                text = "🏁 NEW RACE",
                onClick = { engine.newRace() },
                topColor = Color(0xFF4FC3F7),
                bottomColor = Color(0xFF0288D1),
                textColor = Color.White,
                fontSize = 11.sp,
                horizontalPadding = 11.dp
            )

            ArcadeButton(
                text = "🚀 START RACE",
                onClick = { engine.startRace() },
                enabled = canStart,
                topColor = Color(0xFF69F0AE),
                bottomColor = Color(0xFF00C853),
                textColor = Color(0xFF052912),
                fontSize = 11.sp,
                horizontalPadding = 11.dp
            )

            ArcadeButton(
                text = "🛑 ABORT",
                onClick = { engine.restartOrAbortRace() },
                enabled = isSequenceOrRacing,
                topColor = Color(0xFFFF5252),
                bottomColor = Color(0xFFD50000),
                textColor = Color.White,
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )

            ArcadeButton(
                text = "📣 FALSE START",
                onClick = { engine.triggerManualFalseStart() },
                enabled = isSequenceOrRacing,
                topColor = Color(0xFFFFB300),
                bottomColor = Color(0xFFE65100),
                textColor = Color.White,
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )

            ArcadeButton(
                text = "⚙️ SETUP",
                onClick = onOpenSetup,
                topColor = Color(0xFFFFD54F),
                bottomColor = Color(0xFFFF8F00),
                textColor = Color(0xFF1A1A1A),
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )

            ArcadeButton(
                text = "🏆 PODIUM",
                onClick = onTogglePodium,
                topColor = Color(0xFFFFB74D),
                bottomColor = Color(0xFFF57C00),
                textColor = Color(0xFF1A1A1A),
                fontSize = 11.sp,
                horizontalPadding = 10.dp
            )
        }

        // Center: Starting Traffic Lights + Digital Race Clock
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RaceStartLights(state = uiState.raceState)

            // Digital Race Timer Box
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF070F1F))
                    .border(2.5.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val formatted = DansprintProtocol.formatRaceTimeMs(uiState.elapsedTimerMs.toFloat())
                Text(
                    text = formatted,
                    color = if (uiState.raceState == RaceState.RACING) RegattaTheme.SuccessGreen else RegattaTheme.ArcadeBorderGold,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Right: Distance Badge, Visible Lanes & Track Zoom Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ArcadeBadge(
                text = "${uiState.raceDistanceMeters}m",
                bgColor = Color(0xFF1565C0),
                fontSize = 14.sp,
                horizontalPadding = 10.dp,
                verticalPadding = 4.dp
            )
            val visibleLanesCount = uiState.visibleLanes().size
            val laneBadgeText = if (visibleLanesCount == uiState.configuredLanesCount) {
                "$visibleLanesCount ${if (visibleLanesCount == 1) "LANE" else "LANES"}"
            } else {
                "$visibleLanesCount/${uiState.configuredLanesCount} ${if (uiState.configuredLanesCount == 1) "LANE" else "LANES"}"
            }
            ArcadeBadge(
                text = laneBadgeText,
                bgColor = Color(0xFF00695C),
                fontSize = 13.sp,
                horizontalPadding = 10.dp,
                verticalPadding = 4.dp
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0C1830))
                    .border(1.dp, Color(0xFF4FC3F7), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🔍 ${uiState.viewCourseSpanMeters.toInt()}m view",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "➕",
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { engine.zoomInCourseView() }.padding(2.dp)
                )
                Text(
                    text = "➖",
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { engine.zoomOutCourseView() }.padding(2.dp)
                )
            }
        }
    }
}

@Composable
private fun RaceStartLights(state: RaceState) {
    val redOn = state.trafficLightStage == 1 || state.trafficLightStage == 4
    val yellowOn = state.trafficLightStage == 2 || state.trafficLightStage == 4
    val greenOn = state.trafficLightStage == 3

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF0A1220))
            .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        TrafficBulb(active = redOn, activeColor = Color(0xFFFF1744))
        TrafficBulb(active = yellowOn, activeColor = Color(0xFFFFEA00))
        TrafficBulb(active = greenOn, activeColor = Color(0xFF00E676))
        Text(
            text = state.title.uppercase(),
            color = Color(0xFFB3E5FC),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TrafficBulb(active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (active) activeColor else activeColor.copy(alpha = 0.18f))
            .border(if (active) 2.dp else 1.dp, Color.White.copy(alpha = if (active) 0.95f else 0.3f), CircleShape)
    )
}

@Composable
private fun EmulatorAndWireLabPanel(
    uiState: RaceUiState,
    emulatorUnits: List<EmulatedErgoUnitState>,
    wireLog: List<WirePacketLogEntry>,
    engine: RaceEngine,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember(uiState.usingEmulator) {
        mutableStateOf(if (uiState.usingEmulator) 0 else 1)
    } // 0 = Virtual Ergo Controls, 1 = Live Wire Hex Log

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RegattaTheme.ArcadePanelBg)
            .border(2.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab switcher header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0B162C))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 0) RegattaTheme.ArcadeBorderGold else Color.Transparent)
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🧪 EMULATOR LAB",
                    color = if (selectedTab == 0) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 1) RegattaTheme.ArcadeBorderGold else Color.Transparent)
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📡 WIRE HEX (${wireLog.size})",
                    color = if (selectedTab == 1) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        if (selectedTab == 0) {
            EmulatorControlsTab(
                uiState = uiState,
                emulatorUnits = emulatorUnits,
                engine = engine
            )
        } else {
            WireProtocolHexLogTab(
                wireLog = wireLog,
                onClear = { engine.activeTransport.clearWireLog() }
            )
        }
    }
}

@Composable
private fun ColumnScope.EmulatorControlsTab(
    uiState: RaceUiState,
    emulatorUnits: List<EmulatedErgoUnitState>,
    engine: RaceEngine
) {
    val emu = engine.emulatedTransport

    // Player Manual Paddle & Quick Powerup Bar
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF102142))
            .border(1.dp, Color(0xFF4FC3F7), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎮 MANUAL STROKE (SPACEBAR)",
                color = RegattaTheme.ArcadeBorderGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
            ArcadeButton(
                text = "🚣 PADDLE! (SPACE)",
                onClick = { emu.triggerManualPaddleStroke() },
                topColor = Color(0xFF00E676),
                bottomColor = Color(0xFF00C853),
                textColor = Color(0xFF04210D),
                fontSize = 11.sp,
                horizontalPadding = 10.dp,
                verticalPadding = 4.dp
            )
        }

        Text(
            text = "Trigger speed boosts, spin-outs, false-start movement (0x04), hardware lane conflicts (0x0E), or 'READ' power resets:",
            color = Color(0xFFB3E5FC),
            fontSize = 10.sp
        )
    }

    // Per-unit Emulator Control Cards
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(emulatorUnits.take(uiState.configuredLanesCount)) { unit ->
            val style = RegattaTheme.styleForLane(unit.usbIndex)
            val isManual = (unit.persona == PaddlerPersona.MANUAL_PLAYER)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0E1D3A))
                    .border(
                        1.dp,
                        if (isManual) RegattaTheme.ArcadeBorderGold else style.badgeColor.copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(style.badgeColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${unit.configuredHardwareLane1Based}",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Lane ${unit.configuredHardwareLane1Based} (SN ${unit.serialNumber})",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Toggle USB Plugged In + AI vs Player (SPACE) mode
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (unit.pluggedIn) "🔌 ON" else "🔌 OFF",
                            color = if (unit.pluggedIn) RegattaTheme.SuccessGreen else RegattaTheme.AlertRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF091224))
                                .clickable {
                                    emu.setUnitPluggedIn(unit.usbIndex, !unit.pluggedIn)
                                }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                        Text(
                            text = if (isManual) "🎮 SPACE" else "🤖 AI",
                            color = if (isManual) RegattaTheme.ArcadeBorderGold else Color(0xFF90CAF9),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val nextPersona = if (isManual) {
                                        PaddlerPersona.entries[unit.usbIndex % 8]
                                    } else {
                                        PaddlerPersona.MANUAL_PLAYER
                                    }
                                    emu.setUnitPersona(unit.usbIndex, nextPersona)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Action buttons for this virtual ergometer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmallEmuChip(
                        label = "🔥 Boost",
                        bgColor = Color(0xFF2E7D32),
                        onClick = { emu.triggerTurboBoost(unit.usbIndex) }
                    )
                    SmallEmuChip(
                        label = "🌀 Spin",
                        bgColor = Color(0xFFF57F17),
                        onClick = { emu.triggerSpinOut(unit.usbIndex) }
                    )
                    SmallEmuChip(
                        label = if (unit.forcePreStartPaddling) "⚠️ Moving:ON" else "⚠️ JumpStart",
                        bgColor = if (unit.forcePreStartPaddling) Color(0xFFD50000) else Color(0xFFC62828),
                        onClick = {
                            emu.toggleForcePreStartPaddling(unit.usbIndex)
                            emu.triggerManualPaddleStroke(unit.usbIndex)
                        }
                    )
                    SmallEmuChip(
                        label = "⚡ 'READ' Reset",
                        bgColor = Color(0xFF6A1B9A),
                        onClick = { emu.injectPowerResetReadMarker(unit.usbIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallEmuChip(
    label: String,
    bgColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ColumnScope.WireProtocolHexLogTab(
    wireLog: List<WirePacketLogEntry>,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Live 500 kHz USB HID Frames (FB/FC/FE):",
            color = Color(0xFFB3E5FC),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        SmallEmuChip(
            label = "🗑 Clear Log",
            bgColor = Color(0xFF37474F),
            onClick = onClear
        )
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF060D1A))
            .border(1.dp, Color(0xFF263238), RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(wireLog.asReversed()) { entry ->
            val isTx = entry.direction == WirePacketLogEntry.Direction.HOST_TO_ERGO
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isTx) Color(0xFF1A237E).copy(alpha = 0.45f) else Color(0xFF004D40).copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isTx) "TX -> Dev ${entry.deviceOrLaneIndex}" else "RX <- Dev ${entry.deviceOrLaneIndex}",
                        color = if (isTx) Color(0xFF82B1FF) else Color(0xFF69F0AE),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = entry.description,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = entry.hexPreview,
                    color = Color(0xFFB0BEC5),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AlertModalOverlay(
    message: String,
    onDismiss: () -> Unit,
    onReconnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(RegattaTheme.ArcadePanelBg)
                .border(3.dp, RegattaTheme.AlertRed, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "⚠️ ERGOMETER HARDWARE ALERT",
                color = RegattaTheme.ArcadeBorderGold,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArcadeButton(
                    text = "🔄 RECONNECT USB",
                    onClick = onReconnect,
                    topColor = Color(0xFF4FC3F7),
                    bottomColor = Color(0xFF0288D1),
                    textColor = Color.White
                )
                Spacer(Modifier.width(8.dp))
                ArcadeButton(
                    text = "OK",
                    onClick = onDismiss,
                    topColor = RegattaTheme.SuccessGreen,
                    bottomColor = Color(0xFF00C853),
                    textColor = Color.Black
                )
            }
        }
    }
}

@Composable
private fun SetupRaceDialog(
    uiState: RaceUiState,
    emulatorUnits: List<EmulatedErgoUnitState>,
    engine: RaceEngine,
    onDismiss: () -> Unit
) {
    var raceDistance by remember(uiState.raceDistanceMeters) { mutableStateOf(uiState.raceDistanceMeters) }
    var configuredLanes by remember(uiState.configuredLanesCount) { mutableStateOf(uiState.configuredLanesCount) }
    var raceNoText by remember(uiState.raceNumber) { mutableStateOf(uiState.raceNumber.toString()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(660.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(RegattaTheme.ArcadePanelBg)
                .border(3.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "⚙️ REGATTA & HARDWARE SETUP",
                color = RegattaTheme.ArcadeBorderGold,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )

            // Race Number & Distance Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = raceNoText,
                    onValueChange = { raceNoText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Race Number") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )

                Column {
                    Text("Race Distance (Meters - Opcode 0x07):", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (dist in listOf(100, 200, 500, 1000, 2000, 5000)) {
                            ArcadeButton(
                                text = "${dist}m",
                                onClick = { raceDistance = dist },
                                topColor = if (raceDistance == dist) RegattaTheme.ArcadeBorderGold else Color(0xFF263238),
                                bottomColor = if (raceDistance == dist) Color(0xFFFF8F00) else Color(0xFF1C262B),
                                textColor = if (raceDistance == dist) Color.Black else Color.White,
                                fontSize = 11.sp,
                                horizontalPadding = 10.dp,
                                verticalPadding = 5.dp
                            )
                        }
                    }
                }
            }

            // Configured Lanes & Options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Configured Regatta Lanes (1–8):",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (nLanes in 1..DansprintProtocol.MAX_LANES) {
                            ArcadeButton(
                                text = "$nLanes",
                                onClick = { configuredLanes = nLanes },
                                topColor = if (configuredLanes == nLanes) RegattaTheme.SuccessGreen else Color(0xFF263238),
                                bottomColor = if (configuredLanes == nLanes) Color(0xFF00C853) else Color(0xFF1C262B),
                                textColor = if (configuredLanes == nLanes) Color.Black else Color.White,
                                fontSize = 12.sp,
                                horizontalPadding = 11.dp,
                                verticalPadding = 5.dp
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.displayWeight,
                            onCheckedChange = { engine.toggleDisplayWeight() }
                        )
                        Text("Display Paddler Weight (Kg)", color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.usbTestActive,
                            onCheckedChange = { engine.toggleUsbTestMode() }
                        )
                        Text("100ms USB Poll Test Mode (Timer2)", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // Emulator Hardware Lane Assignment Inspector (tests 0x0E duplicate lane conflict detection!)
            if (uiState.usingEmulator) {
                Text(
                    text = "Emulator Hardware Lane Numbers (reported in Opcode 0x0E):",
                    color = RegattaTheme.ArcadeBorderGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (unit in emulatorUnits) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D1B36))
                                .border(1.dp, Color(0xFF395B9E), RoundedCornerShape(8.dp))
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("USB #${unit.usbIndex + 1}", color = Color(0xFF90CAF9), fontSize = 10.sp)
                            Text(
                                text = "Lane ${unit.configuredHardwareLane1Based}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "◀",
                                    color = RegattaTheme.ArcadeBorderGold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        engine.emulatedTransport.setConfiguredLane1Based(
                                            unit.usbIndex,
                                            (unit.configuredHardwareLane1Based - 1).coerceAtLeast(1)
                                        )
                                    }
                                )
                                Text(
                                    text = "▶",
                                    color = RegattaTheme.ArcadeBorderGold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        engine.emulatedTransport.setConfiguredLane1Based(
                                            unit.usbIndex,
                                            (unit.configuredHardwareLane1Based + 1).coerceAtMost(8)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Save / Cancel Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArcadeButton(
                    text = "CANCEL",
                    onClick = onDismiss,
                    topColor = Color(0xFF78909C),
                    bottomColor = Color(0xFF455A64),
                    textColor = Color.White
                )
                Spacer(Modifier.width(10.dp))
                ArcadeButton(
                    text = "✅ APPLY SETTINGS",
                    onClick = {
                        val parsedRaceNo = raceNoText.toIntOrNull() ?: uiState.raceNumber
                        engine.setRaceNumber(parsedRaceNo)
                        if (configuredLanes != uiState.configuredLanesCount) {
                            engine.setConfiguredLanesCount(configuredLanes)
                        }
                        engine.setRaceDistanceMeters(raceDistance)
                        onDismiss()
                    },
                    topColor = RegattaTheme.SuccessGreen,
                    bottomColor = Color(0xFF00C853),
                    textColor = Color.Black
                )
            }
        }
    }
}

@Composable
private fun RaceResultsOverlay(
    uiState: RaceUiState,
    onDismiss: () -> Unit,
    onNewRace: () -> Unit
) {
    val activeLanes = uiState.lanes
        .take(uiState.configuredLanesCount)
        .filter { it.active || it.disqualified || it.isFinished }
        .sortedWith(
            compareBy<LaneTelemetryState> { it.disqualified || it.rank == 999 }
                .thenBy { if (it.rank in 1..8) it.rank else it.livePlace }
                .thenByDescending { it.interpolatedDistanceMeters }
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBB000000))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .widthIn(min = 600.dp, max = 740.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(RegattaTheme.ArcadePanelBg)
                .border(3.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(22.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🏆 RACE PODIUM",
                        color = RegattaTheme.ArcadeBorderGold,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                    Text(
                        text = "Race #${uiState.raceNumber} • ${uiState.raceDistanceMeters}m (Saved to ./results/)",
                        color = Color(0xFFB3E5FC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcadeButton(
                        text = "🏁 NEXT RACE",
                        onClick = onNewRace,
                        topColor = RegattaTheme.SuccessGreen,
                        bottomColor = Color(0xFF00C853),
                        textColor = Color.Black,
                        fontSize = 13.sp
                    )
                    ArcadeButton(
                        text = "✖ CLOSE",
                        onClick = onDismiss,
                        topColor = Color(0xFFFF5252),
                        bottomColor = Color(0xFFD50000),
                        textColor = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            // Standings Table
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (lane in activeLanes) {
                    val style = RegattaTheme.styleForLane(lane.laneIndex)
                    val place = if (lane.rank in 1..8) lane.rank else lane.livePlace
                    val medal = when {
                        lane.disqualified || lane.rank == 999 -> "❌ DSQ"
                        place == 1 -> "🥇 1ST"
                        place == 2 -> "🥈 2ND"
                        place == 3 -> "🥉 3RD"
                        place in 4..8 -> "#$place"
                        else -> "-"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0E1D3A))
                            .border(
                                2.5.dp,
                                when {
                                    lane.disqualified -> RegattaTheme.AlertRed
                                    place == 1 -> RegattaTheme.GoldTrophy
                                    place == 2 -> RegattaTheme.SilverTrophy
                                    place == 3 -> RegattaTheme.BronzeTrophy
                                    else -> Color(0xFF37474F)
                                },
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = medal,
                                color = RegattaTheme.ArcadeBorderGold,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                modifier = Modifier.width(138.dp)
                            )
                            ArcadeBadge(
                                text = "LANE ${lane.laneNumber1Based}",
                                bgColor = style.badgeColor,
                                textColor = Color.Black,
                                fontSize = 23.sp,
                                horizontalPadding = 14.dp,
                                verticalPadding = 5.dp
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lane.formattedFinishTime,
                                color = RegattaTheme.SuccessGreen,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            Text(
                                text = "${lane.interpolatedDistanceMeters.toInt()}m",
                                color = Color(0xFF81D4FA),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            if (uiState.displayWeight) {
                                Text(
                                    text = "${lane.weightKg}kg",
                                    color = Color.White,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileRemoteInfoDialog(
    webInfo: WebServerInfo,
    onDismiss: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    val qrMatrix = remember(webInfo.lanUrl) {
        if (webInfo.running && webInfo.lanUrl.isNotBlank()) {
            runCatching { QrCodeGenerator.encode(webInfo.lanUrl) }.getOrNull()
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(RegattaTheme.ArcadePanelBg)
                .border(3.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "📱 MOBILE RACE REMOTE SERVER",
                color = RegattaTheme.ArcadeBorderGold,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )

            if (webInfo.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (qrMatrix != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(184.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .border(2.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(12.dp))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val quietZone = 3
                                    val totalCells = qrMatrix.size + quietZone * 2
                                    val cellSize = minOf(size.width, size.height) / totalCells.toFloat()
                                    val offsetX = (size.width - cellSize * totalCells) * 0.5f
                                    val offsetY = (size.height - cellSize * totalCells) * 0.5f
                                    val drawSize = cellSize + 0.45f
                                    for (y in 0 until qrMatrix.size) {
                                        for (x in 0 until qrMatrix.size) {
                                            if (qrMatrix.isDark(x, y)) {
                                                drawRect(
                                                    color = Color.Black,
                                                    topLeft = Offset(
                                                        offsetX + (x + quietZone) * cellSize,
                                                        offsetY + (y + quietZone) * cellSize
                                                    ),
                                                    size = Size(drawSize, drawSize)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "SCAN WITH PHONE CAMERA",
                                color = Color(0xFF80CBC4),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Scan the QR code or open this URL on any phone or tablet on the same Wi-Fi network to configure races, start/abort, and monitor live status & results:",
                            color = Color(0xFFB3E5FC),
                            fontSize = 12.sp
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0A1428))
                                .border(1.5.dp, Color(0xFF26A69A), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PHONE / LAN URL (INCLUDES PIN)",
                                    color = Color(0xFF80CBC4),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                if (webInfo.accessPin.isNotBlank()) {
                                    ArcadeBadge(
                                        text = "PIN: ${webInfo.accessPin}",
                                        bgColor = Color(0xFF1A237E),
                                        textColor = RegattaTheme.ArcadeBorderGold,
                                        borderColor = RegattaTheme.ArcadeBorderGold,
                                        fontSize = 12.sp,
                                        horizontalPadding = 10.dp,
                                        verticalPadding = 3.dp
                                    )
                                }
                            }
                            Text(
                                text = webInfo.lanUrl,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            if (webInfo.localUrl != webInfo.lanUrl) {
                                Text(
                                    text = "This computer: ${webInfo.localUrl}",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = webInfo.errorMessage ?: "Embedded web server is not running.",
                    color = RegattaTheme.AlertRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (webInfo.running) {
                    ArcadeButton(
                        text = if (copied) "✅ COPIED!" else "📋 COPY URL",
                        onClick = {
                            try {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                    StringSelection(webInfo.lanUrl),
                                    null
                                )
                                copied = true
                            } catch (_: Throwable) {
                            }
                        },
                        topColor = Color(0xFF26A69A),
                        bottomColor = Color(0xFF00695C),
                        textColor = Color.White
                    )
                    Spacer(Modifier.width(10.dp))
                }
                ArcadeButton(
                    text = "CLOSE",
                    onClick = onDismiss,
                    topColor = Color(0xFFFFD54F),
                    bottomColor = Color(0xFFFF8F00),
                    textColor = Color(0xFF1A1A1A)
                )
            }
        }
    }
}
