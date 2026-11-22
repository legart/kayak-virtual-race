package dk.kodeologi.virtualrace

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dk.kodeologi.virtualrace.engine.FileRaceSettingsStore
import dk.kodeologi.virtualrace.engine.RaceEngine
import dk.kodeologi.virtualrace.web.RaceWebServer

fun main() = application {
    val engine = remember { RaceEngine(settingsStore = FileRaceSettingsStore()) }
    val webServer = remember { RaceWebServer(engine) }

    DisposableEffect(Unit) {
        engine.start()
        webServer.start()
        onDispose {
            webServer.stop()
            engine.stop()
        }
    }

    Window(
        onCloseRequest = {
            webServer.stop()
            engine.stop()
            exitApplication()
        },
        title = "Kayak Virtual Race (for Dansprint Ergometer)",
        state = rememberWindowState(width = 1400.dp, height = 860.dp),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                handleKeyboardShortcuts(event, engine)
            } else {
                false
            }
        }
    ) {
        dk.kodeologi.virtualrace.ui.VirtualRaceApp(engine = engine, webServer = webServer)
    }
}

/**
 * Implements both the original Dansprint Virtual Race keyboard shortcuts (from FormKeyDown 0x0040c504)
 * and the arcade SPACEBAR manual paddle stroke control.
 */
private fun handleKeyboardShortcuts(event: KeyEvent, engine: RaceEngine): Boolean {
    val ctrl = event.isCtrlPressed
    val alt = event.isAltPressed
    val shift = event.isShiftPressed

    // SPACE: Manual paddle stroke in Emulator mode
    if (!ctrl && !alt && !shift && event.key == Key.Spacebar) {
        engine.emulatedTransport.triggerManualPaddleStroke()
        return true
    }

    // UP / DOWN Arrow: Track Zoom In / Out (matching 0x404772 & 0x4047ad)
    if (!ctrl && !alt && !shift && event.key == Key.DirectionUp) {
        engine.zoomInCourseView()
        return true
    }
    if (!ctrl && !alt && !shift && event.key == Key.DirectionDown) {
        engine.zoomOutCourseView()
        return true
    }

    // Ctrl + Alt shortcuts (matching FormKeyDown 0x40c504):
    // Ctrl+Alt+C = New Race (0x43)
    // Ctrl+Alt+Z = Start Race (0x5A)
    // Ctrl+Alt+K = Restart / Abort Race (0x4B)
    // Ctrl+Alt+M = End Race Early (0x4D)
    // Ctrl+Alt+N = Manual False Start (0x4E)
    if (ctrl && alt) {
        when (event.key) {
            Key.C -> {
                engine.newRace()
                return true
            }
            Key.Z -> {
                engine.startRace()
                return true
            }
            Key.K -> {
                engine.restartOrAbortRace()
                return true
            }
            Key.M -> {
                engine.finishRaceEarly()
                return true
            }
            Key.N -> {
                engine.triggerManualFalseStart()
                return true
            }
        }
    }

    // Shift + 1..8 (and 9 for all): Activate lane(s)
    // Ctrl + 1..8 (and 9 for all): Deactivate lane(s) and send 0x1A
    val numberIndex = when (event.key) {
        Key.One -> 0
        Key.Two -> 1
        Key.Three -> 2
        Key.Four -> 3
        Key.Five -> 4
        Key.Six -> 5
        Key.Seven -> 6
        Key.Eight -> 7
        Key.Nine -> 8
        else -> -1
    }
    if (numberIndex in 0..7) {
        if (shift && !ctrl && !alt) {
            engine.setLaneActive(numberIndex, true)
            return true
        }
        if (ctrl && !shift && !alt) {
            engine.setLaneActive(numberIndex, false)
            return true
        }
    } else if (numberIndex == 8) {
        if (shift && !ctrl && !alt) {
            for (i in 0 until 8) engine.setLaneActive(i, true)
            return true
        }
        if (ctrl && !shift && !alt) {
            for (i in 0 until 8) engine.setLaneActive(i, false)
            return true
        }
    }

    return false
}
