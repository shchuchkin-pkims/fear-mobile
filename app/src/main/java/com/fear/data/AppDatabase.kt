package com.fear.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local-only message store. No network leakage: every row is the
 * already-decrypted plaintext as the user actually saw it. The DB file
 * itself sits inside the app sandbox (filesDir).
 *
 * Schema version 1: messages table only.
 * (Phase A §9a — see doc/architecture-decisions.md)
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "fear-history.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
