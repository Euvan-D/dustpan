package app.dustpan.solana

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature

/**
 * Full create, scan and sweep cycle against a real validator, using the app's own RPC client,
 * parser and transaction builder. A local Ed25519 key stands in for the wallet.
 *
 * Skipped unless DUSTPAN_E2E_RPC is set, for example:
 *   solana-test-validator --reset &
 *   DUSTPAN_E2E_RPC=http://127.0.0.1:8899 ./gradlew :app:testDebugUnitTest
 */
class ValidatorEndToEndTest {
    private val rpcUrl: String? = System.getenv("DUSTPAN_E2E_RPC")

    @Test
    fun createScanAndSweep() = runBlocking {
        assumeTrue("Set DUSTPAN_E2E_RPC to run this test", !rpcUrl.isNullOrBlank())
        val rpc = SolanaRpc(rpcUrl!!)
        val signer = TestSigner.generate()
        val wallet = signer.publicKey

        rpc.confirm(rpc.requestAirdrop(wallet, 2 * Sol.LAMPORTS_PER_SOL))
        val tokenRent = rpc.getMinimumBalanceForRentExemption(Programs.TOKEN_ACCOUNT_SIZE)

        // A Token-2022 mint we control, created from a seed so only the wallet signs.
        val mint = PublicKey.createWithSeed(wallet, "e2e-mint", Programs.TOKEN_2022)
        rpc.send(
            signer,
            SystemInstructions.createAccountWithSeed(
                wallet, mint, wallet, "e2e-mint", rpc.getMinimumBalanceForRentExemption(MINT_SIZE), MINT_SIZE.toLong(), Programs.TOKEN_2022,
            ),
            initializeMint2(Programs.TOKEN_2022, mint, wallet),
        )

        // 22 empty classic accounts (wrapped SOL), made the same way the app's devnet helper does.
        // 22 forces the sweep into two transactions.
        TestAccounts.instructions(wallet, List(22) { Programs.NATIVE_MINT }, tokenRent, "e2e-")
            .chunked(10)
            .forEach { rpc.send(signer, *it.toTypedArray()) }

        // Two Token-2022 accounts: one stays empty, one gets 5 tokens and must be left alone.
        val (empty22, holding22) = listOf("e2e-t22-a", "e2e-t22-b").map { seed ->
            val account = PublicKey.createWithSeed(wallet, seed, Programs.TOKEN_2022)
            rpc.send(
                signer,
                SystemInstructions.createAccountWithSeed(
                    wallet, account, wallet, seed, tokenRent, Programs.TOKEN_ACCOUNT_SIZE.toLong(), Programs.TOKEN_2022,
                ),
                TokenInstructions.initializeAccount3(Programs.TOKEN_2022, account, mint, wallet),
            )
            account
        }
        rpc.send(signer, mintTo(Programs.TOKEN_2022, mint, holding22, wallet, amount = 5))

        val scan = rpc.scan(wallet)
        assertEquals(23, scan.closeable.size)
        assertTrue(scan.closeable.any { it.address == empty22 && it.programId == Programs.TOKEN_2022 })
        assertEquals(listOf(holding22), scan.holding.map { it.address })
        assertEquals(0, scan.blocked.size)

        // Sweep exactly like the app does, then sign where the wallet would.
        val before = rpc.getBalance(wallet)
        val batches = Reclaim.batches(scan.closeable)
        assertEquals(listOf(20, 3), batches.map { it.size })
        val blockhash = rpc.getLatestBlockhash()
        val signatures = batches.map { batch ->
            val unsigned = Transactions.buildUnsigned(wallet, blockhash, Reclaim.closeInstructions(wallet, batch))
            rpc.sendTransaction(signer.sign(unsigned))
        }
        val statuses = rpc.awaitSignatures(signatures, timeoutMs = 30_000)
        assertEquals(List(signatures.size) { SignatureStatus.Confirmed }, statuses)

        val after = rpc.getBalance(wallet)
        val fees = BASE_FEE_LAMPORTS * batches.size
        assertEquals(before + scan.reclaimableLamports - fees, after)

        val rescan = rpc.scan(wallet)
        assertEquals(0, rescan.closeable.size)
        assertEquals(listOf(holding22), rescan.holding.map { it.address })
        println("E2E: swept ${scan.closeable.size} accounts in ${batches.size} transactions, got back ${Sol.format(after - before)} SOL net of fees")
    }

    private fun SolanaRpc.scan(wallet: PublicKey) = ScanResult.of(
        wallet,
        TokenAccountParser.parse(getTokenAccountsByOwner(wallet, Programs.TOKEN), Programs.TOKEN) +
            TokenAccountParser.parse(getTokenAccountsByOwner(wallet, Programs.TOKEN_2022), Programs.TOKEN_2022),
    )

    private suspend fun SolanaRpc.send(signer: TestSigner, vararg instructions: Instruction) {
        val unsigned = Transactions.buildUnsigned(signer.publicKey, getLatestBlockhash(), instructions.toList())
        confirm(sendTransaction(signer.sign(unsigned)))
    }

    private suspend fun SolanaRpc.confirm(signature: String) {
        assertEquals(SignatureStatus.Confirmed, awaitSignatures(listOf(signature), timeoutMs = 30_000).single())
    }

    private fun initializeMint2(programId: PublicKey, mint: PublicKey, authority: PublicKey) = Instruction(
        programId,
        listOf(AccountMeta(mint, isSigner = false, isWritable = true)),
        byteArrayOf(20, 0) + authority.bytes + byteArrayOf(0), // decimals 0, no freeze authority
    )

    private fun mintTo(programId: PublicKey, mint: PublicKey, destination: PublicKey, authority: PublicKey, amount: Long) =
        Instruction(
            programId,
            listOf(
                AccountMeta(mint, isSigner = false, isWritable = true),
                AccountMeta(destination, isSigner = false, isWritable = true),
                AccountMeta(authority, isSigner = true, isWritable = false),
            ),
            ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(7).putLong(amount).array(),
        )

    /** Signs the single signature slot of an unsigned transaction, like a wallet does. */
    private class TestSigner(private val key: PrivateKey, val publicKey: PublicKey) {
        fun sign(unsigned: ByteArray): ByteArray {
            require(unsigned[0].toInt() == 1) { "expected one signature slot" }
            val message = unsigned.copyOfRange(65, unsigned.size)
            val signature = Signature.getInstance("Ed25519").run {
                initSign(key)
                update(message)
                sign()
            }
            return unsigned.copyOf().also { System.arraycopy(signature, 0, it, 1, 64) }
        }

        companion object {
            fun generate(): TestSigner {
                val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                // X.509 SubjectPublicKeyInfo for Ed25519 ends with the raw 32-byte key.
                return TestSigner(pair.private, PublicKey(pair.public.encoded.takeLast(32).toByteArray()))
            }
        }
    }

    private companion object {
        const val MINT_SIZE = 82
        const val BASE_FEE_LAMPORTS = 5_000L
    }
}
