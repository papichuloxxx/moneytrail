package com.moneytrail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample lines follow the real statement layouts as PDFBox extracts them; names and numbers are made up. */
class StatementParserTest {
    private val ecocash = """
        EcoCash Statement
        EcoCash Zimbabwe Pvt Ltd From Date: 2026-07-22
        Currency: USD
        Tran ID Tran Date Service Type Receiver Deposits (CR) Withdrawal (DR) Balance
        MP260723.0832.T1111111 2026-07-23 08:32:30.0 Online Merchant Payment 263770000001 2 3.95
        PP260723.1739.T2222222 2026-07-23 17:39:11.0 Send Money to Registered 263770000002 7 10.95
        CO260723.1850.T3333333 2026-07-23 18:50:28.0 Customer Cash Out at Agent 263770000003 5 5.95
        2026-07-23 18:50:28.0 Customer Cash Out at Agent Fee Account 0.08 5.87
        1 of 22
        Tran ID Tran Date Service Type Receiver Deposits (CR) Withdrawal (DR) Balance
        IP260726.1353.T4444444 2026-07-26 13:53:18.0 Customer Master Card Digital Fee Account 1 3.87
        2026-07-26 13:53:18.0 Customer Master Card Digital Tax Account 0.16 3.71
        2026-07-26 13:53:18.0 Customer Master Card Digital 263770000004 1 4.87
        BW260803.1707.T5555555 2026-08-03 17:07:09.0 Linked Bank to Customer null 80 83.71
        CI260815.1336.T6666666 2026-08-15 13:36:33.0 Customer Loan Disbursement 263770000005 36 119.71
    """.trimIndent()

    private val omari = """
        OM
        Wallet USD Statement
        MSISDN: 263770000009 Account Product
        omzw_bundle_01
        Statement Period: 23-Sep-2026 to 22-Oct-2026 Account Currency USD
        Posting Date Value Date Ref Transaction Description Terminal ID Debits Credits Balance
        23/09/2026 23/09/2026 Statement Opening Balance 1.09
        00:00:00 00:00:00
        12/10/2026 12/10/2026 000100000001 Loan Repay Debit OMZW9100 (1.09) 0.00
        04:27:31 04:27:31 OMARI LOANS            Harare         ZW
        15/10/2026 15/10/2026 000100000002 Transfer-Receive OMZ09004 4.00 4.00
        16:01:16 16:01:16 OMARI MOBILE           Harare
        ZW:SenderMsdn:263770000008 Name:Test
        Person
        15/10/2026 15/10/2026 000100000003 ZIPIT-Send OMZ09004 (2.00) 2.00
        19:21:13 19:21:13 OMARI MOBILE           Harare
        ZW:RecipientAccount:0770000009 : RecipientBank:
        15/10/2026 15/10/2026 000100000003 ZIPIT-Send (Fees & Taxes) (0.41) 1.59
        19:21:13 19:21:13 OMARI MOBILE           Harare
        ZW:RecipientAccount:0770000009 : RecipientBank:
        VISA Reservations (Not Yet Completed):
        Opening Balance Credit Trans Credit Total Debit Trans Debit Total Closing Balance Reserved Available
        1.09 1 4.00 3 (3.50) 1.59 0.00 1.59
    """.trimIndent()

    // Newest first, like the real statement
    private val nmb = """
        STATEMENT OF ACCOUNT
        NMB BANK LIMITED
        ACCOUNT TYPE NMBLITEFCY
        DATE NARRATION REF. NO. DEBIT CREDIT BALANCE
        22-10-2026 Transaction fee 13350 0.1 502.99
        22-10-2026 Zimswitch/purchase 10280 1.0 503.09
        /89010000/pizza inn test road
        harare zw/
        rrn:000000000001/5049xxxxxxxx
        0000
        22-10-2026 Cash wd 1234ab_ cash 3074 200.0 504.09
        withdrawal/ vbs-test
        STATEMENT OF ACCOUNT
        NMB BANK LIMITED
        ACCOUNT TYPE NMBLITEFCY
        22-10-2026 Fbcbp26295a01531 test serv 5345 525.0 704.09
        ta clone lab
        03-10-2026 185 0.11 179.09
        03-10-2026 B2w/ rrn:627500000000 185 7.0 179.20
    """.trimIndent()

    @Test fun ecoCashReadsRowsFeesAndDirections() {
        val s = StatementParser.parse(ecocash)!!
        assertEquals(Source.ECOCASH, s.source); assertEquals("USD", s.currency)
        val byRef = s.rows.associateBy { it.reference }
        assertEquals(9, s.rows.size)

        val send = byRef.getValue("PP260723.1739.T2222222")
        assertTrue(send.isCredit); assertEquals(700, send.amountCents); assertEquals(1095L, send.balanceCents)
        val fee = byRef.getValue("CO260723.1850.T3333333-FEE")
        assertEquals("FEE", fee.kind); assertEquals("Fees & Tax", fee.category); assertFalse(fee.isCredit)
        assertTrue(fee.raw.startsWith(StatementParser.RAW_PREFIX))

        // Tran ID printed on the fee row and rows listed out of order: the ID goes to the payment
        val card = byRef.getValue("IP260726.1353.T4444444")
        assertEquals("MERCHANT", card.kind); assertEquals(100, card.amountCents)
        assertEquals(1, s.rows.indexOf(card) - s.rows.indexOf(byRef.getValue("CO260723.1850.T3333333-FEE")))
        assertEquals("FEE", byRef.getValue("IP260726.1353.T4444444-FEE").kind)
        assertEquals(16, byRef.getValue("IP260726.1353.T4444444-TAX").amountCents)

        val topup = byRef.getValue("BW260803.1707.T5555555")
        assertEquals("WALLET_TOPUP", topup.kind); assertTrue(topup.isInternal); assertEquals("Linked Bank to Customer", topup.counterparty)
        val loan = byRef.getValue("CI260815.1336.T6666666")
        assertEquals("LOAN", loan.kind); assertTrue(loan.isCredit); assertEquals("Loans", loan.category)
    }

    @Test fun ecoCashBalancesChain() {
        val rows = StatementParser.parse(ecocash)!!.rows
        rows.zipWithNext().forEach { (a, b) ->
            val step = if (b.isCredit) b.amountCents else -b.amountCents
            assertEquals(b.raw, a.balanceCents!! + step, b.balanceCents)
        }
    }

    @Test fun omariReadsLoansFeesAndOwnTransfers() {
        val s = StatementParser.parse(omari)!!
        assertEquals(Source.OMARI, s.source)
        val byRef = s.rows.associateBy { it.reference }
        assertEquals(4, s.rows.size)
        val repay = byRef.getValue("OMARI-000100000001")
        assertEquals("LOAN_REPAYMENT", repay.kind); assertFalse(repay.isCredit); assertEquals(109, repay.amountCents)
        val local = java.time.Instant.ofEpochMilli(repay.timestamp).atZone(Analysis.zone)
        assertEquals(4, local.hour); assertEquals(27, local.minute)
        val received = byRef.getValue("OMARI-000100000002")
        assertTrue(received.isCredit); assertEquals("Test Person", received.counterparty)
        val toSelf = byRef.getValue("OMARI-000100000003")
        assertEquals("WALLET_TO_BANK", toSelf.kind); assertTrue(toSelf.isInternal)
        val fee = byRef.getValue("OMARI-000100000003-FEE")
        assertEquals("FEE", fee.kind); assertEquals(41, fee.amountCents); assertEquals(159L, fee.balanceCents)
    }

    @Test fun nmbReadsNewestFirstWithContinuationLines() {
        val s = StatementParser.parse(nmb)!!
        assertEquals(Source.NMB, s.source); assertEquals("USD", s.currency)
        assertEquals(6, s.rows.size)
        // Oldest first after parsing
        assertEquals("BANK_TO_WALLET", s.rows[0].kind); assertTrue(s.rows[0].isInternal)
        assertEquals(11, s.rows[1].amountCents) // row with no narration still read
        val credit = s.rows[2]
        assertTrue(credit.isCredit); assertEquals("BANK_CREDIT", credit.kind)
        assertEquals("TEST SERV TA CLONE LAB", credit.counterparty)
        assertEquals("NMB-2026-10-22-USD-52500-70409", credit.reference)
        assertEquals("WITHDRAWAL", s.rows[3].kind); assertFalse(s.rows[3].isCredit)
        assertEquals("PIZZA INN TEST ROAD", s.rows[4].counterparty); assertEquals("Food", s.rows[4].category)
        assertEquals("FEE", s.rows[5].kind)
    }

    @Test fun otherPdfsAreNotRecognised() {
        assertNull(StatementParser.parse("Some other bank\nDATE NARRATION AMOUNT"))
    }

    private fun known(t: ParsedTx, fromStatement: Boolean = false) =
        Known(t.reference, t.source.name, t.currency, t.isCredit, t.amountCents, t.timestamp, fromStatement)

    @Test fun mergeSkipsWhatSmsAlreadyHas() {
        val rows = StatementParser.parse(ecocash)!!.rows
        // Same transaction arrived by SMS: same reference
        val sms = SmsParser.parse("+263164USD", "Transfer Confirmation: USD 7.00 from TEST PERSON. Approval Code: PP260723.1739.T2222222 New balance: USD 10.95.", 0L)!!
        val fresh = StatementMerge.fresh(rows, listOf(known(sms)))
        assertEquals(rows.size - 1, fresh.size)
        assertTrue(fresh.none { it.reference == sms.reference })
    }

    @Test fun mergeMatchesSmsWithoutReferenceByAmountAndTime() {
        val rows = StatementParser.parse(omari)!!.rows
        val repay = rows.first { it.kind == "LOAN_REPAYMENT" }
        val sms = SmsParser.parse("Omari", "Your wallet has been debited with USD1.09 on 2026-10-12 04:27 via Omari Loan Repayment. .", 0L)!!
        assertEquals(repay.timestamp / 60_000, sms.timestamp / 60_000)
        val fresh = StatementMerge.fresh(rows, listOf(known(sms)))
        assertEquals(rows.size - 1, fresh.size)
        assertTrue(fresh.none { it.reference == repay.reference })
    }

    @Test fun mergeOnNmbToleratesDifferentBalanceInSms() {
        val rows = StatementParser.parse(nmb)!!.rows
        val sms = SmsParser.parse("NMB Bank", "Dear TEST, your Account No. XXXXXXX0000 has been credited with USD 525.00 on 2026-10-22T00:00:00 by Transfer. Your new balance is USD 701.34 .", rows[2].timestamp + 3_600_000)!!
        val fresh = StatementMerge.fresh(rows, listOf(known(sms)))
        assertEquals(rows.size - 1, fresh.size)
    }

    @Test fun reimportingAddsNothing() {
        val rows = StatementParser.parse(nmb)!!.rows
        assertEquals(0, StatementMerge.fresh(rows, rows.map { known(it, fromStatement = true) }).size)
    }
}
