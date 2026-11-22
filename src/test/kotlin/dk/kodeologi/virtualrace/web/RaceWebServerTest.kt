package dk.kodeologi.virtualrace.web

import dk.kodeologi.virtualrace.engine.RaceEngine
import dk.kodeologi.virtualrace.engine.RaceState
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaceWebServerTest {

    @Test
    fun `test JSON helpers escape strings and parse flat payloads`() {
        val parsed = RaceWebServer.parseFlatJsonObject(
            """{"raceDistanceMeters": 500, "configuredLanesCount": 7, "displayWeight": false, "active": true}"""
        )
        assertEquals("500", parsed["raceDistanceMeters"])
        assertEquals("7", parsed["configuredLanesCount"])
        assertEquals("false", parsed["displayWeight"])
        assertEquals("true", parsed["active"])

        assertEquals(""""Race \"A\"\\B\nC"""", RaceWebServer.jsonString("Race \"A\"\\B\nC"))
    }

    @Test
    fun `test embedded web server endpoints configure and control races`() {
        val engine = RaceEngine(defaultUseEmulator = true)
        engine.start()
        val server = RaceWebServer(engine, preferredPort = 0)
        try {
            val info = server.start()
            assertTrue(info.running, "Web server should start cleanly: ${info.errorMessage}")
            assertTrue(info.port > 0)
            assertEquals(RaceWebServer.PIN_LENGTH, info.accessPin.length)
            assertTrue(info.lanUrl.endsWith("/?pin=${info.accessPin}"))
            assertTrue(info.localUrl.endsWith("/?pin=${info.accessPin}"))
            val pin = info.accessPin
            val base = "http://127.0.0.1:${info.port}"

            // Wait briefly for emulator discovery to map lanes
            val deadline = System.currentTimeMillis() + 3_000L
            while (!engine.uiState.value.isMapped && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }

            // 1. Mobile HTML UI served on GET / (including /?pin=XXXX from QR code)
            val htmlRes = httpRequest("GET", "$base/?pin=$pin")
            assertEquals(200, htmlRes.status)
            assertTrue(htmlRes.body.contains("Virtual Kayak Race Remote"))
            assertTrue(htmlRes.body.contains("Number of Lanes (1–8)"))
            assertEquals(null, htmlRes.corsHeader)

            // 1b. Security checks: missing/wrong PIN -> 401; cross-origin -> 403; OPTIONS -> 403
            assertEquals(401, httpRequest("GET", "$base/api/state").status)
            assertEquals(401, httpRequest("GET", "$base/api/state", pin = "0000").status)
            assertEquals(401, httpRequest("POST", "$base/api/action/start", "{}").status)
            assertEquals(RaceState.IDLE_PRE_RACE, engine.uiState.value.raceState)

            assertEquals(
                403,
                rawHttpRequest(
                    port = info.port,
                    method = "GET",
                    path = "/api/state",
                    headers = mapOf(
                        RaceWebServer.PIN_HEADER to pin,
                        "Origin" to "https://evil.example"
                    )
                ).status
            )
            assertEquals(
                403,
                rawHttpRequest(
                    port = info.port,
                    method = "POST",
                    path = "/api/action/start",
                    body = "{}",
                    headers = mapOf(
                        RaceWebServer.PIN_HEADER to pin,
                        "Sec-Fetch-Site" to "cross-site"
                    )
                ).status
            )
            assertEquals(
                403,
                rawHttpRequest(
                    port = info.port,
                    method = "OPTIONS",
                    path = "/api/state",
                    headers = mapOf(RaceWebServer.PIN_HEADER to pin)
                ).status
            )
            assertEquals(RaceState.IDLE_PRE_RACE, engine.uiState.value.raceState)

            // 2. GET /api/state with valid same-origin PIN returns live JSON snapshot (and no wildcard CORS)
            val stateRes = rawHttpRequest(
                port = info.port,
                method = "GET",
                path = "/api/state",
                headers = mapOf(
                    RaceWebServer.PIN_HEADER to pin,
                    "Origin" to base,
                    "Sec-Fetch-Site" to "same-origin"
                )
            )
            assertEquals(200, stateRes.status)
            assertEquals(null, stateRes.corsHeader)
            assertTrue(stateRes.body.contains(""""raceState":"IDLE_PRE_RACE""""))
            assertTrue(stateRes.body.contains(""""configuredLanesCount":6"""))

            // 3. POST /api/config updates lanes (7), distance (500m), raceNumber (4), displayWeight (false)
            val cfgRes = httpRequest(
                "POST",
                "$base/api/config",
                """{"configuredLanesCount":7,"raceDistanceMeters":500,"raceNumber":4,"displayWeight":false}""",
                pin = pin
            )
            assertEquals(200, cfgRes.status)
            assertEquals(7, engine.uiState.value.configuredLanesCount)
            assertEquals(500, engine.uiState.value.raceDistanceMeters)
            assertEquals(4, engine.uiState.value.raceNumber)
            assertFalse(engine.uiState.value.displayWeight)
            assertTrue(cfgRes.body.contains(""""configuredLanesCount":7"""))
            assertTrue(cfgRes.body.contains(""""raceDistanceMeters":500"""))

            // Wait for re-discovery after changing lane count
            val remapDeadline = System.currentTimeMillis() + 3_000L
            while (!engine.uiState.value.isMapped && System.currentTimeMillis() < remapDeadline) {
                Thread.sleep(40)
            }

            // 4. Toggle single lane active state (verify synchronous JSON response from POST)
            val laneRes = httpRequest("POST", "$base/api/lanes/0/active", """{"active":false}""", pin = pin)
            assertEquals(200, laneRes.status)
            assertTrue(laneRes.body.contains(""""laneIndex":0"""))
            val lane0Block = laneRes.body.substringAfter(""""laneIndex":0""").substringBefore(""""laneIndex":1""")
            assertTrue(lane0Block.contains(""""active":false"""), "Expected lane 0 deactivated in response: $lane0Block")

            val allRes = httpRequest("POST", "$base/api/lanes/activate-all", "{}", pin = pin)
            assertEquals(200, allRes.status)
            val lane0AfterAll = allRes.body.substringAfter(""""laneIndex":0""").substringBefore(""""laneIndex":1""")
            assertTrue(lane0AfterAll.contains(""""active":true"""), "Expected lane 0 activated in response: $lane0AfterAll")

            // 5. Start race -> False start -> Abort -> New race
            val startRes = httpRequest("POST", "$base/api/action/start", "{}", pin = pin)
            assertEquals(200, startRes.status)
            assertEquals(RaceState.STOP_PADDLING, engine.uiState.value.raceState)
            assertTrue(startRes.body.contains(""""isSequenceOrRacing":true"""))

            val fsRes = httpRequest("POST", "$base/api/action/false-start", "{}", pin = pin)
            assertEquals(200, fsRes.status)
            assertEquals(RaceState.FALSE_START, engine.uiState.value.raceState)

            val abortRes = httpRequest("POST", "$base/api/action/abort", "{}", pin = pin)
            assertEquals(200, abortRes.status)
            assertEquals(RaceState.IDLE_PRE_RACE, engine.uiState.value.raceState)
            assertTrue(abortRes.body.contains(""""canStart":true"""))

            val newRaceRes = httpRequest("POST", "$base/api/action/new-race", "{}", pin = pin)
            assertEquals(200, newRaceRes.status)
            assertEquals(RaceState.IDLE_PRE_RACE, engine.uiState.value.raceState)

            // 6. Verify 1-lane configuration also works cleanly
            val oneLaneRes = httpRequest("POST", "$base/api/config", """{"configuredLanesCount":1}""", pin = pin)
            assertEquals(200, oneLaneRes.status)
            assertEquals(1, engine.uiState.value.configuredLanesCount)
            assertTrue(oneLaneRes.body.contains(""""configuredLanesCount":1"""))
        } finally {
            server.stop()
            engine.stop()
        }
    }

    private data class HttpResult(val status: Int, val body: String, val corsHeader: String?)

    private fun httpRequest(
        method: String,
        url: String,
        body: String? = null,
        pin: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResult {
        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        if (pin != null) {
            conn.setRequestProperty(RaceWebServer.PIN_HEADER, pin)
        }
        for ((k, v) in extraHeaders) {
            conn.setRequestProperty(k, v)
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = conn.responseCode
        val corsHeader = conn.getHeaderField("Access-Control-Allow-Origin")
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return HttpResult(status, text, corsHeader)
    }

    /**
     * Sends raw HTTP/1.1 over a TCP socket so restricted headers like `Origin` are not stripped by JDK's HttpURLConnection.
     */
    private fun rawHttpRequest(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            val req = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Connection: close\r\n")
                for ((k, v) in headers) {
                    append("$k: $v\r\n")
                }
                if (body != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                }
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(req.toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()

            val rawResp = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val headerPart = rawResp.substringBefore("\r\n\r\n")
            val bodyPart = rawResp.substringAfter("\r\n\r\n", "")
            val statusLine = headerPart.lineSequence().firstOrNull() ?: ""
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val corsHeader = headerPart.lineSequence()
                .firstOrNull { it.startsWith("Access-Control-Allow-Origin:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            return HttpResult(status, bodyPart, corsHeader)
        }
    }
}
