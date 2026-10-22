package com.moneytrail.app

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GREEN = Color(0xFF1B7F3B)
private val RED = Color(0xFFB3261E)
private val AMBER = Color(0xFF9A6700)
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

/**
 * Jetpack Compose screen that displays a summary list of transactions using [TransactionViewModel],
 * including total balance calculation, income/expense breakdown, and category summaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSummaryScreen(
    vm: TransactionViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val flags = remember(transactions) { Analysis.flags(transactions) }

    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedCurrency by remember { mutableStateOf("USD") }
    var selectedTxn by remember { mutableStateOf<Transaction?>(null) }
    var showAllTime by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }

    val currencies = remember(transactions) {
        (transactions.map { it.currency } + listOf("USD", "ZWG")).distinct().sorted()
    }

    // Filter transactions by currency, time period, bank source, and search query
    val filteredTxns = remember(transactions, selectedMonth, selectedCurrency, showAllTime, searchQuery, selectedSource) {
        transactions.filter { txn ->
            val currencyMatches = txn.currency == selectedCurrency
            val periodMatches = if (showAllTime) {
                true
            } else {
                val txnMonth = YearMonth.from(Instant.ofEpochMilli(txn.timestamp).atZone(Analysis.zone))
                txnMonth == selectedMonth
            }
            val sourceMatches = selectedSource == null || txn.source.equals(selectedSource, ignoreCase = true)
            val searchMatches = searchQuery.isBlank() ||
                txn.counterparty.contains(searchQuery.trim(), ignoreCase = true) ||
                txn.category.contains(searchQuery.trim(), ignoreCase = true)

            currencyMatches && periodMatches && sourceMatches && searchMatches
        }
    }

    // Balance calculations
    val totalIncomeCents = filteredTxns
        .filter { it.isCredit && !it.isInternal }
        .sumOf { it.amountCents }

    val totalSpendingCents = filteredTxns
        .filter { !it.isCredit && !it.isInternal }
        .sumOf { it.amountCents }

    val netBalanceCents = totalIncomeCents - totalSpendingCents

    val internalMovedCents = filteredTxns
        .filter { it.isInternal }
        .sumOf { it.amountCents }

    // Latest reported account balance from SMS in this filtered set
    val latestReportedBalance = remember(filteredTxns) {
        filteredTxns.firstOrNull { it.balanceCents != null }?.balanceCents
    }

    val byCategory = remember(filteredTxns) {
        filteredTxns.filter { !it.isCredit && !it.isInternal }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amountCents } }
            .toList()
            .sortedByDescending { it.second }
    }

    Scaffold(
        modifier = modifier.testTag("transaction_summary_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Money Trail", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    TextButton(
                        onClick = { vm.importInbox() },
                        enabled = !importing,
                        modifier = Modifier.testTag("rescan_sms_button"),
                    ) {
                        Text(if (importing) "Scanning…" else "Rescan SMS")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("transaction_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Search Bar for quickly finding transactions by merchant name
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_text_field"),
                    placeholder = { Text("Search by merchant name...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.testTag("clear_search_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // Filter Controls: Bank Source, Currency, and Time Period
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Bank Source Dropdown Select
                    var sourceExpanded by remember { mutableStateOf(false) }
                    val sourceOptions = listOf(
                        null to "All Sources",
                        "ECOCASH" to "EcoCash",
                        "NMB" to "NMB Bank",
                        "OMARI" to "Omari",
                    )
                    val selectedSourceLabel = sourceOptions.firstOrNull { it.first == selectedSource }?.second ?: "All Sources"

                    ExposedDropdownMenuBox(
                        expanded = sourceExpanded,
                        onExpandedChange = { sourceExpanded = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("source_dropdown_box"),
                    ) {
                        OutlinedTextField(
                            value = selectedSourceLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Bank Source") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                                .testTag("source_dropdown_field"),
                            shape = RoundedCornerShape(12.dp),
                        )
                        ExposedDropdownMenu(
                            expanded = sourceExpanded,
                            onDismissRequest = { sourceExpanded = false },
                            modifier = Modifier.testTag("source_dropdown_menu"),
                        ) {
                            sourceOptions.forEach { (sourceKey, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (selectedSource == sourceKey) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        selectedSource = sourceKey
                                        sourceExpanded = false
                                    },
                                    modifier = Modifier.testTag("source_option_${sourceKey ?: "all"}"),
                                )
                            }
                        }
                    }

                    // Currency selectors
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        currencies.forEach { curr ->
                            FilterChip(
                                selected = curr == selectedCurrency,
                                onClick = { selectedCurrency = curr },
                                label = { Text(curr) },
                                modifier = Modifier.testTag("currency_chip_$curr"),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        SuggestionChip(
                            onClick = { showAllTime = !showAllTime },
                            label = { Text(if (showAllTime) "Monthly View" else "All Time") },
                            modifier = Modifier.testTag("toggle_all_time_button"),
                        )
                    }

                    // Month navigator (when not in all-time view)
                    if (!showAllTime) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { selectedMonth = selectedMonth.minusMonths(1) },
                                modifier = Modifier.testTag("prev_month_button"),
                            ) {
                                Text("‹ Prev")
                            }
                            Text(
                                text = selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("current_month_text"),
                            )
                            TextButton(
                                onClick = { selectedMonth = selectedMonth.plusMonths(1) },
                                modifier = Modifier.testTag("next_month_button"),
                            ) {
                                Text("Next ›")
                            }
                        }
                    }
                }
            }

            // Total Balance Card
            item {
                BalanceSummaryCard(
                    currency = selectedCurrency,
                    netBalanceCents = netBalanceCents,
                    totalIncomeCents = totalIncomeCents,
                    totalSpendingCents = totalSpendingCents,
                    latestReportedBalance = latestReportedBalance,
                    internalMovedCents = internalMovedCents,
                )
            }

            // Spending by Category Card
            if (byCategory.isNotEmpty()) {
                item {
                    CategoryBreakdownCard(
                        currency = selectedCurrency,
                        byCategory = byCategory,
                        totalSpendingCents = totalSpendingCents,
                    )
                }
            }

            // Transaction List Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Transactions (${filteredTxns.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (filteredTxns.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val emptyMsg = when {
                            searchQuery.isNotBlank() -> "No transactions matching \"$searchQuery\"."
                            selectedSource != null -> "No $selectedSource transactions found for this period."
                            else -> "No transactions found for this period and currency."
                        }
                        Text(
                            text = emptyMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("empty_transactions_message"),
                        )
                    }
                }
            } else {
                items(filteredTxns, key = { it.id }) { txn ->
                    TransactionItemRow(
                        transaction = txn,
                        isDuplicate = flags.duplicates.contains(txn.id),
                        balanceGap = flags.balanceGaps[txn.id],
                        onClick = { selectedTxn = txn },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    selectedTxn?.let { txn ->
        val liveTxn = transactions.firstOrNull { it.id == txn.id } ?: txn
        TransactionDetailDialog(
            transaction = liveTxn,
            vm = vm,
            onDismiss = { selectedTxn = null },
        )
    }
}

/**
 * Card displaying total balance calculation and income/spending breakdown.
 */
@Composable
private fun BalanceSummaryCard(
    currency: String,
    netBalanceCents: Long,
    totalIncomeCents: Long,
    totalSpendingCents: Long,
    latestReportedBalance: Long?,
    internalMovedCents: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("balance_summary_card"),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Total Net Balance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val netPrefix = if (netBalanceCents >= 0) "+ " else "- "
            val netColor = if (netBalanceCents >= 0) GREEN else RED
            Text(
                text = "$netPrefix$currency ${money(netBalanceCents)}",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = netColor,
                modifier = Modifier.testTag("net_balance_value"),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Total Inflow (+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "+ $currency ${money(totalIncomeCents)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GREEN,
                        modifier = Modifier.testTag("total_income_value"),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Outflow (-)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "- $currency ${money(totalSpendingCents)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = RED,
                        modifier = Modifier.testTag("total_spending_value"),
                    )
                }
            }

            if (latestReportedBalance != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Latest Wallet/Bank Balance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$currency ${money(latestReportedBalance)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (internalMovedCents > 0) {
                Text(
                    text = "$currency ${money(internalMovedCents)} transferred internally (excluded from income/spending)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Breakdown of spending across categories.
 */
@Composable
private fun CategoryBreakdownCard(
    currency: String,
    byCategory: List<Pair<String, Long>>,
    totalSpendingCents: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("category_breakdown_card"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Spending by Category",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            byCategory.forEach { (cat, cents) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(cat, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$currency ${money(cents)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (totalSpendingCents > 0) cents.toFloat() / totalSpendingCents else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * Single transaction list row.
 */
@Composable
private fun TransactionItemRow(
    transaction: Transaction,
    isDuplicate: Boolean,
    balanceGap: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("transaction_item_${transaction.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.counterparty,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${transaction.source} · ${kindLabel(transaction.kind)} · ${transaction.category}" +
                    if (transaction.isInternal) " · Internal" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Instant.ofEpochMilli(transaction.timestamp).atZone(Analysis.zone).format(TIME_FORMATTER) +
                    (transaction.balanceCents?.let { " · Balance: ${transaction.currency} ${money(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (isDuplicate) {
                Text(
                    text = "⚠ Possible double charge (same payee & amount)",
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER,
                )
            }
            if (balanceGap != null) {
                val sign = if (balanceGap > 0) "+" else "-"
                Text(
                    text = "⚠ Balance gap: $sign${money(balanceGap)} (unaccounted fee or missing SMS)",
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        val amountPrefix = if (transaction.isCredit) "+" else "-"
        val amountColor = if (transaction.isCredit) GREEN else RED
        Text(
            text = "$amountPrefix${transaction.currency} ${money(transaction.amountCents)}",
            color = amountColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Edit dialog to modify category or internal transfer toggle.
 */
@Composable
private fun TransactionDetailDialog(
    transaction: Transaction,
    vm: TransactionViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dialog_done_button")) {
                Text("Done")
            }
        },
        title = { Text(transaction.counterparty) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Internal transfer\n(excluded from income & spending)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = transaction.isInternal,
                        onCheckedChange = { vm.setInternal(transaction.id, it) },
                        modifier = Modifier.testTag("internal_transfer_switch"),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Categorizer.all.forEach { category ->
                    val isSelected = category == transaction.category
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setCategory(transaction.id, category) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isSelected) "● " else "○ ",
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = category,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    "TRANSFER_OUT" -> "Sent"
    "TRANSFER_IN" -> "Received"
    "MERCHANT" -> "Merchant Payment"
    "CASH_IN" -> "Cash in"
    "CASH_OUT" -> "Cash out"
    "WALLET_TOPUP" -> "Bank → Wallet"
    "ZIPIT" -> "ZIPIT in"
    "WITHDRAWAL" -> "Bank Withdrawal"
    "BANK_CREDIT" -> "Bank Credit"
    "BANK_DEBIT" -> "Bank Debit"
    "BUNDLE" -> "Bundle"
    "LOAN" -> "Loan"
    "LOAN_REPAYMENT" -> "Loan Repayment"
    else -> "Other"
}
