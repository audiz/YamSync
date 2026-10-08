package io.github.audiz.sync

/**
 * 🏁 Чистый мультиплатформенный генератор матриц QR-кодов без внешних зависимостей.
 * Поддерживает Byte mode (ISO/IEC 18004), коррекцию ошибок Reed-Solomon (уровни L, M, Q, H).
 */
object QrCodeGenerator {

    enum class EccLevel(val ordinalValue: Int, val formatBits: Int) {
        L(0, 1), // ~7% восстановление
        M(1, 0), // ~15% восстановление
        Q(2, 3), // ~25% восстановление
        H(3, 2)  // ~30% восстановление
    }

    /**
     * Сгенерировать булеву матрицу QR-кода (true = черный модуль, false = белый).
     * @param text Текст/URL для кодирования
     * @param ecc Уровень коррекции ошибок (по умолчанию Medium)
     */
    fun encode(text: String, ecc: EccLevel = EccLevel.M): Array<BooleanArray> {
        val dataBytes = text.encodeToByteArray()
        val version = chooseMinVersion(dataBytes.size, ecc)
        val size = version * 4 + 17

        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        // 1. Отрисовка шаблонов позиционирования (Finder Patterns)
        drawFinderPattern(modules, isFunction, 0, 0)
        drawFinderPattern(modules, isFunction, size - 7, 0)
        drawFinderPattern(modules, isFunction, 0, size - 7)

        // 2. Шаблоны выравнивания (Alignment Patterns)
        val alignPositions = getAlignmentPatternPositions(version)
        for (i in alignPositions.indices) {
            for (j in alignPositions.indices) {
                val r = alignPositions[i]
                val c = alignPositions[j]
                if (!isFunction[r][c]) {
                    drawAlignmentPattern(modules, isFunction, r, c)
                }
            }
        }

        // 3. Синхронизирующие полосы (Timing Patterns)
        for (i in 8 until size - 8) {
            val bit = (i % 2 == 0)
            modules[6][i] = bit
            isFunction[6][i] = true
            modules[i][6] = bit
            isFunction[i][6] = true
        }

        // 4. Тёмный модуль
        modules[size - 8][8] = true
        isFunction[size - 8][8] = true

        // Резервирование областей форматной информации
        reserveFormatAreas(isFunction, size)

        // 5. Кодирование данных и коррекция Reed-Solomon
        val codewords = encodeData(dataBytes, version, ecc)

        // 6. Размещение кодовых слов в матрице
        placeCodewords(modules, isFunction, codewords)

        // 7. Подбор наилучшей маски и ее наложение
        val mask = chooseBestMask(modules, isFunction)
        applyMask(modules, isFunction, mask)

        // 8. Запись информации о формате (ECC + Mask)
        writeFormatInfo(modules, ecc, mask)

        return modules
    }

    private fun drawFinderPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, row: Int, col: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val nr = row + r
                val nc = col + c
                if (nr in modules.indices && nc in modules.indices) {
                    val inCenter = (r in 0..6 && c in 0..6)
                    val isBlack = inCenter && (r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4))
                    modules[nr][nc] = isBlack
                    isFunction[nr][nc] = true
                }
            }
        }
    }

    private fun drawAlignmentPattern(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, centerR: Int, centerC: Int) {
        for (r in -2..2) {
            for (c in -2..2) {
                val isBlack = (kotlin.math.max(kotlin.math.abs(r), kotlin.math.abs(c)) != 1)
                modules[centerR + r][centerC + c] = isBlack
                isFunction[centerR + r][centerC + c] = true
            }
        }
    }

    private fun reserveFormatAreas(isFunction: Array<BooleanArray>, size: Int) {
        for (i in 0..8) {
            isFunction[8][i] = true
            isFunction[i][8] = true
        }
        for (i in size - 8 until size) {
            isFunction[8][i] = true
            isFunction[i][8] = true
        }
    }

    private fun chooseMinVersion(dataLen: Int, ecc: EccLevel): Int {
        for (v in 1..40) {
            val capacity = getDataCapacityBytes(v, ecc)
            val headerBits = 4 + (if (v <= 9) 8 else 16)
            val totalNeeded = dataLen + (headerBits + 7) / 8
            if (capacity >= totalNeeded) return v
        }
        return 40
    }

    private fun getDataCapacityBytes(version: Int, ecc: EccLevel): Int {
        val totalCodewords = getTotalCodewords(version)
        val eccCodewords = getEccCodewordsPerBlock(version, ecc) * getNumEccBlocks(version, ecc)
        return totalCodewords - eccCodewords
    }

    private fun encodeData(data: ByteArray, version: Int, ecc: EccLevel): ByteArray {
        val totalCapacity = getDataCapacityBytes(version, ecc)
        val bits = BitBuffer()

        // Byte mode indicator: 0100
        bits.appendBits(0b0100, 4)
        // Count indicator
        val countBits = if (version <= 9) 8 else 16
        bits.appendBits(data.size, countBits)
        // Data bytes
        for (b in data) {
            bits.appendBits(b.toInt() and 0xFF, 8)
        }
        // Terminator (до 4 нулей)
        val terminatorLen = kotlin.math.min(4, totalCapacity * 8 - bits.length)
        bits.appendBits(0, terminatorLen)
        // Выравнивание до байта
        while (bits.length % 8 != 0) {
            bits.appendBit(false)
        }
        // Заполнение паддингами (0xEC, 0x11)
        val padBytes = byteArrayOf(0xEC.toByte(), 0x11.toByte())
        var padIndex = 0
        while (bits.length / 8 < totalCapacity) {
            bits.appendBits(padBytes[padIndex].toInt() and 0xFF, 8)
            padIndex = 1 - padIndex
        }

        val rawData = bits.toByteArray()
        return interleaveBlocks(rawData, version, ecc)
    }

    private fun interleaveBlocks(data: ByteArray, version: Int, ecc: EccLevel): ByteArray {
        val numBlocks = getNumEccBlocks(version, ecc)
        val eccPerBlock = getEccCodewordsPerBlock(version, ecc)
        val totalData = data.size
        val shortBlockLen = totalData / numBlocks
        val numShortBlocks = numBlocks - (totalData % numBlocks)

        val dataBlocks = Array(numBlocks) { i ->
            val len = if (i < numShortBlocks) shortBlockLen else shortBlockLen + 1
            ByteArray(len)
        }

        var offset = 0
        for (i in 0 until numBlocks) {
            val len = dataBlocks[i].size
            data.copyInto(dataBlocks[i], 0, offset, offset + len)
            offset += len
        }

        val eccBlocks = Array(numBlocks) { i ->
            computeReedSolomon(dataBlocks[i], eccPerBlock)
        }

        val totalCodewords = getTotalCodewords(version)
        val result = ByteArray(totalCodewords)
        var outIdx = 0

        val maxDataLen = dataBlocks.maxOf { it.size }
        for (i in 0 until maxDataLen) {
            for (b in 0 until numBlocks) {
                if (i < dataBlocks[b].size) {
                    result[outIdx++] = dataBlocks[b][i]
                }
            }
        }

        for (i in 0 until eccPerBlock) {
            for (b in 0 until numBlocks) {
                result[outIdx++] = eccBlocks[b][i]
            }
        }

        return result
    }

    private fun computeReedSolomon(data: ByteArray, eccCount: Int): ByteArray {
        val genPoly = getGeneratorPoly(eccCount)
        val res = ByteArray(eccCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor (res[0].toInt() and 0xFF)
            SystemCopy(res, 1, res, 0, eccCount - 1)
            res[eccCount - 1] = 0
            for (i in 0 until eccCount) {
                res[i] = (res[i].toInt() xor gfMul(genPoly[i], factor)).toByte()
            }
        }
        return res
    }

    private fun SystemCopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }

    private fun getGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val root = gfPow2(i)
            for (j in poly.indices) {
                next[j] = next[j] xor gfMul(poly[j], root)
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly.copyOfRange(1, poly.size)
    }

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfExp[i + 255] = x
            gfLog[x] = i
            x = (x shl 1)
            if ((x and 0x100) != 0) x = x xor 0x11D
        }
    }

    private fun gfPow2(power: Int): Int = gfExp[power % 255]
    private fun gfMul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return gfExp[gfLog[x] + gfLog[y]]
    }

    private fun placeCodewords(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, codewords: ByteArray) {
        val size = modules.size
        var bitIndex = 0
        var upwards = true
        var right = size - 1

        while (right > 0) {
            if (right == 6) right-- // Пропускаем вертикальную полосу синхронизации
            val cols = intArrayOf(right, right - 1)
            val rowRange = if (upwards) (size - 1 downTo 0) else (0 until size)

            for (row in rowRange) {
                for (col in cols) {
                    if (!isFunction[row][col]) {
                        val bit = if (bitIndex < codewords.size * 8) {
                            val byteVal = codewords[bitIndex / 8].toInt() and 0xFF
                            val bitVal = (byteVal ushr (7 - (bitIndex % 8))) and 1
                            bitVal == 1
                        } else false
                        modules[row][col] = bit
                        bitIndex++
                    }
                }
            }
            upwards = !upwards
            right -= 2
        }
    }

    private fun chooseBestMask(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>): Int {
        var bestMask = 0
        var minPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, isFunction, mask)
            val penalty = evaluatePenalty(modules)
            applyMask(modules, isFunction, mask) // откат
            if (penalty < minPenalty) {
                minPenalty = penalty
                bestMask = mask
            }
        }
        return bestMask
    }

    private fun applyMask(modules: Array<BooleanArray>, isFunction: Array<BooleanArray>, mask: Int) {
        val size = modules.size
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (!isFunction[r][c]) {
                    val invert = when (mask) {
                        0 -> (r + c) % 2 == 0
                        1 -> r % 2 == 0
                        2 -> c % 3 == 0
                        3 -> (r + c) % 3 == 0
                        4 -> (r / 2 + c / 3) % 2 == 0
                        5 -> ((r * c) % 2) + ((r * c) % 3) == 0
                        6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
                        7 -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
                        else -> false
                    }
                    if (invert) modules[r][c] = !modules[r][c]
                }
            }
        }
    }

    private fun evaluatePenalty(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var penalty = 0

        // Правило 1: 5+ одноцветных модулей подряд
        for (r in 0 until size) {
            var count = 0
            var last = false
            for (c in 0 until size) {
                val b = modules[r][c]
                if (c == 0 || b != last) {
                    last = b
                    count = 1
                } else {
                    count++
                    if (count == 5) penalty += 3
                    else if (count > 5) penalty += 1
                }
            }
        }

        // Правило 2: блоки 2x2 одного цвета
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val b = modules[r][c]
                if (b == modules[r + 1][c] && b == modules[r][c + 1] && b == modules[r + 1][c + 1]) {
                    penalty += 3
                }
            }
        }

        return penalty
    }

    private fun writeFormatInfo(modules: Array<BooleanArray>, ecc: EccLevel, mask: Int) {
        val size = modules.size
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        // Вокруг левого верхнего шаблона
        for (i in 0..5) modules[8][i] = (bits ushr i) and 1 != 0
        modules[8][7] = (bits ushr 6) and 1 != 0
        modules[8][8] = (bits ushr 7) and 1 != 0
        modules[7][8] = (bits ushr 8) and 1 != 0
        for (i in 9..14) modules[14 - i][8] = (bits ushr i) and 1 != 0

        // Вокруг правого верхнего и левого нижнего шаблонов
        for (i in 0..7) modules[size - 1 - i][8] = (bits ushr i) and 1 != 0
        for (i in 8..14) modules[8][size - 15 + i] = (bits ushr i) and 1 != 0
    }

    private fun getTotalCodewords(version: Int): Int {
        val s = version * 4 + 17
        var totalBits = s * s
        // Вычитаем функциональные шаблоны
        totalBits -= 3 * 64 // Finder
        totalBits -= 2 * (s - 16) // Timing
        val alignCount = getAlignmentPatternPositions(version).size
        if (alignCount > 0) {
            val alignPatterns = alignCount * alignCount - 3 // Без углов
            totalBits -= alignPatterns * 25
        }
        totalBits -= 31 // Format info & dark module
        return totalBits / 8
    }

    private fun getEccCodewordsPerBlock(version: Int, ecc: EccLevel): Int = when (ecc) {
        EccLevel.L -> when {
            version <= 1 -> 7; version <= 2 -> 10; version <= 4 -> 15; version <= 6 -> 18; else -> 20
        }
        EccLevel.M -> when {
            version <= 1 -> 10; version <= 2 -> 16; version <= 3 -> 26; version <= 4 -> 18; version <= 5 -> 24; version <= 6 -> 16; else -> 22
        }
        EccLevel.Q -> when {
            version <= 1 -> 13; version <= 2 -> 22; version <= 3 -> 18; version <= 4 -> 26; version <= 5 -> 18; else -> 24
        }
        EccLevel.H -> when {
            version <= 1 -> 17; version <= 2 -> 28; version <= 3 -> 22; version <= 4 -> 16; version <= 5 -> 22; else -> 28
        }
    }

    private fun getNumEccBlocks(version: Int, ecc: EccLevel): Int = when (ecc) {
        EccLevel.L -> when {
            version <= 4 -> 1; version <= 7 -> 2; else -> 4
        }
        EccLevel.M -> when {
            version <= 2 -> 1; version <= 4 -> 2; version <= 6 -> 4; else -> 6
        }
        EccLevel.Q -> when {
            version <= 1 -> 1; version <= 3 -> 2; version <= 5 -> 4; else -> 6
        }
        EccLevel.H -> when {
            version <= 1 -> 1; version <= 2 -> 2; version <= 4 -> 4; else -> 6
        }
    }

    private fun getAlignmentPatternPositions(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val num = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
        val result = IntArray(num)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in num - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size

        fun appendBit(bit: Boolean) {
            bits.add(bit)
        }

        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits.add(((value ushr i) and 1) != 0)
            }
        }

        fun toByteArray(): ByteArray {
            val result = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i]) {
                    result[i / 8] = (result[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
                }
            }
            return result
        }
    }
}
