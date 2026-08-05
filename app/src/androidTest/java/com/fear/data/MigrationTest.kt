package com.fear.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Переписка обязана пережить обновление.
 *
 * До этого база открывалась с fallbackToDestructiveMigration: расхождение
 * схемы Room решал стиранием файла. Контакты это переживали - их копия
 * лежит на сервере зашифрованным блобом. История не переживала ничего:
 * ретранслятор её не хранит, офлайн-ящик отдаёт письмо один раз. Человек
 * узнал бы о потере, открыв пустой чат после обычного обновления.
 *
 * Поэтому тест проверяет не то, что миграция «прошла», а то, что строки на
 * месте и читаются: миграция, которая создала таблицы и потеряла данные,
 * формально успешна и ровно так же бесполезна.
 *
 * Тест инструментальный - ему нужен настоящий SQLite с устройства или
 * эмулятора: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v1_to_v2_сохраняет_переписку() {
        // Старая база с настоящей строкой в истории.
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO messages " +
                    "(roomId, senderName, senderPkB64, text, ts, fromSelf, isSystem) " +
                    "VALUES ('general', 'alice', '', 'сказанное до обновления', 1700000000000, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, Migrations.MIGRATION_1_2,
        )

        // Главное: сказанное до обновления никуда не делось.
        db.query("SELECT roomId, senderName, text, ts FROM messages").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("general", c.getString(0))
            assertEquals("alice", c.getString(1))
            assertEquals("сказанное до обновления", c.getString(2))
            assertEquals(1700000000000L, c.getLong(3))
        }

        // И адресная книга появилась пустой, а не отсутствующей.
        db.query("SELECT count(*) FROM contacts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun адресная_книга_после_миграции_принимает_записи() {
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, Migrations.MIGRATION_1_2,
        )
        // Столбцы и типы должны совпасть с ContactEntity, иначе первая же
        // запись контакта после обновления упадёт на устройстве.
        db.execSQL(
            "INSERT INTO contacts " +
                "(identityPkB64, displayName, handle, server, addedAt, verified) " +
                "VALUES ('pk-1', 'Боб', 'bob', 'fear-project.ru', 1700000000000, 1)"
        )
        db.query("SELECT displayName, verified FROM contacts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Боб", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.close()
    }
}
