package dk.kodeologi.virtualrace.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import dk.kodeologi.virtualrace.engine.LaneTelemetryState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Detailed 2.5D / 3D-shaded Olympic Sprint Kayak renderer.
 *
 * Lighting model: overhead sun from top-left (-X, -Y), casting soft water shadows toward (+X, +Y),
 * cambered 3D hull shading with turquoise water bounce-light, glossy fiberglass specular highlights,
 * raised cockpit coaming, animated 3D seesaw wing-blade carbon paddle with water splashes,
 * reaching arms with racing gloves, and color-coded 3D sprint helmets.
 */
internal fun kayakHalfLengthForLane(laneHeight: Float): Float =
    (laneHeight * 2.45f).coerceIn(110f, 184f) * 0.5f

internal fun DrawScope.drawSprintKayak(
    centerX: Float,
    centerY: Float,
    laneHeight: Float,
    lane: LaneTelemetryState,
    style: RegattaTheme.KayakLaneStyle,
    isLeader: Boolean,
    isBoosting: Boolean,
    isSpinningOut: Boolean,
    wavePhase: Float,
    isManualPlayer: Boolean = false,
    boostIntensity: Float = if (isBoosting) 1.0f else 0.0f
) {
    val halfLen = kayakHalfLengthForLane(laneHeight)
    val halfWidth = (laneHeight * 0.165f).coerceIn(8.5f, 13.2f)

    val isMoving = lane.displayedSpeedKmh > 0.7f && !lane.disqualified
    val speedRatio = (lane.displayedSpeedKmh / 20.0f).coerceIn(0f, 1.4f)
    val showBoost = isMoving && !isSpinningOut && (isBoosting || lane.isBoosting)
    val effectiveBoostIntensity = (if (boostIntensity > 0f) boostIntensity else lane.boostIntensity).coerceIn(0.35f, 1.0f)

    // Seamless bobbing & stroke dynamics (integer multiples of wavePhase avoid loop seam jumps)
    val bobY = sin(wavePhase * 2f + lane.laneIndex * 1.2f) * (if (isMoving) 0.3f else 0.6f)
    val strokePhase = if (isMoving) {
        lane.interpolatedDistanceMeters * 2.5f + wavePhase * 2f
    } else {
        lane.laneIndex * 0.85f
    }
    val strokeSine = sin(strokePhase)
    val strokeCos = cos(strokePhase)

    // Sprint kayaks track nearly dead straight under rudder control; keep hull yaw subtle except on spin-out
    val yawDeg = when {
        isSpinningOut -> sin(wavePhase * 4f) * 34f + cos(wavePhase * 2f) * 14f
        isMoving -> strokeSine * (0.35f + speedRatio * 0.15f)
        else -> sin(wavePhase + lane.laneIndex * 1.1f) * 0.3f
    }

    val cx = centerX
    val cy = centerY + bobY
    val cockpitX = cx - halfLen * 0.06f

    // Paddle geometry computed up front so water-level shadows render cleanly beneath the hull
    val paddleYawDeg = if (isMoving) strokeSine * 38f else 14f
    val torsoTwistDeg = paddleYawDeg * 0.34f
    val paddleHalfSpan = halfWidth * 2.30f
    val paddleRad = paddleYawDeg * (PI.toFloat() / 180f)
    val shaftDirX = -sin(paddleRad)
    val shaftDirY = cos(paddleRad)

    val paddleTilt = if (isMoving) strokeCos else 0.0f
    val topSubmerged = isMoving && paddleTilt < -0.15f
    val bottomSubmerged = isMoving && paddleTilt > 0.15f
    val topSubmergeAmt = if (isMoving) (-paddleTilt).coerceIn(0f, 1f) else 0f
    val bottomSubmergeAmt = if (isMoving) paddleTilt.coerceIn(0f, 1f) else 0f

    val topSpan = paddleHalfSpan * (1f - 0.14f * topSubmergeAmt + 0.06f * bottomSubmergeAmt)
    val bottomSpan = paddleHalfSpan * (1f - 0.14f * bottomSubmergeAmt + 0.06f * topSubmergeAmt)
    val topTip = Offset(cockpitX - shaftDirX * topSpan, cy - shaftDirY * topSpan)
    val bottomTip = Offset(cockpitX + shaftDirX * bottomSpan, cy + shaftDirY * bottomSpan)

    // 1. Optional subtle player spotlight halo on the water for manual SPACEBAR racer
    if (isManualPlayer && !lane.disqualified) {
        val pulse = 0.55f + 0.35f * sin(wavePhase * 2f)
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color(0xFF00E5FF).copy(alpha = 0.34f * pulse), Color.Transparent),
                center = Offset(cx, cy),
                radius = halfLen * 1.15f
            ),
            topLeft = Offset(cx - halfLen * 1.15f, cy - halfWidth * 2.15f),
            size = Size(halfLen * 2.3f, halfWidth * 4.3f)
        )
    }

    // 2. Water V-Wake & Foam Trails (or chaotic spin-out water swirl!)
    if (isSpinningOut) {
        drawSpinOutWaterSwirl(cx = cx, cy = cy, halfWidth = halfWidth, wavePhase = wavePhase)
    } else if (isMoving) {
        drawWaterWake(
            cx = cx,
            cy = cy,
            halfLen = halfLen,
            halfWidth = halfWidth,
            speedRatio = speedRatio,
            wavePhase = wavePhase,
            laneIndex = lane.laneIndex
        )
    }

    // 3. Rotated 3D Kayak Assembly (attached bow spray, turbo flames, hull, cockpit, paddler & wing paddle)
    rotate(degrees = yawDeg, pivot = Offset(cx, cy)) {
        val hullPath = buildSprintHullPath(cx, cy, halfLen, halfWidth)

        // 3a. Bow Wave Spray anchored directly to the rotated needle bow
        if (isMoving && !isSpinningOut && speedRatio > 0.22f) {
            drawBowWaveSpray(
                bowX = cx + halfLen,
                cy = cy,
                halfWidth = halfWidth,
                speedRatio = speedRatio,
                wavePhase = wavePhase,
                laneIndex = lane.laneIndex
            )
        }

        // 3b. Dynamic Turbo Flames anchored cleanly to the rotated stern
        if (showBoost) {
            drawTurboBoostFlames(
                sternX = cx - halfLen,
                cy = cy,
                halfWidth = halfWidth,
                wavePhase = wavePhase,
                intensity = effectiveBoostIntensity
            )
        }

        // 3c. Soft 3D Underwater Drop Shadow for Hull & Lifted Paddle (beneath the hull so nothing crosses the deck!)
        translate(left = 4.4f, top = 6.6f) {
            drawPath(path = hullPath, color = Color(0x26001026))
        }
        translate(left = 2.8f, top = 4.6f) {
            drawPath(path = hullPath, color = Color(0x4D00142C))
        }
        val paddleShadowShift = Offset(3.5f, 5.2f)
        drawLine(
            color = Color(0x2A001026),
            start = topTip + paddleShadowShift,
            end = bottomTip + paddleShadowShift,
            strokeWidth = 2.8f,
            cap = StrokeCap.Round
        )

        // Water splash rings around whichever blade is dipping into the water
        if (topSubmerged) {
            drawPaddleSplash(
                tip = topTip,
                intensity = topSubmergeAmt * speedRatio.coerceAtLeast(0.45f),
                wavePhase = wavePhase,
                sideSign = -1f
            )
        }
        if (bottomSubmerged) {
            drawPaddleSplash(
                tip = bottomTip,
                intensity = bottomSubmergeAmt * speedRatio.coerceAtLeast(0.45f),
                wavePhase = wavePhase,
                sideSign = 1f
            )
        }

        // 3d. Trailing Carbon Steering Rudder Fin at Stern
        drawSternRudder(
            sternX = cx - halfLen,
            cy = cy,
            halfWidth = halfWidth,
            rudderAngleDeg = if (isMoving) -strokeSine * 2.5f else 0f
        )

        // 3e. Sculpted 3D Kayak Hull & Custom Racing Livery
        val baseHull = if (lane.disqualified) Color(0xFF757575) else style.hullPrimary
        val accentHull = if (lane.disqualified) Color(0xFF424242) else style.hullSecondary

        drawSculpted3DHull(
            hullPath = hullPath,
            cx = cx,
            cy = cy,
            halfLen = halfLen,
            halfWidth = halfWidth,
            baseColor = baseHull,
            accentColor = accentHull,
            isLeader = isLeader,
            isBoosting = showBoost
        )

        // 3f. Raised 3D Fiberglass Cockpit Coaming & Recessed Interior Well
        draw3DCockpit(
            cockpitX = cockpitX,
            cy = cy,
            halfLen = halfLen,
            halfWidth = halfWidth,
            accentColor = accentHull
        )

        // 3g. Wing Paddle Blades + Paddler Torso + Reaching Gloved Arms
        val bladeColor = if (lane.disqualified) Color(0xFF616161) else style.badgeColor

        drawWingPaddleBlade(
            tip = topTip,
            shaftAngleDeg = paddleYawDeg,
            isTopBlade = true,
            bladeColor = bladeColor,
            halfWidth = halfWidth,
            submergeAmt = topSubmergeAmt
        )
        drawWingPaddleBlade(
            tip = bottomTip,
            shaftAngleDeg = paddleYawDeg,
            isTopBlade = false,
            bladeColor = bladeColor,
            halfWidth = halfWidth,
            submergeAmt = bottomSubmergeAmt
        )

        // Paddler Shoulders / Racing Jersey + Life Vest
        drawPaddlerTorso(
            cockpitX = cockpitX,
            cy = cy,
            halfWidth = halfWidth,
            torsoTwistDeg = torsoTwistDeg,
            jerseyColor = if (lane.disqualified) Color(0xFF757575) else style.hullSecondary,
            trimColor = if (lane.disqualified) Color(0xFF424242) else style.hullPrimary
        )

        // Reaching Arms from Shoulders to Paddle Grips
        val gripDist = halfWidth * 0.86f
        val topHand = Offset(cockpitX - shaftDirX * gripDist, cy - shaftDirY * gripDist)
        val bottomHand = Offset(cockpitX + shaftDirX * gripDist, cy + shaftDirY * gripDist)

        val torsoRad = torsoTwistDeg * (PI.toFloat() / 180f)
        val shOffset = halfWidth * 0.56f
        val topShoulder = Offset(
            cockpitX - 1.5f + sin(torsoRad) * shOffset,
            cy - cos(torsoRad) * shOffset
        )
        val bottomShoulder = Offset(
            cockpitX - 1.5f - sin(torsoRad) * shOffset,
            cy + cos(torsoRad) * shOffset
        )

        val sleeveColor = if (lane.disqualified) Color(0xFF616161) else style.hullPrimary
        drawPaddlerArm(topShoulder, topHand, halfWidth, sleeveColor)
        drawPaddlerArm(bottomShoulder, bottomHand, halfWidth, sleeveColor)

        // Carbon-Fiber Paddle Shaft with 3D specular highlight
        drawLine(
            color = Color(0xFF181C20),
            start = topTip,
            end = bottomTip,
            strokeWidth = (halfWidth * 0.24f).coerceIn(2.2f, 3.2f),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White.copy(alpha = 0.42f),
            start = Offset(topTip.x - 0.5f, topTip.y - 0.5f),
            end = Offset(bottomTip.x - 0.5f, bottomTip.y - 0.5f),
            strokeWidth = 0.9f,
            cap = StrokeCap.Round
        )

        // White Racing Gloves gripping the shaft
        val gloveRadius = (halfWidth * 0.23f).coerceIn(2.2f, 3.4f)
        drawRacingGlove(topHand, gloveRadius)
        drawRacingGlove(bottomHand, gloveRadius)

        // 3h. 3D Paddler Helmet / Racing Cap
        drawPaddlerHelmet(
            headCenter = Offset(cockpitX + halfWidth * 0.16f, cy),
            headRadius = halfWidth * 0.60f,
            style = style,
            disqualified = lane.disqualified
        )
    }

    // 4. Leader Floating 3D Golden Crown (upright ahead of bow with gentle tilt & sparkle)
    if (isLeader) {
        drawLeaderCrown(
            crownCenter = Offset(cx + halfLen + 12f, cy + sin(wavePhase * 2f) * 2.0f),
            scale = (halfWidth / 12f).coerceIn(0.78f, 1.18f),
            wavePhase = wavePhase
        )
    }

    // 5. False Start / Spin-Out / DSQ Orbiting 3D Cartoon Stars
    if (lane.falseStartCount > 0 || lane.disqualified || isSpinningOut) {
        drawDizzyStars(
            center = Offset(cockpitX, cy),
            halfWidth = halfWidth,
            wavePhase = wavePhase,
            isDisqualified = lane.disqualified
        )
    }

    // 6. Finish Celebration Sparkles!
    if (lane.isFinished && !lane.disqualified) {
        drawFinishSparkles(
            cx = cx,
            cy = cy,
            halfLen = halfLen,
            halfWidth = halfWidth,
            wavePhase = wavePhase,
            laneIndex = lane.laneIndex
        )
    }
}

private fun buildSprintHullPath(
    cx: Float,
    cy: Float,
    halfLen: Float,
    halfWidth: Float
): Path = Path().apply {
    // Sleek razor-sharp 5.2m ICF K1 sprint kayak silhouette
    moveTo(cx + halfLen, cy) // Needle bow tip
    cubicTo(
        cx + halfLen * 0.66f, cy - halfWidth * 0.52f,
        cx + halfLen * 0.22f, cy - halfWidth * 1.02f,
        cx - halfLen * 0.14f, cy - halfWidth * 0.94f
    )
    cubicTo(
        cx - halfLen * 0.54f, cy - halfWidth * 0.72f,
        cx - halfLen * 0.84f, cy - halfWidth * 0.28f,
        cx - halfLen, cy
    )
    cubicTo(
        cx - halfLen * 0.84f, cy + halfWidth * 0.28f,
        cx - halfLen * 0.54f, cy + halfWidth * 0.72f,
        cx - halfLen * 0.14f, cy + halfWidth * 0.94f
    )
    cubicTo(
        cx + halfLen * 0.22f, cy + halfWidth * 1.02f,
        cx + halfLen * 0.66f, cy + halfWidth * 0.52f,
        cx + halfLen, cy
    )
    close()
}

private fun DrawScope.drawSpinOutWaterSwirl(
    cx: Float,
    cy: Float,
    halfWidth: Float,
    wavePhase: Float
) {
    val r1 = halfWidth * 1.75f
    val r2 = halfWidth * 2.25f
    val sweepStart = (wavePhase * 180f / PI.toFloat()) * 2f
    drawArc(
        color = Color.White.copy(alpha = 0.55f),
        startAngle = sweepStart,
        sweepAngle = 125f,
        useCenter = false,
        topLeft = Offset(cx - r1, cy - r1 * 0.75f),
        size = Size(r1 * 2f, r1 * 1.5f),
        style = Stroke(width = 2.2f, cap = StrokeCap.Round)
    )
    drawArc(
        color = Color(0xFFB3E5FC).copy(alpha = 0.45f),
        startAngle = sweepStart + 180f,
        sweepAngle = 115f,
        useCenter = false,
        topLeft = Offset(cx - r2, cy - r2 * 0.72f),
        size = Size(r2 * 2f, r2 * 1.44f),
        style = Stroke(width = 1.8f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawWaterWake(
    cx: Float,
    cy: Float,
    halfLen: Float,
    halfWidth: Float,
    speedRatio: Float,
    wavePhase: Float,
    laneIndex: Int
) {
    val sternX = cx - halfLen
    val wakeLen = (26f + 38f * speedRatio)
    val wakeSpread = halfWidth * (1.35f + speedRatio * 1.02f)

    // Soft translucent inner churn wake
    val innerWake = Path().apply {
        moveTo(sternX + 8f, cy)
        lineTo(sternX - wakeLen, cy - wakeSpread)
        quadraticTo(sternX - wakeLen * 0.62f, cy, sternX - wakeLen, cy + wakeSpread)
        close()
    }
    drawPath(
        path = innerWake,
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color(0x66B3E5FC), Color.White.copy(alpha = 0.56f)),
            startX = sternX - wakeLen,
            endX = sternX + 8f
        )
    )

    // Crisp outer V-wake foam ribbons trailing from near the mid-hull
    val ribbonStart = cx - halfLen * 0.22f
    val upperRibbon = Path().apply {
        moveTo(ribbonStart, cy - halfWidth * 0.88f)
        quadraticTo(
            sternX - wakeLen * 0.35f,
            cy - wakeSpread * 0.75f,
            sternX - wakeLen * 1.08f,
            cy - wakeSpread * 1.15f
        )
    }
    val lowerRibbon = Path().apply {
        moveTo(ribbonStart, cy + halfWidth * 0.88f)
        quadraticTo(
            sternX - wakeLen * 0.35f,
            cy + wakeSpread * 0.75f,
            sternX - wakeLen * 1.08f,
            cy + wakeSpread * 1.15f
        )
    }
    val ribbonBrush = Brush.horizontalGradient(
        listOf(Color.Transparent, Color.White.copy(alpha = 0.68f)),
        startX = sternX - wakeLen * 1.08f,
        endX = ribbonStart
    )
    drawPath(upperRibbon, brush = ribbonBrush, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    drawPath(lowerRibbon, brush = ribbonBrush, style = Stroke(width = 2.2f, cap = StrokeCap.Round))

    // Animated wake foam bubbles drifting behind stern
    for (b in 0..4) {
        val rawT = ((wavePhase / (2f * PI.toFloat())) + b * 0.2f + laneIndex * 0.13f) % 1f
        val bx = sternX - rawT * wakeLen * 0.92f
        val side = if (b % 2 == 0) -1f else 1f
        val by = cy + side * (halfWidth * 0.25f + rawT * wakeSpread * 0.62f)
        val radius = (1f - rawT) * (2.4f + speedRatio * 1.1f)
        if (radius > 0.4f) {
            drawCircle(
                color = Color.White.copy(alpha = (1f - rawT) * 0.72f),
                radius = radius,
                center = Offset(bx, by)
            )
        }
    }
}

private fun DrawScope.drawBowWaveSpray(
    bowX: Float,
    cy: Float,
    halfWidth: Float,
    speedRatio: Float,
    wavePhase: Float,
    laneIndex: Int
) {
    val sprayAlpha = ((speedRatio - 0.2f) * 0.85f).coerceIn(0f, 0.78f)
    val sprayLen = 10f + 13f * speedRatio
    val spraySpread = halfWidth * (0.95f + 0.35f * speedRatio)

    val upperBow = Path().apply {
        moveTo(bowX - 1f, cy)
        quadraticTo(bowX - sprayLen * 0.45f, cy - spraySpread * 0.72f, bowX - sprayLen, cy - spraySpread)
    }
    val lowerBow = Path().apply {
        moveTo(bowX - 1f, cy)
        quadraticTo(bowX - sprayLen * 0.45f, cy + spraySpread * 0.72f, bowX - sprayLen, cy + spraySpread)
    }
    drawPath(
        upperBow,
        color = Color.White.copy(alpha = sprayAlpha),
        style = Stroke(width = 1.9f, cap = StrokeCap.Round)
    )
    drawPath(
        lowerBow,
        color = Color.White.copy(alpha = sprayAlpha),
        style = Stroke(width = 1.9f, cap = StrokeCap.Round)
    )

    // Tiny animated spray droplets flicking off bow
    val dropPulse = ((wavePhase / (2f * PI.toFloat())) * 2f + laneIndex * 0.3f) % 1f
    val dx = bowX - 3f - dropPulse * sprayLen * 0.85f
    val dy = spraySpread * (0.55f + dropPulse * 0.55f)
    val dropAlpha = (1f - dropPulse) * sprayAlpha
    drawCircle(Color.White.copy(alpha = dropAlpha), radius = 1.6f, center = Offset(dx, cy - dy))
    drawCircle(Color.White.copy(alpha = dropAlpha), radius = 1.6f, center = Offset(dx, cy + dy))
}

private fun DrawScope.drawTurboBoostFlames(
    sternX: Float,
    cy: Float,
    halfWidth: Float,
    wavePhase: Float,
    intensity: Float = 1.0f
) {
    val scale = 0.55f + 0.55f * intensity.coerceIn(0.2f, 1.0f)
    val flicker = (sin(wavePhase * 6f) * 5.5f + cos(wavePhase * 4f) * 2.5f) * scale
    val outerLen = 30f * scale + flicker
    val midLen = 21f * scale + flicker * 0.7f
    val coreLen = 12f * scale + flicker * 0.45f

    // Outer crimson-orange turbo cone
    val outerPath = Path().apply {
        moveTo(sternX + 2f, cy - halfWidth * 0.58f)
        quadraticTo(sternX - outerLen * 0.55f, cy - halfWidth * 0.50f, sternX - outerLen, cy)
        quadraticTo(sternX - outerLen * 0.55f, cy + halfWidth * 0.50f, sternX + 2f, cy + halfWidth * 0.58f)
        close()
    }
    drawPath(
        path = outerPath,
        brush = Brush.horizontalGradient(
            listOf(Color(0x00FF1744), Color(0xFFFF1744), Color(0xFFFF9100)),
            startX = sternX - outerLen,
            endX = sternX + 2f
        )
    )

    // Mid bright golden flame tongue
    val midPath = Path().apply {
        moveTo(sternX + 1f, cy - halfWidth * 0.38f)
        lineTo(sternX - midLen, cy)
        lineTo(sternX + 1f, cy + halfWidth * 0.38f)
        close()
    }
    drawPath(
        path = midPath,
        brush = Brush.horizontalGradient(
            listOf(Color(0xFFFF9100), Color(0xFFFFEA00)),
            startX = sternX - midLen,
            endX = sternX + 1f
        )
    )

    // White-hot core
    val corePath = Path().apply {
        moveTo(sternX + 1f, cy - halfWidth * 0.20f)
        lineTo(sternX - coreLen, cy)
        lineTo(sternX + 1f, cy + halfWidth * 0.20f)
        close()
    }
    drawPath(path = corePath, color = Color(0xFFFFFDE7))

    // Flying golden turbo sparks
    for (s in 0..3) {
        val t = ((wavePhase / (2f * PI.toFloat())) * 3f + s * 0.25f) % 1f
        val sx = sternX - 4f - t * (outerLen + 14f)
        val sy = cy + sin(s * 2.1f + wavePhase * 2f) * (halfWidth * 0.85f * t + 2f)
        drawCircle(
            color = Color(0xFFFFEA00).copy(alpha = 1f - t),
            radius = (1f - t) * 2.3f,
            center = Offset(sx, sy)
        )
    }
}

private fun DrawScope.drawSternRudder(
    sternX: Float,
    cy: Float,
    halfWidth: Float,
    rudderAngleDeg: Float
) {
    rotate(degrees = rudderAngleDeg, pivot = Offset(sternX + 1f, cy)) {
        val finLen = (halfWidth * 0.45f).coerceIn(4.2f, 6.8f)
        val rudderPath = Path().apply {
            moveTo(sternX + 2f, cy - 1.5f)
            lineTo(sternX - finLen, cy - 0.7f)
            lineTo(sternX - finLen, cy + 0.7f)
            lineTo(sternX + 2f, cy + 1.5f)
            close()
        }
        drawPath(rudderPath, color = Color(0xFFCFD8DC))
        drawPath(rudderPath, color = Color(0xFF37474F), style = Stroke(width = 0.8f))
    }
}

private fun DrawScope.drawSculpted3DHull(
    hullPath: Path,
    cx: Float,
    cy: Float,
    halfLen: Float,
    halfWidth: Float,
    baseColor: Color,
    accentColor: Color,
    isLeader: Boolean,
    isBoosting: Boolean
) {
    // 1. 3D Freeboard Side Wall / Lower Hull Lip (gives distinct 3/4 vertical hull depth)
    translate(left = 0.8f, top = 2.6f) {
        drawPath(
            path = hullPath,
            brush = Brush.horizontalGradient(
                listOf(baseColor.darken(0.62f), baseColor.darken(0.50f), baseColor.darken(0.38f)),
                startX = cx - halfLen,
                endX = cx + halfLen
            )
        )
    }

    // 2. Main 3D Cambered Deck Volume (top-lit upper gunwale -> core shadow -> cyan water bounce-light)
    drawPath(
        path = hullPath,
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to baseColor.lighten(0.40f),
                0.22f to baseColor.lighten(0.14f),
                0.50f to baseColor,
                0.80f to baseColor.darken(0.34f),
                0.94f to baseColor.darken(0.46f),
                1.00f to Color(0xFF29B6F6).blendWith(baseColor.darken(0.30f), 0.72f)
            ),
            startY = cy - halfWidth,
            endY = cy + halfWidth
        )
    )

    // 3. Clipped 3D Deck Graphics, Racing Stripes, Nose Cone & Specular Gloss
    clipPath(hullPath) {
        // Tapered dynamic aft deck racing livery
        val aftAccentPath = Path().apply {
            moveTo(cx - halfLen, cy - halfWidth)
            lineTo(cx - halfLen * 0.24f, cy - halfWidth)
            lineTo(cx - halfLen * 0.44f, cy)
            lineTo(cx - halfLen * 0.24f, cy + halfWidth)
            lineTo(cx - halfLen, cy + halfWidth)
            close()
        }
        drawPath(
            path = aftAccentPath,
            brush = Brush.verticalGradient(
                listOf(accentColor.lighten(0.25f), accentColor, accentColor.darken(0.34f)),
                startY = cy - halfWidth,
                endY = cy + halfWidth
            )
        )

        // Sporty Racing Chevrons on Foredeck (pointing forward toward bow)
        val chev1X = cx + halfLen * 0.38f
        val chev2X = cx + halfLen * 0.56f
        for ((idx, tipX) in listOf(chev1X, chev2X).withIndex()) {
            val chevronWidth = halfLen * (if (idx == 0) 0.12f else 0.09f)
            val chevronPath = Path().apply {
                moveTo(tipX, cy)
                lineTo(tipX - chevronWidth * 1.15f, cy - halfWidth * 1.1f)
                lineTo(tipX - chevronWidth * 1.90f, cy - halfWidth * 1.1f)
                lineTo(tipX - chevronWidth * 0.75f, cy)
                lineTo(tipX - chevronWidth * 1.90f, cy + halfWidth * 1.1f)
                lineTo(tipX - chevronWidth * 1.15f, cy + halfWidth * 1.1f)
                close()
            }
            val fillCol = if (idx == 0) accentColor else Color.White.copy(alpha = 0.94f)
            drawPath(
                path = chevronPath,
                brush = Brush.verticalGradient(
                    listOf(fillCol.lighten(0.22f), fillCol, fillCol.darken(0.28f)),
                    startY = cy - halfWidth,
                    endY = cy + halfWidth
                )
            )
        }

        // Mini Checkered Racing Accent Band near Stern
        val checkX = cx - halfLen * 0.66f
        val sq = (halfWidth * 0.30f).coerceIn(2.5f, 4.2f)
        for (row in -3..3) {
            for (col in 0..1) {
                val isWhite = (row + col) % 2 == 0
                drawRect(
                    color = if (isWhite) Color.White.copy(alpha = 0.90f) else Color(0xFF1A1A1A).copy(alpha = 0.86f),
                    topLeft = Offset(checkX + col * sq, cy + row * sq),
                    size = Size(sq, sq)
                )
            }
        }

        // Crisp White Olympic Bow Nose Cone with 3D vertical shading
        val noseStartX = cx + halfLen * 0.79f
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.White, Color(0xFFECEFF1), Color(0xFF90A4AE)),
                startY = cy - halfWidth * 0.55f,
                endY = cy + halfWidth * 0.55f
            ),
            topLeft = Offset(noseStartX, cy - halfWidth),
            size = Size(halfLen * 0.25f, halfWidth * 2f)
        )

        // 3D Peaked Foredeck & Aft-Deck Centerline Spine Ridge (bright above, soft shadow below)
        drawLine(
            color = Color.White.copy(alpha = 0.38f),
            start = Offset(cx + halfLen * 0.18f, cy - 0.8f),
            end = Offset(cx + halfLen * 0.94f, cy - 0.4f),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.20f),
            start = Offset(cx + halfLen * 0.18f, cy + 0.9f),
            end = Offset(cx + halfLen * 0.94f, cy + 0.5f),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White.copy(alpha = 0.26f),
            start = Offset(cx - halfLen * 0.90f, cy - 0.6f),
            end = Offset(cx - halfLen * 0.30f, cy - 0.8f),
            strokeWidth = 1.2f,
            cap = StrokeCap.Round
        )

        // Foredeck Bungee Cord Rigging detail in front of cockpit
        val bungeeX1 = cx + halfLen * 0.19f
        val bungeeX2 = cx + halfLen * 0.30f
        val bungeeY = halfWidth * 0.44f
        drawLine(
            color = Color(0xAA102030),
            start = Offset(bungeeX1, cy - bungeeY),
            end = Offset(bungeeX2, cy + bungeeY * 0.72f),
            strokeWidth = 1.0f
        )
        drawLine(
            color = Color(0xAA102030),
            start = Offset(bungeeX1, cy + bungeeY),
            end = Offset(bungeeX2, cy - bungeeY * 0.72f),
            strokeWidth = 1.0f
        )

        // Sweeping Glossy Fiberglass Specular Highlight along sunlit top-left deck curve
        val glossPath = Path().apply {
            moveTo(cx - halfLen * 0.72f, cy - halfWidth * 0.42f)
            cubicTo(
                cx - halfLen * 0.22f, cy - halfWidth * 0.72f,
                cx + halfLen * 0.25f, cy - halfWidth * 0.72f,
                cx + halfLen * 0.85f, cy - halfWidth * 0.14f
            )
        }
        drawPath(
            path = glossPath,
            brush = Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.52f),
                    Color.White.copy(alpha = 0.66f),
                    Color.White.copy(alpha = 0.18f)
                ),
                startX = cx - halfLen * 0.72f,
                endX = cx + halfLen * 0.85f
            ),
            style = Stroke(width = (halfWidth * 0.22f).coerceIn(1.8f, 3.0f), cap = StrokeCap.Round)
        )

        // Soft ambient shading along bottom gunwale inside clip
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f)),
                startY = cy + halfWidth * 0.25f,
                endY = cy + halfWidth
            ),
            topLeft = Offset(cx - halfLen, cy + halfWidth * 0.25f),
            size = Size(halfLen * 2f, halfWidth * 0.85f)
        )
    }

    // 4. Crisp Hull Rim & Golden Leader / Boost Glow
    if (isBoosting) {
        drawPath(
            path = hullPath,
            color = Color(0x88FFEA00),
            style = Stroke(width = 4.2f, join = StrokeJoin.Round)
        )
    }
    drawPath(
        path = hullPath,
        color = if (isLeader) RegattaTheme.GoldTrophy else Color(0xDD0B1A30),
        style = Stroke(width = if (isLeader) 2.4f else 1.5f, join = StrokeJoin.Round)
    )
    // Thin bright upper sunlit bevel accent
    val topRimPath = Path().apply {
        moveTo(cx - halfLen * 0.92f, cy - halfWidth * 0.16f)
        cubicTo(
            cx - halfLen * 0.45f, cy - halfWidth * 0.84f,
            cx + halfLen * 0.20f, cy - halfWidth * 1.00f,
            cx + halfLen * 0.94f, cy - halfWidth * 0.08f
        )
    }
    drawPath(
        path = topRimPath,
        color = Color.White.copy(alpha = 0.58f),
        style = Stroke(width = 1.0f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.draw3DCockpit(
    cockpitX: Float,
    cy: Float,
    halfLen: Float,
    halfWidth: Float,
    accentColor: Color
) {
    val cockpitW = (halfLen * 0.38f).coerceIn(22f, 36f)
    val cockpitH = halfWidth * 1.24f

    // Drop shadow cast by raised coaming lip onto the deck
    drawOval(
        color = Color(0x45000000),
        topLeft = Offset(cockpitX - cockpitW * 0.5f + 1.4f, cy - cockpitH * 0.5f + 1.8f),
        size = Size(cockpitW, cockpitH)
    )

    // Raised 3D molded fiberglass coaming rim
    drawOval(
        brush = Brush.verticalGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFCFD8DC), Color(0xFF546E7A)),
            startY = cy - cockpitH * 0.5f,
            endY = cy + cockpitH * 0.5f
        ),
        topLeft = Offset(cockpitX - cockpitW * 0.5f, cy - cockpitH * 0.5f),
        size = Size(cockpitW, cockpitH)
    )

    // Deep recessed cockpit interior well
    val inset = 2.0f
    val innerW = (cockpitW - inset * 2f).coerceAtLeast(8f)
    val innerH = (cockpitH - inset * 2f).coerceAtLeast(6f)
    drawOval(
        brush = Brush.radialGradient(
            listOf(Color(0xFF263238), Color(0xFF0E141B), Color(0xFF06090D)),
            center = Offset(cockpitX + innerW * 0.14f, cy + innerH * 0.10f),
            radius = innerW * 0.65f
        ),
        topLeft = Offset(cockpitX - innerW * 0.5f, cy - innerH * 0.5f),
        size = Size(innerW, innerH)
    )

    // Spray-skirt collar accent rim inside cockpit
    drawOval(
        color = accentColor.darken(0.15f).copy(alpha = 0.72f),
        topLeft = Offset(cockpitX - innerW * 0.5f + 0.8f, cy - innerH * 0.5f + 0.8f),
        size = Size(innerW - 1.6f, innerH - 1.6f),
        style = Stroke(width = 1.3f)
    )
}

private fun DrawScope.drawPaddleSplash(
    tip: Offset,
    intensity: Float,
    wavePhase: Float,
    sideSign: Float
) {
    val clamped = intensity.coerceIn(0.15f, 1.0f)
    val pulse = ((wavePhase / (2f * PI.toFloat())) * 3f) % 1f
    val ringR = 4.5f + 7.5f * clamped + pulse * 3.5f

    // Expanding water splash ripple ring
    drawCircle(
        color = Color.White.copy(alpha = (1f - pulse * 0.65f) * 0.68f * clamped),
        radius = ringR,
        center = tip,
        style = Stroke(width = 1.6f)
    )
    // Bright foam burst right around the blade entry
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.78f * clamped), Color.Transparent),
            center = tip,
            radius = 6.5f + 3f * clamped
        ),
        radius = 6.5f + 3f * clamped,
        center = tip
    )
    // 3 little animated splash droplets
    for (d in -1..1) {
        val angle = (if (sideSign < 0f) -PI.toFloat() * 0.5f else PI.toFloat() * 0.5f) + d * 0.62f
        val dist = 5f + 5.5f * clamped + pulse * 4f
        val dx = tip.x + cos(angle) * dist - 2.5f
        val dy = tip.y + sin(angle) * dist
        drawCircle(
            color = Color.White.copy(alpha = (1f - pulse * 0.5f) * 0.82f * clamped),
            radius = 1.4f,
            center = Offset(dx, dy)
        )
    }
}

private fun DrawScope.drawWingPaddleBlade(
    tip: Offset,
    shaftAngleDeg: Float,
    isTopBlade: Boolean,
    bladeColor: Color,
    halfWidth: Float,
    submergeAmt: Float
) {
    val bladeW = (halfWidth * 0.60f).coerceIn(5.8f, 8.8f)
    val bladeH = (halfWidth * 0.94f).coerceIn(8.5f, 13.4f) * (1f - 0.18f * submergeAmt)
    val dir = if (isTopBlade) -1f else 1f

    rotate(degrees = shaftAngleDeg, pivot = tip) {
        // Sculpted curved wing-paddle teardrop blade
        val bladePath = Path().apply {
            moveTo(tip.x, tip.y - dir * bladeH * 0.35f)
            cubicTo(
                tip.x + bladeW * 0.70f, tip.y + dir * bladeH * 0.05f,
                tip.x + bladeW * 0.60f, tip.y + dir * bladeH * 0.65f,
                tip.x + bladeW * 0.06f, tip.y + dir * bladeH * 0.74f
            )
            cubicTo(
                tip.x - bladeW * 0.46f, tip.y + dir * bladeH * 0.68f,
                tip.x - bladeW * 0.54f, tip.y + dir * bladeH * 0.10f,
                tip.x, tip.y - dir * bladeH * 0.35f
            )
            close()
        }

        val alpha = 1f - 0.25f * submergeAmt
        drawPath(
            path = bladePath,
            brush = Brush.horizontalGradient(
                listOf(
                    bladeColor.darken(0.30f).copy(alpha = alpha),
                    bladeColor.copy(alpha = alpha),
                    bladeColor.lighten(0.36f).copy(alpha = alpha)
                ),
                startX = tip.x - bladeW * 0.55f,
                endX = tip.x + bladeW * 0.70f
            )
        )
        // Carbon center rib along wing blade
        drawLine(
            color = Color(0x55000000).copy(alpha = 0.35f * alpha),
            start = Offset(tip.x, tip.y - dir * bladeH * 0.28f),
            end = Offset(tip.x + bladeW * 0.06f, tip.y + dir * bladeH * 0.58f),
            strokeWidth = 1.1f,
            cap = StrokeCap.Round
        )
        // Crisp white racing tip stripe
        drawLine(
            color = Color.White.copy(alpha = 0.88f * alpha),
            start = Offset(tip.x - bladeW * 0.28f, tip.y + dir * bladeH * 0.45f),
            end = Offset(tip.x + bladeW * 0.36f, tip.y + dir * bladeH * 0.45f),
            strokeWidth = 1.7f,
            cap = StrokeCap.Round
        )
        // Crisp outline
        drawPath(
            path = bladePath,
            color = Color(0xCC0D1B2A).copy(alpha = alpha),
            style = Stroke(width = 1.0f)
        )
    }
}

private fun DrawScope.drawPaddlerTorso(
    cockpitX: Float,
    cy: Float,
    halfWidth: Float,
    torsoTwistDeg: Float,
    jerseyColor: Color,
    trimColor: Color
) {
    rotate(degrees = torsoTwistDeg, pivot = Offset(cockpitX, cy)) {
        val shoulderW = halfWidth * 0.84f
        val shoulderH = halfWidth * 1.24f
        val tx = cockpitX - 1.5f

        // Subtle torso drop shadow
        drawRoundRect(
            color = Color(0x44000000),
            topLeft = Offset(tx - shoulderW * 0.5f + 1.2f, cy - shoulderH * 0.5f + 1.5f),
            size = Size(shoulderW, shoulderH),
            cornerRadius = CornerRadius(shoulderW * 0.5f, shoulderW * 0.5f)
        )

        // 3D Shaded Shoulders & Racing Singlet
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(jerseyColor.lighten(0.28f), jerseyColor, jerseyColor.darken(0.34f)),
                startY = cy - shoulderH * 0.5f,
                endY = cy + shoulderH * 0.5f
            ),
            topLeft = Offset(tx - shoulderW * 0.5f, cy - shoulderH * 0.5f),
            size = Size(shoulderW, shoulderH),
            cornerRadius = CornerRadius(shoulderW * 0.5f, shoulderW * 0.5f)
        )

        // Sporty PFD / Racing Jersey Shoulder Straps
        drawLine(
            color = trimColor.lighten(0.12f),
            start = Offset(tx - shoulderW * 0.25f, cy - shoulderH * 0.34f),
            end = Offset(tx + shoulderW * 0.26f, cy - shoulderH * 0.34f),
            strokeWidth = 2.0f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = trimColor.lighten(0.12f),
            start = Offset(tx - shoulderW * 0.25f, cy + shoulderH * 0.34f),
            end = Offset(tx + shoulderW * 0.26f, cy + shoulderH * 0.34f),
            strokeWidth = 2.0f,
            cap = StrokeCap.Round
        )

        // Crisp outline
        drawRoundRect(
            color = Color(0xBB0D1B2A),
            topLeft = Offset(tx - shoulderW * 0.5f, cy - shoulderH * 0.5f),
            size = Size(shoulderW, shoulderH),
            cornerRadius = CornerRadius(shoulderW * 0.5f, shoulderW * 0.5f),
            style = Stroke(width = 1.1f)
        )
    }
}

private fun DrawScope.drawPaddlerArm(
    shoulder: Offset,
    hand: Offset,
    halfWidth: Float,
    sleeveColor: Color
) {
    val armStroke = (halfWidth * 0.30f).coerceIn(2.6f, 4.2f)
    drawLine(
        color = Color(0xAA0D1B2A),
        start = shoulder,
        end = hand,
        strokeWidth = armStroke + 1.4f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = sleeveColor.lighten(0.10f),
        start = shoulder,
        end = hand,
        strokeWidth = armStroke,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawRacingGlove(
    hand: Offset,
    radius: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White, Color(0xFFE0E0E0), Color(0xFF90A4AE)),
            center = Offset(hand.x - radius * 0.25f, hand.y - radius * 0.25f),
            radius = radius * 1.25f
        ),
        radius = radius,
        center = hand
    )
    drawCircle(
        color = Color(0xAA102030),
        radius = radius,
        center = hand,
        style = Stroke(width = 0.9f)
    )
}

private fun DrawScope.drawPaddlerHelmet(
    headCenter: Offset,
    headRadius: Float,
    style: RegattaTheme.KayakLaneStyle,
    disqualified: Boolean
) {
    val hx = headCenter.x
    val hy = headCenter.y
    val r = headRadius
    val mainCol = if (disqualified) Color(0xFF9E9E9E) else style.helmetColor
    val stripeCol = if (disqualified) Color(0xFF616161) else style.hullSecondary

    // 1. Soft 3D Head Shadow cast onto shoulders
    drawCircle(
        color = Color(0x4D000000),
        radius = r * 1.04f,
        center = Offset(hx + 1.8f, hy + 2.2f)
    )

    // 2. Forward curved visor bill
    val visorPath = Path().apply {
        moveTo(hx + r * 0.15f, hy - r * 0.78f)
        quadraticTo(hx + r * 1.46f, hy - r * 0.46f, hx + r * 1.40f, hy)
        quadraticTo(hx + r * 1.46f, hy + r * 0.46f, hx + r * 0.15f, hy + r * 0.78f)
        close()
    }
    drawPath(
        path = visorPath,
        brush = Brush.verticalGradient(
            listOf(mainCol.lighten(0.16f), mainCol.darken(0.28f)),
            startY = hy - r * 0.78f,
            endY = hy + r * 0.78f
        )
    )
    drawPath(visorPath, color = Color(0xCC0D1B2A), style = Stroke(width = 1.0f))

    // 3. 3D Glossy Racing Cap / Helmet Dome
    draw3DSphere(headCenter, r, mainCol)

    // 4. Centerline racing stripe & dark goggle/visor rim
    drawLine(
        color = stripeCol.copy(alpha = 0.85f),
        start = Offset(hx - r * 0.82f, hy),
        end = Offset(hx + r * 0.72f, hy),
        strokeWidth = (r * 0.28f).coerceIn(1.6f, 3.0f),
        cap = StrokeCap.Round
    )
    drawArc(
        color = Color(0xCC102030),
        startAngle = -52f,
        sweepAngle = 104f,
        useCenter = false,
        topLeft = Offset(hx - r * 0.82f, hy - r * 0.82f),
        size = Size(r * 1.64f, r * 1.64f),
        style = Stroke(width = 2.0f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.draw3DSphere(
    center: Offset,
    radius: Float,
    color: Color
) {
    val lightCenter = Offset(center.x - radius * 0.32f, center.y - radius * 0.32f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to color.lighten(0.55f),
                0.35f to color.lighten(0.15f),
                0.72f to color,
                1.00f to color.darken(0.45f)
            ),
            center = lightCenter,
            radius = radius * 1.45f
        ),
        radius = radius,
        center = center
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.58f),
        radius = radius * 0.22f,
        center = Offset(center.x - radius * 0.30f, center.y - radius * 0.32f)
    )
    drawCircle(
        color = Color(0xCC0D1B2A),
        radius = radius,
        center = center,
        style = Stroke(width = 1.2f)
    )
}

private fun DrawScope.drawLeaderCrown(
    crownCenter: Offset,
    scale: Float,
    wavePhase: Float
) {
    val cx = crownCenter.x
    val cy = crownCenter.y
    val w = 13f * scale
    val h = 10f * scale
    val tiltDeg = sin(wavePhase * 2f) * 6f

    // Soft golden halo glow
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0x77FFD700), Color.Transparent),
            center = crownCenter,
            radius = w * 1.20f
        ),
        radius = w * 1.20f,
        center = crownCenter
    )

    rotate(degrees = tiltDeg, pivot = crownCenter) {
        val crownPath = Path().apply {
            moveTo(cx - w * 0.50f, cy + h * 0.45f)
            lineTo(cx - w * 0.62f, cy - h * 0.35f)
            lineTo(cx - w * 0.24f, cy + h * 0.02f)
            lineTo(cx, cy - h * 0.58f)
            lineTo(cx + w * 0.24f, cy + h * 0.02f)
            lineTo(cx + w * 0.62f, cy - h * 0.35f)
            lineTo(cx + w * 0.50f, cy + h * 0.45f)
            close()
        }

        translate(left = 1.4f, top = 2.0f) {
            drawPath(crownPath, color = Color(0x55000000))
        }

        drawPath(
            path = crownPath,
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFFF59D), Color(0xFFFFD700), Color(0xFFFF8F00)),
                startY = cy - h * 0.6f,
                endY = cy + h * 0.5f
            )
        )
        drawPath(
            path = crownPath,
            color = Color(0xFFB26A00),
            style = Stroke(width = 1.2f, join = StrokeJoin.Round)
        )

        val pearlR = 1.4f * scale
        drawCircle(Color(0xFFFFFDE7), radius = pearlR, center = Offset(cx - w * 0.62f, cy - h * 0.38f))
        drawCircle(Color(0xFFFFFDE7), radius = pearlR * 1.15f, center = Offset(cx, cy - h * 0.62f))
        drawCircle(Color(0xFFFFFDE7), radius = pearlR, center = Offset(cx + w * 0.62f, cy - h * 0.38f))

        drawCircle(Color(0xFFFF1744), radius = 1.5f * scale, center = Offset(cx, cy + h * 0.18f))
        drawCircle(Color(0xFF00E5FF), radius = 1.2f * scale, center = Offset(cx - w * 0.30f, cy + h * 0.22f))
        drawCircle(Color(0xFF00E676), radius = 1.2f * scale, center = Offset(cx + w * 0.30f, cy + h * 0.22f))

        val twinkle = abs(sin(wavePhase * 3f))
        if (twinkle > 0.2f) {
            drawFourPointSparkle(
                center = Offset(cx + w * 0.42f, cy - h * 0.68f),
                radius = 3.8f * scale * twinkle,
                color = Color.White
            )
        }
    }
}

private fun DrawScope.drawDizzyStars(
    center: Offset,
    halfWidth: Float,
    wavePhase: Float,
    isDisqualified: Boolean
) {
    val rx = halfWidth * 1.48f
    val ry = halfWidth * 1.10f
    for (s in 0..2) {
        val angle = wavePhase * 3f + s * (2f * PI.toFloat() / 3f)
        val sx = center.x + cos(angle) * rx
        val sy = center.y + sin(angle) * ry
        val depthScale = 0.85f + 0.28f * sin(angle)
        val starR = 4.6f * depthScale
        val fill = if (isDisqualified) RegattaTheme.AlertRed else RegattaTheme.AccentYellow
        val stroke = if (isDisqualified) Color(0xFF7F0000) else Color(0xFFFF6F00)
        drawFivePointStar(
            center = Offset(sx, sy),
            outerRadius = starR,
            innerRadius = starR * 0.46f,
            rotationRad = wavePhase * 2f + s,
            fillColor = fill,
            strokeColor = stroke
        )
    }
}

private fun DrawScope.drawFinishSparkles(
    cx: Float,
    cy: Float,
    halfLen: Float,
    halfWidth: Float,
    wavePhase: Float,
    laneIndex: Int
) {
    val sparkleColors = listOf(Color(0xFFFFEA00), Color(0xFF00E5FF), Color(0xFFFF4081), Color.White)
    for (i in 0..3) {
        val angle = i * (PI.toFloat() * 0.5f) + laneIndex * 0.6f
        val pulse = abs(sin(wavePhase * 2f + i * 0.85f))
        val sx = cx + cos(angle) * (halfLen * 0.72f)
        val sy = cy + sin(angle) * (halfWidth * 1.45f)
        if (pulse > 0.25f) {
            drawFourPointSparkle(
                center = Offset(sx, sy),
                radius = 4.5f * pulse,
                color = sparkleColors[i % sparkleColors.size].copy(alpha = pulse)
            )
        }
    }
}

private fun DrawScope.drawFivePointStar(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    rotationRad: Float,
    fillColor: Color,
    strokeColor: Color
) {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerRadius else innerRadius
        val a = rotationRad + i * (PI.toFloat() / 5f)
        val x = center.x + cos(a) * r
        val y = center.y + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = fillColor)
    drawPath(path, color = strokeColor, style = Stroke(width = 1.0f, join = StrokeJoin.Round))
}

private fun DrawScope.drawFourPointSparkle(
    center: Offset,
    radius: Float,
    color: Color
) {
    val inner = radius * 0.26f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + inner, center.y - inner)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + inner, center.y + inner)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - inner, center.y + inner)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - inner, center.y - inner)
        close()
    }
    drawPath(path, color = color)
}

private fun Color.lighten(factor: Float): Color = Color(
    red = (red + (1f - red) * factor).coerceIn(0f, 1f),
    green = (green + (1f - green) * factor).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * factor).coerceIn(0f, 1f),
    alpha = alpha
)

private fun Color.darken(factor: Float): Color = Color(
    red = (red * (1f - factor)).coerceIn(0f, 1f),
    green = (green * (1f - factor)).coerceIn(0f, 1f),
    blue = (blue * (1f - factor)).coerceIn(0f, 1f),
    alpha = alpha
)

private fun Color.blendWith(other: Color, otherWeight: Float): Color {
    val w = otherWeight.coerceIn(0f, 1f)
    val inv = 1f - w
    return Color(
        red = (red * inv + other.red * w).coerceIn(0f, 1f),
        green = (green * inv + other.green * w).coerceIn(0f, 1f),
        blue = (blue * inv + other.blue * w).coerceIn(0f, 1f),
        alpha = (alpha * inv + other.alpha * w).coerceIn(0f, 1f)
    )
}
