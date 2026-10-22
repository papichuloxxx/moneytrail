package com.moneytrail.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * TransactionViewModel interacts with [TransactionDao] and exposes transaction data
 * as a [StateFlow] for the UI layer to consume.
 *
 * @param app The Application context.
 * @param dao The [TransactionDao] used for Room database queries and mutations (defaults to [AppDb.transactionDao]).
 */
open class TransactionViewModel(
    app: Application,
    protected val dao: TransactionDao = AppDb.get(app).transactionDao(),
) : AndroidViewModel(app) {

    /**
     * Real-time stream of all transactions from the Room database, exposed as a [StateFlow].
     */
    val transactions: StateFlow<List<Transaction>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    /** Backward-compatible alias for [transactions]. */
    val all: StateFlow<List<Transaction>> = transactions

    private val _importing = MutableStateFlow(false)

    /** Exposes the SMS scanning/importing state. */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /**
     * Scans the SMS inbox for transactions and inserts new records into the database via [dao].
     */
    fun importInbox() {
        viewModelScope.launch {
            _importing.value = true
            try {
                SmsImporter.importInbox(getApplication())
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * Inserts a single transaction into the database via [dao].
     */
    fun insert(transaction: Transaction) {
        viewModelScope.launch {
            dao.insert(transaction)
        }
    }

    /**
     * Inserts multiple transactions into the database via [dao].
     */
    fun insertAll(transactions: List<Transaction>) {
        viewModelScope.launch {
            dao.insertAll(transactions)
        }
    }

    /**
     * Updates the category of a transaction by ID.
     */
    fun setCategory(id: Long, category: String) {
        viewModelScope.launch {
            dao.setCategory(id, category)
        }
    }

    /**
     * Toggles whether a transaction is an internal transfer.
     */
    fun setInternal(id: Long, internal: Boolean) {
        viewModelScope.launch {
            dao.setInternal(id, internal)
        }
    }

    /**
     * Observes transactions filtered by bank source (e.g. "ECOCASH", "NMB", "OMARI").
     */
    fun observeBySource(source: String): Flow<List<Transaction>> {
        return dao.observeBySource(source)
    }

    /**
     * Retrieves a single transaction by ID.
     */
    suspend fun getById(id: Long): Transaction? {
        return dao.getById(id)
    }
}
