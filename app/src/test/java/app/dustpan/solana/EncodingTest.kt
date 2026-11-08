package app.dustpan.solana

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class EncodingTest {

    @Test
    fun base58KnownVectors() {
        assertEquals("", Base58.encode(ByteArray(0)))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertEquals("JxF12TrwUP45BMd", Base58.encode("Hello World".toByteArray()))
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        assertArrayEquals("Hello World".toByteArray(), Base58.decode("JxF12TrwUP45BMd"))
    }

    @Test
    fun base58RoundTrips() {
        val random = Random(42)
        repeat(500) {
            val bytes = random.nextBytes(random.nextInt(0, 70))
            if (random.nextBoolean() && bytes.isNotEmpty()) bytes[0] = 0
            assertArrayEquals(bytes, Base58.decode(Base58.encode(bytes)))
        }
    }

    @Test
    fun base58RejectsBadCharacters() {
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("0OIl") }
    }

    @Test
    fun publicKeysParseAndCompare() {
        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", Programs.TOKEN.toBase58())
        assertEquals(PublicKey(Programs.TOKEN.bytes), Programs.TOKEN)
        assertEquals(null, PublicKey.parseOrNull("not a key"))
        assertThrows(IllegalArgumentException::class.java) { PublicKey(ByteArray(31)) }
    }

    @Test
    fun shortVecMatchesSpec() {
        assertArrayEquals(byteArrayOf(0x00), ShortVec.encode(0))
        assertArrayEquals(byteArrayOf(0x7f), ShortVec.encode(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ShortVec.encode(128))
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x7f), ShortVec.encode(16383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), ShortVec.encode(16384))
    }

    @Test
    fun solFormatting() {
        assertEquals("0", Sol.format(0))
        assertEquals("0.002039", Sol.format(2_039_280))
        assertEquals("0.0061", Sol.format(6_117_840, maxDecimals = 4))
        assertEquals("1.5", Sol.format(1_500_000_000))
    }
}
