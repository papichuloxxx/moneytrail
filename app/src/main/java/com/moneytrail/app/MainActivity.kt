package com.moneytrail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(vm) } }
    }
}

private val GREEN = Color(0xFF1B7F3B)
private val RED = Color(0xFFB3261E)
private val AMBER = Color(0xFF9A6700)

@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
    fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    var granted by remember { mutableStateOf(hasPerms()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms()
    }
    LaunchedEffect(granted) { if (granted) vm.importInbox() }

    if (granted) {
        TrackerScreen(vm)
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Money Trail", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "To track your EcoCash, NMB Bank and Omari transactions, the app needs permission to read " +
                    "those SMS messages. Everything stays on this phone.",
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(perms) }) { Text("Allow SMS access") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(vm: MainViewModel) {
    val all by vm.all.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val flags = remember(all) { Analysis.flags(all) }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var currency by remember { mutableStateOf("USD") }
    var selected by remember { mutableStateOf<Txn?>(null) }

    val currencies = remember(all) {
        (all.map { it.currency } + listOf("USD", "ZWG")).distinct().sorted()
    }
    val inMonth = remember(all, month, currency) {
        all.filter {
            it.currency == currency &&
                YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(Analysis.zone)) == month
        }
    }
    val income = inMonth.filter { it.isCredit && !it.isInternal }.sumOf { it.amountCents }
    val spending = inMonth.filter { !it.isCredit && !it.isInternal }.sumOf { it.amountCents }
    val moved = inMonth.filter { it.isInternal }.sumOf { it.amountCents }
    val byCategory = inMonth.filter { !it.isCredit && !it.isInternal }
        .groupBy { it.category }
        .mapValues { e -> e.value.sumOf { it.amountCents } }
        .toList().sortedByDescending { it.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Money Trail") },
                actions = {
                    TextButton(onClick = { vm.importInbox() }, enabled = !importing) {
                        Text(if (importing) "Scanning…" else "Rescan SMS")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ Prev") }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("Next ›") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { c ->
                        FilterChip(selected = c == currency, onClick = { currency = c }, label = { Text(c) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SummaryLine("Money in", "+ $currency ${money(income)}", GREEN)
                        SummaryLine("Money out", "- $currency ${money(spending)}", RED)
                        HorizontalDivider()
                        val net = income - spending
                        SummaryLine("Net", (if (net >= 0) "+ " else "- ") + "$currency ${money(net)}", if (net >= 0) GREEN else RED, bold = true)
                        if (moved > 0) {
                            Text(
                                "$currency ${money(moved)} moved between your own accounts / to cash (not counted)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (byCategory.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Spending by category", style = MaterialTheme.typography.titleSmall)
                            byCategory.forEach { (cat, cents) ->
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(cat)
                                        Text(money(cents))
                                    }
                                    LinearProgressIndicator(
                                        progress = { if (spending > 0) cents.toFloat() / spending else 0f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Transactions (${inMonth.size})", style = MaterialTheme.typography.titleSmall)
                if (inMonth.isEmpty()) {
                    Text("Nothing for this month and currency.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(inMonth, key = { it.id }) { t ->
                TxnRow(t, flags.duplicates.contains(t.id), flags.balanceGaps[t.id]) { selected = t }
                HorizontalDivider()
            }
        }
    }

    selected?.let { t ->
        // Re-read from the list so the dialog reflects edits immediately
        val live = all.firstOrNull { it.id == t.id } ?: t
        EditDialog(live, vm) { selected = null }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "TRANSFER_OUT" -> "Sent"
    "TRANSFER_IN" -> "Received"
    "MERCHANT" -> "Payment"
    "CASH_IN" -> "Cash in"
    "CASH_OUT" -> "Cash out"
    "WALLET_TOPUP" -> "Bank → wallet"
    "ZIPIT" -> "ZIPIT in"
    "WITHDRAWAL" -> "Bank withdrawal"
    "BANK_CREDIT" -> "Bank credit"
    "BANK_DEBIT" -> "Bank debit"
    "BUNDLE" -> "Bundle"
    "LOAN" -> "Loan"
    "LOAN_REPAYMENT" -> "Loan repayment"
    else -> "Other"
}

private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

@Composable
private fun TxnRow(t: Txn, duplicate: Boolean, gap: Long?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.counterparty, fontWeight = FontWeight.Medium)
            Text(
                "${kindLabel(t.kind)} · ${t.category}" + if (t.isInternal) " · internal" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                Instant.ofEpochMilli(t.timestamp).atZone(Analysis.zone).format(timeFmt) +
                    (t.balanceCents?.let { " · bal ${money(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (duplicate) {
                Text("⚠ Same payee, amount and day as another payment: possible double charge",
                    style = MaterialTheme.typography.bodySmall, color = AMBER)
            }
            if (gap != null) {
                Text("⚠ Balance is ${if (gap > 0) "+" else "-"}${money(gap)} off: a fee or a missed message",
                    style = MaterialTheme.typography.bodySmall, color = AMBER)
            }
        }
        Text(
            (if (t.isCredit) "+" else "-") + money(t.amountCents),
            color = if (t.isCredit) GREEN else RED,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EditDialog(t: Txn, vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text(t.counterparty) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Internal transfer\n(don't count as income/spending)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = t.isInternal, onCheckedChange = { vm.setInternal(t.id, it) })
                }
                Spacer(Modifier.height(8.dp))
                Text("Category", style = MaterialTheme.typography.titleSmall)
                Categorizer.all.forEach { c ->
                    Text(
                        (if (c == t.category) "● " else "○ ") + c,
                        Modifier.fillMaxWidth().clickable { vm.setCategory(t.id, c) }.padding(vertical = 8.dp),
                    )
                }
            }
        },
    )
}
