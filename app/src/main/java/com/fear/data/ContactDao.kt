package com.fear.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity): Long

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun all(): List<ContactEntity>

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE identityPkB64 = :pkB64 LIMIT 1")
    suspend fun findByPk(pkB64: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE identityPkB64 = :pkB64")
    suspend fun deleteByPk(pkB64: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    /** Bulk replace — used after pulling the encrypted blob from a server. */
    @androidx.room.Transaction
    suspend fun replaceAll(items: List<ContactEntity>) {
        deleteAll()
        for (c in items) upsert(c)
    }
}
