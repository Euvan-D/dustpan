@file:OptIn(ExperimentalMaterial3Api::class)

package app.dustpan.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dustpan.BuildConfig
import app.dustpan.Busy
import app.dustpan.Cluster
import app.dustpan.DustpanViewModel
import app.dustpan.R
import app.dustpan.Receipt
import app.dustpan.UiState
import app.dustpan.solana.PublicKey
import app.dustpan.solana.ScanResult
import app.dustpan.solana.Sol
import app.dustpan.solana.TokenAccount
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/** Everything the screen can ask the app to do. */
class DustpanActions(
    val onSelectCluster: (Cluster) -> Unit = {},
    val onSetRpcUrl: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onToggle: (PublicKey) -> Unit = {},
    val onSelectAll: (Boolean) -> Unit = {},
    val onReclaim: () -> Unit = {},
    val onCreateTestAccounts: () -> Unit = {},
)

@Composable
fun DustpanScreen(viewModel: DustpanViewModel, sender: ActivityResultSender) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.actionLabel == null,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) message.url?.let { context.openUrl(it) }
        }
    }

    val actions = remember(viewModel, sender) {
        DustpanActions(
            onSelectCluster = { viewModel.selectCluster(it) },
            onSetRpcUrl = { viewModel.setRpcUrl(it) },
            onConnect = { viewModel.connect(sender) },
            onDisconnect = { viewModel.disconnect() },
            onScan = { viewModel.scan() },
            onToggle = { viewModel.toggle(it) },
            onSelectAll = { viewModel.selectAll(it) },
            onReclaim = { viewModel.reclaim(sender) },
            onCreateTestAccounts = { viewModel.createTestAccounts(sender) },
        )
    }
    DustpanContent(state = state, actions = actions, snackbar = snackbar)
}

/** The whole screen as a function of [state], so it can be previewed and screenshot-tested. */
@Composable
fun DustpanContent(state: UiState, actions: DustpanActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val context = LocalContext.current
    var editingRpc by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Dustpan", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NetworkPicker(
                    state = state,
                    onSelect = actions.onSelectCluster,
                    onEditRpc = { editingRpc = true },
                )
            }
            state.busy?.let { busy -> item { BusyCard(busy) } }

            val wallet = state.wallet
            if (wallet == null) {
                item { IntroCard(enabled = state.busy == null, onConnect = actions.onConnect) }
                item { HowItWorksCard() }
            } else {
                item {
                    WalletCard(
                        wallet = wallet,
                        balanceLamports = state.balanceLamports,
                        enabled = state.busy == null,
                        onCopy = { context.copyToClipboard("Wallet address", wallet.toBase58()) },
                        onOpenExplorer = { context.openUrl(state.cluster.explorerAddress(wallet.toBase58())) },
                        onDisconnect = actions.onDisconnect,
                    )
                }
                state.receipt?.let { receipt ->
                    item { ReceiptCard(receipt, onOpenTx = { context.openUrl(receipt.cluster.explorerTx(it)) }) }
                }
                item {
                    ReclaimCard(
                        state = state,
                        onReclaim = actions.onReclaim,
                        onScan = actions.onScan,
                    )
                }
                val scan = state.scan
                if (scan != null && scan.closeable.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Empty accounts",
                            action = if (state.selected.size == scan.closeable.size) "Select none" else "Select all",
                            onAction = { actions.onSelectAll(state.selected.size != scan.closeable.size) },
                        )
                    }
                    items(scan.closeable, key = { it.address.toBase58() }) { account ->
                        AccountRow(
                            account = account,
                            checked = account.address in state.selected,
                            enabled = state.busy == null,
                            onToggle = { actions.onToggle(account.address) },
                        )
                    }
                }
                if (scan != null && (scan.holding.isNotEmpty() || scan.blocked.isNotEmpty())) {
                    item { LeftAloneCard(scan) }
                }
                if (state.cluster == Cluster.Devnet) {
                    item {
                        DevnetToolsCard(
                            enabled = state.busy == null,
                            onGetSol = {
                                context.copyToClipboard("Wallet address", wallet.toBase58())
                                context.openUrl(DustpanViewModel.FAUCET_URL)
                            },
                            onCreateTestAccounts = actions.onCreateTestAccounts,
                        )
                    }
                }
            }
            item { Footer() }
        }
    }

    if (editingRpc) {
        RpcDialog(
            cluster = state.cluster,
            current = state.rpcUrl,
            onDismiss = { editingRpc = false },
            onSave = {
                editingRpc = false
                actions.onSetRpcUrl(it)
            },
        )
    }
}

@Composable
private fun NetworkPicker(state: UiState, onSelect: (Cluster) -> Unit, onEditRpc: () -> Unit) {
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Cluster.entries.forEachIndexed { index, cluster ->
                SegmentedButton(
                    selected = state.cluster == cluster,
                    onClick = { onSelect(cluster) },
                    shape = SegmentedButtonDefaults.itemShape(index, Cluster.entries.size),
                    enabled = state.busy == null,
                ) { Text(cluster.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RPC: ${Uri.parse(state.rpcUrl).host ?: state.rpcUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEditRpc, enabled = state.busy == null) { Text("Change") }
        }
    }
}

@Composable
private fun BusyCard(busy: Busy) {
    val text = when (busy) {
        Busy.Connecting -> "Opening your wallet…"
        Busy.Reclaiming -> "Approve the sweep in your wallet. Dustpan waits for it to confirm."
        Busy.CreatingTestAccounts -> "Approve the test transaction in your wallet…"
    }
    DustCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun IntroCard(enabled: Boolean, onConnect: () -> Unit) {
    DustCard {
        Text("Get your SOL back", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Every token account on Solana holds a rent deposit of about 0.002 SOL. " +
                "When a token balance drops to zero, the account stays open and the deposit stays locked. " +
                "Dustpan finds those empty accounts and closes them, so the SOL goes back to your wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Connect wallet")
        }
    }
}

@Composable
private fun HowItWorksCard() {
    DustCard {
        Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Step("1", "Connect", "Dustpan talks to your wallet app through Mobile Wallet Adapter. Your keys never leave the wallet.")
        Step("2", "Scan", "It reads your token accounts and picks out the ones with a zero balance.")
        Step("3", "Sweep", "You approve one transaction per 20 accounts. The rent goes straight back to you. No fees.")
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    Row(Modifier.padding(bottom = 12.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WalletCard(
    wallet: PublicKey,
    balanceLamports: Long?,
    enabled: Boolean,
    onCopy: () -> Unit,
    onOpenExplorer: () -> Unit,
    onDisconnect: () -> Unit,
) {
    DustCard {
        Text("Wallet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            wallet.short(6),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable(onClick = onCopy),
        )
        Text(
            text = balanceLamports?.let { "${Sol.format(it, 4)} SOL" } ?: "Balance loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onOpenExplorer) { Text("Explorer") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDisconnect, enabled = enabled) { Text("Disconnect") }
        }
    }
}

@Composable
private fun ReclaimCard(state: UiState, onReclaim: () -> Unit, onScan: () -> Unit) {
    val scan = state.scan
    DustCard {
        Text("Reclaimable rent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scan == null) {
            Spacer(Modifier.height(8.dp))
            if (state.scanning) {
                Text("Scanning your token accounts…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text("Scan to see what you can get back.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan") }
            }
        } else {
            ScanSummary(state, scan, onReclaim, onScan)
        }
    }
}

@Composable
private fun ScanSummary(state: UiState, scan: ScanResult, onReclaim: () -> Unit, onScan: () -> Unit) {
    Column {
        Text(
            "${Sol.format(scan.reclaimableLamports)} SOL",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when {
                scan.totalAccounts == 0 -> "This wallet has no token accounts yet."
                scan.closeable.isEmpty() -> "No empty accounts out of ${scan.totalAccounts}. Nothing to sweep."
                else -> "${plural(scan.closeable.size, "empty account")} out of ${scan.totalAccounts}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.scanning) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(14.dp))
        if (scan.closeable.isNotEmpty()) {
            val count = state.selectedAccounts.size
            Button(
                onClick = onReclaim,
                enabled = state.busy == null && count > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (count == 0) "Select accounts to sweep" else "Sweep ${plural(count, "account")} · ${Sol.format(state.selectedLamports)} SOL")
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(onClick = onScan, enabled = !state.scanning, modifier = Modifier.fillMaxWidth()) {
            Text("Scan again")
        }
    }
}

@Composable
private fun ReceiptCard(receipt: Receipt, onOpenTx: (String) -> Unit) {
    DustCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text("Last sweep", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "${Sol.format(receipt.reclaimedLamports)} SOL back from ${plural(receipt.closedAccounts, "account")}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (receipt.failedTransactions > 0 || receipt.pendingTransactions > 0) {
            Text(
                buildList {
                    if (receipt.failedTransactions > 0) add("${plural(receipt.failedTransactions, "transaction")} failed")
                    if (receipt.pendingTransactions > 0) add("${receipt.pendingTransactions} still confirming")
                }.joinToString(", ") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        receipt.signatures.forEachIndexed { index, signature ->
            TextButton(onClick = { onOpenTx(signature) }) {
                Text(if (receipt.signatures.size == 1) "View transaction" else "View transaction ${index + 1}")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun AccountRow(account: TokenAccount, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(account.tokenLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${account.programLabel} · ${account.address.short()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${Sol.format(account.lamports)} SOL", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LeftAloneCard(scan: ScanResult) {
    DustCard {
        Text("Left alone", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (scan.holding.isNotEmpty()) {
            Text(
                "${plural(scan.holding.size, "account")} still hold tokens. Dustpan never touches those.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scan.blocked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${plural(scan.blocked.size, "empty account")} can't be closed:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scan.blocked.take(5).forEach { (account, reason) ->
                Text(
                    "• ${account.tokenLabel}: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (scan.blocked.size > 5) {
                Text("• and ${scan.blocked.size - 5} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DevnetToolsCard(enabled: Boolean, onGetSol: () -> Unit, onCreateTestAccounts: () -> Unit) {
    DustCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text("Testing on devnet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "No empty accounts to try it on? Make three. They lock about 0.006 SOL in total, and the sweep gives it all back. " +
                "Need devnet SOL? \"Get devnet SOL\" copies your address and opens the faucet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGetSol, modifier = Modifier.weight(1f)) { Text("Get devnet SOL") }
            Button(onClick = onCreateTestAccounts, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Make 3 test accounts") }
        }
    }
}

@Composable
private fun Footer() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "Open source. No fees. Dustpan only closes token accounts with a zero balance, " +
                "and every transaction is approved in your own wallet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun RpcDialog(cluster: Cluster, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${cluster.label} RPC") },
        text = {
            Column {
                Text(
                    "The public endpoint works, but it's rate limited. You can paste your own RPC URL here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("https://…") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DustCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No browser found to open $url", Toast.LENGTH_LONG).show()
    }
}

private fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
    }
}
