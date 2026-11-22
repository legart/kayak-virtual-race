package dk.kodeologi.virtualrace.ui

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrCodeGeneratorTest {

    @Test
    fun `test ISO 18004 format information BCH vectors`() {
        // Known ISO/IEC 18004 Table C.1 format strings
        assertEquals(0x5412, QrCodeGenerator.computeFormatBits(ecFormatBits = 0, mask = 0)) // M, mask 0
        assertEquals(0x5125, QrCodeGenerator.computeFormatBits(ecFormatBits = 0, mask = 1)) // M, mask 1
        assertEquals(0x5E7C, QrCodeGenerator.computeFormatBits(ecFormatBits = 0, mask = 2)) // M, mask 2
        assertEquals(0x77C4, QrCodeGenerator.computeFormatBits(ecFormatBits = 1, mask = 0)) // L, mask 0
    }

    @Test
    fun `test Reed-Solomon generator and remainder`() {
        val div2 = QrCodeGenerator.rsComputeDivisor(2)
        assertContentEquals(intArrayOf(3, 2), div2)

        // ISO/IEC 18004 Annex I Version 1-M standard example (16 data -> 10 EC codewords)
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val expectedEc = intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23)
        val div10 = QrCodeGenerator.rsComputeDivisor(10)
        assertContentEquals(expectedEc, QrCodeGenerator.rsComputeRemainder(data, div10))
    }

    @Test
    fun `test encoded LAN URLs round-trip cleanly from final masked QR matrix`() {
        val testUrls = listOf(
            "http://10.0.0.5",                  // Version 2 (15 bytes)
            "http://localhost:8080",            // Version 2 (21 bytes)
            "http://192.168.1.42:8080",         // Version 2 (24 bytes)
            "http://192.168.100.254:8080",      // Version 3 (27 bytes)
            "http://virtual-race-regatta-host.local:8080/remote?v=2" // Version 4 (51 bytes, 2 RS blocks)
        )

        for (url in testUrls) {
            val qr = QrCodeGenerator.encode(url)
            val size = qr.size
            assertEquals(qr.version * 4 + 17, size)

            // 1. Verify fixed dark module at (8, size - 8)
            assertTrue(qr.isDark(8, size - 8), "Dark module missing for $url")

            // 2. Verify all 3 finder centers & white separators
            for ((cx, cy) in listOf(3 to 3, (size - 4) to 3, 3 to (size - 4))) {
                assertTrue(qr.isDark(cx, cy))
                assertFalse(qr.isDark(cx + 2, cy))
                assertTrue(qr.isDark(cx + 3, cy))
            }

            // 3. Verify timing patterns along row 6 and col 6
            for (i in 8 until size - 8) {
                assertEquals(i % 2 == 0, qr.isDark(i, 6))
                assertEquals(i % 2 == 0, qr.isDark(6, i))
            }

            // 4. Read BOTH copies of the 15-bit format string and verify they match
            var fmt1 = 0
            for (i in 0..5) if (qr.isDark(8, i)) fmt1 = fmt1 or (1 shl i)
            if (qr.isDark(8, 7)) fmt1 = fmt1 or (1 shl 6)
            if (qr.isDark(8, 8)) fmt1 = fmt1 or (1 shl 7)
            if (qr.isDark(7, 8)) fmt1 = fmt1 or (1 shl 8)
            for (i in 9..14) if (qr.isDark(14 - i, 8)) fmt1 = fmt1 or (1 shl i)

            var fmt2 = 0
            for (i in 0..7) if (qr.isDark(size - 1 - i, 8)) fmt2 = fmt2 or (1 shl i)
            for (i in 8..14) if (qr.isDark(8, size - 15 + i)) fmt2 = fmt2 or (1 shl i)

            assertEquals(fmt1, fmt2, "Format string copies differ for $url")
            val unmaskedFmt = fmt1 xor 0x5412
            val ecBits = (unmaskedFmt ushr 13) and 3
            val mask = (unmaskedFmt ushr 10) and 7
            assertEquals(0, ecBits, "Expected EC level M (00)")
            assertEquals(fmt1, QrCodeGenerator.computeFormatBits(ecBits, mask))

            // 5. Reconstruct function mask and unmask zig-zag bits to verify payload + RS zero syndrome
            val isFunc = buildFunctionMask(qr.version, size)
            val rawBits = mutableListOf<Int>()
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                val upward = ((right + 1) and 2) == 0
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (!isFunc[y][x]) {
                            val inv = maskCondition(mask, x, y)
                            val bit = if (qr.isDark(x, y) xor inv) 1 else 0
                            rawBits.add(bit)
                        }
                    }
                }
                right -= 2
            }

            val totalCodewords = rawBits.size / 8
            val codewords = IntArray(totalCodewords) { byteIdx ->
                var b = 0
                for (k in 0..7) b = (b shl 1) or rawBits[byteIdx * 8 + k]
                b
            }

            // De-interleave according to Version M block counts
            val (numBlocks, dataPerBlock, ecPerBlock) = when (qr.version) {
                1 -> Triple(1, 16, 10)
                2 -> Triple(1, 28, 16)
                3 -> Triple(1, 44, 26)
                4 -> Triple(2, 32, 18)
                5 -> Triple(2, 43, 24)
                6 -> Triple(4, 27, 16)
                else -> error("Unexpected version ${qr.version}")
            }
            val dataBytes = IntArray(numBlocks * dataPerBlock)
            val div = QrCodeGenerator.rsComputeDivisor(ecPerBlock)
            for (b in 0 until numBlocks) {
                val blockData = IntArray(dataPerBlock) { i -> codewords[i * numBlocks + b] }
                val blockEc = IntArray(ecPerBlock) { i -> codewords[numBlocks * dataPerBlock + i * numBlocks + b] }
                val computedEc = QrCodeGenerator.rsComputeRemainder(blockData, div)
                assertContentEquals(computedEc, blockEc, "RS ECC mismatch in block $b for $url")
                for (i in 0 until dataPerBlock) {
                    dataBytes[b * dataPerBlock + i] = blockData[i]
                }
            }

            // Decode byte-mode stream: 4-bit mode (0100) + 8-bit length + UTF-8 bytes
            val dataBitStream = IntArray(dataBytes.size * 8) { i ->
                (dataBytes[i / 8] ushr (7 - (i % 8))) and 1
            }
            fun readBits(start: Int, len: Int): Int {
                var v = 0
                for (i in 0 until len) v = (v shl 1) or dataBitStream[start + i]
                return v
            }
            assertEquals(0b0100, readBits(0, 4), "Expected Byte Mode 0100")
            val len = readBits(4, 8)
            val decodedBytes = ByteArray(len) { idx -> readBits(12 + idx * 8, 8).toByte() }
            assertEquals(url, String(decodedBytes, StandardCharsets.UTF_8))
        }
    }

    private fun maskCondition(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    private fun buildFunctionMask(version: Int, size: Int): Array<BooleanArray> {
        val f = Array(size) { BooleanArray(size) }
        for (i in 0 until size) {
            f[6][i] = true
            f[i][6] = true
        }
        for ((cx, cy) in listOf(3 to 3, (size - 4) to 3, 3 to (size - 4))) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) f[y][x] = true
            }
        }
        if (version >= 2) {
            val c = size - 7
            for (dy in -2..2) for (dx in -2..2) f[c + dy][c + dx] = true
        }
        for (i in 0..8) {
            f[8][i] = true
            f[i][8] = true
        }
        for (i in 0..7) f[8][size - 1 - i] = true
        for (i in 0..7) f[size - 1 - i][8] = true
        return f
    }
}
