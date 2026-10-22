package com.moneytrail.app

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StatementImporter {
    /**
     * [found] rows were read from the statement, [added] of them were new (the rest were already
     * known from SMS or an earlier import). [error] is set when nothing could be read.
     */
    data class Result(val source: Source?, val found: Int, val added: Int, val error: String? = null)

    /**
     * Imports an EcoCash, Omari or NMB statement PDF picked by the user (e.g. with
     * ActivityResultContracts.OpenDocument and "application/pdf"). Safe to run repeatedly.
     */
    suspend fun importPdf(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val text = try {
            PDFBoxResourceLoader.init(context.applicationContext)
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
            } ?: return@withContext Result(null, 0, 0, "Could not open the file")
        } catch (e: InvalidPasswordException) {
            return@withContext Result(null, 0, 0, "This PDF is password protected")
        } catch (e: Exception) {
            return@withContext Result(null, 0, 0, "Could not read this PDF")
        }

        val statement = StatementParser.parse(text)
            ?: return@withContext Result(null, 0, 0, "Not an EcoCash, Omari or NMB statement")

        val dao = AppDb.get(context).dao()
        val known = dao.getAll().map { it.toKnown() }
        val fresh = StatementMerge.fresh(statement.rows, known)
        val added = dao.insertAll(fresh.map { it.toTxn() }).count { it != -1L }
        Result(statement.source, statement.rows.size, added)
    }
}

fun Txn.toKnown() = Known(
    reference, source, currency, isCredit, amountCents, timestamp,
    fromStatement = raw.startsWith(StatementParser.RAW_PREFIX),
)
