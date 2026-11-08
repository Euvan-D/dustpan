package app.dustpan.solana

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Programs {
    val SYSTEM = PublicKey("11111111111111111111111111111111")
    val TOKEN = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022 = PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val NATIVE_MINT = PublicKey("So11111111111111111111111111111111111111112")

    /** Size of a plain SPL token account. */
    const val TOKEN_ACCOUNT_SIZE = 165
}

object TokenInstructions {
    private const val INITIALIZE_ACCOUNT_3: Byte = 18
    private const val CLOSE_ACCOUNT: Byte = 9

    /** Closes [account] and sends all of its lamports to [destination]. Works for Token and Token-2022. */
    fun closeAccount(programId: PublicKey, account: PublicKey, destination: PublicKey, owner: PublicKey) =
        Instruction(
            programId = programId,
            accounts = listOf(
                AccountMeta(account, isSigner = false, isWritable = true),
                AccountMeta(destination, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = false),
            ),
            data = byteArrayOf(CLOSE_ACCOUNT),
        )

    fun initializeAccount3(programId: PublicKey, account: PublicKey, mint: PublicKey, owner: PublicKey) =
        Instruction(
            programId = programId,
            accounts = listOf(
                AccountMeta(account, isSigner = false, isWritable = true),
                AccountMeta(mint, isSigner = false, isWritable = false),
            ),
            data = byteArrayOf(INITIALIZE_ACCOUNT_3) + owner.bytes,
        )
}

object SystemInstructions {
    private const val CREATE_ACCOUNT_WITH_SEED = 3

    /**
     * Creates an account at `createWithSeed(base, seed, owner)`. Only [base] has to sign, so the
     * app can make new accounts without generating extra keypairs.
     */
    fun createAccountWithSeed(
        from: PublicKey,
        newAccount: PublicKey,
        base: PublicKey,
        seed: String,
        lamports: Long,
        space: Long,
        owner: PublicKey,
    ): Instruction {
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val data = ByteBuffer.allocate(4 + 32 + 8 + seedBytes.size + 8 + 8 + 32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CREATE_ACCOUNT_WITH_SEED)
            .put(base.bytes)
            .putLong(seedBytes.size.toLong())
            .put(seedBytes)
            .putLong(lamports)
            .putLong(space)
            .put(owner.bytes)
            .array()
        val accounts = mutableListOf(
            AccountMeta(from, isSigner = true, isWritable = true),
            AccountMeta(newAccount, isSigner = false, isWritable = true),
        )
        if (base != from) accounts += AccountMeta(base, isSigner = true, isWritable = false)
        return Instruction(Programs.SYSTEM, accounts, data)
    }
}
