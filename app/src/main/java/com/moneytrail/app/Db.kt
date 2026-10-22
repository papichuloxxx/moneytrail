package com.moneytrail.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TxnDao {
    /** Returns -1 when the reference already exists (duplicate message). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: Txn): Long

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<Txn>>

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String)

    @Query("UPDATE transactions SET isInternal = :internal WHERE id = :id")
    suspend fun setInternal(id: Long, internal: Boolean)
}

@Database(entities = [Txn::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TxnDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "moneytrail.db")
                .build().also { instance = it }
        }
    }
}
