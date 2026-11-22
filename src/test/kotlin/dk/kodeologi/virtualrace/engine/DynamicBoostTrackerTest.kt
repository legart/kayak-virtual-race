package dk.kodeologi.virtualrace.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicBoostTrackerTest {

    @Test
    fun `steady cruise does not trigger boost`() {
        val tracker = DynamicBoostTracker()
        var t = 1_000L
        var dist = 5f
        for (i in 0 until 25) {
            val snap = tracker.onSpeedSample(t, 14.2f, dist)
            assertFalse(snap.active, "Steady 14.2 km/h cruise should not trigger boost at step $i")
            t += 300L
            dist += 1.2f
        }
    }

    @Test
    fun `sudden mid-race acceleration triggers boost even at modest speeds`() {
        val tracker = DynamicBoostTracker()
        var t = 1_000L
        var dist = 10f
        // Establish steady 10.5 km/h cruise for a youth/recreational paddler
        for (i in 0 until 15) {
            assertFalse(tracker.onSpeedSample(t, 10.5f, dist).active)
            t += 300L
            dist += 0.9f
        }
        // Sudden sprint kick: 10.5 -> 12.4 km/h over 0.6s
        tracker.onSpeedSample(t + 300L, 11.4f, dist + 1.0f)
        val surge = tracker.onSpeedSample(t + 600L, 12.4f, dist + 2.1f)
        assertTrue(surge.active, "Sudden +1.9 km/h acceleration surge should trigger boost")
        assertTrue(surge.intensity >= 0.45f)

        // Still held smoothly 500ms later without flickering
        assertTrue(tracker.evaluateAt(t + 1_100L, 12.1f).active)

        // Drops off cleanly once hold expires and speed returns to cruise
        val settled = tracker.evaluateAt(t + 2_500L, 10.6f)
        assertFalse(settled.active)
    }

    @Test
    fun `personal sprint kick above cruise pace triggers boost`() {
        val tracker = DynamicBoostTracker()
        var t = 0L
        var dist = 5f
        // Cruise at 14.0 km/h for ~10s
        for (i in 0 until 30) {
            tracker.onSpeedSample(t, 14.0f, dist)
            t += 350L
            dist += 1.35f
        }
        // Gradual ramp to 15.8 km/h (+12.8% above personal cruise)
        var triggered = false
        val ramp = listOf(14.4f, 14.8f, 15.2f, 15.6f, 15.9f)
        for (spd in ramp) {
            t += 350L
            dist += 1.5f
            if (tracker.onSpeedSample(t, spd, dist).active) {
                triggered = true
            }
        }
        assertTrue(triggered, "Surging +12% above personal cruise should trigger sprint boost")
    }

    @Test
    fun `top end redline speed continuously triggers boost`() {
        val tracker = DynamicBoostTracker()
        tracker.onSpeedSample(1_000L, 18.6f, 40f)
        val snap = tracker.onSpeedSample(1_300L, 18.8f, 41.6f)
        assertTrue(snap.active)
        assertTrue(snap.intensity >= 0.70f)

        // Stopping immediately clears boost
        val stopped = tracker.evaluateAt(1_400L, 0f)
        assertFalse(stopped.active)
    }
}
