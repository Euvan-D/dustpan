package app.dustpan.solana

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks our transaction encoder against bytes produced by @solana/web3.js and @solana/spl-token.
 * Regenerate the fixture with `npm run generate` in tools/fixtures.
 */
class Web3jsParityTest {
    private val fixture = JSONObject(javaClass.getResource("/web3js-fixtures.json")!!.readText())
    private val wallet = PublicKey(fixture.getString("wallet"))
    private val blockhash = fixture.getString("blockhash")

    @Test
    fun seededAddressesMatchWeb3js() {
        val seeded = fixture.getJSONArray("seeded")
        for (i in 0 until seeded.length()) {
            val entry = seeded.getJSONObject(i)
            assertEquals(
                entry.getString("address"),
                PublicKey.createWithSeed(wallet, entry.getString("seed"), Programs.TOKEN).toBase58(),
            )
        }
    }

    @Test
    fun testAccountTransactionMatchesWeb3js() {
        val usdc = PublicKey(fixture.getString("devnetUsdc"))
        assertEquals(listOf(usdc, Programs.NATIVE_MINT, usdc), TestAccounts.DEVNET_MINTS)

        val instructions = TestAccounts.instructions(
            wallet = wallet,
            mints = TestAccounts.DEVNET_MINTS,
            rentLamports = fixture.getLong("rent"),
            seedPrefix = fixture.getString("seedPrefix"),
        )
        assertEquals(fixture.getString("createMessageHex"), Transactions.compileMessage(wallet, blockhash, instructions).toHex())
    }

    @Test
    fun sweepTransactionMatchesWeb3js() {
        val targets = fixture.getJSONArray("closeTargets")
        val instructions = (0 until targets.length()).map { i ->
            val target = targets.getJSONObject(i)
            TokenInstructions.closeAccount(
                programId = PublicKey(target.getString("program")),
                account = PublicKey(target.getString("address")),
                destination = wallet,
                owner = wallet,
            )
        }
        assertEquals(fixture.getString("closeMessageHex"), Transactions.compileMessage(wallet, blockhash, instructions).toHex())

        // A full batch has to leave headroom for wallets that add compute-budget instructions.
        val transaction = Transactions.buildUnsigned(wallet, blockhash, instructions)
        assertEquals(Reclaim.CLOSES_PER_TRANSACTION, instructions.size)
        assertTrue("batch is ${transaction.size} bytes", transaction.size <= 1_050)
    }

    @Test
    fun unsignedTransactionHasOneEmptySignatureSlot() {
        val instruction = TokenInstructions.closeAccount(Programs.TOKEN, Programs.NATIVE_MINT, wallet, wallet)
        val message = Transactions.compileMessage(wallet, blockhash, listOf(instruction))
        val transaction = Transactions.withEmptySignatures(message)
        assertEquals(1, transaction[0].toInt())
        assertTrue(transaction.copyOfRange(1, 65).all { it.toInt() == 0 })
        assertEquals(message.toHex(), transaction.copyOfRange(65, transaction.size).toHex())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
