package app.dustpan.solana

import java.security.MessageDigest

/** A 32-byte Solana address. */
class PublicKey(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "A public key is $SIZE bytes, got ${bytes.size}" }
    }

    constructor(base58: String) : this(Base58.decode(base58))

    fun toBase58(): String = Base58.encode(bytes)

    /** "7xKX…a9Pq" style label for tight UI spots. */
    fun short(chars: Int = 4): String = toBase58().let { "${it.take(chars)}…${it.takeLast(chars)}" }

    override fun toString(): String = toBase58()
    override fun equals(other: Any?): Boolean = other is PublicKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val SIZE = 32
        const val MAX_SEED_LENGTH = 32

        fun parseOrNull(base58: String?): PublicKey? =
            base58?.let { runCatching { PublicKey(it) }.getOrNull() }

        /** Same derivation as `PublicKey.createWithSeed` in web3.js / `Pubkey::create_with_seed` in Rust. */
        fun createWithSeed(base: PublicKey, seed: String, owner: PublicKey): PublicKey {
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            require(seedBytes.size <= MAX_SEED_LENGTH) { "Seed is longer than $MAX_SEED_LENGTH bytes" }
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(base.bytes)
                update(seedBytes)
                update(owner.bytes)
                digest()
            }
            return PublicKey(digest)
        }
    }
}
