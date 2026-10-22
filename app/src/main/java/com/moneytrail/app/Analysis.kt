package com.moneytrail.app

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

data class Flags(
    /** Ids of payments that look like double charges (same payee, amount, day). */
    val duplicates: Set<Long>,
    /** id -> difference in cents between the reported and expected balance. */
    val balanceGaps: Map<Long, Long>,
)

object Analysis {
    val zone: ZoneId = ZoneId.of("Africa/Harare")

    fun flags(all: List<Txn>): Flags {
        val dupes = all.filter { !it.isCredit && it.kind != "WITHDRAWAL" }
            .groupBy {
                listOf(it.source, it.currency, it.counterparty, it.amountCents,
                    Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate())
            }
            .values.filter { it.size > 1 }
            .flatten().map { it.id }.toSet()

        // Each message carries the new balance, so previous balance +/- amount should equal it.
        // A mismatch means a missed message or a fee that was charged.
        val gaps = HashMap<Long, Long>()
        all.groupBy { it.source + it.currency }.values.forEach { list ->
            var prev: Long? = null
            for (t in list.sortedWith(compareBy({ it.timestamp }, { it.id }))) {
                val bal = t.balanceCents
                val p = prev
                if (bal != null && p != null) {
                    val expected = p + if (t.isCredit) t.amountCents else -t.amountCents
                    if (expected != bal) gaps[t.id] = bal - expected
                }
                prev = bal
            }
        }
        return Flags(dupes, gaps)
    }
}

fun money(cents: Long): String {
    val a = abs(cents)
    return "%,d.%02d".format(Locale.US, a / 100, a % 100)
}
