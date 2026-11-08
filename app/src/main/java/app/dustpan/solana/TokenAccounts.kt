package app.dustpan.solana

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

data class TokenAccount(
    val address: PublicKey,
    val programId: PublicKey,
    val mint: PublicKey,
    val owner: PublicKey,
    val lamports: Long,
    val rawAmount: String,
    val isNative: Boolean,
    val state: String,
    val closeAuthority: PublicKey?,
    val hasWithheldFees: Boolean,
    val usesConfidentialTransfers: Boolean,
) {
    /** True only when the token balance is exactly zero. Anything unparseable counts as "has tokens". */
    val isEmpty: Boolean get() = rawAmount.toBigIntegerOrNull()?.signum() == 0

    val programLabel: String get() = if (programId == Programs.TOKEN_2022) "Token-2022" else "Token"

    val tokenLabel: String get() = KnownMints.label(mint) ?: "Token ${mint.short()}"

    /** Why [wallet] can't close this (empty) account, or null when it can. */
    fun closeBlocker(wallet: PublicKey): String? = when {
        state != "initialized" -> "Frozen by the token issuer"
        closeAuthority != null && closeAuthority != wallet -> "Another address controls closing it"
        hasWithheldFees -> "Still holds withheld transfer fees"
        usesConfidentialTransfers -> "Uses confidential transfers"
        else -> null
    }
}

object TokenAccountParser {
    fun parse(value: JSONArray, programId: PublicKey): List<TokenAccount> =
        (0 until value.length()).mapNotNull { i ->
            runCatching { parseOne(value.getJSONObject(i), programId) }.getOrNull()
        }

    private fun parseOne(entry: JSONObject, programId: PublicKey): TokenAccount? {
        val account = entry.getJSONObject("account")
        // Without jsonParsed data we can't check the balance, so we skip the account.
        val parsed = account.optJSONObject("data")?.optJSONObject("parsed") ?: return null
        if (parsed.optString("type") != "account") return null
        val info = parsed.getJSONObject("info")

        var withheldFees = false
        var confidential = false
        info.optJSONArray("extensions")?.let { extensions ->
            for (i in 0 until extensions.length()) {
                val extension = extensions.optJSONObject(i) ?: continue
                when (extension.optString("extension")) {
                    "transferFeeAmount" -> {
                        val withheld = extension.optJSONObject("state")?.opt("withheldAmount")?.toString()
                        withheldFees = withheld?.toBigIntegerOrNull()?.signum() != 0
                    }
                    "confidentialTransferAccount" -> confidential = true
                }
            }
        }

        return TokenAccount(
            address = PublicKey(entry.getString("pubkey")),
            programId = programId,
            mint = PublicKey(info.getString("mint")),
            owner = PublicKey(info.getString("owner")),
            lamports = account.getLong("lamports"),
            rawAmount = info.getJSONObject("tokenAmount").getString("amount"),
            isNative = info.optBoolean("isNative", false),
            state = info.optString("state", "initialized"),
            closeAuthority = PublicKey.parseOrNull(info.optString("closeAuthority").ifEmpty { null }),
            hasWithheldFees = withheldFees,
            usesConfidentialTransfers = confidential,
        )
    }
}

/** What a scan found, split into what Dustpan will and won't touch. */
data class ScanResult(
    val closeable: List<TokenAccount>,
    val holding: List<TokenAccount>,
    val blocked: List<Pair<TokenAccount, String>>,
) {
    val reclaimableLamports: Long get() = closeable.sumOf { it.lamports }
    val totalAccounts: Int get() = closeable.size + holding.size + blocked.size

    companion object {
        fun of(wallet: PublicKey, accounts: List<TokenAccount>): ScanResult {
            val mine = accounts.filter { it.owner == wallet }
            val (empty, holding) = mine.partition { it.isEmpty }
            val (closeable, blocked) = empty.partition { it.closeBlocker(wallet) == null }
            return ScanResult(
                closeable = closeable.sortedByDescending { it.lamports },
                holding = holding,
                blocked = blocked.map { it to it.closeBlocker(wallet)!! },
            )
        }
    }
}

object Reclaim {
    /** 20 closes come to about 980 bytes, which leaves room under the 1232-byte cap for wallet-added fee instructions. */
    const val CLOSES_PER_TRANSACTION = 20

    fun batches(accounts: List<TokenAccount>, size: Int = CLOSES_PER_TRANSACTION): List<List<TokenAccount>> =
        accounts.chunked(size)

    fun closeInstructions(wallet: PublicKey, batch: List<TokenAccount>): List<Instruction> =
        batch.map { TokenInstructions.closeAccount(it.programId, it.address, destination = wallet, owner = wallet) }
}

/** Builds empty token accounts on devnet so there's something to sweep while testing. */
object TestAccounts {
    private val DEVNET_USDC = PublicKey("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")

    /** Both mints exist on devnet: Circle's devnet USDC and wrapped SOL. */
    val DEVNET_MINTS = listOf(DEVNET_USDC, Programs.NATIVE_MINT, DEVNET_USDC)

    fun newSeedPrefix(nowMillis: Long = System.currentTimeMillis()): String =
        "dustpan-${(nowMillis / 1000).toString(36)}-"

    fun instructions(
        wallet: PublicKey,
        mints: List<PublicKey>,
        rentLamports: Long,
        seedPrefix: String,
        tokenProgram: PublicKey = Programs.TOKEN,
    ): List<Instruction> = mints.flatMapIndexed { i, mint ->
        val seed = "$seedPrefix$i"
        val account = PublicKey.createWithSeed(wallet, seed, tokenProgram)
        listOf(
            SystemInstructions.createAccountWithSeed(
                from = wallet,
                newAccount = account,
                base = wallet,
                seed = seed,
                lamports = rentLamports,
                space = Programs.TOKEN_ACCOUNT_SIZE.toLong(),
                owner = tokenProgram,
            ),
            TokenInstructions.initializeAccount3(tokenProgram, account, mint, wallet),
        )
    }
}

object KnownMints {
    private val names = mapOf(
        "So11111111111111111111111111111111111111112" to "Wrapped SOL",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo" to "PYUSD",
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "BONK",
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "JUP",
        "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to "WIF",
        "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3" to "PYTH",
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" to "mSOL",
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn" to "JitoSOL",
        "jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v" to "JupSOL",
        "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to "USDC (devnet)",
    )

    fun label(mint: PublicKey): String? = names[mint.toBase58()]
}

object Sol {
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    fun format(lamports: Long, maxDecimals: Int = 6): String {
        val sol = BigDecimal.valueOf(lamports).movePointLeft(9).setScale(maxDecimals, RoundingMode.DOWN)
        return if (sol.signum() == 0) "0" else sol.stripTrailingZeros().toPlainString()
    }
}
