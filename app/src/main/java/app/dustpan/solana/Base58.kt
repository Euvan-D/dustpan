package app.dustpan.solana

/** Bitcoin-alphabet Base58, the encoding Solana uses for keys, blockhashes and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { indexes ->
        ALPHABET.forEachIndexed { i, c -> indexes[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        val number = input.copyOf()
        val encoded = CharArray(input.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < number.size) {
            encoded[--outputStart] = ALPHABET[divmod(number, inputStart, 256, 58)]
            if (number[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < encoded.size && encoded[outputStart] == ALPHABET[0]) outputStart++
        repeat(zeros) { encoded[--outputStart] = ALPHABET[0] }
        return String(encoded, outputStart, encoded.size - outputStart)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = ByteArray(input.length)
        input.forEachIndexed { i, c ->
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character '$c'" }
            input58[i] = digit.toByte()
        }
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) zeros++

        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256).toByte()
            if (input58[inputStart].toInt() == 0) inputStart++
        }
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) outputStart++

        val result = ByteArray(zeros + decoded.size - outputStart)
        System.arraycopy(decoded, outputStart, result, zeros, decoded.size - outputStart)
        return result
    }

    /** Divides [number] in place (big-endian digits in [base]) and returns the remainder. */
    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}
