package com.moneytrail.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "transactions", indices = [Index(value = ["reference"], unique = true)])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
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

fun ParsedTx.toTxn() = Txn(
    source = source.name, currency = currency, reference = reference, timestamp = timestamp,
    isCredit = isCredit, kind = kind, counterparty = counterparty, amountCents = amountCents,
    balanceCents = balanceCents, isInternal = isInternal, category = category, raw = raw,
)
