package dk.kodeologi.virtualrace.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a discovered Dansprint USB HID ergometer (matching 0x0040d7d0).
 */
data class DiscoveredErgoDevice(
    val usbIndex: Int,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val product: String,
    val serialNumber: Long
)

/**
 * Wire packet log entry for the live protocol inspector in the UI.
 */
data class WirePacketLogEntry(
    val id: Long,
    val timestampMs: Long,
    val direction: Direction,
    val deviceOrLaneIndex: Int,
    val rawBytes: ByteArray,
    val hexPreview: String,
    val description: String
) {
    enum class Direction { HOST_TO_ERGO, ERGO_TO_HOST }
}

/**
 * Pluggable low-level transport interface allowing seamless runtime switching between:
 * 1. [UsbHidErgoTransport] - Physical Dansprint USB HID ergometers via native `hidapi`
 * 2. [EmulatedErgoTransport] - Full wire-protocol ergometer fleet emulator for testing without hardware
 */
interface ErgoTransport {
    val modeName: String
    val isEmulated: Boolean
    val wirePacketLog: StateFlow<List<WirePacketLogEntry>>

    /**
     * Enumerates up to 8 Dansprint USB HID devices (VID=0x16C0, PID=0x1503, Manufacturer="Moebius", Product="Dansprint")
     * and opens their handles in non-blocking mode (matching 0x40d7d0 & 0x40dcf0).
     */
    fun enumerateAndOpenDevices(): List<DiscoveredErgoDevice>

    /**
     * Returns whether the USB handle at [usbIndex] (0..7) is currently open (matching 0x485a44[usbIndex]).
     */
    fun isDeviceOpen(usbIndex: Int): Boolean

    /**
     * Writes a framed protocol packet (e.g. 29-byte Type 1 or 18-byte Type 2) to the device at [usbIndex].
     * Prepends HID Report ID 0x00 on physical USB HID (matching 0x40e0cc).
     */
    fun writePacket(usbIndex: Int, packet: ByteArray, description: String = ""): Boolean

    /**
     * Performs a non-blocking read on the device at [usbIndex] (matching 0x40ddcc).
     * Returns an empty ByteArray if no data is available.
     */
    fun readNonBlocking(usbIndex: Int): ByteArray

    /**
     * Closes all open USB handles.
     */
    fun closeAll()

    /**
     * Clears the wire packet log.
     */
    fun clearWireLog()
}
