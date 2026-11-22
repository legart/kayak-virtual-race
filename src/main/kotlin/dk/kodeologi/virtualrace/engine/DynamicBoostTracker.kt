package dk.kodeologi.virtualrace.engine

import kotlin.math.exp
import kotlin.math.max

/**
 * Per-lane dynamic turbo boost detector driven by real ergometer telemetry:
 * 1. Sudden acceleration bursts (speed rising sharply above a ~1.4s rolling EMA)
 * 2. Personal sprint kicks (surging significantly above the paddler's own ~7s cruise pace)
 * 3. Top-end redline sprint (>= 18.0 km/h sustained speed)
 *
 * Includes a smooth minimum hold and fade-out so boost flames feel responsive without flickering
 * between individual paddle strokes.
 */
class DynamicBoostTracker {
    private var lastUpdateMs: Long = -1L
    private var mediumEmaKmh: Float = 0f   // ~1.4s time constant for sudden acceleration detection
    private var cruiseEmaKmh: Float = 0f   // ~7.0s time constant for personal cruise pace
    private var boostHoldUntilMs: Long = 0L
    private var currentIntensity: Float = 0f

    data class BoostSnapshot(
        val active: Boolean,
        val intensity: Float // 0f..1f
    )

    fun reset() {
        lastUpdateMs = -1L
        mediumEmaKmh = 0f
        cruiseEmaKmh = 0f
        boostHoldUntilMs = 0L
        currentIntensity = 0f
    }

    fun onSpeedSample(
        nowMs: Long,
        speedKmh: Float,
        distanceMeters: Float
    ): BoostSnapshot {
        if (speedKmh < 5.0f) {
            if (lastUpdateMs < 0L) {
                mediumEmaKmh = speedKmh
                cruiseEmaKmh = speedKmh
            } else {
                val dtSec = (nowMs - lastUpdateMs).coerceIn(10L, 2_000L) / 1000f
                val alphaMed = 1f - exp(-dtSec / 1.4f)
                mediumEmaKmh += alphaMed * (speedKmh - mediumEmaKmh)
            }
            lastUpdateMs = nowMs
            boostHoldUntilMs = 0L
            currentIntensity = 0f
            return BoostSnapshot(false, 0f)
        }

        if (lastUpdateMs < 0L) {
            lastUpdateMs = nowMs
            mediumEmaKmh = speedKmh
            cruiseEmaKmh = speedKmh
            return evaluateAt(nowMs, speedKmh)
        }

        val dtMs = (nowMs - lastUpdateMs).coerceIn(10L, 2_000L)
        val dtSec = dtMs / 1000f
        lastUpdateMs = nowMs

        // Compare against baselines before updating so sudden surges aren't absorbed
        val accelDelta = speedKmh - mediumEmaKmh
        val cruiseDelta = speedKmh - cruiseEmaKmh

        // 1. Sudden acceleration surge
        val accelThreshold = if (distanceMeters < 10f) 1.5f else 1.1f
        val minAccelSpeed = if (distanceMeters < 10f) 12.0f else 9.0f
        val accelScore = if (speedKmh >= minAccelSpeed && accelDelta >= accelThreshold) {
            ((accelDelta - accelThreshold) / 1.8f + 0.55f).coerceIn(0.55f, 1.0f)
        } else {
            0f
        }

        // 2. Personal sprint kick above the paddler's own cruise baseline
        val cruiseSurgeThreshold = max(1.2f, cruiseEmaKmh * 0.095f)
        val cruiseScore = if (distanceMeters >= 20f && cruiseEmaKmh >= 7.5f && cruiseDelta >= cruiseSurgeThreshold) {
            ((cruiseDelta - cruiseSurgeThreshold) / 1.6f + 0.50f).coerceIn(0.50f, 1.0f)
        } else {
            0f
        }

        // 3. Top-end redline sprint (>= 18.0 km/h)
        val redlineScore = if (speedKmh >= 18.0f) {
            ((speedKmh - 18.0f) / 2.5f + 0.65f).coerceIn(0.65f, 1.0f)
        } else {
            0f
        }

        // Update time-constant-normalized EMAs
        val alphaMed = 1f - exp(-dtSec / 1.4f)
        mediumEmaKmh += alphaMed * (speedKmh - mediumEmaKmh)

        val alphaCruise = 1f - exp(-dtSec / 7.0f)
        if (cruiseEmaKmh < 5.0f) {
            cruiseEmaKmh = speedKmh
        } else {
            cruiseEmaKmh += alphaCruise * (speedKmh - cruiseEmaKmh)
        }

        val triggerScore = max(accelScore, max(cruiseScore, redlineScore))
        if (triggerScore > 0f) {
            val holdMs = if (accelScore > 0f) 1_300L else 950L
            boostHoldUntilMs = max(boostHoldUntilMs, nowMs + holdMs)
            currentIntensity = max(triggerScore, currentIntensity * 0.85f).coerceIn(0.45f, 1.0f)
        }

        return evaluateAt(nowMs, speedKmh)
    }

    fun evaluateAt(nowMs: Long, speedKmh: Float): BoostSnapshot {
        if (speedKmh < 6.0f) {
            boostHoldUntilMs = 0L
            currentIntensity = 0f
            return BoostSnapshot(false, 0f)
        }
        val active = nowMs < boostHoldUntilMs || speedKmh >= 18.0f
        if (!active) {
            currentIntensity = 0f
            return BoostSnapshot(false, 0f)
        }
        val remainingMs = (boostHoldUntilMs - nowMs).coerceAtLeast(0L)
        val fade = if (speedKmh >= 18.0f || remainingMs >= 300L) {
            1f
        } else {
            (remainingMs / 300f).coerceIn(0.25f, 1f)
        }
        val base = max(currentIntensity, if (speedKmh >= 18.0f) 0.70f else 0.45f)
        return BoostSnapshot(active = true, intensity = (base * fade).coerceIn(0.25f, 1.0f))
    }
}
