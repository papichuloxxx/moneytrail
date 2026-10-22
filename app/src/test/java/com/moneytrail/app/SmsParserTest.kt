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

    @Test fun omariZipitInAndSent() {
        val z = p("Omari", "Zipit Receive of : USD4.00 was successful. Trans ID 000044103842. New wallet bal USD52.11. Earn points for every USD50 to stand a chance to win in the Omari Transact & Win promo")!!
        assertEquals("ZIPIT", z.kind); assertTrue(z.isCredit); assertEquals(400, z.amountCents)
        assertEquals(5211L, z.balanceCents); assertEquals("OMARI-000044103842", z.reference)
        assertEquals(Source.OMARI, z.source); assertEquals("Income", z.category)
        val s = p("Omari", "You have successfully sent:\nUSD34.00 to 5644391 acc. *2247,\ntrans ID 000143111877. Your new Omari wallet balance is USD1.09 Earn points for every USD50 to stand a chance to win in the Omari Transact & Win promo")!!
        assertEquals("TRANSFER_OUT", s.kind); assertFalse(s.isCredit); assertEquals(3400, s.amountCents)
        assertEquals(109L, s.balanceCents); assertEquals("5644391 *2247", s.counterparty)
        assertEquals("OMARI-000143111877", s.reference)
    }

    @Test fun omariBundleLoanAndRepayment() {
        val b = p("Omari", "Your USD \$1.00 Omari Mahala Bundle purchase was successful,ID 000142738169.\nTo transact for free buy Omari for Mahala Bundles on *707#.")!!
        assertEquals("BUNDLE", b.kind); assertFalse(b.isCredit); assertEquals(100, b.amountCents)
        assertEquals("Omari Mahala Bundle", b.counterparty); assertEquals("Airtime & Data", b.category)
        val l = p("Omari", "You have received a Cash Loan of USD10.80 from Omari.")!!
        assertEquals("LOAN", l.kind); assertTrue(l.isCredit); assertTrue(l.isInternal); assertEquals(1080, l.amountCents)
        assertEquals("Loans", l.category)
        val r = p("Omari", "Your wallet has been debited with USD1.09 on 2026-10-12 04:27 via Omari Loan Repayment. .")!!
        assertEquals("LOAN_REPAYMENT", r.kind); assertFalse(r.isCredit); assertEquals(109, r.amountCents)
        val local = java.time.Instant.ofEpochMilli(r.timestamp).atZone(Analysis.zone)
        assertEquals(12, local.dayOfMonth); assertEquals(4, local.hour); assertEquals(27, local.minute)
    }

    @Test fun omariOnlyFromOmariSender() {
        assertNull(p("+263771234567", "You have received a Cash Loan of USD34.20 from Omari."))
    }

    @Test fun omariNoticesAreIgnored() {
        assertNull(p("Omari", "Your Omari outstanding loan is USD50.00,due 11/10/2026. Dial *707# on O'mari,option 6 then option 5 to repay loan!"))
        assertNull(p("Omari", "Your USD \$1.00 Omari Mahala Bundle purchase has expired,\nTo transact for free buy Omari for Mahala Bundles on *707#."))
        assertNull(p("Omari", "Dear Kudakwashe,\nRepay your overdue Omari Cash Loan of USD10.91 today & unlock instant access to another USD loan. To repay,Cash In or ZIPIT USD into your wallet."))
        assertNull(p("Omari", "Hi Kudakwashe,\nYour Omari Cash Loan repayment of USD38.0 is due today. Please Cash In USD at an Omari Branch or Agent or ZIPIT into your Omari wallet."))
        assertNull(p("Omari", "Buy ZESA tokens with your Omari USD or ZiG wallet. Fund your wallet via ZIPIT from any bank or CashIn USD at any Omari Shop, Bhola,TM PnP & Spar. Dial *707# now"))
    }

    @Test fun senderMatching() {
        assertNotNull(SmsParser.sourceFor("+263164USD"))
        assertNotNull(SmsParser.sourceFor("263164ZWG"))
        assertNotNull(SmsParser.sourceFor("NMB Bank"))
        assertEquals(Source.OMARI, SmsParser.sourceFor("OMARI"))
        assertNull(SmsParser.sourceFor("Ecosure"))
    }
}
