package com.moneytrail.app

enum class Source { ECOCASH, NMB }

/** A transaction freshly parsed from an SMS, before it is stored. Money is in cents. */
data class ParsedTx(
    val source: Source,
    val currency: String,
    val reference: String,
    val timestamp: Long,
    val isCredit: Boolean,
    val kind: String,
    val counterparty: String,
    val amountCents: Long,
    val balanceCents: Long?,
    val isInternal: Boolean,
    val category: String,
    val raw: String,
)
