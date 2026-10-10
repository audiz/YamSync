package io.github.audiz.sync

/**
 * 🏁 Чистый мультиплатформенный генератор матриц QR-кодов по стандарту ISO/IEC 18004.
 * Реализация основана на эталонном алгоритме Project Nayuki (MIT License).
 * Поддерживает авто-выбор режима (Numeric, Alphanumeric, Byte UTF-8),
 * уровни коррекции ошибок Reed-Solomon (L, M, Q, H), расчет масок и штрафов.
 */
object QrCodeGenerator {

    enum class EccLevel(val formatBits: Int) {
        L(1), // ~7% восстановление (минимальный размер матрицы)
        M(0), // ~15% восстановление
        Q(3), // ~25% восстановление
        H(2)  // ~30% восстановление
    }

    /**
     * Сгенерировать булеву матрицу QR-кода (true = черный модуль, false = белый).
     * @param text Текст/URL для кодирования
     * @param ecc Уровень коррекции ошибок (по умолчанию Low для максимальной читаемости на экранах)
     */
    fun encode(text: String, ecc: EccLevel = EccLevel.L): Array<BooleanArray> {
        val qr = encodeText(text, ecc)
        val size = qr.size
        return Array(size) { row ->
            BooleanArray(size) { col ->
                qr.getModule(col, row)
            }
        }
    }

    private fun encodeText(text: String, ecl: EccLevel): QrCode {
        val segs = QrSegment.makeSegments(text)
        return encodeSegments(segs, ecl)
    }

    private fun encodeSegments(
        segs: List<QrSegment>,
        ecl: EccLevel,
        minVersion: Int = 1,
        maxVersion: Int = 40,
        mask: Int = -1,
        boostEcl: Boolean = false
    ): QrCode {
        require(minVersion in 1..maxVersion && maxVersion <= 40) { "Invalid version range" }
        require(mask in -1..7) { "Invalid mask" }

        // Поиск минимальной подходящей версии
        var version = minVersion
        var dataUsedBits = -1
        while (true) {
            val dataCapacityBits = getNumDataCodewords(version, ecl) * 8
            val used = QrSegment.getTotalBits(segs, version)
            if (used != -1 && used <= dataCapacityBits) {
                dataUsedBits = used
                break
            }
            if (version >= maxVersion) {
                error("Segment too long: data length exceeds capacity")
            }
            version++
        }

        var effectiveEcl = ecl
        if (boostEcl) {
            for (newEcl in EccLevel.entries) {
                if (dataUsedBits <= getNumDataCodewords(version, newEcl) * 8) {
                    effectiveEcl = newEcl
                }
            }
        }

        // Формирование битового потока
        val bb = BitBuffer()
        for (seg in segs) {
            bb.appendBits(seg.mode.modeBits, 4)
            bb.appendBits(seg.numChars, seg.mode.numCharCountBits(version))
            bb.appendData(seg.data)
        }

        val dataCapacityBits = getNumDataCodewords(version, effectiveEcl) * 8
        val terminatorLen = minOf(4, dataCapacityBits - bb.bitLength)
        bb.appendBits(0, terminatorLen)
        bb.appendBits(0, (8 - bb.bitLength % 8) % 8)

        // Дополнение чередующимися байтами 0xEC и 0x11
        var padByte = 0xEC
        while (bb.bitLength < dataCapacityBits) {
            bb.appendBits(padByte, 8)
            padByte = padByte xor (0xEC xor 0x11)
        }

        val dataCodewords = ByteArray(bb.bitLength / 8)
        for (i in 0 until bb.bitLength) {
            val byteIndex = i ushr 3
            dataCodewords[byteIndex] = (dataCodewords[byteIndex].toInt() or (bb.getBit(i) shl (7 - (i and 7)))).toByte()
        }

        return QrCode(version, effectiveEcl, dataCodewords, mask)
    }

    private class QrCode(
        val version: Int,
        val errorCorrectionLevel: EccLevel,
        dataCodewords: ByteArray,
        var mask: Int
    ) {
        val size: Int = version * 4 + 17
        private val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        init {
            drawFunctionPatterns()
            val allCodewords = addEccAndInterleave(dataCodewords)
            drawCodewords(allCodewords)

            if (mask == -1) {
                var minPenalty = Int.MAX_VALUE
                for (m in 0..7) {
                    applyMask(m)
                    drawFormatBits(m)
                    val penalty = getPenaltyScore()
                    if (penalty < minPenalty) {
                        mask = m
                        minPenalty = penalty
                    }
                    applyMask(m) // Откат маски
                }
            }
            applyMask(mask)
            drawFormatBits(mask)
        }

        fun getModule(x: Int, y: Int): Boolean {
            return x in 0 until size && y in 0 until size && modules[y][x]
        }

        private fun setFunctionModule(x: Int, y: Int, isDark: Boolean) {
            modules[y][x] = isDark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            // Синхронизирующие полосы (Timing patterns)
            for (i in 0 until size) {
                setFunctionModule(6, i, i % 2 == 0)
                setFunctionModule(i, 6, i % 2 == 0)
            }

            // 3 шаблона позиционирования (Finder patterns)
            drawFinderPattern(3, 3)
            drawFinderPattern(size - 4, 3)
            drawFinderPattern(3, size - 4)

            // Шаблоны выравнивания (Alignment patterns)
            val alignPos = getAlignmentPatternPositions()
            val numAlign = alignPos.size
            for (i in 0 until numAlign) {
                for (j in 0 until numAlign) {
                    if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1 || i == numAlign - 1 && j == 0)) {
                        drawAlignmentPattern(alignPos[i], alignPos[j])
                    }
                }
            }

            // Форматные биты и информация о версии
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFormatBits(msk: Int) {
            val data = (errorCorrectionLevel.formatBits shl 3) or msk
            var rem = data
            for (i in 0 until 10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or rem) xor 0x5412

            // Первая копия (вокруг левого верхнего)
            for (i in 0..5) setFunctionModule(8, i, getBit(bits, i))
            setFunctionModule(8, 7, getBit(bits, 6))
            setFunctionModule(8, 8, getBit(bits, 7))
            setFunctionModule(7, 8, getBit(bits, 8))
            for (i in 9..14) setFunctionModule(14 - i, 8, getBit(bits, i))

            // Вторая копия (вокруг правого верхнего и левого нижнего)
            for (i in 0..7) setFunctionModule(size - 1 - i, 8, getBit(bits, i))
            for (i in 8..14) setFunctionModule(8, size - 15 + i, getBit(bits, i))
            setFunctionModule(8, size - 8, true) // Dark module
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) {
                rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = getBit(bits, i)
                val a = size - 11 + i % 3
                val b = i / 3
                setFunctionModule(a, b, bit)
                setFunctionModule(b, a, bit)
            }
        }

        private fun drawFinderPattern(x: Int, y: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until size && yy in 0 until size) {
                        setFunctionModule(xx, yy, dist != 2 && dist != 4)
                    }
                }
            }
        }

        private fun drawAlignmentPattern(x: Int, y: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    setFunctionModule(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        private fun getAlignmentPatternPositions(): IntArray {
            if (version == 1) return intArrayOf()
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun addEccAndInterleave(data: ByteArray): ByteArray {
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel.ordinal][version].toInt()
            val blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal][version].toInt()
            val rawCodewords = getNumRawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks

            val blocks = Array(numBlocks) { ByteArray(0) }
            val rsDiv = reedSolomonComputeDivisor(blockEccLen)
            var k = 0
            for (i in 0 until numBlocks) {
                val dataLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
                val dat = data.copyOfRange(k, k + dataLen)
                k += dat.size
                val block = ByteArray(shortBlockLen + 1)
                dat.copyInto(block, 0, 0, dat.size)
                val ecc = reedSolomonComputeRemainder(dat, rsDiv)
                ecc.copyInto(block, block.size - blockEccLen, 0, ecc.size)
                blocks[i] = block
            }

            val result = ByteArray(rawCodewords)
            var outIdx = 0
            for (i in 0 until blocks[0].size) {
                for (j in 0 until blocks.size) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[outIdx++] = blocks[j][i]
                    }
                }
            }
            return result
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = getBit(data[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(msk: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert = when (msk) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                        else -> false
                    }
                    if (invert && !isFunction[y][x]) {
                        modules[y][x] = !modules[y][x]
                    }
                }
            }
        }

        private fun getPenaltyScore(): Int {
            var result = 0
            // N1: Соседние модули в строке одинакового цвета
            for (y in 0 until size) {
                var runColor = false
                var runX = 0
                val runHistory = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runX++
                        if (runX == 5) result += 3
                        else if (runX > 5) result++
                    } else {
                        finderPenaltyAddHistory(runX, runHistory)
                        if (!runColor) result += finderPenaltyCountPatterns(runHistory) * 40
                        runColor = modules[y][x]
                        runX = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runColor, runX, runHistory) * 40
            }

            // N1: Соседние модули в столбце одинакового цвета
            for (x in 0 until size) {
                var runColor = false
                var runY = 0
                val runHistory = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runY++
                        if (runY == 5) result += 3
                        else if (runY > 5) result++
                    } else {
                        finderPenaltyAddHistory(runY, runHistory)
                        if (!runColor) result += finderPenaltyCountPatterns(runHistory) * 40
                        runColor = modules[y][x]
                        runY = 1
                    }
                }
                result += finderPenaltyTerminateAndCount(runColor, runY, runHistory) * 40
            }

            // N2: Блоки 2x2 одного цвета
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                        result += 3
                    }
                }
            }

            // N4: Баланс светлых и темных модулей
            var dark = 0
            for (row in modules) {
                for (color in row) {
                    if (color) dark++
                }
            }
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10

            return result
        }

        private fun finderPenaltyCountPatterns(runHistory: IntArray): Int {
            val n = runHistory[1]
            val core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n
            return (if (core && runHistory[0] >= n * 4 && runHistory[6] >= n) 1 else 0) +
                   (if (core && runHistory[6] >= n * 4 && runHistory[0] >= n) 1 else 0)
        }

        private fun finderPenaltyTerminateAndCount(currentRunColor: Boolean, runLength: Int, runHistory: IntArray): Int {
            var len = runLength
            if (currentRunColor) {
                finderPenaltyAddHistory(len, runHistory)
                len = 0
            }
            len += size
            finderPenaltyAddHistory(len, runHistory)
            return finderPenaltyCountPatterns(runHistory)
        }

        private fun finderPenaltyAddHistory(currentRunLength: Int, runHistory: IntArray): IntArray {
            var len = currentRunLength
            if (runHistory[0] == 0) len += size
            SystemCopy(runHistory, 0, runHistory, 1, runHistory.size - 1)
            runHistory[0] = len
            return runHistory
        }
    }

    private fun getBit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

    private fun getNumRawDataModules(ver: Int): Int {
        val size = ver * 4 + 17
        var result = size * size
        result -= 8 * 8 * 3 // 3 finders с сепараторами
        result -= 15 * 2 + 1 // Форматная инфо и темный модуль
        result -= (size - 16) * 2 // Timing полосы
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (numAlign - 1) * (numAlign - 1) * 25
            result -= (numAlign - 2) * 2 * 20
            if (ver >= 7) {
                result -= 6 * 3 * 2
            }
        }
        return result
    }

    private fun getNumDataCodewords(ver: Int, ecl: EccLevel): Int {
        return getNumRawDataModules(ver) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver].toInt() *
                NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver].toInt()
    }

    private fun reedSolomonComputeDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = (reedSolomonMultiply(result[j].toInt() and 0xFF, root)).toByte()
                if (j + 1 < result.size) {
                    result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
            }
            root = reedSolomonMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonComputeRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            SystemCopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = (result[i].toInt() xor reedSolomonMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun reedSolomonMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun SystemCopy(src: Any, srcPos: Int, dest: Any, destPos: Int, length: Int) {
        when {
            src is ByteArray && dest is ByteArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
            src is IntArray && dest is IntArray -> src.copyInto(dest, destPos, srcPos, srcPos + length)
        }
    }

    private class BitBuffer {
        private val data = ArrayList<Boolean>()
        val bitLength: Int get() = data.size

        fun getBit(index: Int): Int = if (data[index]) 1 else 0

        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                data.add(((value ushr i) and 1) != 0)
            }
        }

        fun appendData(other: BitBuffer) {
            data.addAll(other.data)
        }
    }

    private class QrSegment(
        val mode: Mode,
        val numChars: Int,
        val data: BitBuffer
    ) {
        enum class Mode(val modeBits: Int, private val cc0: Int, private val cc1: Int, private val cc2: Int) {
            NUMERIC(0x1, 10, 12, 14),
            ALPHANUMERIC(0x2, 9, 11, 13),
            BYTE(0x4, 8, 16, 16);

            fun numCharCountBits(ver: Int): Int {
                return when ((ver + 7) / 17) {
                    0 -> cc0
                    1 -> cc1
                    else -> cc2
                }
            }
        }

        companion object {
            private const val ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

            fun makeBytes(data: ByteArray): QrSegment {
                val bb = BitBuffer()
                for (b in data) {
                    bb.appendBits(b.toInt() and 0xFF, 8)
                }
                return QrSegment(Mode.BYTE, data.size, bb)
            }

            fun makeNumeric(digits: String): QrSegment {
                val bb = BitBuffer()
                var i = 0
                while (i < digits.length) {
                    val n = minOf(digits.length - i, 3)
                    bb.appendBits(digits.substring(i, i + n).toInt(), n * 3 + 1)
                    i += n
                }
                return QrSegment(Mode.NUMERIC, digits.length, bb)
            }

            fun makeAlphanumeric(text: String): QrSegment {
                val bb = BitBuffer()
                var i = 0
                while (i <= text.length - 2) {
                    val temp = ALPHANUMERIC_CHARSET.indexOf(text[i]) * 45 + ALPHANUMERIC_CHARSET.indexOf(text[i + 1])
                    bb.appendBits(temp, 11)
                    i += 2
                }
                if (i < text.length) {
                    bb.appendBits(ALPHANUMERIC_CHARSET.indexOf(text[i]), 6)
                }
                return QrSegment(Mode.ALPHANUMERIC, text.length, bb)
            }

            fun makeSegments(text: String): List<QrSegment> {
                if (text.isEmpty()) return emptyList()
                return when {
                    text.all { it in '0'..'9' } -> listOf(makeNumeric(text))
                    text.all { it in ALPHANUMERIC_CHARSET } -> listOf(makeAlphanumeric(text))
                    else -> listOf(makeBytes(text.encodeToByteArray()))
                }
            }

            fun getTotalBits(segs: List<QrSegment>, version: Int): Int {
                var result = 0L
                for (seg in segs) {
                    val ccbits = seg.mode.numCharCountBits(version)
                    if (seg.numChars >= (1 shl ccbits)) return -1
                    result += 4L + ccbits + seg.data.bitLength
                    if (result > Int.MAX_VALUE) return -1
                }
                return result.toInt()
            }
        }
    }

    // Таблицы стандартов ISO/IEC 18004
    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        byteArrayOf(-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        byteArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        byteArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        byteArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        byteArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        byteArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        byteArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        byteArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )
}
