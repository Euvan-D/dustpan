package app.dustpan

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dustpan.solana.Base58
import app.dustpan.solana.Programs
import app.dustpan.solana.PublicKey
import app.dustpan.solana.Reclaim
import app.dustpan.solana.ScanResult
import app.dustpan.solana.SignatureStatus
import app.dustpan.solana.Sol
import app.dustpan.solana.SolanaRpc
import app.dustpan.solana.TestAccounts
import app.dustpan.solana.TokenAccount
import app.dustpan.solana.TokenAccountParser
import app.dustpan.solana.Transactions
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Cluster(val label: String, val defaultRpcUrl: String, private val explorerQuery: String) {
    Devnet("Devnet", "https://api.devnet.solana.com", "?cluster=devnet"),
    Mainnet("Mainnet", "https://api.mainnet-beta.solana.com", "");

    fun explorerTx(signature: String) = "https://explorer.solana.com/tx/$signature$explorerQuery"
    fun explorerAddress(address: String) = "https://explorer.solana.com/address/$address$explorerQuery"
}

enum class Busy { Connecting, Reclaiming, CreatingTestAccounts }

data class UiMessage(val text: String, val actionLabel: String? = null, val url: String? = null)

data class Receipt(
    val cluster: Cluster,
    val reclaimedLamports: Long,
    val closedAccounts: Int,
    val signatures: List<String>,
    val failedTransactions: Int,
    val pendingTransactions: Int,
)

data class UiState(
    val cluster: Cluster = Cluster.Devnet,
    val rpcUrl: String = Cluster.Devnet.defaultRpcUrl,
    val wallet: PublicKey? = null,
    val balanceLamports: Long? = null,
    val scanning: Boolean = false,
    val scan: ScanResult? = null,
    val selected: Set<PublicKey> = emptySet(),
    val busy: Busy? = null,
    val receipt: Receipt? = null,
) {
    val selectedAccounts: List<TokenAccount>
        get() = scan?.closeable?.filter { it.address in selected }.orEmpty()
    val selectedLamports: Long get() = selectedAccounts.sumOf { it.lamports }

    /** Same network and busy flag, fresh wallet data. */
    fun withWallet(wallet: PublicKey?) =
        copy(wallet = wallet, balanceLamports = null, scanning = false, scan = null, selected = emptySet(), receipt = null)
}

/** Thrown inside a wallet session when the wallet hands back a different account than the one we scanned. */
class WalletAccountChanged(val newWallet: PublicKey) :
    IllegalStateException("Your wallet switched to a different account (${newWallet.short()}).")

class DustpanViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("dustpan", Context.MODE_PRIVATE)

    private val walletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(BuildConfig.IDENTITY_URI),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Dustpan",
        ),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var scanJob: Job? = null
    private var scanGeneration = 0

    init {
        val saved = prefs.getString(KEY_CLUSTER, null)
        selectCluster(Cluster.entries.firstOrNull { it.name == saved } ?: Cluster.Devnet, force = true)
    }

    // ---- Network ------------------------------------------------------------------------------

    fun selectCluster(cluster: Cluster, force: Boolean = false) {
        val current = _state.value
        if (!force && (current.cluster == cluster || current.busy != null)) return
        cancelScan()
        prefs.edit().putString(KEY_CLUSTER, cluster.name).apply()
        // Changing the chain clears the adapter's token, so restore the one saved for this cluster.
        walletAdapter.blockchain = cluster.blockchain
        walletAdapter.authToken = prefs.getString(tokenKey(cluster), null)
        val wallet = PublicKey.parseOrNull(prefs.getString(walletKey(cluster), null))
        _state.value = UiState(cluster = cluster, rpcUrl = rpcUrlFor(cluster), wallet = wallet)
        if (wallet != null) scan()
    }

    fun setRpcUrl(url: String) {
        val cluster = _state.value.cluster
        val clean = url.trim()
        if (clean.isNotEmpty() && !clean.startsWith("https://")) {
            emit(UiMessage("The RPC URL has to start with https://"))
            return
        }
        prefs.edit().apply {
            if (clean.isEmpty() || clean == cluster.defaultRpcUrl) remove(rpcKey(cluster)) else putString(rpcKey(cluster), clean)
        }.apply()
        _state.update { it.copy(rpcUrl = rpcUrlFor(cluster)) }
        scan()
    }

    // ---- Wallet -------------------------------------------------------------------------------

    fun connect(sender: ActivityResultSender) = runBusy(Busy.Connecting) {
        when (val result = walletAdapter.connect(sender)) {
            is TransactionResult.Success -> {
                val wallet = result.authResult.primaryKey()
                saveSession(wallet)
                _state.update { it.withWallet(wallet) }
                scan()
            }
            is TransactionResult.NoWalletFound -> emitNoWallet()
            is TransactionResult.Failure -> handleFailure("Couldn't connect", result)
        }
    }

    /** Forgets the wallet on this device. Nothing is sent to the wallet app. */
    fun disconnect() {
        if (_state.value.busy != null) return
        cancelScan()
        forgetSession()
        _state.update { UiState(cluster = it.cluster, rpcUrl = it.rpcUrl) }
    }

    // ---- Scan ---------------------------------------------------------------------------------

    fun scan() {
        val snapshot = _state.value
        val wallet = snapshot.wallet ?: return
        cancelScan()
        val generation = scanGeneration
        _state.update { it.copy(scanning = true) }
        scanJob = viewModelScope.launch {
            try {
                val rpc = SolanaRpc(snapshot.rpcUrl)
                val (balance, accounts) = withContext(Dispatchers.IO) {
                    val balance = rpc.getBalance(wallet)
                    val accounts = listOf(Programs.TOKEN, Programs.TOKEN_2022).flatMap { program ->
                        TokenAccountParser.parse(rpc.getTokenAccountsByOwner(wallet, program), program)
                    }
                    balance to accounts
                }
                val result = ScanResult.of(wallet, accounts)
                if (generation == scanGeneration) {
                    _state.update {
                        it.copy(
                            balanceLamports = balance,
                            scan = result,
                            selected = result.closeable.map { account -> account.address }.toSet(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == scanGeneration) emit(UiMessage("Scan failed: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                if (generation == scanGeneration) _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun toggle(account: PublicKey) = _state.update { s ->
        s.copy(selected = if (account in s.selected) s.selected - account else s.selected + account)
    }

    fun selectAll(select: Boolean) = _state.update { s ->
        s.copy(selected = if (select) s.scan?.closeable?.map { it.address }?.toSet().orEmpty() else emptySet())
    }

    // ---- Reclaim ------------------------------------------------------------------------------

    fun reclaim(sender: ActivityResultSender) {
        val snapshot = _state.value
        val wallet = snapshot.wallet ?: return
        val targets = snapshot.selectedAccounts
        if (targets.isEmpty()) {
            emit(UiMessage("Select at least one account to close."))
            return
        }
        runBusy(Busy.Reclaiming) {
            val rpc = SolanaRpc(snapshot.rpcUrl)
            val batches = Reclaim.batches(targets)
            val result = walletAdapter.transact(sender) { auth ->
                requireAccount(auth, wallet)
                val blockhash = rpc.getLatestBlockhash()
                val transactions = batches.map { batch ->
                    Transactions.buildUnsigned(wallet, blockhash, Reclaim.closeInstructions(wallet, batch))
                }
                signAndSendAll(rpc, transactions)
            }
            when (result) {
                is TransactionResult.Success -> {
                    saveSession(wallet)
                    val signatures = result.payload
                    val statuses = rpc.awaitSignatures(signatures)
                    val confirmed = batches.filterIndexed { i, _ -> statuses.getOrNull(i) == SignatureStatus.Confirmed }.flatten()
                    val receipt = Receipt(
                        cluster = snapshot.cluster,
                        reclaimedLamports = confirmed.sumOf { it.lamports },
                        closedAccounts = confirmed.size,
                        signatures = signatures,
                        failedTransactions = statuses.count { it is SignatureStatus.Failed },
                        pendingTransactions = statuses.count { it == SignatureStatus.Pending },
                    )
                    _state.update { it.copy(receipt = receipt) }
                    when {
                        receipt.closedAccounts == targets.size ->
                            emit(UiMessage("Swept ${receipt.closedAccounts} accounts. ${Sol.format(receipt.reclaimedLamports)} SOL is back in your wallet."))
                        receipt.pendingTransactions > 0 ->
                            emit(UiMessage("Sent. Some transactions are still confirming, so scan again in a minute."))
                        else ->
                            emit(UiMessage("Some transactions failed. The accounts they covered are still listed."))
                    }
                    scan()
                }
                is TransactionResult.NoWalletFound -> emitNoWallet()
                is TransactionResult.Failure -> handleFailure("Couldn't reclaim", result)
            }
        }
    }

    // ---- Devnet helpers -----------------------------------------------------------------------

    fun createTestAccounts(sender: ActivityResultSender) {
        val snapshot = _state.value
        val wallet = snapshot.wallet ?: return
        if (snapshot.cluster != Cluster.Devnet) return
        runBusy(Busy.CreatingTestAccounts) {
            val rpc = SolanaRpc(snapshot.rpcUrl)
            val mints = TestAccounts.DEVNET_MINTS
            val (rent, balance) = withContext(Dispatchers.IO) {
                rpc.getMinimumBalanceForRentExemption(Programs.TOKEN_ACCOUNT_SIZE) to rpc.getBalance(wallet)
            }
            val needed = rent * mints.size + 10_000
            if (balance < needed) {
                emit(UiMessage("You need about ${Sol.format(needed, 4)} SOL on devnet first.", "Get devnet SOL", FAUCET_URL))
                return@runBusy
            }
            val seedPrefix = TestAccounts.newSeedPrefix()
            val result = walletAdapter.transact(sender) { auth ->
                requireAccount(auth, wallet)
                val blockhash = rpc.getLatestBlockhash()
                val instructions = TestAccounts.instructions(wallet, mints, rent, seedPrefix)
                signAndSendAll(rpc, listOf(Transactions.buildUnsigned(wallet, blockhash, instructions)))
            }
            when (result) {
                is TransactionResult.Success -> {
                    saveSession(wallet)
                    when (val status = rpc.awaitSignatures(result.payload).firstOrNull()) {
                        SignatureStatus.Confirmed ->
                            emit(UiMessage("Made ${mints.size} empty token accounts. Now sweep them up."))
                        is SignatureStatus.Failed ->
                            emit(UiMessage("The test transaction failed: ${status.error}"))
                        else ->
                            emit(UiMessage("Sent. It's slow to confirm, so scan again in a moment."))
                    }
                    scan()
                }
                is TransactionResult.NoWalletFound -> emitNoWallet()
                is TransactionResult.Failure -> handleFailure("Couldn't create test accounts", result)
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /**
     * Sends everything in as few wallet prompts as the wallet allows. Wallets that can't submit
     * transactions themselves (older MWA 1.x ones) sign, and Dustpan submits over [rpc].
     */
    @Suppress("DEPRECATION")
    private suspend fun AdapterOperations.signAndSendAll(rpc: SolanaRpc, transactions: List<ByteArray>): List<String> {
        val capabilities = runCatching { getCapabilities() }.getOrNull()
        val limit = capabilities?.maxTransactionsPerSigningRequest ?: 0
        val walletSubmits = capabilities?.supportsSignAndSendTransactions ?: true
        val chunks = if (limit > 0) transactions.chunked(limit) else listOf(transactions)
        return chunks.flatMap { chunk ->
            if (walletSubmits) {
                signAndSendTransactions(chunk.toTypedArray()).signatures.map { Base58.encode(it) }
            } else {
                signTransactions(chunk.toTypedArray()).signedPayloads.map { rpc.sendTransaction(it) }
            }
        }
    }

    private fun requireAccount(auth: AuthorizationResult, expected: PublicKey) {
        val keys = auth.allKeys()
        if (expected !in keys) throw WalletAccountChanged(keys.first())
    }

    private fun handleFailure(prefix: String, failure: TransactionResult.Failure<*>) {
        val cause = failure.e
        when {
            cause is WalletAccountChanged -> {
                saveSession(cause.newWallet)
                _state.update { it.withWallet(cause.newWallet) }
                emit(UiMessage("${cause.message} Dustpan scanned that account instead, so check the list and try again."))
                scan()
            }
            failure.message == "Auth token invalid" -> {
                cancelScan()
                forgetSession()
                _state.update { it.withWallet(null) }
                emit(UiMessage("Your wallet session expired. Connect again."))
            }
            else -> emit(UiMessage("$prefix: ${failure.friendlyReason()}"))
        }
    }

    private fun emitNoWallet() = emit(
        UiMessage(
            "No Solana wallet found on this device. Install one that supports Mobile Wallet Adapter, like Phantom or Solflare.",
            "Learn more",
            "https://docs.solanamobile.com",
        ),
    )

    private fun runBusy(busy: Busy, block: suspend CoroutineScope.() -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(UiMessage(e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun cancelScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    private fun saveSession(wallet: PublicKey) {
        val cluster = _state.value.cluster
        prefs.edit()
            .putString(walletKey(cluster), wallet.toBase58())
            .putString(tokenKey(cluster), walletAdapter.authToken)
            .apply()
    }

    private fun forgetSession() {
        val cluster = _state.value.cluster
        prefs.edit().remove(walletKey(cluster)).remove(tokenKey(cluster)).apply()
        walletAdapter.authToken = null
    }

    private fun rpcUrlFor(cluster: Cluster) = prefs.getString(rpcKey(cluster), null) ?: cluster.defaultRpcUrl

    private fun emit(message: UiMessage) {
        _messages.tryEmit(message)
    }

    companion object {
        const val FAUCET_URL = "https://faucet.solana.com"
        private const val KEY_CLUSTER = "cluster"
        private fun walletKey(cluster: Cluster) = "wallet_${cluster.name}"
        private fun tokenKey(cluster: Cluster) = "auth_token_${cluster.name}"
        private fun rpcKey(cluster: Cluster) = "rpc_${cluster.name}"
    }
}

private val Cluster.blockchain: Blockchain
    get() = when (this) {
        Cluster.Devnet -> Solana.Devnet
        Cluster.Mainnet -> Solana.Mainnet
    }

@Suppress("DEPRECATION")
private fun AuthorizationResult.allKeys(): List<PublicKey> =
    accounts.map { PublicKey(it.publicKey) }.ifEmpty { listOf(PublicKey(publicKey)) }

private fun AuthorizationResult.primaryKey(): PublicKey = allKeys().first()

private fun TransactionResult.Failure<*>.friendlyReason(): String {
    val detail = (e.cause ?: e).message
    return when {
        message == "User did not authorize signing" -> "you declined the request in your wallet."
        message.contains("interrupted", ignoreCase = true) || message.contains("cancelled", ignoreCase = true) ->
            "the wallet request was cancelled."
        message.contains("timed out", ignoreCase = true) -> "the wallet didn't respond in time."
        message == "Not all transactions were submitted" -> "the wallet couldn't submit every transaction."
        detail != null && detail != message -> "$message ($detail)"
        else -> message
    }
}
