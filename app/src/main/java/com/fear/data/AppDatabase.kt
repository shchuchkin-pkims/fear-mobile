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
 * Версии схемы:
 *   v1 — таблица messages (Phase A §9a)
 *   v2 — добавлена таблица contacts (Phase B-3 §3)
 *
 * База открывается с явными миграциями (см. [Migrations]). Раньше здесь
 * стоял fallbackToDestructiveMigration, и он был неправ: контакты
 * действительно лежат копией на сервере, а переписка - нигде. Ретранслятор
 * её не хранит, офлайн-ящик отдаёт письмо один раз. Стёртая история не
 * восстанавливается ниоткуда, и человек узнал бы об этом, открыв пустой чат
 * после обычного обновления.
 *
 * Меняешь сущность - поднимай версию и пиши переход. Пропущенный переход
 * теперь роняет открытие базы (и падает тест миграций), а не вычищает чужую
 * переписку молча.
 */
@Database(
    entities = [MessageEntity::class, ContactEntity::class],
    version = 2,
    exportSchema = true,
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
                ).addMigrations(*Migrations.ALL)
                 .build().also { INSTANCE = it }
            }
        }
    }
}
