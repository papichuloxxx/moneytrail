package com.moneytrail.app

import android.content.Context
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsImporter {
    /** Scans the SMS inbox for tracked senders. Safe to run repeatedly: duplicates are ignored. */
    suspend fun importInbox(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDb.get(context).dao()
        var added = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "date ASC",
        ) ?: return@withContext 0

        cursor.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val parsed = SmsParser.parse(c.getString(iAddr), c.getString(iBody) ?: "", c.getLong(iDate))
                    ?: continue
                if (dao.insert(parsed.toTxn()) != -1L) added++
            }
        }
        added
    }
}
