package dk.kodeologi.virtualrace.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.kodeologi.virtualrace.engine.BoxStatusColor
import dk.kodeologi.virtualrace.engine.LaneTelemetryState
import dk.kodeologi.virtualrace.engine.RaceState
import dk.kodeologi.virtualrace.engine.RaceUiState
import dk.kodeologi.virtualrace.transport.EmulatedErgoUnitState
import dk.kodeologi.virtualrace.transport.PaddlerPersona
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Arcade-style Sprint Kayak Regatta Track Canvas.
 * Renders:
 * - Animated sparkling water ripples
 * - Top regatta bank with cheering crowd & floating distance marker buoys (respecting course zoom)
 * - Colorful floating lane ropes (red/white/yellow buoys)
 * - Automatic submerged start gates that drop on RACE_START
 * - Checkered finish line banner
 * - Custom sprint kayaks with racing helmet, rotating double-bladed carbon paddle, wake foam,
 *   turbo boost flames, spin-out / false-start dizzy stars, and 1ST place gold crown.
 */
@Composable
fun KayakRaceTrackCanvas(
    uiState: RaceUiState,
    emulatorUnits: List<EmulatedErgoUnitState>,
    onToggleLaneActive: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val visibleLanes = uiState.visibleLanes()
    val visibleLaneCount = visibleLanes.size
    val rowIndexByLaneIndex = visibleLanes.withIndex().associate { (rowIdx, lane) -> lane.laneIndex to rowIdx }
    val textMeasurer = rememberTextMeasurer()

    // Active non-disqualified lanes sorted front-to-back for bow sightlines & gap-to-next-boat readouts
    val rankedActiveLanes = visibleLanes
        .filter { it.active && !it.disqualified }
        .sortedWith(
            compareByDescending<LaneTelemetryState> { it.interpolatedDistanceMeters }
                .thenBy { if (it.isFinished) it.finishTimeMs else Float.MAX_VALUE }
                .thenBy { it.laneIndex }
        )
    val nextBoatAheadByLaneIndex: Map<Int, LaneTelemetryState> = buildMap {
        for (rankIdx in 1 until rankedActiveLanes.size) {
            put(rankedActiveLanes[rankIdx].laneIndex, rankedActiveLanes[rankIdx - 1])
        }
    }
    val leaderLaneIdx: Int? = rankedActiveLanes.firstOrNull()?.takeIf { it.interpolatedDistanceMeters > 0.3f }?.laneIndex

    // Determine visible meter window based on viewCourseSpanMeters and leader position (matching 0x403318)
    val totalRaceMeters = uiState.raceDistanceMeters.toFloat().coerceAtLeast(50.0f)
    val windowSpanMeters = uiState.viewCourseSpanMeters.coerceIn(12.5f, max(totalRaceMeters, 100.0f))

    val leaderDistance = uiState.maxBoatDistanceMeters.coerceIn(0.0f, totalRaceMeters)

    val windowStartMeters = if (windowSpanMeters >= totalRaceMeters) {
        0.0f
    } else {
        // Keep leading kayak around 70% across the viewport when zoomed in
        (leaderDistance - windowSpanMeters * 0.70f).coerceIn(0.0f, max(0.0f, totalRaceMeters - windowSpanMeters))
    }
    val windowEndMeters = (windowStartMeters + windowSpanMeters).coerceAtMost(max(totalRaceMeters, windowSpanMeters))

    val topBankHeightDp = 54.dp
    val bottomBankHeightDp = 28.dp
    val dockInsetXDp = 8.dp
    val dockCardWidthDp = 264.dp
    val leftMarginDp = 466.dp // Dock card (272dp) + 194dp so full 184px kayak sits behind 0m with bow on start line
    val rightMarginDp = 142.dp // 20dp checkered flag + 4dp gap + 112dp rank badge + 6dp right padding
    val buoyPillWidthDp = 74.dp
    val buoyPillHeightDp = 28.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(16.dp))
            .background(RegattaTheme.WaterDeep)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val topBankHeight = topBankHeightDp.toPx()
            val bottomBankHeight = bottomBankHeightDp.toPx()
            val waterTop = topBankHeight
            val waterBottom = (h - bottomBankHeight).coerceAtLeast(waterTop + 80.dp.toPx())
            val waterHeight = waterBottom - waterTop

            val leftMargin = leftMarginDp.toPx()
            val rightMargin = rightMarginDp.toPx()
            val trackPixelWidth = (w - leftMargin - rightMargin).coerceAtLeast(200.dp.toPx())
            val buoyPillW = buoyPillWidthDp.toPx()
            val buoyPillH = buoyPillHeightDp.toPx()

            fun metersToX(meters: Float): Float {
                val fraction = ((meters - windowStartMeters) / (windowEndMeters - windowStartMeters).coerceAtLeast(1.0f))
                    .coerceIn(-0.06f, 1.06f)
                return leftMargin + fraction * trackPixelWidth
            }

            // 1. Draw Top Regatta Grandstand Bank & Grass
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF2E7D32), Color(0xFF43A047), Color(0xFFD7CCC8)),
                    startY = 0f,
                    endY = topBankHeight
                ),
                topLeft = Offset(0f, 0f),
                size = Size(w, topBankHeight)
            )
            // Grandstand colorful awning blocks
            val awningWidth = 36f
            var awningX = 0f
            var awningToggle = false
            while (awningX < w) {
                drawRect(
                    color = if (awningToggle) Color(0xFFE53935) else Color(0xFFFFF8E1),
                    topLeft = Offset(awningX, 0f),
                    size = Size(awningWidth, 12f)
                )
                awningX += awningWidth
                awningToggle = !awningToggle
            }

            // 2. Draw Water Gradient & Animated Wave Ripples
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(RegattaTheme.WaterDeep, RegattaTheme.WaterMid, RegattaTheme.WaterBright, RegattaTheme.WaterMid),
                    startY = waterTop,
                    endY = waterBottom
                ),
                topLeft = Offset(0f, waterTop),
                size = Size(w, waterHeight)
            )

            drawAnimatedWaterRipples(
                waterTop = waterTop,
                waterBottom = waterBottom,
                width = w,
                wavePhase = wavePhase
            )

            // 3. Draw Distance Grid Lines & Floating Distance Buoys
            val stepMeters = when {
                windowSpanMeters <= 50f -> 10
                windowSpanMeters <= 150f -> 25
                windowSpanMeters <= 300f -> 50
                windowSpanMeters <= 600f -> 100
                windowSpanMeters <= 1500f -> 250
                else -> 500
            }
            val firstTick = ((windowStartMeters / stepMeters).toInt() * stepMeters).coerceAtLeast(0)
            var tickM = firstTick
            while (tickM <= windowEndMeters.toInt()) {
                val x = metersToX(tickM.toFloat())
                if (x in (leftMargin - 5f)..(w - rightMargin + 5f)) {
                    val isStartOrFinish = (tickM == 0 || tickM == uiState.raceDistanceMeters)
                    if (!isStartOrFinish) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.14f),
                            start = Offset(x, waterTop),
                            end = Offset(x, waterBottom),
                            strokeWidth = 1.0f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
                        )
                    }
                    // Draw floating marker buoy on top bank
                    drawRoundRect(
                        color = if (tickM == uiState.raceDistanceMeters) RegattaTheme.ArcadeBorderGold else Color(0xFF102040),
                        topLeft = Offset(x - buoyPillW * 0.5f, topBankHeight - buoyPillH - 4.dp.toPx()),
                        size = Size(buoyPillW, buoyPillH),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                }
                tickM += stepMeters
            }

            // 4. Draw Start Gate (0 m) if visible
            if (0.0f in windowStartMeters..windowEndMeters) {
                val startX = metersToX(0.0f)
                val gateUp = uiState.raceState in listOf(
                    RaceState.STARTUP_DISCOVERY,
                    RaceState.IDLE_PRE_RACE,
                    RaceState.STOP_PADDLING,
                    RaceState.START_POSITION,
                    RaceState.READY_COUNTDOWN,
                    RaceState.FALSE_START
                )
                drawStartGate(startX, waterTop, waterBottom, gateUp)
            }

            // 5. Draw Checkered Finish Line if visible
            if (totalRaceMeters in windowStartMeters..windowEndMeters) {
                val finishX = metersToX(totalRaceMeters)
                drawCheckeredFinishLine(finishX, waterTop, waterBottom)
            }

            // 6. Draw Lanes, Subtle Vertical Boat Sightlines, Buoy Lines, Gap Readouts, and Sprint Kayaks
            if (visibleLaneCount > 0) {
                val laneHeight = waterHeight / visibleLaneCount.toFloat()
                val kayakHalfLen = kayakHalfLengthForLane(laneHeight)
                val showComparisonGuides = uiState.maxBoatDistanceMeters > 0.3f

                // 6a. Alternating subtle lane tints first
                for (rowIdx in 0 until visibleLaneCount) {
                    val laneTop = waterTop + rowIdx * laneHeight
                    if (rowIdx % 2 == 1) {
                        drawRect(
                            color = Color(0x14000000),
                            topLeft = Offset(0f, laneTop),
                            size = Size(w, laneHeight)
                        )
                    }
                }

                // 6b. Very subtle vertical bow sightlines across water for easy boat-to-boat comparison
                if (showComparisonGuides) {
                    for (lane in rankedActiveLanes) {
                        val rowIdx = rowIndexByLaneIndex[lane.laneIndex] ?: continue
                        if (lane.interpolatedDistanceMeters !in windowStartMeters..windowEndMeters) continue
                        val bowX = metersToX(lane.interpolatedDistanceMeters)
                        if (bowX !in (leftMargin - 4f)..(w - rightMargin + 6f)) continue

                        val isFirst = (lane.laneIndex == leaderLaneIdx)
                        val style = RegattaTheme.styleForLane(lane.laneIndex)
                        val ownLaneTop = waterTop + rowIdx * laneHeight
                        val ownLaneBottom = ownLaneTop + laneHeight

                        drawBowSightline(
                            bowX = bowX,
                            waterTop = waterTop,
                            waterBottom = waterBottom,
                            ownLaneTop = ownLaneTop,
                            ownLaneBottom = ownLaneBottom,
                            accentColor = style.badgeColor,
                            isLeader = isFirst
                        )
                    }
                }

                // 6c. Buoy ropes, left dock cards, gap-to-next-boat bridges, and 3D sprint kayaks
                for (rowIdx in 0 until visibleLaneCount) {
                    val lane = visibleLanes[rowIdx]
                    val laneTop = waterTop + rowIdx * laneHeight
                    val laneCenterY = laneTop + laneHeight * 0.5f
                    val style = RegattaTheme.styleForLane(lane.laneIndex)
                    val emuUnit = emulatorUnits.getOrNull(lane.laneIndex)

                    // Draw buoy rope on bottom of lane (starts just right of the dock card)
                    val dockInsetX = dockInsetXDp.toPx()
                    val dockCardWidth = dockCardWidthDp.toPx()
                    drawBuoyLine(
                        y = laneTop + laneHeight,
                        startX = dockInsetX + dockCardWidth + 6.dp.toPx(),
                        endX = w - rightMargin / 2f,
                        wavePhase = wavePhase + rowIdx * 0.5f
                    )

                    // Draw subtle "meters up to next boat" arrow & pill in this lane
                    if (showComparisonGuides && lane.active && !lane.disqualified && !lane.isFinished) {
                        val nextBoat = nextBoatAheadByLaneIndex[lane.laneIndex]
                        if (nextBoat != null && lane.interpolatedDistanceMeters in windowStartMeters..windowEndMeters) {
                            val gapMeters = (nextBoat.interpolatedDistanceMeters - lane.interpolatedDistanceMeters)
                                .coerceAtLeast(0f)
                            val myBowX = metersToX(lane.interpolatedDistanceMeters)
                            val nextBowX = metersToX(nextBoat.interpolatedDistanceMeters)
                                .coerceAtMost(w - rightMargin - 2f)
                            val chasingLeader = (nextBoat.laneIndex == leaderLaneIdx)

                            drawGapToNextBoatIndicator(
                                myBowX = myBowX,
                                nextBowX = nextBowX,
                                laneTop = laneTop,
                                laneCenterY = laneCenterY,
                                laneHeight = laneHeight,
                                trackLeft = leftMargin,
                                trackRight = w - rightMargin,
                                gapMeters = gapMeters,
                                chasingLeader = chasingLeader,
                                textMeasurer = textMeasurer
                            )
                        }
                    }

                    // Draw the Kayak with its bow (front tip) aligned to the exact race distance
                    if (lane.active || lane.connected || lane.disqualified) {
                        val bowX = metersToX(lane.interpolatedDistanceMeters)
                        val kayakCenterX = bowX - kayakHalfLen
                        val isLeader = (lane.livePlace == 1 && lane.interpolatedDistanceMeters > 1.0f && !lane.disqualified)
                        val emuBoosting = uiState.usingEmulator && (emuUnit?.boostRemainingMs ?: 0L) > 0L
                        val isBoosting = lane.isBoosting || emuBoosting
                        val boostIntensity = if (emuBoosting) 1.0f else lane.boostIntensity
                        val isSpinningOut = uiState.usingEmulator && (emuUnit?.spinOutRemainingMs ?: 0L) > 0L
                        val isManualPlayer = uiState.usingEmulator && emuUnit?.persona == PaddlerPersona.MANUAL_PLAYER
                        drawSprintKayak(
                            centerX = kayakCenterX,
                            centerY = laneCenterY,
                            laneHeight = laneHeight,
                            lane = lane,
                            style = style,
                            isLeader = isLeader,
                            isBoosting = isBoosting,
                            boostIntensity = boostIntensity,
                            isSpinningOut = isSpinningOut,
                            wavePhase = wavePhase,
                            isManualPlayer = isManualPlayer
                        )
                    }
                }
            }

            // 7. Draw Bottom Shoreline Bank
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFD7CCC8), Color(0xFF388E3C), Color(0xFF1B5E20)),
                    startY = waterBottom,
                    endY = h
                ),
                topLeft = Offset(0f, waterBottom),
                size = Size(w, bottomBankHeight)
            )
        }

        // Compose Text Overlays for crisp Distance Markers & Left Lane HUD Badges
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val trackWidthDp = (maxWidth - leftMarginDp - rightMarginDp).coerceAtLeast(100.dp)
            val waterHeightDp = (maxHeight - topBankHeightDp - bottomBankHeightDp).coerceAtLeast(80.dp)

            // Distance marker labels along top bank
            val stepMeters = when {
                windowSpanMeters <= 50f -> 10
                windowSpanMeters <= 150f -> 25
                windowSpanMeters <= 300f -> 50
                windowSpanMeters <= 600f -> 100
                windowSpanMeters <= 1500f -> 250
                else -> 500
            }
            val firstTick = ((windowStartMeters / stepMeters).toInt() * stepMeters).coerceAtLeast(0)
            var tickM = firstTick
            while (tickM <= windowEndMeters.toInt()) {
                val fraction = ((tickM.toFloat() - windowStartMeters) / (windowEndMeters - windowStartMeters).coerceAtLeast(1.0f))
                if (fraction in -0.02f..1.02f) {
                    val xDp = leftMarginDp + trackWidthDp * fraction - buoyPillWidthDp * 0.5f
                    val isFinish = (tickM == uiState.raceDistanceMeters)
                    Box(
                        modifier = Modifier
                            .offset(x = xDp, y = topBankHeightDp - buoyPillHeightDp - 4.dp)
                            .width(buoyPillWidthDp)
                            .height(buoyPillHeightDp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isFinish) "FINISH" else "${tickM}m",
                            color = if (isFinish) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                tickM += stepMeters
            }

            if (visibleLaneCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xE00D1B36))
                            .border(2.5.dp, RegattaTheme.ArcadeBorderGold, RoundedCornerShape(16.dp))
                            .padding(horizontal = 32.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🔌 NO ERGOMETERS CONNECTED",
                            color = RegattaTheme.ArcadeBorderGold,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = if (uiState.usingEmulator) {
                                "Plug in a virtual ergometer in the Emulator Lab to show its lane."
                            } else {
                                "Connect a Dansprint USB ergometer to automatically show its lane."
                            },
                            color = Color(0xFFB3E5FC),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                val laneHeightDp = waterHeightDp / visibleLaneCount
                val laneH = laneHeightDp.value
                val laneNumFontSize = (laneH * 0.42f).coerceIn(22f, 42f).sp
                val primaryMetricFontSize = (laneH * 0.42f).coerceIn(20f, 46f).sp
                val finishTimeFontSize = (laneH * 0.30f).coerceIn(17f, 30f).sp
                val speedNumberFontSize = (laneH * 0.33f).coerceIn(17f, 36f).sp
                val speedUnitFontSize = (laneH * 0.19f).coerceIn(12f, 20f).sp
                val rankFontSize = (laneH * 0.34f).coerceIn(18f, 32f).sp
                val weightFontSize = (laneH * 0.17f).coerceIn(12f, 18f).sp

                // Left Starting Dock Lane/Distance/Speed HUD + Right Live Rank Pill
                for (rowIdx in 0 until visibleLaneCount) {
                    val lane = visibleLanes[rowIdx]
                    val style = RegattaTheme.styleForLane(lane.laneIndex)
                    val yDp = topBankHeightDp + laneHeightDp * rowIdx

                    // Left Dock Card: clickable in lobby, green border when active, only L1..L8 + centered Distance & Speed
                    val dockCardBg = when {
                        lane.disqualified -> Color(0xDE3A0E18)
                        lane.active -> Color(0xDE0E3422)
                        else -> Color(0xCC0D1B36)
                    }
                    val dockBorderColor = when {
                        lane.disqualified -> RegattaTheme.AlertRed
                        lane.boxStatus == BoxStatusColor.BLUE_DUPLICATE_LANE -> RegattaTheme.AccentBlue
                        lane.boxStatus == BoxStatusColor.PURPLE_POWER_REAPPLIED -> RegattaTheme.AccentPurple
                        lane.active -> RegattaTheme.SuccessGreen
                        else -> Color(0xFF455A64)
                    }
                    val dockBorderWidth = if (lane.active || lane.disqualified) 3.dp else 1.5.dp
                    val canToggleInLobby = uiState.raceState == RaceState.IDLE_PRE_RACE ||
                        uiState.raceState == RaceState.STARTUP_DISCOVERY

                    Row(
                        modifier = Modifier
                            .offset(x = dockInsetXDp, y = yDp + 4.dp)
                            .width(dockCardWidthDp)
                            .height((laneHeightDp - 8.dp).coerceAtLeast(24.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(dockCardBg)
                            .border(dockBorderWidth, dockBorderColor, RoundedCornerShape(10.dp))
                            .clickable(enabled = canToggleInLobby) {
                                onToggleLaneActive(lane.laneIndex)
                            }
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Left lane color accent bar
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .fillMaxHeight()
                                .background(if (lane.active) style.badgeColor else Color(0xFF546E7A))
                        )

                        Text(
                            text = "L${lane.laneNumber1Based}",
                            color = style.badgeColor,
                            fontSize = laneNumFontSize,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when {
                                lane.disqualified -> {
                                    Text(
                                        text = "DSQ",
                                        color = RegattaTheme.AlertRed,
                                        fontSize = primaryMetricFontSize,
                                        lineHeight = primaryMetricFontSize * 1.05f,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                                lane.isFinished -> {
                                    Text(
                                        text = lane.formattedFinishTime,
                                        color = RegattaTheme.SuccessGreen,
                                        fontSize = finishTimeFontSize,
                                        lineHeight = finishTimeFontSize * 1.05f,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${lane.interpolatedDistanceMeters.toInt()}m",
                                        color = Color(0xFF81D4FA),
                                        fontSize = speedNumberFontSize,
                                        lineHeight = speedNumberFontSize * 1.05f,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                                !lane.active -> {
                                    Text(
                                        text = if (lane.connected) "READY" else "OFF",
                                        color = if (lane.connected) Color(0xFF81D4FA) else Color.Gray,
                                        fontSize = finishTimeFontSize,
                                        lineHeight = finishTimeFontSize * 1.05f,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "${lane.interpolatedDistanceMeters.toInt()}m",
                                        color = if (lane.falseStartCount > 0) RegattaTheme.AccentYellow else Color(0xFFFFF59D),
                                        fontSize = primaryMetricFontSize,
                                        lineHeight = primaryMetricFontSize * 1.04f,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = String.format("%.1f", lane.displayedSpeedKmh),
                                            color = Color(0xFF81D4FA),
                                            fontSize = speedNumberFontSize,
                                            lineHeight = speedNumberFontSize * 1.04f,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = " km/h",
                                            color = Color(0xFF81D4FA).copy(alpha = 0.85f),
                                            fontSize = speedUnitFontSize,
                                            lineHeight = speedUnitFontSize * 1.05f,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right Rank / Weight Badge (clear of the 20dp checkered finish flag)
                    val displayPlace = if (lane.rank in 1..8) lane.rank else lane.livePlace
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth - rightMarginDp + 24.dp, y = yDp + 6.dp)
                            .width(rightMarginDp - 30.dp)
                            .height((laneHeightDp - 12.dp).coerceAtLeast(24.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xD90D1B36))
                            .border(
                                1.5.dp,
                                when {
                                    lane.disqualified -> RegattaTheme.AlertRed
                                    displayPlace == 1 && lane.active -> RegattaTheme.GoldTrophy
                                    displayPlace == 2 && lane.active -> RegattaTheme.SilverTrophy
                                    displayPlace == 3 && lane.active -> RegattaTheme.BronzeTrophy
                                    else -> Color(0xFF37474F)
                                },
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val rankText = when {
                            lane.disqualified || lane.rank == 999 -> "DSQ"
                            !lane.active -> "--"
                            displayPlace == 1 -> "1ST"
                            displayPlace == 2 -> "2ND"
                            displayPlace == 3 -> "3RD"
                            displayPlace in 4..8 -> "${displayPlace}TH"
                            else -> "RDY"
                        }
                        val rankColor = when (displayPlace) {
                            1 -> RegattaTheme.GoldTrophy
                            2 -> RegattaTheme.SilverTrophy
                            3 -> RegattaTheme.BronzeTrophy
                            else -> Color.White
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = rankText,
                                color = if (lane.disqualified) RegattaTheme.AlertRed else rankColor,
                                fontSize = rankFontSize,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )
                            if (uiState.displayWeight && lane.connected) {
                                Text(
                                    text = "${lane.weightKg} kg",
                                    color = Color(0xFF81D4FA),
                                    fontSize = weightFontSize,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // High-visibility paddler callout banner during start sequence & false starts
            val showStartCallout = uiState.raceState in listOf(
                RaceState.STOP_PADDLING,
                RaceState.START_POSITION,
                RaceState.READY_COUNTDOWN,
                RaceState.RACE_START_HOLD,
                RaceState.FALSE_START
            )
            if (showStartCallout) {
                val (calloutBg, calloutBorder, calloutText) = when (uiState.raceState) {
                    RaceState.STOP_PADDLING -> Triple(Color(0xEB8E0000), Color(0xFFFF5252), "🛑 STOP PADDLING!")
                    RaceState.START_POSITION -> Triple(Color(0xEBE65100), Color(0xFFFFAB40), "🚣 TAKE START POSITION!")
                    RaceState.READY_COUNTDOWN -> Triple(Color(0xEB7C5800), Color(0xFFFFEA00), "🟡 READY... HOLD STILL!")
                    RaceState.RACE_START_HOLD -> Triple(Color(0xEB006024), Color(0xFF00E676), "🟢 GO!! GO!! GO!!")
                    RaceState.FALSE_START -> Triple(Color(0xEB9B0000), Color(0xFFFF1744), "🚨 FALSE START! RETURN TO LINE!")
                    else -> Triple(Color(0xE00D1B36), RegattaTheme.ArcadeBorderGold, uiState.raceState.bannerText)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topBankHeightDp + 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(calloutBg)
                        .border(3.dp, calloutBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 30.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = calloutText,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawAnimatedWaterRipples(
    waterTop: Float,
    waterBottom: Float,
    width: Float,
    wavePhase: Float
) {
    val rows = 10
    val rowStep = (waterBottom - waterTop) / rows
    for (r in 0 until rows) {
        val yBase = waterTop + r * rowStep + rowStep * 0.5f
        var x = ((r * 47f) + wavePhase * 28f) % 120f - 120f
        while (x < width) {
            val waveY = yBase + sin(wavePhase + r * 0.7f + x * 0.02f) * 3f
            drawLine(
                color = RegattaTheme.WaterFoam.copy(alpha = 0.22f),
                start = Offset(x, waveY),
                end = Offset(x + 34f, waveY),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
            x += 120f
        }
    }
}

private fun DrawScope.drawBuoyLine(
    y: Float,
    startX: Float,
    endX: Float,
    wavePhase: Float
) {
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = 1.5f
    )
    var x = startX + 14f
    var idx = 0
    while (x < endX) {
        val buoyColor = when {
            idx % 6 == 0 -> Color(0xFFFF1744) // Red marker buoy
            idx % 2 == 0 -> Color(0xFFFFEA00) // Yellow buoy
            else -> Color.White
        }
        val bobOffset = sin(wavePhase + idx * 0.4f) * 1.4f
        drawCircle(
            color = buoyColor,
            radius = 3.5f,
            center = Offset(x, y + bobOffset)
        )
        x += 22f
        idx++
    }
}

private fun DrawScope.drawStartGate(
    startX: Float,
    waterTop: Float,
    waterBottom: Float,
    gateUp: Boolean
) {
    val gateColor = if (gateUp) Color(0xFFFF5252) else Color(0x66B0BEC5)
    drawLine(
        color = gateColor,
        start = Offset(startX, waterTop),
        end = Offset(startX, waterBottom),
        strokeWidth = if (gateUp) 5f else 2f,
        pathEffect = if (gateUp) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
    )
}

private fun DrawScope.drawCheckeredFinishLine(
    finishX: Float,
    waterTop: Float,
    waterBottom: Float
) {
    val squareSize = 10f
    var y = waterTop
    var row = 0
    while (y < waterBottom) {
        val h = min(squareSize, waterBottom - y)
        val c1 = if (row % 2 == 0) Color.White else Color.Black
        val c2 = if (row % 2 == 0) Color.Black else Color.White
        // Leading left edge sits directly on finishX so the boat finishes the instant its bow reaches the flag
        drawRect(c1, topLeft = Offset(finishX, y), size = Size(squareSize, h))
        drawRect(c2, topLeft = Offset(finishX + squareSize, y), size = Size(squareSize, h))
        y += squareSize
        row++
    }
    // Red Finish Buoys at top & bottom of the finish line
    drawCircle(Color(0xFFFF1744), radius = 8f, center = Offset(finishX, waterTop + 6f))
    drawCircle(Color(0xFFFF1744), radius = 8f, center = Offset(finishX, waterBottom - 6f))
}

/**
 * Very subtle vertical bow sightline spanning the water so spectators can compare
 * boat-to-boat positions across lanes at a glance.
 */
private fun DrawScope.drawBowSightline(
    bowX: Float,
    waterTop: Float,
    waterBottom: Float,
    ownLaneTop: Float,
    ownLaneBottom: Float,
    accentColor: Color,
    isLeader: Boolean
) {
    val lineColor = if (isLeader) {
        RegattaTheme.GoldTrophy.copy(alpha = 0.34f)
    } else {
        Color.White.copy(alpha = 0.22f)
    }
    val dash = if (isLeader) floatArrayOf(5f, 4f) else floatArrayOf(3f, 5f)
    drawLine(
        color = lineColor,
        start = Offset(bowX, waterTop),
        end = Offset(bowX, waterBottom),
        strokeWidth = if (isLeader) 1.3f else 1.0f,
        pathEffect = PathEffect.dashPathEffect(dash, 0f)
    )

    // Subtle colored highlight segment within the boat's own lane to tie the hairline to this kayak
    drawLine(
        color = (if (isLeader) RegattaTheme.GoldTrophy else accentColor).copy(alpha = if (isLeader) 0.45f else 0.30f),
        start = Offset(bowX, ownLaneTop + 3f),
        end = Offset(bowX, ownLaneBottom - 3f),
        strokeWidth = 1.4f
    )

    // Tiny subtle leader marker pips at top & bottom edge of the water
    if (isLeader) {
        val pipColor = RegattaTheme.GoldTrophy.copy(alpha = 0.55f)
        drawCircle(pipColor, radius = 2.4f, center = Offset(bowX, waterTop + 3.5f))
        drawCircle(pipColor, radius = 2.4f, center = Offset(bowX, waterBottom - 3.5f))
    }
}

/**
 * Subtle horizontal gap connector from a trailing boat's bow to the vertical sightline
 * of the next boat ahead, with a compact pill showing meters up to the next boat.
 */
private fun DrawScope.drawGapToNextBoatIndicator(
    myBowX: Float,
    nextBowX: Float,
    laneTop: Float,
    laneCenterY: Float,
    laneHeight: Float,
    trackLeft: Float,
    trackRight: Float,
    gapMeters: Float,
    chasingLeader: Boolean,
    textMeasurer: TextMeasurer
) {
    val startX = (myBowX + 4f).coerceIn(trackLeft, trackRight)
    val endX = nextBowX.coerceIn(startX, trackRight)
    val spanPx = endX - startX

    val accentTint = if (chasingLeader) {
        RegattaTheme.GoldTrophy
    } else {
        Color(0xFFB3E5FC)
    }

    // 1. Horizontal measurement bridge from bow to next boat's vertical sightline
    if (spanPx >= 7f) {
        drawLine(
            color = accentTint.copy(alpha = if (chasingLeader) 0.65f else 0.48f),
            start = Offset(startX, laneCenterY),
            end = Offset(endX - 1f, laneCenterY),
            strokeWidth = 2.0f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
        )
        // Crisp right-pointing arrowhead meeting the next boat's vertical line
        val arrowSize = 6.0f
        val arrowPath = Path().apply {
            moveTo(endX - arrowSize, laneCenterY - arrowSize * 0.72f)
            lineTo(endX, laneCenterY)
            lineTo(endX - arrowSize, laneCenterY + arrowSize * 0.72f)
        }
        drawPath(
            path = arrowPath,
            color = accentTint.copy(alpha = if (chasingLeader) 0.85f else 0.72f),
            style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    // 2. High-legibility meter-gap readout pill (e.g., "2.4m" or "TIE")
    val labelText = when {
        gapMeters < 0.05f -> "TIE"
        gapMeters < 100f -> String.format("%.1fm", gapMeters)
        else -> "${gapMeters.toInt()}m"
    }
    val gapFontSp = ((laneHeight / density) * 0.19f).coerceIn(14f, 18f).sp
    val textLayout = textMeasurer.measure(
        text = labelText,
        style = TextStyle(
            color = if (chasingLeader) Color(0xFFFFF59D) else Color(0xFFE1F5FE),
            fontSize = gapFontSp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    )
    val textW = textLayout.size.width.toFloat()
    val textH = textLayout.size.height.toFloat()
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val pillW = textW + padX * 2f
    val pillH = textH + padY * 2f

    // Place pill centered on the gap bridge when there's room, otherwise tucked neatly just ahead of the bow
    val preferCenterX = if (spanPx >= pillW + 12f) {
        (startX + endX) * 0.5f
    } else {
        max(endX, startX) + pillW * 0.5f + 5f
    }
    val clampedLeft = (preferCenterX - pillW * 0.5f)
        .coerceIn(trackLeft + 2f, (trackRight - pillW - 4f).coerceAtLeast(trackLeft + 2f))

    // If near the right edge where the pill would crowd the bow, lift it gently near the top of the lane
    val nearRightEdge = (clampedLeft < myBowX + 2f)
    val pillTop = if (nearRightEdge) {
        (laneTop + 3f).coerceAtLeast(laneTop + 1f)
    } else {
        (laneCenterY - pillH * 0.5f).coerceIn(laneTop + 2f, laneTop + laneHeight - pillH - 2f)
    }

    drawRoundRect(
        color = Color(0xDD07142B),
        topLeft = Offset(clampedLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
    )
    drawRoundRect(
        color = accentTint.copy(alpha = if (chasingLeader) 0.78f else 0.55f),
        topLeft = Offset(clampedLeft, pillTop),
        size = Size(pillW, pillH),
        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
        style = Stroke(width = 1.4f)
    )
    drawText(
        textLayoutResult = textLayout,
        topLeft = Offset(clampedLeft + padX, pillTop + padY)
    )
}
