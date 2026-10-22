package com.moneytrail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {
    private val usd = "+263164USD"
    private val zwg = "+263164ZWG"

    private fun p(addr: String, body: String) = SmsParser.parse(addr, body, 0L)

    @Test fun transferSent() {
        val t = p(usd, "Transfer Confirmation: USD 3.00 sent to ANESU LLOYD NJANJARI. Approval Code:\nPP261022.1114.T6537009. New balance:  USD 3.59.\nTransact using the Super App on https://goo.su/bCutT.")!!
        assertEquals(300, t.amountCents); assertEquals(359L, t.balanceCents)
        assertFalse(t.isCredit); assertEquals("TRANSFER_OUT", t.kind)
        assertEquals("ANESU LLOYD NJANJARI", t.counterparty)
        assertEquals("PP261022.1114.T6537009", t.reference)
        assertEquals("Transfers", t.category)
    }

    @Test fun merchantPaymentOldAndNewWording() {
        val a = p(usd, "Payment Confirmation: USD 1.63 paid to 92447 - MERCHANT CABS. Approval Code: MP261022.0837.T5145898. New balance: USD 6.59.")!!
        assertEquals("MERCHANT CABS", a.counterparty); assertEquals("Transport", a.category)
        val b = p(usd, "USD 30.00 paid to 278443 - KASHAGI ECOCASH. Txn ID: MP261022.0504.T4214243. New wallet balance: USD 8.22.")!!
        assertEquals(3000, b.amountCents); assertEquals(822L, b.balanceCents)
    }

    @Test fun cashIn() {
        val t = p(usd, "Cashin Confirmation: USD 60.00 received from 051818-JOSPHINE SAMURIWO. Approval Code: CI261021.1726.T1656594. New balance: USD 73.36.")!!
        assertTrue(t.isCredit); assertEquals("CASH_IN", t.kind); assertEquals(6000, t.amountCents)
    }

    @Test fun transferReceivedWithoutPeriodAfterCode() {
        val t = p(zwg, "Transfer Confirmation: ZWG 500.00 from HILDAH VENGESAI. Approval Code: PP261016.0550.T3970358 New balance: ZWG 540.72.")!!
        assertTrue(t.isCredit); assertEquals("HILDAH VENGESAI", t.counterparty); assertEquals(54072L, t.balanceCents)
    }

    @Test fun zipitAndBankToWallet() {
        val z = p(zwg, "Transfer Confirmation. ZWG 500.00 ZIPIT Receive. Approval Code: RA260915.1620.T7346067. New wallet balance: ZWG 618.06.")!!
        assertEquals("ZIPIT", z.kind); assertTrue(z.isCredit)
        val b = p(zwg, "Transfer Confirmation: Bank to Wallet of ZWG 50.00 from NMB BANK ZWG successful. Approval Code: BW260917.0923.T9891823. New balance: ZWG 70.72.")!!
        assertEquals("WALLET_TOPUP", b.kind); assertTrue(b.isInternal)
    }

    @Test fun timestampComesFromReference() {
        val t = p(usd, "Transfer Confirmation: USD 3.00 sent to X Y. Approval Code: PP261022.1114.T6537009. New balance: USD 3.59.")!!
        val local = java.time.Instant.ofEpochMilli(t.timestamp).atZone(Analysis.zone)
        assertEquals(2026, local.year); assertEquals(10, local.monthValue)
        assertEquals(22, local.dayOfMonth); assertEquals(11, local.hour); assertEquals(14, local.minute)
    }

    @Test fun nmbCreditAndWithdrawal() {
        val c = p("NMB Bank", "Dear KUDAKWASHE, your Account No. XXXXXXX5773511 has been credited with USD 525.00 on 2026-10-22T00:00:00 by Transfer. Your new balance is USD 711.84 .")!!
        assertTrue(c.isCredit); assertEquals(52500, c.amountCents); assertEquals(71184L, c.balanceCents)
        val w = p("NMB Bank", "Your CASH WITHDRAWAL of \$USD200 was successful with Reference : G61S20B3074T22102026.")!!
        assertEquals(20000, w.amountCents); assertTrue(w.isInternal); assertFalse(w.isCredit)
    }

    @Test fun nonTransactionMessagesAreIgnored() {
        assertNull(p("NMB Bank", "Your RTGS Transfer of \$USD525 was successfully credited into your Account : 00000235773511 with Reference : FBCBP26295A01531."))
        assertNull(p("NMB Bank", "Your Cash Withdrawal request was received successfully with Reference : 6337E2. Kindly collect your cash at AVONDALE branch between 8:00 AM and 3:00 PM."))
        assertNull(p("NMB Bank", "Dear Kudakwashe, we are pleased to advise that your account has been successfully upgraded."))
        assertNull(p("Ecosure", "Dear X, your EcoSure Standard package has been renewed successfully for the month of NOV2026."))
    }

    @Test fun senderMatching() {
        assertNotNull(SmsParser.sourceFor("+263164USD"))
        assertNotNull(SmsParser.sourceFor("263164ZWG"))
        assertNotNull(SmsParser.sourceFor("NMB Bank"))
        assertNull(SmsParser.sourceFor("Ecosure"))
    }
}
