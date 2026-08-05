package com.fear.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Миграция обязана строить ровно ту схему, которую ждёт Room.
 *
 * Room сверяет получившуюся после миграции базу с ожидаемой схемой побайтно
 * - вплоть до порядка столбцов и слова NOT NULL. Расхождение он замечает уже
 * на устройстве, при открытии базы, то есть у человека, а не у нас.
 *
 * Здесь то же самое проверяется на сборке: DDL миграции сличается с
 * выложенной схемой (app/schemas). Настоящий прогон миграции на живом SQLite
 * - в MigrationTest, но он инструментальный и требует устройства; этот тест
 * ловит самую вероятную поломку - когда сущность правят, а миграцию забывают
 * - без всякого устройства.
 */
class MigrationSqlTest {

    private fun schema(version: Int): JSONObject {
        // Рабочий каталог теста - модуль app.
        val f = File("schemas/com.fear.data.AppDatabase/$version.json")
        assertTrue(
            "нет выложенной схемы v$version (${f.absolutePath}) - " +
                "exportSchema выключен или схему не закоммитили",
            f.exists(),
        )
        return JSONObject(f.readText()).getJSONObject("database")
    }

    private fun createSql(version: Int, table: String): String? {
        val entities = schema(version).getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == table) {
                return e.getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        return null
    }

    @Test
    fun `ddl контактов совпадает с выложенной схемой`() {
        val expected = createSql(2, "contacts")
        assertNotNull("в схеме v2 нет таблицы contacts", expected)
        assertEquals(expected, Migrations.CONTACTS_DDL)
    }

    @Test
    fun `в схеме v1 контактов ещё нет`() {
        // Иначе миграция создавала бы уже существующую таблицу, и её
        // единственный шаг был бы пустышкой.
        assertEquals(null, createSql(1, "contacts"))
        assertNotNull("в схеме v1 должна быть таблица messages", createSql(1, "messages"))
    }

    @Test
    fun `переходы покрывают все версии подряд`() {
        // Дыра в цепочке означает ровно одно: база на пропущенной версии не
        // откроется. Проверяем цепочку, а не наличие файлов.
        val versions = File("schemas/com.fear.data.AppDatabase")
            .listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json").toInt() }
            .sorted()
        assertTrue("схем не выложено вовсе", versions.isNotEmpty())

        val steps = Migrations.ALL.associateBy { it.startVersion }
        for (v in versions.first() until versions.last()) {
            assertNotNull("нет перехода с версии $v на ${v + 1}", steps[v])
            assertEquals(v + 1, steps[v]!!.endVersion)
        }
    }

    @Test
    fun `таблица сообщений при миграции не трогается`() {
        // Ради этого всё и затевалось: переписку не хранит ни ретранслятор,
        // ни офлайн-ящик после выдачи. Любой DROP или RENAME над messages в
        // шаге миграции - потеря без возможности восстановления.
        assertTrue(
            "миграция не должна упоминать messages",
            !Migrations.CONTACTS_DDL.contains("messages", ignoreCase = true),
        )
    }
}
