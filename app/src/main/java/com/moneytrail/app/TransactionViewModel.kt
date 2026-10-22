package com.moneytrail.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel exposing Room database transactions as a StateFlow for the UI layer.
 */
open class TransactionViewModel(app: Application) : AndroidViewModel(app) {
    protected val dao: TransactionDao = AppDb.get(app).transactionDao()

    /** Transactions flow collected from the Room database, emitted as StateFlow */
    val transactions: StateFlow<List<Transaction>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    /** Alias for transactions */
    val all: StateFlow<List<Transaction>> = transactions

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

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

    fun setCategory(id: Long, category: String) {
        viewModelScope.launch { dao.setCategory(id, category) }
    }

    fun setInternal(id: Long, internal: Boolean) {
        viewModelScope.launch { dao.setInternal(id, internal) }
    }

    fun insert(transaction: Transaction) {
        viewModelScope.launch { dao.insert(transaction) }
    }
}
