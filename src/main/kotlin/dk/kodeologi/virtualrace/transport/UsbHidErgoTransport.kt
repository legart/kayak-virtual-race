package dk.kodeologi.virtualrace.transport

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import dk.kodeologi.virtualrace.protocol.DansprintProtocol.toHexString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Physical USB HID transport for Moebius Dansprint ergometers (`VID=0x16C0`, `PID=0x1503`).
 *
 * - On **Linux**, talks directly to kernel `/dev/hidraw*` devices via JNA `libc` (`open`/`read`/`write`/`close`)
 *   with zero external `libhidapi` dependency, reading USB metadata from `/sys/class/hidraw`.
 * - On **macOS / Windows** (or if Linux hidraw is unavailable), binds to native `hidapi` via JNA.
 */
class UsbHidErgoTransport : ErgoTransport {
    override val modeName: String = "Physical USB HID (16C0:1503)"
    override val isEmulated: Boolean = false

    private val _wirePacketLog = MutableStateFlow<List<WirePacketLogEntry>>(emptyList())
    override val wirePacketLog: StateFlow<List<WirePacketLogEntry>> = _wirePacketLog.asStateFlow()
    private val packetSeq = AtomicLong(0L)

    // Linux direct hidraw file descriptors (-1 = closed)
    private val hidrawFds = IntArray(DansprintProtocol.MAX_LANES) { -1 }
    private val hidrawNodeNames = arrayOfNulls<String>(DansprintProtocol.MAX_LANES)

    // Cross-platform hidapi handles (null = closed)
    private val hidapiHandles = arrayOfNulls<Pointer>(DansprintProtocol.MAX_LANES)

    private val serialNumbers = LongArray(DansprintProtocol.MAX_LANES)
    private val handleOpen = BooleanArray(DansprintProtocol.MAX_LANES)
    private val lastIoErrors = arrayOfNulls<String>(DansprintProtocol.MAX_LANES)
    private var lastEnumeratedDeviceKeys: Set<String> = emptySet()

    enum class HotplugCheckResult {
        NO_CHANGE,
        DEVICES_REMOVED,
        NEW_DEVICES_DETECTED
    }

    private val osNameLower: String =
        System.getProperty("os.name", "").lowercase(Locale.US)

    private val isLinux: Boolean = osNameLower.contains("linux")
    private val isWindows: Boolean = osNameLower.contains("win")
    private val isMac: Boolean = osNameLower.contains("mac") || osNameLower.contains("darwin")

    private val linuxLibC: LinuxLibC? by lazy {
        if (!isLinux) null else runCatching { Native.load("c", LinuxLibC::class.java) }.getOrNull()
    }

    private val hidLib: HidApiLibrary? by lazy { loadHidApiLibrary() }

    @Volatile
    var lastStatusMessage: String = "Ready to scan USB HID bus for Moebius Dansprint (16C0:1503)"
        private set

    @Volatile
    var lastPermissionError: String? = null
        private set

    private var activeBackendIsHidraw: Boolean = false

    @Synchronized
    override fun enumerateAndOpenDevices(): List<DiscoveredErgoDevice> {
        closeAllInternal()
        lastPermissionError = null

        if (isLinux && linuxLibC != null) {
            return enumerateAndOpenLinuxHidraw(linuxLibC!!)
        }
        return enumerateAndOpenHidApi()
    }

    // =========================================================================
    // Backend 1: Direct Linux /dev/hidraw* via JNA libc (no libhidapi needed!)
    // =========================================================================

    private fun enumerateAndOpenLinuxHidraw(libc: LinuxLibC): List<DiscoveredErgoDevice> {
        activeBackendIsHidraw = true
        val sysHidrawDir = File("/sys/class/hidraw")
        val entries = sysHidrawDir.listFiles()
            ?.filter { it.name.startsWith("hidraw") }
            ?.sortedBy { it.name.removePrefix("hidraw").toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()

        val discovered = mutableListOf<DiscoveredErgoDevice>()
        val discoveredKeys = mutableSetOf<String>()
        val permissionDeniedPaths = mutableListOf<String>()
        val openErrors = mutableListOf<String>()
        var usbIdx = 0
        var openedCount = 0

        for (entry in entries) {
            if (usbIdx >= DansprintProtocol.MAX_LANES) break
            val meta = readLinuxHidrawMeta(entry) ?: continue
            if (!matchesDansprintDevice(meta.vendorId, meta.productId, meta.manufacturer, meta.product)) {
                continue
            }

            discoveredKeys.add("${entry.name}:${meta.serialString}")
            val devPath = "/dev/${entry.name}"
            val serial = meta.serialString.trim().toLongOrNull() ?: 0L
            serialNumbers[usbIdx] = serial
            discovered.add(
                DiscoveredErgoDevice(
                    usbIndex = usbIdx,
                    vendorId = meta.vendorId,
                    productId = meta.productId,
                    manufacturer = meta.manufacturer,
                    product = meta.product,
                    serialNumber = serial
                )
            )

            try {
                val fd = libc.open(devPath, O_RDWR or O_NONBLOCK)
                if (fd >= 0) {
                    hidrawFds[usbIdx] = fd
                    hidrawNodeNames[usbIdx] = entry.name
                    handleOpen[usbIdx] = true
                    openedCount++
                } else {
                    openErrors.add("$devPath (fd=$fd)")
                }
            } catch (e: LastErrorException) {
                val err = e.errorCode
                if (err == EACCES || err == EPERM) {
                    permissionDeniedPaths.add(devPath)
                } else {
                    openErrors.add("$devPath (errno=$err)")
                }
            } catch (t: Throwable) {
                openErrors.add("$devPath (${t.message})")
            }

            usbIdx++
        }

        lastEnumeratedDeviceKeys = discoveredKeys

        when {
            discovered.isEmpty() -> {
                lastStatusMessage = "No physical Dansprint ergometers (16C0:1503) detected on /dev/hidraw*."
            }
            openedCount == 0 && permissionDeniedPaths.isNotEmpty() -> {
                val pathsText = permissionDeniedPaths.joinToString(", ")
                val msg = "Found ${discovered.size} Dansprint USB device(s), but permission denied on $pathsText. " +
                    "Add udev rule: SUBSYSTEM==\"hidraw\", ATTRS{idVendor}==\"16c0\", ATTRS{idProduct}==\"1503\", MODE=\"0666\""
                lastStatusMessage = msg
                lastPermissionError = msg
            }
            openedCount < discovered.size -> {
                val extra = (permissionDeniedPaths.map { "$it (EACCES)" } + openErrors).joinToString(", ")
                lastStatusMessage = "Opened $openedCount/${discovered.size} Dansprint USB device(s) via hidraw. Failed: $extra"
                if (permissionDeniedPaths.isNotEmpty()) {
                    lastPermissionError = lastStatusMessage
                }
            }
            else -> {
                lastStatusMessage = "Opened $openedCount Dansprint USB ergometer(s) directly via Linux hidraw!"
            }
        }

        return discovered
    }

    private data class LinuxHidrawMeta(
        val vendorId: Int,
        val productId: Int,
        val manufacturer: String,
        val product: String,
        val serialString: String
    )

    private fun readLinuxHidrawMeta(hidrawSysDir: File): LinuxHidrawMeta? {
        val ueventFile = File(hidrawSysDir, "device/uevent")
        if (!ueventFile.isFile) return null

        val uevent = runCatching { ueventFile.readLines() }.getOrNull() ?: return null
        var vid = 0
        var pid = 0
        var hidName = ""
        var hidUniq = ""

        for (line in uevent) {
            when {
                line.startsWith("HID_ID=") -> {
                    // Format: HID_ID=0003:000016C0:00001503
                    val parts = line.removePrefix("HID_ID=").split(":")
                    if (parts.size == 3) {
                        vid = parts[1].toIntOrNull(16) ?: 0
                        pid = parts[2].toIntOrNull(16) ?: 0
                    }
                }
                line.startsWith("HID_NAME=") -> hidName = line.removePrefix("HID_NAME=").trim()
                line.startsWith("HID_UNIQ=") -> hidUniq = line.removePrefix("HID_UNIQ=").trim()
            }
        }

        // Also inspect parent USB sysfs node for exact manufacturer/product/serial strings
        val usbDeviceDir = runCatching {
            File(hidrawSysDir, "device").canonicalFile.parentFile?.parentFile
        }.getOrNull()

        fun readSysAttr(name: String): String {
            if (usbDeviceDir == null) return ""
            val f = File(usbDeviceDir, name)
            return if (f.isFile) runCatching { f.readText().trim() }.getOrDefault("") else ""
        }

        if (vid == 0) vid = readSysAttr("idVendor").toIntOrNull(16) ?: 0
        if (pid == 0) pid = readSysAttr("idProduct").toIntOrNull(16) ?: 0

        val manufacturer = readSysAttr("manufacturer").ifBlank {
            if (hidName.startsWith(DansprintProtocol.USB_MANUFACTURER)) DansprintProtocol.USB_MANUFACTURER else ""
        }
        val product = readSysAttr("product").ifBlank {
            hidName.removePrefix(DansprintProtocol.USB_MANUFACTURER).trim()
        }
        val serial = readSysAttr("serial").ifBlank { hidUniq }

        return LinuxHidrawMeta(
            vendorId = vid,
            productId = pid,
            manufacturer = manufacturer,
            product = product,
            serialString = serial
        )
    }

    // =========================================================================
    // Backend 2: Native hidapi via JNA (macOS / Windows / fallback)
    // =========================================================================

    private fun enumerateAndOpenHidApi(): List<DiscoveredErgoDevice> {
        activeBackendIsHidraw = false
        val lib = hidLib
        if (lib == null) {
            lastStatusMessage = when {
                isWindows -> "Native hidapi.dll not found (place 64-bit hidapi.dll in the app folder or on PATH, or use Emulator Mode)"
                isMac -> "Native libhidapi not found (run 'brew install hidapi' or use Emulator Mode)"
                else -> "Native libhidapi not found on system (install libhidapi-hidraw0 or use Emulator Mode)"
            }
            return emptyList()
        }

        val discovered = mutableListOf<DiscoveredErgoDevice>()
        val discoveredKeys = mutableSetOf<String>()
        val pathsByUsbIdx = arrayOfNulls<String>(DansprintProtocol.MAX_LANES)

        val headPtr = try {
            lib.hid_enumerate(
                DansprintProtocol.USB_VENDOR_ID.toShort(),
                DansprintProtocol.USB_PRODUCT_ID.toShort()
            )
        } catch (t: Throwable) {
            lastStatusMessage = "hid_enumerate error: ${t.message}"
            null
        }

        if (headPtr != null) {
            try {
                var cur: HidDeviceInfo? = HidDeviceInfo(headPtr)
                var usbIdx = 0
                while (cur != null && usbIdx < DansprintProtocol.MAX_LANES) {
                    val vid = cur.vendor_id.toInt() and 0xFFFF
                    val pid = cur.product_id.toInt() and 0xFFFF
                    val manufacturer = cur.manufacturer_string?.toString() ?: ""
                    val product = cur.product_string?.toString() ?: ""
                    val serialStr = cur.serial_number?.toString()?.trim() ?: "0"

                    if (matchesDansprintDevice(vid, pid, manufacturer, product)) {
                        val serial = serialStr.toLongOrNull() ?: 0L
                        serialNumbers[usbIdx] = serial
                        pathsByUsbIdx[usbIdx] = cur.path
                        discoveredKeys.add("${cur.path ?: usbIdx}:$serialStr")
                        discovered.add(
                            DiscoveredErgoDevice(
                                usbIndex = usbIdx,
                                vendorId = vid,
                                productId = pid,
                                manufacturer = manufacturer,
                                product = product,
                                serialNumber = serial
                            )
                        )
                        usbIdx++
                    }
                    val nextPtr = cur.next
                    cur = if (nextPtr != null && Pointer.nativeValue(nextPtr) != 0L) {
                        HidDeviceInfo(nextPtr)
                    } else {
                        null
                    }
                }
            } finally {
                lib.hid_free_enumeration(headPtr)
            }
        }

        lastEnumeratedDeviceKeys = discoveredKeys

        var openedCount = 0
        for (dev in discovered) {
            val path = pathsByUsbIdx[dev.usbIndex]
            val handle = (if (!path.isNullOrBlank()) lib.hid_open_path(path) else null)
                ?: lib.hid_open(
                    DansprintProtocol.USB_VENDOR_ID.toShort(),
                    DansprintProtocol.USB_PRODUCT_ID.toShort(),
                    WString("%10d".format(dev.serialNumber))
                )
                ?: lib.hid_open(
                    DansprintProtocol.USB_VENDOR_ID.toShort(),
                    DansprintProtocol.USB_PRODUCT_ID.toShort(),
                    WString(dev.serialNumber.toString())
                )

            if (handle != null && Pointer.nativeValue(handle) != 0L) {
                lib.hid_set_nonblocking(handle, 1)
                hidapiHandles[dev.usbIndex] = handle
                handleOpen[dev.usbIndex] = true
                openedCount++
            }
        }

        lastStatusMessage = if (discovered.isEmpty()) {
            "No physical Dansprint ergometers (16C0:1503) detected on USB bus."
        } else {
            "Found ${discovered.size} Dansprint USB device(s), opened $openedCount handle(s)."
        }
        return discovered
    }

    private fun matchesDansprintDevice(
        vid: Int,
        pid: Int,
        manufacturer: String,
        product: String
    ): Boolean {
        if (vid != DansprintProtocol.USB_VENDOR_ID || pid != DansprintProtocol.USB_PRODUCT_ID) {
            return false
        }
        val mOk = manufacturer.isBlank() || manufacturer.startsWith(DansprintProtocol.USB_MANUFACTURER, ignoreCase = true)
        val pOk = product.isBlank() || product.startsWith(DansprintProtocol.USB_PRODUCT, ignoreCase = true)
        return mOk && pOk
    }

    // =========================================================================
    // USB Hotplug Detection
    // =========================================================================

    /**
     * Lightweight poll to detect newly plugged-in or unplugged Dansprint ergometers
     * without closing active handles unless a device actually disappeared.
     */
    @Synchronized
    fun pollHotplugChanges(): HotplugCheckResult {
        if (isLinux && linuxLibC != null) {
            val currentKeys = scanLinuxHidrawDeviceKeys()
            val added = currentKeys - lastEnumeratedDeviceKeys
            val removed = lastEnumeratedDeviceKeys - currentKeys

            var closedAny = false
            for (i in 0 until DansprintProtocol.MAX_LANES) {
                val nodeName = hidrawNodeNames[i] ?: continue
                val stillPresent = currentKeys.any { it.startsWith("$nodeName:") }
                if (!stillPresent) {
                    closeSingleHidraw(i)
                    closedAny = true
                }
            }

            if (added.isNotEmpty()) {
                return HotplugCheckResult.NEW_DEVICES_DETECTED
            }
            if (removed.isNotEmpty() || closedAny) {
                lastEnumeratedDeviceKeys = currentKeys
                return HotplugCheckResult.DEVICES_REMOVED
            }
            return HotplugCheckResult.NO_CHANGE
        }

        val currentKeys = scanHidApiDeviceKeys() ?: return HotplugCheckResult.NO_CHANGE
        val added = currentKeys - lastEnumeratedDeviceKeys
        val removed = lastEnumeratedDeviceKeys - currentKeys
        if (added.isNotEmpty()) {
            return HotplugCheckResult.NEW_DEVICES_DETECTED
        }
        if (removed.isNotEmpty()) {
            lastEnumeratedDeviceKeys = currentKeys
            return HotplugCheckResult.DEVICES_REMOVED
        }
        return HotplugCheckResult.NO_CHANGE
    }

    private fun scanLinuxHidrawDeviceKeys(): Set<String> {
        val sysHidrawDir = File("/sys/class/hidraw")
        val entries = sysHidrawDir.listFiles()
            ?.filter { it.name.startsWith("hidraw") }
            ?: return emptySet()

        val keys = mutableSetOf<String>()
        for (entry in entries) {
            val meta = readLinuxHidrawMeta(entry) ?: continue
            if (matchesDansprintDevice(meta.vendorId, meta.productId, meta.manufacturer, meta.product)) {
                keys.add("${entry.name}:${meta.serialString}")
            }
        }
        return keys
    }

    private fun scanHidApiDeviceKeys(): Set<String>? {
        val lib = hidLib ?: return null
        val headPtr = runCatching {
            lib.hid_enumerate(
                DansprintProtocol.USB_VENDOR_ID.toShort(),
                DansprintProtocol.USB_PRODUCT_ID.toShort()
            )
        }.getOrNull() ?: return emptySet()

        val keys = mutableSetOf<String>()
        try {
            var cur: HidDeviceInfo? = HidDeviceInfo(headPtr)
            var idx = 0
            while (cur != null && idx < DansprintProtocol.MAX_LANES) {
                val vid = cur.vendor_id.toInt() and 0xFFFF
                val pid = cur.product_id.toInt() and 0xFFFF
                val manufacturer = cur.manufacturer_string?.toString() ?: ""
                val product = cur.product_string?.toString() ?: ""
                val serialStr = cur.serial_number?.toString()?.trim() ?: "0"
                if (matchesDansprintDevice(vid, pid, manufacturer, product)) {
                    keys.add("${cur.path ?: idx}:$serialStr")
                    idx++
                }
                val nextPtr = cur.next
                cur = if (nextPtr != null && Pointer.nativeValue(nextPtr) != 0L) {
                    HidDeviceInfo(nextPtr)
                } else {
                    null
                }
            }
        } finally {
            lib.hid_free_enumeration(headPtr)
        }
        return keys
    }

    private fun closeSingleHidraw(usbIndex: Int) {
        val fd = hidrawFds[usbIndex]
        if (fd >= 0) {
            val libc = linuxLibC
            if (libc != null) {
                runCatching { libc.close(fd) }
            }
        }
        hidrawFds[usbIndex] = -1
        hidrawNodeNames[usbIndex] = null
        handleOpen[usbIndex] = false
    }

    // =========================================================================
    // I/O Operations
    // =========================================================================

    @Synchronized
    override fun isDeviceOpen(usbIndex: Int): Boolean {
        return usbIndex in 0 until DansprintProtocol.MAX_LANES && handleOpen[usbIndex]
    }

    @Synchronized
    override fun writePacket(usbIndex: Int, packet: ByteArray, description: String): Boolean {
        if (!isDeviceOpen(usbIndex)) {
            if (usbIndex in 0 until DansprintProtocol.MAX_LANES) {
                lastIoErrors[usbIndex] = "USB device handle not open"
            }
            return false
        }

        // Byte 0 is HID Report ID 0x00 (unnumbered 40-byte Output report), followed by up to 40 payload bytes
        val report = ByteArray(DansprintProtocol.HID_OUTPUT_REPORT_LENGTH)
        report[0] = 0x00
        System.arraycopy(packet, 0, report, 1, min(packet.size, report.size - 1))

        val ok = if (activeBackendIsHidraw) {
            val fd = hidrawFds[usbIndex]
            val node = hidrawNodeNames[usbIndex] ?: "hidraw?"
            val libc = linuxLibC
            if (fd < 0 || libc == null) {
                lastIoErrors[usbIndex] = "hidraw fd closed on /dev/$node"
                false
            } else {
                try {
                    val written = libc.write(fd, report, report.size)
                    if (written < 0) {
                        lastIoErrors[usbIndex] = "hidraw write returned $written on /dev/$node"
                    }
                    written >= 0
                } catch (e: LastErrorException) {
                    val err = e.errorCode
                    if (err != EAGAIN && err != EINTR) {
                        lastIoErrors[usbIndex] = "hidraw write failed (errno=$err) on /dev/$node - handle closed"
                        closeSingleHidraw(usbIndex)
                    } else {
                        lastIoErrors[usbIndex] = "hidraw write transient errno=$err on /dev/$node"
                    }
                    false
                } catch (t: Throwable) {
                    lastIoErrors[usbIndex] = "hidraw write exception (${t.message}) on /dev/$node - handle closed"
                    closeSingleHidraw(usbIndex)
                    false
                }
            }
        } else {
            val handle = hidapiHandles[usbIndex]
            val lib = hidLib
            if (handle == null || lib == null) {
                lastIoErrors[usbIndex] = "hidapi handle closed"
                false
            } else {
                val written = lib.hid_write(handle, report, report.size)
                if (written < 0) {
                    lastIoErrors[usbIndex] = "hid_write failed (ret=$written) - handle closed"
                    handleOpen[usbIndex] = false
                }
                written >= 0
            }
        }

        appendLog(
            WirePacketLogEntry.Direction.HOST_TO_ERGO,
            usbIndex,
            packet,
            if (ok) description else "$description [WRITE FAILED]"
        )
        return ok
    }

    @Synchronized
    override fun readNonBlocking(usbIndex: Int): ByteArray {
        if (!isDeviceOpen(usbIndex)) return ByteArray(0)

        val buf = ByteArray(64)
        val bytesRead = if (activeBackendIsHidraw) {
            val fd = hidrawFds[usbIndex]
            val node = hidrawNodeNames[usbIndex] ?: "hidraw?"
            val libc = linuxLibC
            if (fd < 0 || libc == null) {
                0
            } else {
                try {
                    libc.read(fd, buf, buf.size)
                } catch (e: LastErrorException) {
                    val err = e.errorCode
                    if (err == EAGAIN || err == EINTR) {
                        0 // Normal non-blocking empty poll
                    } else {
                        lastIoErrors[usbIndex] = "hidraw read failed (errno=$err) on /dev/$node - handle closed"
                        closeSingleHidraw(usbIndex)
                        -1
                    }
                } catch (t: Throwable) {
                    lastIoErrors[usbIndex] = "hidraw read exception (${t.message}) on /dev/$node - handle closed"
                    closeSingleHidraw(usbIndex)
                    -1
                }
            }
        } else {
            val handle = hidapiHandles[usbIndex]
            val lib = hidLib
            if (handle == null || lib == null) {
                0
            } else {
                val n = lib.hid_read(handle, buf, buf.size)
                if (n < 0) {
                    lastIoErrors[usbIndex] = "hid_read failed (ret=$n) - handle closed"
                    handleOpen[usbIndex] = false
                }
                n
            }
        }

        if (bytesRead <= 0) return ByteArray(0)

        val data = buf.copyOfRange(0, bytesRead)
        appendLog(WirePacketLogEntry.Direction.ERGO_TO_HOST, usbIndex, data, "USB HID Input ($bytesRead B)")
        return data
    }

    @Synchronized
    override fun consumeLastIoError(usbIndex: Int): String? {
        if (usbIndex !in 0 until DansprintProtocol.MAX_LANES) return null
        val err = lastIoErrors[usbIndex]
        lastIoErrors[usbIndex] = null
        return err
    }

    @Synchronized
    override fun closeAll() {
        closeAllInternal()
    }

    private fun closeAllInternal() {
        val libc = linuxLibC
        val lib = hidLib
        for (i in 0 until DansprintProtocol.MAX_LANES) {
            val fd = hidrawFds[i]
            if (fd >= 0 && libc != null) {
                try {
                    libc.close(fd)
                } catch (_: Throwable) {
                }
            }
            hidrawFds[i] = -1
            hidrawNodeNames[i] = null

            val h = hidapiHandles[i]
            if (h != null && lib != null) {
                try {
                    lib.hid_close(h)
                } catch (_: Throwable) {
                }
            }
            hidapiHandles[i] = null
            handleOpen[i] = false
            serialNumbers[i] = 0L
            lastIoErrors[i] = null
        }
        lastEnumeratedDeviceKeys = emptySet()
    }

    override fun clearWireLog() {
        _wirePacketLog.value = emptyList()
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

    private fun loadHidApiLibrary(): HidApiLibrary? {
        // Avoid generic "hid" because Windows System32\hid.dll is Microsoft's Win32 HID API, not libhidapi.
        val candidates = if (isWindows) {
            listOf("hidapi")
        } else {
            listOf("hidapi-hidraw", "hidapi-libusb", "hidapi")
        }
        for (name in candidates) {
            try {
                val lib = Native.load(name, HidApiLibrary::class.java)
                // Verify the loaded library actually exports libhidapi symbols and initializes cleanly.
                if (lib.hid_init() == 0) {
                    return lib
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    interface LinuxLibC : Library {
        @Throws(LastErrorException::class)
        fun open(path: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun read(fd: Int, buf: ByteArray, count: Int): Int

        @Throws(LastErrorException::class)
        fun write(fd: Int, buf: ByteArray, count: Int): Int

        @Throws(LastErrorException::class)
        fun close(fd: Int): Int
    }

    interface HidApiLibrary : Library {
        fun hid_init(): Int
        fun hid_exit(): Int
        fun hid_enumerate(vendor_id: Short, product_id: Short): Pointer?
        fun hid_free_enumeration(devs: Pointer?)
        fun hid_open(vendor_id: Short, product_id: Short, serial_number: WString?): Pointer?
        fun hid_open_path(path: String): Pointer?
        fun hid_close(device: Pointer?)
        fun hid_set_nonblocking(device: Pointer?, nonblock: Int): Int
        fun hid_write(device: Pointer?, data: ByteArray, length: Int): Int
        fun hid_read(device: Pointer?, data: ByteArray, length: Int): Int
    }

    @Structure.FieldOrder(
        "path",
        "vendor_id",
        "product_id",
        "serial_number",
        "release_number",
        "manufacturer_string",
        "product_string",
        "usage_page",
        "usage",
        "interface_number",
        "next"
    )
    class HidDeviceInfo(p: Pointer) : Structure(p) {
        @JvmField var path: String? = null
        @JvmField var vendor_id: Short = 0
        @JvmField var product_id: Short = 0
        @JvmField var serial_number: WString? = null
        @JvmField var release_number: Short = 0
        @JvmField var manufacturer_string: WString? = null
        @JvmField var product_string: WString? = null
        @JvmField var usage_page: Short = 0
        @JvmField var usage: Short = 0
        @JvmField var interface_number: Int = 0
        @JvmField var next: Pointer? = null

        init {
            read()
        }
    }

    companion object {
        // Linux x86_64 / arm64 fcntl & errno constants
        private const val O_RDWR = 2
        private const val O_NONBLOCK = 0x800
        private const val EPERM = 1
        private const val EINTR = 4
        private const val EAGAIN = 11
        private const val EACCES = 13
    }
}
