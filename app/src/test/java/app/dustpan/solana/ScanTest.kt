package app.dustpan.solana

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the scanner on a `getTokenAccountsByOwner` (jsonParsed) response that covers the edge cases. */
class ScanTest {
    private val fixture = JSONObject(javaClass.getResource("/token-accounts.json")!!.readText())
    private val wallet = PublicKey(fixture.getString("wallet"))
    private val accounts =
        TokenAccountParser.parse(fixture.getJSONArray("token"), Programs.TOKEN) +
            TokenAccountParser.parse(fixture.getJSONArray("token2022"), Programs.TOKEN_2022)
    private val scan = ScanResult.of(wallet, accounts)

    @Test
    fun skipsAccountsWithoutParsedData() {
        // 8 classic entries minus the one returned as raw base64, plus 3 Token-2022 entries.
        assertEquals(10, accounts.size)
    }

    @Test
    fun onlyEmptyClosableAccountsAreOffered() {
        val closeable = scan.closeable.map { it.address.toBase58() }.toSet()
        assertEquals(
            setOf(
                "3r9stkGvUo8WiicugM7H3j35d7yHC5qfAwUkV6wLY8FE", // empty USDC
                "Gz7VkD4MacbEB6yC5XD3HcumEiYx2EtDYYrfikGsvJG", // empty wrapped SOL
                "SysvarRent111111111111111111111111111111111", // close authority is the wallet itself
                "Stake11111111111111111111111111111111111111", // Token-2022 with zero withheld fees
            ),
            closeable,
        )
        assertTrue(scan.closeable.all { it.isEmpty && it.owner == wallet })
        assertEquals(Programs.TOKEN_2022, scan.closeable.single { it.address.toBase58().startsWith("Stake") }.programId)
    }

    @Test
    fun accountsWithTokensAreNeverTouched() {
        assertEquals(listOf("6CqoELLUrrGTfPCAhADrVX6GQg4nV723NA5GuTVuLgbW"), scan.holding.map { it.address.toBase58() })
    }

    @Test
    fun blockedAccountsCarryAReason() {
        val reasons = scan.blocked.associate { (account, reason) -> account.address.toBase58() to reason }
        assertEquals(
            mapOf(
                "5ZWj7a1f8tWkjBESHKgrLmXshuXxqeY9SYcfbshpAqPG" to "Frozen by the token issuer",
                "BPFLoaderUpgradeab1e11111111111111111111111" to "Another address controls closing it",
                "Config1111111111111111111111111111111111111" to "Still holds withheld transfer fees",
                "AddressLookupTab1e1111111111111111111111111" to "Uses confidential transfers",
            ),
            reasons,
        )
    }

    @Test
    fun totalsAndOrdering() {
        assertEquals(2_100_000L + 2_074_080L + 2_039_280L * 2, scan.reclaimableLamports)
        assertEquals(scan.closeable.sortedByDescending { it.lamports }, scan.closeable)
        assertEquals(9, scan.totalAccounts) // the account owned by another wallet isn't counted
    }

    @Test
    fun sweepInstructionsSendRentBackToTheWallet() {
        val instructions = Reclaim.closeInstructions(wallet, scan.closeable)
        assertEquals(scan.closeable.size, instructions.size)
        instructions.zip(scan.closeable).forEach { (instruction, account) ->
            assertEquals(account.programId, instruction.programId)
            assertEquals(listOf(account.address, wallet, wallet), instruction.accounts.map { it.pubkey })
            assertEquals(listOf(true, true, false), instruction.accounts.map { it.isWritable })
            assertEquals(listOf(false, false, true), instruction.accounts.map { it.isSigner })
            assertEquals(listOf<Byte>(9), instruction.data.toList())
        }
    }

    @Test
    fun batchesNeverExceedTheLimit() {
        val many = List(45) { scan.closeable[it % scan.closeable.size] }
        assertEquals(listOf(20, 20, 5), Reclaim.batches(many).map { it.size })
    }
}
