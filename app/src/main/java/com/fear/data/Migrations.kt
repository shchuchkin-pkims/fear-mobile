package com.fear.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Переходы между версиями схемы.
 *
 * До этого база открывалась с fallbackToDestructiveMigration: любое
 * расхождение схемы Room решал тем, что стирал файл и заводил новый. Для
 * контактов это было терпимо - их копия лежит на сервере зашифрованным
 * блобом. Для переписки не терпимо совсем: ретранслятор её не хранит, а
 * офлайн-ящик отдаёт письмо один раз и забывает. Стёртая история не
 * восстанавливается ниоткуда, и узнал бы об этом человек постфактум,
 * открыв пустой чат после обычного обновления.
 *
 * Поэтому каждый переход теперь написан явно. Правило простое: меняешь
 * сущность - поднимаешь версию и добавляешь сюда шаг, иначе приложение
 * упадёт при открытии базы, а не молча вычистит чужую переписку на чужом
 * телефоне.
 *
 * DDL вынесен в константы не для красоты: Room сверяет получившуюся схему с
 * ожидаемой побайтно, вплоть до порядка столбцов, и расходится с ней молча
 * до самого запуска на устройстве. Константу же сверяет с выложенной схемой
 * обычный тест на сборке - см. MigrationSqlTest.
 */
object Migrations {

    /**
     * Таблица контактов ровно в том виде, в каком её ждёт Room для
     * ContactEntity. Списана с app/schemas, а не написана по памяти.
     */
    const val CONTACTS_DDL =
        "CREATE TABLE IF NOT EXISTS `contacts` (" +
            "`identityPkB64` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`handle` TEXT, " +
            "`server` TEXT, " +
            "`addedAt` INTEGER NOT NULL, " +
            "`verified` INTEGER NOT NULL, " +
            "PRIMARY KEY(`identityPkB64`))"

    /**
     * v1 → v2: появилась адресная книга.
     *
     * Сообщения не трогаются - они и есть то, ради чего эта миграция
     * написана вместо стирания.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CONTACTS_DDL)
        }
    }

    /** Все переходы разом - в том порядке, в каком их применяет Room. */
    val ALL = arrayOf(MIGRATION_1_2)
}
