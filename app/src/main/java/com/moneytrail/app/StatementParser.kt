package com.moneytrail.app

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Transactions read from one statement, oldest first. */
data class Statement(val source: Source, val currency: String, val rows: List<ParsedTx>)

/**
 * Reads EcoCash, Omari and NMB statements from the text of the PDF (as PDFBox extracts it).
 * Pure Kotlin like [SmsParser] so it can be unit tested on the JVM.
 *
 * Statements list fees, taxes and transactions that never sent an SMS. References are built the
 * same way as in [SmsParser] wherever the statement allows it, so rows that already came in by SMS
 * are recognised; [StatementMerge] catches the rest.
 */
object StatementParser {
    /** Prefix on [ParsedTx.raw] that marks a row as coming from a statement rather than an SMS. */
    const val RAW_PREFIX = "Statement: "

    private val zone: ZoneId = ZoneId.of("Africa/Harare")
    private val WS = Regex("\\s+")

    fun parse(text: String): Statement? {
        val lines = text.lines().map { it.replace(WS, " ").trim() }.filter { it.isNotEmpty() }
        return when {
            lines.any { it.startsWith("EcoCash Statement") } -> parseEcoCash(lines)
            lines.any { it.contains("Statement Opening Balance") } &&
                text.contains("omzw", ignoreCase = true) -> parseOmari(lines)
            lines.any { it == "NMB BANK LIMITED" } || text.contains("nmbz.co.zw") -> parseNmb(lines)
            else -> null
        }
    }

    /** A statement row before we know whether it added or took money. */
    private class Line(
        val ts: Long,
        val day: LocalDate,
        val amount: Long,
        val balance: Long,
        /** Best guess from the wording, used only when the balances cannot tell. */
        val creditHint: Boolean,
        val build: (credit: Boolean) -> ParsedTx,
    )

    /**
     * Works out the direction of each row from the running balance. Rows that happen in the same
     * moment (EcoCash) or on the same day (NMB) are sometimes listed out of order, so within such
     * a group the rows are re-chained: pick the row whose balance follows from the previous one.
     */
    private fun resolve(lines: List<Line>, opening: Long?, sameGroup: (Line, Line) -> Boolean): List<ParsedTx> {
        val groups = mutableListOf<MutableList<Line>>()
        for (l in lines) {
            val g = groups.lastOrNull()
            if (g != null && sameGroup(g.first(), l)) g.add(l) else groups.add(mutableListOf(l))
        }
        val out = mutableListOf<ParsedTx>()
        var prev = opening
        for (g in groups) {
            val left = g.toMutableList()
            while (left.isNotEmpty()) {
                val p = prev
                var credit: Boolean? = null
                var pick = left.first()
                if (p != null) {
                    for (l in left) {
                        credit = when (l.balance) {
                            p + l.amount -> true
                            p - l.amount -> false
                            else -> null
                        }
                        if (credit != null) { pick = l; break }
                    }
                }
                left.remove(pick)
                out.add(pick.build(credit ?: pick.creditHint))
                prev = pick.balance
            }
        }
        return out
    }

    private fun tx(
        source: Source, currency: String, reference: String, ts: Long, credit: Boolean, kind: String,
        who: String, amount: Long, balance: Long, internal: Boolean, raw: String,
    ) = ParsedTx(
        source = source, currency = currency, reference = reference, timestamp = ts, isCredit = credit,
        kind = kind, counterparty = who, amountCents = amount, balanceCents = balance,
        isInternal = internal, category = Categorizer.guess(kind, credit, who), raw = RAW_PREFIX + raw,
    )

    private fun internalKind(kind: String) =
        kind in setOf("WALLET_TOPUP", "WALLET_TO_BANK", "BANK_TO_WALLET", "ACCOUNT_MOVE", "WITHDRAWAL", "LOAN", "LOAN_REPAYMENT")

    // ---------- EcoCash ----------
    // MP260723.0832.T2566661 2026-07-23 08:32:30.0 Online Merchant Payment 263771638441 2 3.95
    // (fee and tax rows have no Tran ID and "Fee Account" / "Tax Account" as receiver)
    private val ECO_ROW = Regex(
        """^(?:([A-Z]{2}\d{6}\.\d{4}\.T\d+) )?(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.\d+)? (.+?) (Fee Account|Tax Account|\S+) (-?[\d,]*\.?\d+) (-?[\d,]*\.?\d+)$"""
    )
    private val ECO_CURRENCY = Regex("""Currency: (USD|ZWG|ZIG)""", RegexOption.IGNORE_CASE)

    private class EcoRow(val id: String?, val ts: Long, val day: LocalDate, val service: String, val receiver: String,
                         val amount: Long, val balance: Long, val raw: String) {
        val isCharge get() = receiver == "Fee Account" || receiver == "Tax Account"
    }

    private fun parseEcoCash(lines: List<String>): Statement {
        val currency = ECO_CURRENCY.find(lines.joinToString("\n"))?.groupValues?.get(1)?.let(::currencyCode) ?: "USD"
        val rows = lines.mapNotNull { line ->
            val m = ECO_ROW.matchEntire(line) ?: return@mapNotNull null
            val g = m.groupValues
            val time = LocalDateTime.of(g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
            EcoRow(g[1].ifEmpty { null }, time.atZone(zone).toInstant().toEpochMilli(), time.toLocalDate(),
                g[8], g[9], cents(g[10]), cents(g[11]), line)
        }

        // A transaction and its fee/tax rows share a timestamp. The Tran ID is printed on one of them,
        // not always on the main row, so hand it to the main row and derive the charges' references.
        val lines2 = mutableListOf<Line>()
        rows.groupBy { it.ts }.forEach { (_, group) ->
            val ids = ArrayDeque(group.mapNotNull { it.id })
            val firstId = ids.firstOrNull()
            val used = HashSet<String>()
            for (r in group) {
                var ref = when {
                    r.isCharge && firstId != null -> firstId + if (r.receiver == "Fee Account") "-FEE" else "-TAX"
                    !r.isCharge && ids.isNotEmpty() -> ids.removeFirst()
                    else -> "ECO-${r.ts}-${r.amount}-${r.balance}"
                }
                var n = 2
                while (!used.add(ref)) ref = ref.substringBeforeLast("#") + "#" + n++
                val s = r.service.lowercase()
                val hint = listOf("cash in", "receive from", "deposit", "disbursement", "linked bank").any { it in s }
                lines2.add(Line(r.ts, r.day, r.amount, r.balance, hint && !r.isCharge) { credit ->
                    val kind = when {
                        r.isCharge -> "FEE"
                        "linked bank" in s -> if (credit) "WALLET_TOPUP" else "WALLET_TO_BANK"
                        "airtime" in s || "bundle" in s -> "BUNDLE"
                        "loan" in s -> if (credit) "LOAN" else "LOAN_REPAYMENT"
                        "cash in" in s -> "CASH_IN"
                        "cash out" in s -> "CASH_OUT"
                        "receive from" in s -> "ZIPIT"
                        "merchant" in s || "collect payment" in s || "master card" in s || s.startsWith("pay ") -> "MERCHANT"
                        credit -> "TRANSFER_IN"
                        "send money" in s -> "TRANSFER_OUT"
                        else -> "OTHER"
                    }
                    val who = when {
                        r.receiver == "Fee Account" -> "EcoCash fee"
                        r.receiver == "Tax Account" -> "EcoCash tax"
                        r.receiver == "null" -> r.service
                        kind == "ZIPIT" -> "ZIPIT"
                        "master card" in s -> "Mastercard payment " + r.receiver
                        else -> r.receiver
                    }
                    tx(Source.ECOCASH, currency, ref, r.ts, credit, kind, who, r.amount, r.balance, internalKind(kind), r.raw)
                })
            }
        }
        return Statement(Source.ECOCASH, currency, resolve(lines2, null) { a, b -> a.ts == b.ts })
    }

    // ---------- Omari ----------
    // 12/10/2026 12/10/2026 000147217499 Loan Repay Debit OMZW9100 (1.09) 0.00
    // 04:27:31 04:27:31 OMARI LOANS Harare ZW
    private val OM_ROW = Regex(
        """^(\d{2})/(\d{2})/(\d{4}) \d{2}/\d{2}/\d{4} (\d+) (.+?) (?:([A-Za-z0-9]+) )?(\(?[\d,]+\.\d{2}\)?) (-?[\d,]+\.\d{2})$"""
    )
    private val OM_TIME = Regex("""^(\d{2}):(\d{2}):(\d{2}) """)
    private val OM_OPENING = Regex("""Statement Opening Balance (-?[\d,]+\.\d{2})""")
    private val OM_CURRENCY = Regex("""Account Currency (\w+)""")
    private val OM_MSISDN = Regex("""MSISDN: (\d+)""")
    private val OM_NAME = Regex("""Name:(.+)""")
    private val OM_ACCOUNT = Regex("""RecipientAccount:(\d+)""")

    private class OmRow(val g: List<String>, val raw: String) {
        var time: List<Int> = listOf(0, 0, 0)
        val detail = StringBuilder()
    }

    private fun parseOmari(lines: List<String>): Statement {
        val all = lines.joinToString("\n")
        val currency = OM_CURRENCY.find(all)?.groupValues?.get(1)?.let(::currencyCode) ?: "USD"
        val me = OM_MSISDN.find(all)?.groupValues?.get(1)?.takeLast(9)
        val opening = OM_OPENING.find(all)?.groupValues?.get(1)?.let(::cents)

        val rows = mutableListOf<OmRow>()
        var current: OmRow? = null
        for (line in lines) {
            if (line.startsWith("VISA Reservations")) break
            val m = OM_ROW.matchEntire(line)
            if (m != null) {
                current = OmRow(m.groupValues, line).also { rows.add(it) }
                continue
            }
            val c = current ?: continue
            val t = OM_TIME.find(line)
            if (t != null && c.detail.isEmpty()) {
                c.time = t.groupValues.drop(1).map { it.toInt() }
                c.detail.append(line.substring(t.value.length))
            } else {
                c.detail.append(' ').append(line)
            }
        }

        val used = HashSet<String>()
        val parsed = rows.map { r ->
            val g = r.g
            val time = LocalDateTime.of(g[3].toInt(), g[2].toInt(), g[1].toInt(), r.time[0], r.time[1], r.time[2])
            val ts = time.atZone(zone).toInstant().toEpochMilli()
            val desc = g[5]
            val d = desc.lowercase()
            val amount = cents(g[7].trim('(', ')'))
            val detail = r.detail.toString()
            val isFee = "fees" in d || "charge" in d
            var ref = "OMARI-" + g[4] + if (isFee) "-FEE" else ""
            var n = 2
            while (!used.add(ref)) ref = ref.substringBeforeLast("#") + "#" + n++
            val recipient = OM_ACCOUNT.find(detail)?.groupValues?.get(1)
            val toSelf = me != null && recipient != null && recipient.takeLast(9) == me
            Line(ts, time.toLocalDate(), amount, cents(g[8]), !g[7].startsWith("(")) { credit ->
                val kind = when {
                    isFee -> "FEE"
                    "loan repay" in d -> "LOAN_REPAYMENT"
                    "loan" in d -> "LOAN"
                    "bundle" in d -> "BUNDLE"
                    "zipit" in d && credit -> "ZIPIT"
                    toSelf -> "WALLET_TO_BANK"
                    credit -> "TRANSFER_IN"
                    "send" in d -> "TRANSFER_OUT"
                    else -> "OTHER"
                }
                val who = when {
                    isFee -> "Omari fee"
                    kind == "LOAN" || kind == "LOAN_REPAYMENT" -> "Omari Cash Loan"
                    kind == "ZIPIT" -> "ZIPIT"
                    else -> OM_NAME.find(detail)?.groupValues?.get(1)?.trim()
                        ?: recipient ?: desc
                }
                tx(Source.OMARI, currency, ref, ts, credit, kind, who, amount, cents(g[8]), internalKind(kind),
                    r.raw + " | " + detail.trim())
            }
        }
        // The amount column already says which way money went, so the chain is only a cross-check
        return Statement(Source.OMARI, currency, resolve(parsed, opening) { a, b -> a.ts == b.ts })
    }

    // ---------- NMB Bank ----------
    // 22-10-2026 Zimswitch/purchase 10280 1.0 508.09
    // /89019333/pizza inn cork road
    // harare zw/
    // Newest first; only one amount column survives text extraction, so direction comes from balances.
    // Some rows have no narration at all: "03-10-2026 185 0.11 258.68"
    private val NMB_ROW = Regex("""^(\d{2})-(\d{2})-(\d{4}) (?:(.+?) )?(\d+) (-?[\d,]*\.\d+) (-?[\d,]*\.\d+)$""")
    private val NMB_RTGS = Regex("""^[a-z]{4,5}p\d{5}a\d+ (.+)""")
    private val NMB_MERCHANT = Regex("""^/[^/]*/([^/]+)/""")

    private class NmbRow(val g: List<String>, val raw: String) {
        val detail = StringBuilder()
    }

    private fun parseNmb(lines: List<String>): Statement {
        val type = lines.firstOrNull { it.startsWith("ACCOUNT TYPE") }.orEmpty().uppercase()
        val currency = if ("ZWG" in type || "ZIG" in type) "ZWG" else "USD"

        val rows = mutableListOf<NmbRow>()
        var current: NmbRow? = null
        for (line in lines) {
            val m = NMB_ROW.matchEntire(line)
            when {
                m != null -> current = NmbRow(m.groupValues, line).also { rows.add(it) }
                line == "STATEMENT OF ACCOUNT" -> current = null // page header follows
                else -> current?.detail?.append(' ')?.append(line)
            }
        }

        val used = HashSet<String>()
        val chronological = rows.asReversed().mapIndexed { i, r ->
            val g = r.g
            val day = LocalDate.of(g[3].toInt(), g[2].toInt(), g[1].toInt())
            // Only the date is printed; keep the statement's order within the day
            val ts = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() + i * 1000L
            val narration = g[4]
            val detail = r.detail.toString().trim()
            val full = "$narration $detail".lowercase()
            val n = narration.lowercase()
            val amount = cents(g[6])
            val balance = cents(g[7])
            // Same reference as an NMB credit/debit SMS: date, currency, amount, balance
            var ref = "NMB-$day-$currency-$amount-$balance"
            var k = 2
            while (!used.add(ref)) ref = ref.substringBeforeLast("#") + "#" + k++
            val rtgs = NMB_RTGS.find(full)
            val hint = rtgs != null || "conv:credit" in full || "reversal" in full || "refund" in full
            Line(ts, day, amount, balance, hint) { credit ->
                val kind = when {
                    "fee" in n || "tax" in n || "maintenance" in n || "insufficient funds" in n ||
                        "withdrawal charge" in full -> "FEE"
                    "account conv" in full -> "ACCOUNT_MOVE"
                    "atm withdrawal" in full || "cash wd" in full -> "WITHDRAWAL"
                    full.startsWith("b2w") -> "BANK_TO_WALLET"
                    "purchase" in full -> "MERCHANT"
                    "zipit" in full -> if (credit) "ZIPIT" else "TRANSFER_OUT"
                    else -> if (credit) "BANK_CREDIT" else "BANK_DEBIT"
                }
                val who = when (kind) {
                    "FEE" -> "NMB " + narration.lowercase().replace(Regex("""\d.*"""), "").trim()
                    "WITHDRAWAL" -> "Cash withdrawal"
                    "BANK_TO_WALLET" -> "Bank to wallet"
                    "ACCOUNT_MOVE" -> "NMB account change"
                    "MERCHANT" -> NMB_MERCHANT.find(detail)?.groupValues?.get(1)
                        ?.removeSuffix(" zw")?.removeSuffix(" harare")?.trim()?.uppercase() ?: "Card purchase"
                    "ZIPIT", "TRANSFER_OUT" -> "ZIPIT"
                    else -> rtgs?.groupValues?.get(1)?.uppercase() ?: ("NMB " + narration).trim()
                }
                tx(Source.NMB, currency, ref, ts, credit, kind, who,
                    amount, balance, internalKind(kind), (r.raw + " " + detail).trim())
            }
        }
        return Statement(Source.NMB, currency, resolve(chronological, null) { a, b -> a.day == b.day })
    }

    private fun currencyCode(c: String) = if (c.equals("ZiG", ignoreCase = true)) "ZWG" else c.uppercase()

    private fun cents(s: String): Long = BigDecimal(s.replace(",", "")).movePointRight(2).toLong()
}

/** What the app already has, reduced to what matching needs. */
data class Known(
    val reference: String,
    val source: String,
    val currency: String,
    val isCredit: Boolean,
    val amountCents: Long,
    val timestamp: Long,
    val fromStatement: Boolean,
)

object StatementMerge {
    /**
     * Returns the statement rows the app does not have yet. A row is already known when its
     * reference exists, or when an SMS transaction has the same source, currency, direction and
     * amount close in time (NMB SMS carry no reference and loans have none either). Each SMS
     * transaction can only account for one statement row.
     */
    fun fresh(rows: List<ParsedTx>, existing: List<Known>): List<ParsedTx> {
        val refs = existing.mapTo(HashSet()) { it.reference }
        val pool = existing.filter { !it.fromStatement }.toMutableList()
        // Exact reference matches use up their SMS first
        rows.forEach { r -> pool.removeAll { it.reference == r.reference } }
        return rows.filter { r ->
            if (r.reference in refs) return@filter false
            val window = if (r.source == Source.NMB) 2 * DAY else 30 * MINUTE
            val match = pool
                .filter {
                    it.source == r.source.name && it.currency == r.currency && it.isCredit == r.isCredit &&
                        it.amountCents == r.amountCents && kotlin.math.abs(it.timestamp - r.timestamp) <= window
                }
                .minByOrNull { kotlin.math.abs(it.timestamp - r.timestamp) }
            if (match != null) pool.remove(match)
            match == null
        }
    }

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
}
