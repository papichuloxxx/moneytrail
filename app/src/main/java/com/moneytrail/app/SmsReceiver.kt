package com.moneytrail.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Captures new transactions the moment the SMS arrives, even when the app is closed. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val byAddress = messages.groupBy { it.originatingAddress }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(context).dao()
                for ((address, parts) in byAddress) {
                    // Long messages arrive in several parts; join them before parsing
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    SmsParser.parse(address, body, parts.first().timestampMillis)
                        ?.let { dao.insert(it.toTxn()) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
