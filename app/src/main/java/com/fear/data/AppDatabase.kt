package com.fear.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local-only application store. No network leakage: every row is the
 * already-decrypted plaintext as the user actually saw it. The DB file
 * itself sits inside the app sandbox (filesDir).
 *
 * Schema versions:
 *   v1 — messages table (Phase A §9a)
 *   v2 — adds contacts table (Phase B-3 §3)
 *
 * fallbackToDestructiveMigration: contact list is re-fetchable from the
 * server's encrypted blob, so wiping local data on schema mismatch is OK.
 */
@Database(
    entities = [MessageEntity::class, ContactEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "fear-history.db",
                ).fallbackToDestructiveMigration()
                 .build().also { INSTANCE = it }
            }
        }
    }
}
