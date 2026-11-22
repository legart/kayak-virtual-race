package dk.kodeologi.virtualrace.ui

import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code matrix generator (Byte mode, Error Correction Level M, Versions 1..6).
 * Supports URLs up to 106 UTF-8 bytes with zero external dependencies.
 */
object QrCodeGenerator {

    data class QrMatrix(
        val version: Int,
        val size: Int,
        val modules: Array<BooleanArray>
    ) {
        fun isDark(x: Int, y: Int): Boolean = modules[y][x]
    }

    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val numBlocks: Int,
        val dataPerBlock: Int,
        val ecPerBlock: Int,
        val alignCenter: Int? // null for Version 1
    ) {
        val totalDataCodewords: Int get() = numBlocks * dataPerBlock
        val maxBytePayload: Int get() = totalDataCodewords - 2 // 4-bit mode + 8-bit count = 1.5 bytes
    }

    // ISO/IEC 18004 Table 9 parameters for Error Correction Level M (15% recovery), Versions 1..6
    private val versionSpecsM = listOf(
        VersionSpec(version = 1, totalCodewords = 26, numBlocks = 1, dataPerBlock = 16, ecPerBlock = 10, alignCenter = null),
        VersionSpec(version = 2, totalCodewords = 44, numBlocks = 1, dataPerBlock = 28, ecPerBlock = 16, alignCenter = 18),
        VersionSpec(version = 3, totalCodewords = 70, numBlocks = 1, dataPerBlock = 44, ecPerBlock = 26, alignCenter = 22),
        VersionSpec(version = 4, totalCodewords = 100, numBlocks = 2, dataPerBlock = 32, ecPerBlock = 18, alignCenter = 26),
        VersionSpec(version = 5, totalCodewords = 134, numBlocks = 2, dataPerBlock = 43, ecPerBlock = 24, alignCenter = 30),
        VersionSpec(version = 6, totalCodewords = 172, numBlocks = 4, dataPerBlock = 27, ecPerBlock = 16, alignCenter = 34)
    )

    private const val EC_LEVEL_M_FORMAT_BITS = 0 // ISO 18004: L=1, M=0, Q=3, H=2

    fun encode(text: String): QrMatrix {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val spec = versionSpecsM.firstOrNull { payload.size <= it.maxBytePayload }
            ?: throw IllegalArgumentException("QR payload too long (${payload.size} bytes, max 106)")

        val dataCodewords = encodeByteModeData(payload, spec.totalDataCodewords)
        val allCodewords = addEccAndInterleave(dataCodewords, spec)

        val size = spec.version * 4 + 17
        val baseModules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(baseModules, isFunction, spec, size)
        drawCodewords(baseModules, isFunction, allCodewords, size)

        // Evaluate all 8 ISO masks and pick the lowest-penalty mask
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules

        for (mask in 0..7) {
            val candidate = Array(size) { y -> baseModules[y].clone() }
            applyMask(candidate, isFunction, mask, size)
            drawFormatBits(candidate, EC_LEVEL_M_FORMAT_BITS, mask, size)
            val penalty = computePenaltyScore(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }

        // Ensure format bits match winning mask (already drawn on bestModules)
        drawFormatBits(bestModules, EC_LEVEL_M_FORMAT_BITS, bestMask, size)
        return QrMatrix(version = spec.version, size = size, modules = bestModules)
    }

    private fun encodeByteModeData(payload: ByteArray, totalDataCodewords: Int): IntArray {
        val bits = ArrayList<Int>(totalDataCodewords * 8)
        fun appendBits(value: Int, length: Int) {
            for (i in (length - 1) downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        // Byte mode indicator (0100) + 8-bit character count (Versions 1..9)
        appendBits(0b0100, 4)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        // Terminator (up to 4 zero bits)
        val capacityBits = totalDataCodewords * 8
        val terminatorLen = minOf(4, capacityBits - bits.size)
        appendBits(0, terminatorLen)

        // Pad to byte boundary
        while (bits.size % 8 != 0) {
            bits.add(0)
        }

        // Pack into bytes and append alternating pad bytes 0xEC, 0x11
        val out = IntArray(totalDataCodewords)
        val usedBytes = bits.size / 8
        for (i in 0 until usedBytes) {
            var v = 0
            for (b in 0..7) {
                v = (v shl 1) or bits[i * 8 + b]
            }
            out[i] = v
        }
        var padByte = 0xEC
        for (i in usedBytes until totalDataCodewords) {
            out[i] = padByte
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }
        return out
    }

    private fun addEccAndInterleave(data: IntArray, spec: VersionSpec): IntArray {
        val divisor = rsComputeDivisor(spec.ecPerBlock)
        val dataBlocks = Array(spec.numBlocks) { b ->
            val start = b * spec.dataPerBlock
            IntArray(spec.dataPerBlock) { i -> data[start + i] }
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            rsComputeRemainder(dataBlocks[b], divisor)
        }

        val result = IntArray(spec.totalCodewords)
        var pos = 0
        for (i in 0 until spec.dataPerBlock) {
            for (b in 0 until spec.numBlocks) {
                result[pos++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until spec.numBlocks) {
                result[pos++] = ecBlocks[b][i]
            }
        }
        return result
    }

    private fun drawFunctionPatterns(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        spec: VersionSpec,
        size: Int
    ) {
        fun setFunc(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        // 1. Timing patterns (row 6 and col 6)
        for (i in 0 until size) {
            setFunc(i, 6, i % 2 == 0)
            setFunc(6, i, i % 2 == 0)
        }

        // 2. Three 7x7 finder patterns + 1-module white separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // 3. Alignment pattern (Versions 2..6 have a single bottom-right 5x5 alignment pattern)
        spec.alignCenter?.let { c ->
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunc(c + dx, c + dy, dist != 1)
                }
            }
        }

        // 4. Reserve format information modules + fixed dark module
        drawFormatBits(modules, EC_LEVEL_M_FORMAT_BITS, 0, size, markFunction = isFunction)
    }

    internal fun computeFormatBits(ecFormatBits: Int, mask: Int): Int {
        val data = ((ecFormatBits and 3) shl 3) or (mask and 7)
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun drawFormatBits(
        modules: Array<BooleanArray>,
        ecFormatBits: Int,
        mask: Int,
        size: Int,
        markFunction: Array<BooleanArray>? = null
    ) {
        val bits = computeFormatBits(ecFormatBits, mask)
        fun setMod(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            if (markFunction != null) markFunction[y][x] = true
        }

        // First copy around top-left finder
        for (i in 0..5) setMod(8, i, ((bits ushr i) and 1) != 0)
        setMod(8, 7, ((bits ushr 6) and 1) != 0)
        setMod(8, 8, ((bits ushr 7) and 1) != 0)
        setMod(7, 8, ((bits ushr 8) and 1) != 0)
        for (i in 9..14) setMod(14 - i, 8, ((bits ushr i) and 1) != 0)

        // Second copy split across top-right and bottom-left
        for (i in 0..7) setMod(size - 1 - i, 8, ((bits ushr i) and 1) != 0)
        for (i in 8..14) setMod(8, size - 15 + i, ((bits ushr i) and 1) != 0)

        // Always-dark module at (8, size - 8)
        setMod(8, size - 8, true)
    }

    private fun drawCodewords(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        codewords: IntArray,
        size: Int
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx ushr 3] ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[y][x] = dark
                        bitIdx++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        mask: Int,
        size: Int
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y][x]) continue
                val invert = when (mask) {
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
                if (invert) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }
    }

    private fun computePenaltyScore(modules: Array<BooleanArray>, size: Int): Int {
        var score = 0

        // Rule 1: adjacent modules in row/column of same color (5+ -> 3 + extra)
        for (y in 0 until size) {
            var runColor = modules[y][0]
            var runLen = 1
            for (x in 1 until size) {
                if (modules[y][x] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = modules[y][x]
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = modules[0][x]
            var runLen = 1
            for (y in 1 until size) {
                if (modules[y][x] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = modules[y][x]
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }

        // Rule 2: 2x2 blocks of same color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    score += 3
                }
            }
        }

        // Rule 3: finder-like 1:1:3:1:1 patterns with 4 light modules on either side
        val patternA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val patternB = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        for (y in 0 until size) {
            for (x in 0..(size - 11)) {
                var matchA = true
                var matchB = true
                for (k in 0 until 11) {
                    if (modules[y][x + k] != patternA[k]) matchA = false
                    if (modules[y][x + k] != patternB[k]) matchB = false
                }
                if (matchA || matchB) score += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..(size - 11)) {
                var matchA = true
                var matchB = true
                for (k in 0 until 11) {
                    if (modules[y + k][x] != patternA[k]) matchA = false
                    if (modules[y + k][x] != patternB[k]) matchB = false
                }
                if (matchA || matchB) score += 40
            }
        }

        // Rule 4: balance of dark vs light modules
        var darkCount = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (modules[y][x]) darkCount++
            }
        }
        val total = size * size
        val k = (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        score += maxOf(0, k) * 10

        return score
    }

    internal fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    internal fun rsComputeDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) {
                    result[j] = result[j] xor result[j + 1]
                }
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    internal fun rsComputeRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = result[i] xor gfMul(divisor[i], factor)
            }
        }
        return result
    }
}
