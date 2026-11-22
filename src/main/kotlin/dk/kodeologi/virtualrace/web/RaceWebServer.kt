package dk.kodeologi.virtualrace.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dk.kodeologi.virtualrace.engine.LaneTelemetryState
import dk.kodeologi.virtualrace.engine.RaceEngine
import dk.kodeologi.virtualrace.engine.RaceState
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.ui.RegattaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class WebServerInfo(
    val running: Boolean = false,
    val port: Int = 0,
    val accessPin: String = "",
    val lanUrl: String = "",
    val localUrl: String = "",
    val errorMessage: String? = null
)

/**
 * Lightweight embedded HTTP server (JDK built-in HttpServer, zero external dependencies)
 * providing a mobile-first Race Director & Remote Control web UI for configuring, starting,
 * aborting, and monitoring live status and results from a phone or tablet on the local network.
 *
 * Security model:
 * - Generates a random 4-character session PIN embedded in the QR code URL (`/?pin=XXXX`).
 * - All `/api/` endpoints require the PIN via the custom `X-Pin` header (constant-time check)
 *   and enforce same-origin access (no wildcard CORS, no Private Network Access headers, cross-origin rejected).
 */
class RaceWebServer(
    private val engine: RaceEngine,
    private val preferredPort: Int = defaultConfiguredPort(),
    accessPin: String = generateRandomPin()
) {
    val accessPin: String = normalizePin(accessPin).ifEmpty { generateRandomPin() }
    private val accessPinBytes: ByteArray = this.accessPin.toByteArray(StandardCharsets.UTF_8)

    private val _info = MutableStateFlow(WebServerInfo(accessPin = this.accessPin))
    val info: StateFlow<WebServerInfo> = _info.asStateFlow()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    private val cachedIndexHtml: ByteArray by lazy {
        val stream = javaClass.getResourceAsStream("/web/index.html")
            ?: error("Missing embedded web UI resource /web/index.html")
        stream.use { it.readBytes() }
    }

    @Synchronized
    fun start(): WebServerInfo {
        if (server != null) return _info.value

        val boundServer = bindFirstAvailablePort(preferredPort)
        if (boundServer == null) {
            val failed = WebServerInfo(
                running = false,
                errorMessage = "Could not bind embedded web server starting at port $preferredPort"
            )
            _info.value = failed
            return failed
        }

        val port = boundServer.address.port
        val lanIp = detectLanIpv4Address()
        val localUrl = "http://localhost:$port/?pin=$accessPin"
        val lanUrl = if (lanIp != null) "http://$lanIp:$port/?pin=$accessPin" else localUrl

        val threadCounter = AtomicInteger(1)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "virtualrace-web-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }

        boundServer.createContext("/") { ex -> handleRoot(ex) }
        boundServer.createContext("/api/state") { ex -> handleState(ex) }
        boundServer.createContext("/api/action/") { ex -> handleAction(ex) }
        boundServer.createContext("/api/config") { ex -> handleConfig(ex) }
        boundServer.createContext("/api/lanes/") { ex -> handleLanes(ex) }

        boundServer.executor = pool
        boundServer.start()

        server = boundServer
        executor = pool

        val started = WebServerInfo(
            running = true,
            port = port,
            accessPin = accessPin,
            lanUrl = lanUrl,
            localUrl = localUrl,
            errorMessage = null
        )
        _info.value = started
        return started
    }

    @Synchronized
    fun stop() {
        val s = server
        server = null
        try {
            s?.stop(0)
        } catch (_: Throwable) {
        }
        val pool = executor
        executor = null
        try {
            pool?.shutdownNow()
        } catch (_: Throwable) {
        }
        _info.value = _info.value.copy(running = false)
    }

    private fun bindFirstAvailablePort(startPort: Int): HttpServer? {
        if (startPort == 0) {
            return try {
                HttpServer.create(InetSocketAddress("0.0.0.0", 0), 32)
            } catch (_: IOException) {
                null
            }
        }
        for (port in startPort..(startPort + 10)) {
            try {
                return HttpServer.create(InetSocketAddress("0.0.0.0", port), 32)
            } catch (_: IOException) {
                // Try next port
            }
        }
        return try {
            HttpServer.create(InetSocketAddress("0.0.0.0", 0), 32)
        } catch (_: IOException) {
            null
        }
    }

    // =========================================================================
    // HTTP Route Handlers
    // =========================================================================

    private fun handleRoot(ex: HttpExchange) {
        try {
            drainRequestBody(ex)
            val path = ex.requestURI.path ?: "/"
            if (path == "/favicon.ico" || path.startsWith("/apple-touch-icon")) {
                ex.responseHeaders.set("Cache-Control", "public, max-age=86400")
                ex.sendResponseHeaders(204, -1)
                return
            }
            if (path != "/" && path != "/index.html") {
                sendJson(ex, 404, """{"error":"Not found"}""")
                return
            }
            if (ex.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                sendJson(ex, 403, """{"error":"Cross-origin requests are not allowed"}""")
                return
            }
            val isGet = ex.requestMethod.equals("GET", ignoreCase = true)
            val isHead = ex.requestMethod.equals("HEAD", ignoreCase = true)
            if (!isGet && !isHead) {
                sendJson(ex, 405, """{"error":"Method not allowed"}""")
                return
            }
            val bytes = cachedIndexHtml
            applySecurityHeaders(ex)
            ex.responseHeaders.set(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'"
            )
            ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            ex.responseHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate")
            if (isHead) {
                ex.sendResponseHeaders(200, -1)
            } else {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        } catch (t: Throwable) {
            sendServerError(ex, t)
        } finally {
            ex.close()
        }
    }

    private fun handleState(ex: HttpExchange) {
        try {
            drainRequestBody(ex)
            if (!authorizeApiRequest(ex)) return
            if (!ex.requestMethod.equals("GET", ignoreCase = true)) {
                sendJson(ex, 405, """{"error":"Method not allowed"}""")
                return
            }
            sendJson(ex, 200, buildStateJson())
        } catch (t: Throwable) {
            sendServerError(ex, t)
        } finally {
            ex.close()
        }
    }

    private fun handleAction(ex: HttpExchange) {
        try {
            drainRequestBody(ex)
            if (!authorizeApiRequest(ex)) return
            if (!ex.requestMethod.equals("POST", ignoreCase = true)) {
                sendJson(ex, 405, """{"error":"Method not allowed"}""")
                return
            }
            val action = ex.requestURI.path.removePrefix("/api/action/").trim('/')
            when (action) {
                "start" -> engine.startRace()
                "abort" -> engine.restartOrAbortRace()
                "new-race" -> engine.newRace()
                "false-start" -> engine.triggerManualFalseStart()
                "finish-early" -> engine.finishRaceEarly()
                "reconnect" -> engine.connectAndDiscoverErgometers(initialStartup = false)
                "dismiss-alert" -> engine.dismissAlertDialog()
                else -> {
                    sendJson(ex, 404, """{"error":"Unknown action"}""")
                    return
                }
            }
            sendJson(ex, 200, buildStateJson())
        } catch (t: Throwable) {
            sendServerError(ex, t)
        } finally {
            ex.close()
        }
    }

    private fun handleConfig(ex: HttpExchange) {
        try {
            val body = readBodyUtf8(ex)
            if (!authorizeApiRequest(ex)) return
            if (!ex.requestMethod.equals("POST", ignoreCase = true)) {
                sendJson(ex, 405, """{"error":"Method not allowed"}""")
                return
            }
            val fields = parseFlatJsonObject(body)
            val current = engine.uiState.value

            val isSequenceOrRacing = isRaceActiveState(current.raceState)

            fields["raceNumber"]?.toIntOrNull()?.let { raceNo ->
                engine.setRaceNumber(raceNo)
            }
            fields["displayWeight"]?.toBooleanStrictOrNull()?.let { wantWeight ->
                if (wantWeight != engine.uiState.value.displayWeight) {
                    engine.toggleDisplayWeight()
                }
            }
            if (!isSequenceOrRacing) {
                fields["usingEmulator"]?.toBooleanStrictOrNull()?.let { wantEmu ->
                    if (wantEmu != engine.uiState.value.usingEmulator) {
                        engine.setUseEmulator(wantEmu)
                    }
                }
                fields["configuredLanesCount"]?.toIntOrNull()?.let { lanesCount ->
                    val clamped = lanesCount.coerceIn(1, DansprintProtocol.MAX_LANES)
                    if (clamped != engine.uiState.value.configuredLanesCount) {
                        engine.setConfiguredLanesCount(clamped)
                    }
                }
                fields["raceDistanceMeters"]?.toIntOrNull()?.let { dist ->
                    engine.setRaceDistanceMeters(dist)
                }
            }

            sendJson(ex, 200, buildStateJson())
        } catch (t: Throwable) {
            sendServerError(ex, t)
        } finally {
            ex.close()
        }
    }

    private fun handleLanes(ex: HttpExchange) {
        try {
            val body = readBodyUtf8(ex)
            if (!authorizeApiRequest(ex)) return
            if (!ex.requestMethod.equals("POST", ignoreCase = true)) {
                sendJson(ex, 405, """{"error":"Method not allowed"}""")
                return
            }
            val subPath = ex.requestURI.path.removePrefix("/api/lanes/").trim('/')
            when {
                subPath == "activate-all" -> {
                    val state = engine.uiState.value
                    for (i in 0 until state.configuredLanesCount) {
                        if (state.lanes[i].connected) {
                            engine.setLaneActive(i, true)
                        }
                    }
                }
                subPath == "deactivate-all" -> {
                    val state = engine.uiState.value
                    for (i in 0 until state.configuredLanesCount) {
                        engine.setLaneActive(i, false)
                    }
                }
                subPath.endsWith("/active") -> {
                    val idxStr = subPath.removeSuffix("/active").trim('/')
                    val laneIndex = idxStr.toIntOrNull()
                    if (laneIndex == null || laneIndex !in 0 until DansprintProtocol.MAX_LANES) {
                        sendJson(ex, 400, """{"error":"Invalid lane index"}""")
                        return
                    }
                    val fields = parseFlatJsonObject(body)
                    val currentLane = engine.uiState.value.lanes[laneIndex]
                    val targetActive = fields["active"]?.toBooleanStrictOrNull() ?: !currentLane.active
                    engine.setLaneActive(laneIndex, targetActive)
                }
                else -> {
                    sendJson(ex, 404, """{"error":"Unknown lane endpoint"}""")
                    return
                }
            }
            sendJson(ex, 200, buildStateJson())
        } catch (t: Throwable) {
            sendServerError(ex, t)
        } finally {
            ex.close()
        }
    }

    // =========================================================================
    // JSON Snapshot Serialization
    // =========================================================================

    internal fun buildStateJson(): String {
        val state = engine.uiState.value
        val emuUnits = engine.emulatedTransport.units.value
        val configuredCount = state.configuredLanesCount.coerceIn(1, DansprintProtocol.MAX_LANES)
        val visibleLanes = state.visibleLanes()

        val isSequenceOrRacing = isRaceActiveState(state.raceState)
        val canStart = state.raceState in listOf(
            RaceState.IDLE_PRE_RACE,
            RaceState.STARTUP_DISCOVERY
        )
        val anyFinished = visibleLanes.any { it.active && it.finishTimeMs > 0f }
        val canFinishEarly = state.raceState == RaceState.RACING ||
            state.raceState == RaceState.POST_FINISH ||
            (anyFinished && isSequenceOrRacing)
        val canConfigure = !isSequenceOrRacing
        val canEditLanes = state.raceState == RaceState.IDLE_PRE_RACE ||
            state.raceState == RaceState.STARTUP_DISCOVERY

        // Rank active non-disqualified lanes front-to-back to compute gap-to-next and gap-to-leader
        val rankedActive = visibleLanes
            .filter { it.active && !it.disqualified }
            .sortedWith(
                compareByDescending<LaneTelemetryState> { it.interpolatedDistanceMeters }
                    .thenBy { if (it.isFinished) it.finishTimeMs else Float.MAX_VALUE }
                    .thenBy { it.laneIndex }
            )
        val gapToNextByLane = mutableMapOf<Int, Float>()
        val gapToLeaderByLane = mutableMapOf<Int, Float>()
        val leaderDist = rankedActive.firstOrNull()?.interpolatedDistanceMeters ?: 0f
        for (rankIdx in rankedActive.indices) {
            val lane = rankedActive[rankIdx]
            if (rankIdx > 0) {
                val ahead = rankedActive[rankIdx - 1]
                gapToNextByLane[lane.laneIndex] =
                    (ahead.interpolatedDistanceMeters - lane.interpolatedDistanceMeters).coerceAtLeast(0f)
                gapToLeaderByLane[lane.laneIndex] =
                    (leaderDist - lane.interpolatedDistanceMeters).coerceAtLeast(0f)
            } else {
                gapToLeaderByLane[lane.laneIndex] = 0f
            }
        }

        // Results ordering: finished/ranked boats first, then active by distance, then disqualified
        val resultLanes = visibleLanes
            .filter { it.active || it.disqualified || it.finishTimeMs > 0f }
            .sortedWith(
                compareBy<LaneTelemetryState> {
                    when {
                        it.disqualified || it.rank == 999 -> 2
                        it.rank in 1..8 -> 0
                        it.isFinished -> 0
                        else -> 1
                    }
                }
                    .thenBy { if (it.rank in 1..8) it.rank else it.livePlace }
                    .thenBy { if (it.isFinished) it.finishTimeMs else Float.MAX_VALUE }
                    .thenByDescending { it.interpolatedDistanceMeters }
                    .thenBy { it.laneIndex }
            )
        val winnerFinishMs = resultLanes
            .firstOrNull { !it.disqualified && it.finishTimeMs > 0f }
            ?.finishTimeMs

        val totalDist = state.raceDistanceMeters.toFloat().coerceAtLeast(1f)
        val serverInfo = _info.value

        fun laneTitle(idx: Int): String = "Lane ${idx + 1}"

        fun colorToHex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

        val lanesJson = visibleLanes.joinToString(",") { lane ->
            val style = RegattaTheme.styleForLane(lane.laneIndex)
            val progressPct = ((lane.interpolatedDistanceMeters / totalDist) * 100f).coerceIn(0f, 100f)
            val gapNext = gapToNextByLane[lane.laneIndex]
            val gapLeader = gapToLeaderByLane[lane.laneIndex]
            val deltaTimeMs = if (winnerFinishMs != null && lane.finishTimeMs > 0f && !lane.disqualified) {
                (lane.finishTimeMs - winnerFinishMs).coerceAtLeast(0f)
            } else {
                null
            }
            val hexColor = colorToHex(
                ((style.badgeColor.red * 255f).toInt() shl 16) or
                    ((style.badgeColor.green * 255f).toInt() shl 8) or
                    (style.badgeColor.blue * 255f).toInt()
            )
            buildString {
                append('{')
                append("\"laneIndex\":").append(lane.laneIndex).append(',')
                append("\"laneNumber\":").append(lane.laneNumber1Based).append(',')
                append("\"title\":").append(jsonString(laneTitle(lane.laneIndex))).append(',')
                append("\"badgeColor\":").append(jsonString(hexColor)).append(',')
                append("\"connected\":").append(lane.connected).append(',')
                append("\"active\":").append(lane.active).append(',')
                append("\"boxStatus\":").append(jsonString(lane.boxStatus.name)).append(',')
                append("\"boxStatusLabel\":").append(jsonString(lane.boxStatus.label)).append(',')
                append("\"weightKg\":").append(lane.weightKg).append(',')
                append("\"distanceMeters\":").append(fmt1(lane.interpolatedDistanceMeters)).append(',')
                append("\"progressPct\":").append(fmt1(progressPct)).append(',')
                append("\"speedKmh\":").append(fmt1(lane.displayedSpeedKmh)).append(',')
                append("\"falseStartCount\":").append(lane.falseStartCount).append(',')
                append("\"disqualified\":").append(lane.disqualified).append(',')
                append("\"isFinished\":").append(lane.isFinished).append(',')
                append("\"finishTimeMs\":").append(fmt1(lane.finishTimeMs)).append(',')
                append("\"formattedFinishTime\":").append(jsonString(lane.formattedFinishTime)).append(',')
                append("\"deltaTimeMs\":").append(if (deltaTimeMs != null) fmt1(deltaTimeMs) else "null").append(',')
                append("\"rank\":").append(lane.rank).append(',')
                append("\"livePlace\":").append(lane.livePlace).append(',')
                append("\"gapToNextMeters\":").append(if (gapNext != null) fmt1(gapNext) else "null").append(',')
                append("\"gapToLeaderMeters\":").append(if (gapLeader != null) fmt1(gapLeader) else "null")
                append('}')
            }
        }

        val resultIndicesJson = resultLanes.joinToString(",") { it.laneIndex.toString() }

        return buildString {
            append('{')
            append("\"raceState\":").append(jsonString(state.raceState.name)).append(',')
            append("\"raceStateCode\":").append(state.raceState.code).append(',')
            append("\"raceStateTitle\":").append(jsonString(state.raceState.title)).append(',')
            append("\"bannerText\":").append(jsonString(state.raceState.bannerText)).append(',')
            append("\"trafficLightStage\":").append(state.raceState.trafficLightStage).append(',')
            append("\"raceNumber\":").append(state.raceNumber).append(',')
            append("\"raceDistanceMeters\":").append(state.raceDistanceMeters).append(',')
            append("\"configuredLanesCount\":").append(configuredCount).append(',')
            append("\"displayWeight\":").append(state.displayWeight).append(',')
            append("\"usingEmulator\":").append(state.usingEmulator).append(',')
            append("\"isMapped\":").append(state.isMapped).append(',')
            append("\"elapsedTimerMs\":").append(state.elapsedTimerMs).append(',')
            append("\"formattedTimer\":").append(jsonString(DansprintProtocol.formatRaceTimeMs(state.elapsedTimerMs.toFloat()))).append(',')
            append("\"maxBoatDistanceMeters\":").append(fmt1(state.maxBoatDistanceMeters)).append(',')
            append("\"alertDialogMessage\":").append(state.alertDialogMessage?.let { jsonString(it) } ?: "null").append(',')
            append("\"statusToastMessage\":").append(jsonString(state.statusToastMessage)).append(',')
            append("\"canStart\":").append(canStart).append(',')
            append("\"isSequenceOrRacing\":").append(isSequenceOrRacing).append(',')
            append("\"canFinishEarly\":").append(canFinishEarly).append(',')
            append("\"canConfigure\":").append(canConfigure).append(',')
            append("\"canEditLanes\":").append(canEditLanes).append(',')
            append("\"lanUrl\":").append(jsonString(serverInfo.lanUrl)).append(',')
            append("\"resultOrder\":[").append(resultIndicesJson).append("],")
            append("\"lanes\":[").append(lanesJson).append(']')
            append('}')
        }
    }

    private fun isRaceActiveState(state: RaceState): Boolean = state in listOf(
        RaceState.STOP_PADDLING,
        RaceState.START_POSITION,
        RaceState.READY_COUNTDOWN,
        RaceState.RACE_START_HOLD,
        RaceState.RACING,
        RaceState.FALSE_START,
        RaceState.POST_FINISH
    )

    private fun drainRequestBody(ex: HttpExchange) {
        try {
            ex.requestBody.use { stream ->
                val buf = ByteArray(1024)
                while (stream.read(buf) != -1) {
                    // Drain completely so HTTP/1.1 Keep-Alive sockets stay cleanly aligned
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun readBodyUtf8(ex: HttpExchange): String =
        ex.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    /**
     * Enforces same-origin client-only access and validates the 4-character session PIN
     * sent in the `X-Pin` header.
     */
    private fun authorizeApiRequest(ex: HttpExchange): Boolean {
        if (ex.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendJson(ex, 403, """{"error":"Cross-origin requests are not allowed"}""")
            return false
        }

        // Reject cross-site / cross-origin browser requests
        val secFetchSite = ex.requestHeaders.getFirst("Sec-Fetch-Site")?.trim()?.lowercase(Locale.US)
        if (!secFetchSite.isNullOrEmpty() && secFetchSite != "same-origin" && secFetchSite != "none") {
            sendJson(ex, 403, """{"error":"Cross-origin API access is forbidden"}""")
            return false
        }

        val origin = ex.requestHeaders.getFirst("Origin")?.trim()
        if (!origin.isNullOrEmpty()) {
            val host = ex.requestHeaders.getFirst("Host")?.trim()
            if (!isSameOrigin(origin, host)) {
                sendJson(ex, 403, """{"error":"Cross-origin API access is forbidden"}""")
                return false
            }
        }

        val providedPin = normalizePin(ex.requestHeaders.getFirst(PIN_HEADER) ?: "")
        val providedBytes = providedPin.toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(accessPinBytes, providedBytes)) {
            sendJson(ex, 401, """{"error":"Invalid or missing PIN"}""")
            return false
        }
        return true
    }

    private fun isSameOrigin(originHeader: String, hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        return try {
            val uri = URI(originHeader)
            val originHost = uri.host ?: return false
            val originAuthority = if (uri.port != -1) "$originHost:${uri.port}" else originHost
            originAuthority.equals(hostHeader, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun applySecurityHeaders(ex: HttpExchange) {
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.set("X-Frame-Options", "DENY")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
    }

    private fun sendJson(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        applySecurityHeaders(ex)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendServerError(ex: HttpExchange, t: Throwable) {
        try {
            sendJson(ex, 500, """{"error":${jsonString(t.message ?: "Internal server error")}}""")
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 8080
        const val PIN_HEADER: String = "X-Pin"
        const val PIN_LENGTH: Int = 4
        // Unambiguous alphanumeric alphabet (excludes 0/O and 1/I/L for easy manual entry)
        private const val PIN_ALPHABET: String = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private val secureRandom = SecureRandom()

        fun generateRandomPin(): String = buildString(PIN_LENGTH) {
            repeat(PIN_LENGTH) {
                append(PIN_ALPHABET[secureRandom.nextInt(PIN_ALPHABET.length)])
            }
        }

        internal fun normalizePin(raw: String): String =
            raw.trim().uppercase(Locale.US).filter { it.isLetterOrDigit() }

        fun defaultConfiguredPort(): Int {
            val prop = System.getProperty("virtualrace.web.port")?.toIntOrNull()
            if (prop != null && prop in 0..65535) return prop
            val env = System.getenv("VIRTUALRACE_WEB_PORT")?.toIntOrNull()
            if (env != null && env in 0..65535) return env
            return DEFAULT_PORT
        }

        internal fun fmt1(value: Float): String = String.format(Locale.US, "%.1f", value)

        internal fun jsonString(value: String): String = buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }

        /**
         * Minimal flat JSON object parser supporting string, integer, boolean, and null values.
         */
        internal fun parseFlatJsonObject(raw: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result
            val regex = Regex(""""([^"\\]+)"\s*:\s*("(?:[^"\\]|\\.)*"|true|false|null|-?\d+(?:\.\d+)?)""")
            for (match in regex.findAll(trimmed)) {
                val key = match.groupValues[1]
                var value = match.groupValues[2]
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value = value.substring(1, value.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
                if (value != "null") {
                    result[key] = value
                }
            }
            return result
        }

        internal fun detectLanIpv4Address(): String? {
            return try {
                val candidates = mutableListOf<String>()
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (ni in interfaces.asSequence()) {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                    val nameLower = ni.name.lowercase(Locale.US)
                    if (nameLower.startsWith("docker") || nameLower.startsWith("br-") || nameLower.startsWith("veth")) {
                        continue
                    }
                    for (addr in ni.inetAddresses.asSequence()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            if (addr.isSiteLocalAddress) {
                                candidates.add(0, host)
                            } else {
                                candidates.add(host)
                            }
                        }
                    }
                }
                candidates.firstOrNull()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
