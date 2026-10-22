package com.moneytrail.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()

    val all: StateFlow<List<Txn>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val importing = MutableStateFlow(false)

    fun importInbox() {
        viewModelScope.launch {
            importing.value = true
            try {
                SmsImporter.importInbox(getApplication())
            } finally {
                importing.value = false
            }
        }
    }

    fun setCategory(id: Long, category: String) {
        viewModelScope.launch { dao.setCategory(id, category) }
    }

    fun setInternal(id: Long, internal: Boolean) {
        viewModelScope.launch { dao.setInternal(id, internal) }
    }
}
