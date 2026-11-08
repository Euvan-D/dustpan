package app.dustpan.solana

import java.io.ByteArrayOutputStream

class AccountMeta(val pubkey: PublicKey, val isSigner: Boolean, val isWritable: Boolean)

class Instruction(val programId: PublicKey, val accounts: List<AccountMeta>, val data: ByteArray)

/** Solana's compact-u16 length prefix. */
object ShortVec {
    fun encode(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "compact-u16 out of range: $value" }
        val out = ByteArrayOutputStream(3)
        var rest = value
        while (true) {
            val element = rest and 0x7F
            rest = rest ushr 7
            if (rest == 0) {
                out.write(element)
                break
            }
            out.write(element or 0x80)
        }
        return out.toByteArray()
    }
}

/**
 * A small legacy-transaction encoder. Account ordering follows `Message.compile` in
 * @solana/web3.js, and the unit tests check the bytes against it.
 */
object Transactions {
    const val MAX_SIZE = 1232
    private const val SIGNATURE_SIZE = 64

    fun compileMessage(feePayer: PublicKey, recentBlockhash: String, instructions: List<Instruction>): ByteArray {
        class KeyFlags(var isSigner: Boolean = false, var isWritable: Boolean = false)

        val keys = LinkedHashMap<PublicKey, KeyFlags>()
        fun flagsFor(key: PublicKey) = keys.getOrPut(key) { KeyFlags() }

        flagsFor(feePayer).apply { isSigner = true; isWritable = true }
        for (instruction in instructions) {
            flagsFor(instruction.programId)
            for (meta in instruction.accounts) {
                flagsFor(meta.pubkey).apply {
                    isSigner = isSigner || meta.isSigner
                    isWritable = isWritable || meta.isWritable
                }
            }
        }

        val entries = keys.entries.toList()
        val writableSigners = entries.filter { it.value.isSigner && it.value.isWritable }
        val readonlySigners = entries.filter { it.value.isSigner && !it.value.isWritable }
        val writableNonSigners = entries.filter { !it.value.isSigner && it.value.isWritable }
        val readonlyNonSigners = entries.filter { !it.value.isSigner && !it.value.isWritable }
        val ordered = (writableSigners + readonlySigners + writableNonSigners + readonlyNonSigners).map { it.key }
        require(ordered.size <= 256) { "Too many accounts in one transaction" }
        val indexOf = ordered.withIndex().associate { (index, key) -> key to index }

        val blockhash = Base58.decode(recentBlockhash)
        require(blockhash.size == 32) { "Invalid blockhash" }

        return ByteArrayOutputStream().apply {
            write(writableSigners.size + readonlySigners.size)
            write(readonlySigners.size)
            write(readonlyNonSigners.size)
            write(ShortVec.encode(ordered.size))
            ordered.forEach { write(it.bytes) }
            write(blockhash)
            write(ShortVec.encode(instructions.size))
            for (instruction in instructions) {
                write(indexOf.getValue(instruction.programId))
                write(ShortVec.encode(instruction.accounts.size))
                instruction.accounts.forEach { write(indexOf.getValue(it.pubkey)) }
                write(ShortVec.encode(instruction.data.size))
                write(instruction.data)
            }
        }.toByteArray()
    }

    /** Wire format with zeroed signature slots. This is what Mobile Wallet Adapter wants. */
    fun withEmptySignatures(message: ByteArray): ByteArray {
        val signatureCount = message[0].toInt() and 0xFF
        return ByteArrayOutputStream().apply {
            write(ShortVec.encode(signatureCount))
            write(ByteArray(SIGNATURE_SIZE * signatureCount))
            write(message)
        }.toByteArray()
    }

    fun buildUnsigned(feePayer: PublicKey, recentBlockhash: String, instructions: List<Instruction>): ByteArray {
        val transaction = withEmptySignatures(compileMessage(feePayer, recentBlockhash, instructions))
        check(transaction.size <= MAX_SIZE) { "Transaction is ${transaction.size} bytes, over the $MAX_SIZE byte limit" }
        return transaction
    }
}
