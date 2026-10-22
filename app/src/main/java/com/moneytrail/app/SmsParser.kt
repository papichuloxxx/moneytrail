package com.moneytrail.app

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure Kotlin (no Android imports) so it can be unit tested on the JVM.
 * Supports EcoCash (+263164USD / +263164ZWG), NMB Bank and Omari alerts.
 */
object SmsParser {
    private val zone: ZoneId = ZoneId.of("Africa/Harare")
    private val WS = Regex("\\s+")

    /** Decides whether an SMS sender is one we track. */
    fun sourceFor(address: String?): Source? {
        val a = address?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        return when {
            a.endsWith("263164USD") || a.endsWith("263164ZWG") -> Source.ECOCASH
            a == "NMBBANK" -> Source.NMB
            a == "OMARI" -> Source.OMARI
            else -> null
        }
    }

    /** Returns null for messages from other senders or messages that are not transactions. */
    fun parse(address: String?, body: String, smsTime: Long): ParsedTx? {
        val text = body.replace(WS, " ").trim()
        // Omari may show up under a phone number rather than a sender name, so also recognise it by
        // wording. parseOmari only accepts exact transaction formats, so this stays strict.
        val source = sourceFor(address)
            ?: if (text.contains("Omari", ignoreCase = true)) Source.OMARI else return null
        return when (source) {
            Source.ECOCASH -> parseEcoCash(text, smsTime)
            Source.NMB -> parseNmb(text, smsTime)
            Source.OMARI -> parseOmari(text, smsTime)
        }
    }

    // ---------- EcoCash ----------
    private val AMOUNT = Regex("""\b(USD|ZWG)\s*([\d,]+\.\d{2})""")
    private val REF = Regex("""(?:Approval Code|Txn ID):\s*(([A-Z]{2})(\d{2})(\d{2})(\d{2})\.(\d{2})(\d{2})\.T\d+)""")
    private val BAL = Regex("""New (?:wallet )?balance:\s*(?:USD|ZWG)\s*([\d,]+\.\d{2})""")
    private val SENT_TO = Regex("""sent to (.+?)\.\s*(?:Approval|Txn)""")
    private val PAID_TO = Regex("""paid to (\S+) - (.+?)\.\s*(?:Approval|Txn)""")
    private val RECEIVED_FROM = Regex("""received from (.+?)\.\s*(?:Approval|Txn)""")
    private val FROM = Regex("""[\d.,]+ from (.+?)\.\s*(?:Approval|Txn)""")
    private val BANK_TO_WALLET = Regex("""from (.+?) successful""")

    private fun parseEcoCash(text: String, smsTime: Long): ParsedTx? {
        val amt = AMOUNT.find(text) ?: return null
        val ref = REF.find(text) ?: return null
        val prefix = ref.groupValues[2]
        val balance = BAL.find(text)?.let { cents(it.groupValues[1]) }

        // The reference code embeds the time: PP261022.1114 = 22 Oct 2026, 11:14
        val ts = try {
            LocalDateTime.of(
                2000 + ref.groupValues[3].toInt(), ref.groupValues[4].toInt(), ref.groupValues[5].toInt(),
                ref.groupValues[6].toInt(), ref.groupValues[7].toInt(),
            ).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            smsTime
        }

        val sentTo = SENT_TO.find(text)?.groupValues?.get(1)
        val paidTo = PAID_TO.find(text)?.groupValues?.get(2)
        val receivedFrom = RECEIVED_FROM.find(text)?.groupValues?.get(1)
        val from = FROM.find(text)?.groupValues?.get(1)
        val bank = BANK_TO_WALLET.find(text)?.groupValues?.get(1)

        val (kind, credit, who) = when {
            prefix == "BW" && bank != null -> Triple("WALLET_TOPUP", true, bank)
            prefix == "RA" -> Triple("ZIPIT", true, "ZIPIT")
            prefix == "CI" && receivedFrom != null -> Triple("CASH_IN", true, receivedFrom)
            prefix == "CO" && sentTo != null -> Triple("CASH_OUT", false, sentTo)
            prefix == "MP" && paidTo != null -> Triple("MERCHANT", false, paidTo)
            prefix == "PP" && sentTo != null -> Triple("TRANSFER_OUT", false, sentTo)
            prefix == "PP" && from != null -> Triple("TRANSFER_IN", true, from)
            // Unknown message types: best-effort fallback by wording
            sentTo != null -> Triple("OTHER", false, sentTo)
            paidTo != null -> Triple("OTHER", false, paidTo)
            receivedFrom != null -> Triple("OTHER", true, receivedFrom)
            else -> return null
        }

        return ParsedTx(
            source = Source.ECOCASH,
            currency = amt.groupValues[1],
            reference = ref.groupValues[1],
            timestamp = ts,
            isCredit = credit,
            kind = kind,
            counterparty = who,
            amountCents = cents(amt.groupValues[2]),
            balanceCents = balance,
            isInternal = kind == "WALLET_TOPUP",
            category = Categorizer.guess(kind, credit, who),
            raw = text,
        )
    }

    // ---------- NMB Bank ----------
    private val NMB_CREDIT = Regex(
        """has been (credited|debited) with (USD|ZWG)\s*([\d,]+\.\d{2}) on (\d{4}-\d{2}-\d{2})T[\d:]+ by (.+?)\. Your new balance is (?:USD|ZWG)\s*([\d,]+\.\d{2})"""
    )
    private val NMB_WITHDRAWAL = Regex(
        """CASH WITHDRAWAL of [$]?\s*(USD|ZWG)\s*([\d,]+(?:\.\d{1,2})?) was successful with Reference\s*:\s*([A-Za-z0-9]+)"""
    )

    private fun parseNmb(text: String, smsTime: Long): ParsedTx? {
        NMB_CREDIT.find(text)?.let { m ->
            val credit = m.groupValues[1] == "credited"
            val currency = m.groupValues[2]
            val amount = cents(m.groupValues[3])
            val date = m.groupValues[4]
            val balance = cents(m.groupValues[6])
            val kind = if (credit) "BANK_CREDIT" else "BANK_DEBIT"
            val who = "NMB " + m.groupValues[5]
            return ParsedTx(
                source = Source.NMB,
                currency = currency,
                // No reference in this message, so build a stable one for de-duplication
                reference = "NMB-$date-$currency-$amount-$balance",
                timestamp = smsTime,
                isCredit = credit,
                kind = kind,
                counterparty = who,
                amountCents = amount,
                balanceCents = balance,
                isInternal = false,
                category = Categorizer.guess(kind, credit, who),
                raw = text,
            )
        }
        NMB_WITHDRAWAL.find(text)?.let { m ->
            return ParsedTx(
                source = Source.NMB,
                currency = m.groupValues[1],
                reference = "NMB-" + m.groupValues[3],
                timestamp = smsTime,
                isCredit = false,
                kind = "WITHDRAWAL",
                counterparty = "Cash withdrawal",
                amountCents = cents(m.groupValues[2]),
                balanceCents = null,
                isInternal = true, // money moved to cash, not spent
                category = "Cash",
                raw = text,
            )
        }
        // RTGS notices, withdrawal requests, upgrade notices etc. are ignored on purpose
        return null
    }

    // ---------- Omari ----------
    private val OM_ZIPIT_IN = Regex(
        """Zipit Receive of\s*:\s*(USD|ZWG|ZiG)\s*([\d,]+\.\d{2}) was successful\. Trans ID (\d+)\. New wallet bal (?:USD|ZWG|ZiG)\s*([\d,]+\.\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val OM_SENT = Regex(
        """successfully sent:\s*(USD|ZWG|ZiG)\s*([\d,]+\.\d{2}) to (.+?), trans ID (\d+)\. Your new Omari wallet balance is (?:USD|ZWG|ZiG)\s*([\d,]+\.\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val OM_BUNDLE = Regex(
        """Your (USD|ZWG|ZiG) [$]?([\d,]+\.\d{2}) (.+?) purchase was successful,\s*ID (\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val OM_LOAN = Regex("""received a Cash Loan of (USD|ZWG|ZiG)\s*([\d,]+\.\d{2}) from Omari""", RegexOption.IGNORE_CASE)
    private val OM_DEBIT = Regex(
        """wallet has been debited with (USD|ZWG|ZiG)\s*([\d,]+\.\d{2}) on (\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}) via (.+?)\.""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseOmari(text: String, smsTime: Long): ParsedTx? {
        fun tx(
            currency: String, reference: String, timestamp: Long, credit: Boolean, kind: String,
            who: String, amount: Long, balance: Long?, internal: Boolean, category: String,
        ) = ParsedTx(
            source = Source.OMARI, currency = omariCurrency(currency), reference = reference,
            timestamp = timestamp, isCredit = credit, kind = kind, counterparty = who,
            amountCents = amount, balanceCents = balance, isInternal = internal,
            category = category, raw = text,
        )

        OM_ZIPIT_IN.find(text)?.let { m ->
            return tx(m.groupValues[1], "OMARI-" + m.groupValues[3], smsTime, true, "ZIPIT", "ZIPIT",
                cents(m.groupValues[2]), cents(m.groupValues[4]), false,
                Categorizer.guess("ZIPIT", true, "ZIPIT"))
        }
        OM_SENT.find(text)?.let { m ->
            // "5644391 acc. *7187" -> "5644391 *7187"
            val who = m.groupValues[3].replace(Regex("""\s*acc\.?\s*"""), " ").trim()
            return tx(m.groupValues[1], "OMARI-" + m.groupValues[4], smsTime, false, "TRANSFER_OUT", who,
                cents(m.groupValues[2]), cents(m.groupValues[5]), false,
                Categorizer.guess("TRANSFER_OUT", false, who))
        }
        OM_BUNDLE.find(text)?.let { m ->
            val who = m.groupValues[3]
            return tx(m.groupValues[1], "OMARI-" + m.groupValues[4], smsTime, false, "BUNDLE", who,
                cents(m.groupValues[2]), null, false, "Airtime & Data")
        }
        OM_LOAN.find(text)?.let { m ->
            val amount = cents(m.groupValues[2])
            // Borrowed money is not income, so it is internal like a bank withdrawal.
            // No reference in this message: the SMS time keeps it unique.
            return tx(m.groupValues[1], "OMARI-LOAN-$smsTime-$amount", smsTime, true, "LOAN", "Omari Cash Loan",
                amount, null, true, "Loans")
        }
        OM_DEBIT.find(text)?.let { m ->
            val g = m.groupValues
            val ts = try {
                LocalDateTime.of(g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                smsTime
            }
            val amount = cents(g[2])
            val who = g[8].trim()
            val repayment = who.contains("Loan", ignoreCase = true)
            return tx(g[1], "OMARI-DEBIT-${g[3]}${g[4]}${g[5]}${g[6]}${g[7]}-$amount", ts, false,
                if (repayment) "LOAN_REPAYMENT" else "OTHER", who, amount, null, repayment,
                if (repayment) "Loans" else Categorizer.guess("OTHER", false, who))
        }
        // Loan balance notices, reminders, expired bundles and promos are ignored on purpose
        return null
    }

    /** Omari writes ZWG as "ZiG"; store it the same way as EcoCash and NMB. */
    private fun omariCurrency(c: String) = if (c.equals("ZiG", ignoreCase = true)) "ZWG" else c.uppercase()

    private fun cents(s: String): Long = BigDecimal(s.replace(",", "")).movePointRight(2).toLong()
}
