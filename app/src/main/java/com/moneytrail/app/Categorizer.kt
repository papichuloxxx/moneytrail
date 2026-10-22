package com.moneytrail.app

object Categorizer {
    val all = listOf(
        "Income", "Cash In", "Transfers", "Transport", "Airtime & Data",
        "Insurance", "Bills", "Food", "Shopping", "Bank", "Cash", "Other",
    )

    private val rules = listOf(
        Regex("ECOSURE|INSURANCE|MEDICAL AID|FUNERAL") to "Insurance",
        Regex("CABS|TAXI|FUEL|ZUPCO|UBER|BUS |PETROL|ENERGY") to "Transport",
        Regex("BROADBAND|ECONET|NETONE|TELONE|AIRTIME|DATA|WIFI") to "Airtime & Data",
        Regex("ZESA|ZINWA|COUNCIL|DSTV|ELECTRICITY|RENT") to "Bills",
        Regex("SUPERMARKET|BUTCHERY|BAKERY|FOOD|PICK N PAY|OK ZIM|CHOPPIES|SPAR|TAKEAWAY|PIZZA") to "Food",
        Regex("BANK|CBZ|STANBIC|FBC|STEWARD|ZB ") to "Bank",
    )

    fun guess(kind: String, isCredit: Boolean, counterparty: String): String {
        val name = counterparty.uppercase()
        if (!isCredit && kind == "MERCHANT") {
            rules.firstOrNull { it.first.containsMatchIn(name) }?.let { return it.second }
        }
        return when (kind) {
            "CASH_IN" -> "Cash In"
            "TRANSFER_OUT" -> "Transfers"
            "TRANSFER_IN", "ZIPIT", "BANK_CREDIT" -> "Income"
            "CASH_OUT", "WITHDRAWAL" -> "Cash"
            "BANK_DEBIT" -> "Bank"
            else -> if (isCredit) "Income" else "Other"
        }
    }
}
