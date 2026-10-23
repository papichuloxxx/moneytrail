package com.moneytrail.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.RadioButton
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
    var selectedTxnId by remember { mutableStateOf<Long?>(null) }
    var showAllTime by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }

    val selectedTxn = remember(transactions, selectedTxnId) {
        transactions.firstOrNull { it.id == selectedTxnId }
    }

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

    // Monthly trends for the past 6 months
    val monthlyTrends = remember(transactions, selectedCurrency, selectedSource, selectedMonth) {
        val endMonth = if (selectedMonth.isAfter(YearMonth.now())) selectedMonth else YearMonth.now()
        (5 downTo 0).map { offset ->
            val m = endMonth.minusMonths(offset.toLong())
            val txnsInM = transactions.filter { txn ->
                val currencyMatches = txn.currency == selectedCurrency
                val sourceMatches = selectedSource == null || txn.source.equals(selectedSource, ignoreCase = true)
                val txMonth = YearMonth.from(Instant.ofEpochMilli(txn.timestamp).atZone(Analysis.zone))
                currencyMatches && sourceMatches && txMonth == m
            }
            val spending = txnsInM.filter { !it.isCredit && !it.isInternal }.sumOf { it.amountCents }
            val income = txnsInM.filter { it.isCredit && !it.isInternal }.sumOf { it.amountCents }
            MonthlyTrend(
                month = m,
                monthLabel = m.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)),
                spendingCents = spending,
                incomeCents = income,
                isSelected = m == selectedMonth,
            )
        }
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

            // Monthly Spending Trends Bar Chart
            item {
                MonthlySpendingBarChart(
                    currency = selectedCurrency,
                    trends = monthlyTrends,
                    onSelectMonth = { m ->
                        selectedMonth = m
                        showAllTime = false
                    },
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
                        onClick = { selectedTxnId = txn.id },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    selectedTxn?.let { txn ->
        TransactionDetailDialog(
            transaction = txn,
            onCategoryChange = { newCat ->
                vm.setCategory(txn.id, newCat)
            },
            onInternalChange = { isInternal ->
                vm.setInternal(txn.id, isInternal)
            },
            onDismiss = { selectedTxnId = null },
        )
    }
}

/**
 * Monthly trend data model for the spending chart.
 */
data class MonthlyTrend(
    val month: YearMonth,
    val monthLabel: String,
    val spendingCents: Long,
    val incomeCents: Long,
    val isSelected: Boolean,
)

/**
 * Bar Chart displaying monthly spending trends.
 * Supports touch interaction to select and filter by month.
 */
@Composable
fun MonthlySpendingBarChart(
    currency: String,
    trends: List<MonthlyTrend>,
    onSelectMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxSpending = remember(trends) { trends.maxOfOrNull { it.spendingCents } ?: 0L }
    val totalPeriodSpending = remember(trends) { trends.sumOf { it.spendingCents } }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("monthly_spending_chart"),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Monthly Spending Trends",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Last 6 months · Tap a bar to filter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$currency ${money(totalPeriodSpending)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = RED,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Bars Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                trends.forEach { trend ->
                    val fraction = if (maxSpending > 0) {
                        (trend.spendingCents.toFloat() / maxSpending.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val barHeight = (fraction * 90).coerceAtLeast(4f).dp

                    val barColor = when {
                        trend.isSelected -> MaterialTheme.colorScheme.primary
                        trend.spendingCents > 0 -> RED.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectMonth(trend.month) }
                            .padding(horizontal = 4.dp)
                            .testTag("chart_bar_${trend.month}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        // Amount label above bar
                        if (trend.spendingCents > 0) {
                            Text(
                                text = money(trend.spendingCents).substringBefore("."),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (trend.isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (trend.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        // Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(barHeight)
                                .background(
                                    color = barColor,
                                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp),
                                ),
                        )

                        Spacer(Modifier.height(6.dp))

                        // Month label below bar
                        Text(
                            text = trend.monthLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (trend.isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (trend.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
                val catVisual = getCategoryVisual(cat)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(catVisual.iconEmoji, style = MaterialTheme.typography.bodyMedium)
                            Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            text = "$currency ${money(cents)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (totalSpendingCents > 0) cents.toFloat() / totalSpendingCents else 0f },
                        color = catVisual.color,
                        trackColor = catVisual.color.copy(alpha = 0.15f),
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
 * Visual styling metadata for transaction categories.
 */
data class CategoryVisual(
    val color: Color,
    val iconEmoji: String,
)

fun getCategoryVisual(category: String): CategoryVisual = when (category) {
    "Income" -> CategoryVisual(Color(0xFF1B7F3B), "💰")
    "Cash In" -> CategoryVisual(Color(0xFF00897B), "💵")
    "Transfers" -> CategoryVisual(Color(0xFF0288D1), "🔁")
    "Transport" -> CategoryVisual(Color(0xFFE65100), "🚗")
    "Airtime & Data" -> CategoryVisual(Color(0xFF8E24AA), "📱")
    "Insurance" -> CategoryVisual(Color(0xFF3949AB), "🛡️")
    "Bills" -> CategoryVisual(Color(0xFFD84315), "⚡")
    "Food" -> CategoryVisual(Color(0xFFD81B60), "🍔")
    "Shopping" -> CategoryVisual(Color(0xFFAD1457), "🛍️")
    "Bank" -> CategoryVisual(Color(0xFF1565C0), "🏦")
    "Cash" -> CategoryVisual(Color(0xFF6D4C41), "🪙")
    "Loans" -> CategoryVisual(Color(0xFFF57F17), "📑")
    else -> CategoryVisual(Color(0xFF546E7A), "🏷️")
}

@Composable
fun CategoryIconBadge(
    category: String,
    modifier: Modifier = Modifier,
) {
    val visual = remember(category) { getCategoryVisual(category) }
    Box(
        modifier = modifier
            .size(42.dp)
            .background(
                color = visual.color.copy(alpha = 0.15f),
                shape = CircleShape,
            )
            .border(
                width = 1.5.dp,
                color = visual.color.copy(alpha = 0.45f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = visual.iconEmoji,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun CategoryPill(
    category: String,
    modifier: Modifier = Modifier,
) {
    val visual = remember(category) { getCategoryVisual(category) }
    Row(
        modifier = modifier
            .background(
                color = visual.color.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = visual.color, shape = CircleShape),
        )
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = visual.color,
        )
    }
}

/**
 * Single transaction list row with color-coded category icon indicator and pill badge.
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
        // Color-coded category icon indicator
        CategoryIconBadge(
            category = transaction.category,
            modifier = Modifier.padding(end = 12.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = transaction.counterparty,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CategoryPill(category = transaction.category)
            }
            Text(
                text = "${transaction.source} · ${kindLabel(transaction.kind)}" +
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

        Spacer(Modifier.width(10.dp))

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
    onCategoryChange: (String) -> Unit,
    onInternalChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentCategory by remember(transaction.id, transaction.category) { mutableStateOf(transaction.category) }
    var currentInternal by remember(transaction.id, transaction.isInternal) { mutableStateOf(transaction.isInternal) }

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
                        checked = currentInternal,
                        onCheckedChange = {
                            currentInternal = it
                            onInternalChange(it)
                        },
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
                    val isSelected = category == currentCategory
                    val catVisual = getCategoryVisual(category)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentCategory = category
                                onCategoryChange(category)
                            }
                            .padding(vertical = 4.dp)
                            .testTag("category_option_$category"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                currentCategory = category
                                onCategoryChange(category)
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(catVisual.color.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(catVisual.iconEmoji, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodyMedium,
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
