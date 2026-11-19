package com.finalexam.to_do_list

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SchemaContractTest : DatabaseTestBase() {
    private val dbml get() = File(requireNotNull(System.getProperty("dbml.path"))).readText()
    private val tables get() = Regex("(?ms)^Table (\\w+) \\{(.*?)^}").findAll(dbml).associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun allColumnsDefaultsNullabilityAndChecksMatchDbmlOnCreateAndReopen() {
        verifyColumnsAndChecks()
        val oldUid = uid
        reopen()
        verifyColumnsAndChecks()
        assertEquals(oldUid, uid)
        assertEquals(1, count("users"))
        assertEquals(4, count("categories"))
        assertEquals(4, count("category_members"))
    }

    private fun verifyColumnsAndChecks() {
        val actualTables = mutableSetOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name!='android_metadata'").use {
            while (it.moveToNext()) actualTables.add(it.getString(0))
        }
        assertEquals(tables.keys, actualTables)
        tables.forEach { (table, body) ->
            val columns = Regex("(?m)^  (\\w+) (text|integer)(?: \\[([^\\n]*)])?\\s*$").findAll(body).associateBy { it.groupValues[1] }
            val actual = mutableSetOf<String>()
            sql.query("PRAGMA table_info('$table')").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    actual.add(name)
                    val spec = columns.getValue(name).groupValues
                    assertEquals("$table.$name type", spec[2].uppercase(), cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals("$table.$name nullability", if (spec[3].contains("not null")) 1 else 0, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    val default = Regex("default: ('[^']*'|[0-9]+)").find(spec[3])?.groupValues?.get(1)
                    assertEquals("$table.$name default", default, cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertEquals("$table columns", columns.keys, actual)
            val ddl = scalar("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'")!!
            val expectedChecks = Regex("`([^`]+)`").findAll(body.substringAfter("checks {", "").substringBefore("}")).map { it.groupValues[1] }.toList()
            assertEquals("$table CHECK count", expectedChecks.size, Regex("CHECK\\s*\\(", RegexOption.IGNORE_CASE).findAll(ddl).count())
            expectedChecks.forEach { assertTrue("Missing CHECK in $table: $it", ddl.contains("CHECK ($it)")) }
        }
    }

    @Test fun primaryAndForeignKeysMatchDbml() {
        tables.forEach { (table, body) ->
            val singlePk = Regex("(?m)^  (\\w+) (?:text|integer) \\[[^\\n]*\\bpk\\b").find(body)?.groupValues?.get(1)
            val compositePk = Regex("\\(([^)]+)\\) \\[pk]").find(body)?.groupValues?.get(1)?.split(',')?.map { it.trim() }
            val expectedPk = singlePk?.let { listOf(it) } ?: compositePk!!
            val actualPk = sortedMapOf<Int, String>()
            sql.query("PRAGMA table_info('$table')").use { cursor -> while (cursor.moveToNext()) {
                val ordinal = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                if (ordinal > 0) actualPk[ordinal] = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            } }
            assertEquals("$table PK", expectedPk, actualPk.values.toList())
            val refs = Regex("(?m)^Ref: $table\\.(\\([^)]+\\)|\\w+) > (\\w+)\\.(\\([^)]+\\)|\\w+) \\[delete: ([^]]+)]").findAll(dbml)
                .map { match ->
                    fun columns(value: String) = value.trim('(', ')').split(',').map { it.trim() }.joinToString(",")
                    "${match.groupValues[2]}|${columns(match.groupValues[1])}|${columns(match.groupValues[3])}|${match.groupValues[4].uppercase()}|NO ACTION"
                }.toSet()
            val rows = mutableMapOf<Int, MutableList<List<String>>>()
            sql.query("PRAGMA foreign_key_list('$table')").use { cursor -> while (cursor.moveToNext()) {
                fun value(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                rows.getOrPut(value("id").toInt()) { mutableListOf() }.add(listOf(value("seq"), value("table"), value("from"), value("to"), value("on_delete"), value("on_update")))
            } }
            val actual = rows.values.map { group ->
                val ordered = group.sortedBy { it[0].toInt() }
                "${ordered[0][1]}|${ordered.joinToString(",") { it[2] }}|${ordered.joinToString(",") { it[3] }}|${ordered[0][4]}|${ordered[0][5]}"
            }.toSet()
            assertEquals("$table FKs", refs, actual)
        }
        assertEquals("1", scalar("PRAGMA foreign_keys"))
    }

    @Test fun declaredIndexesAndPartialPredicatesMatchDbml() {
        tables.forEach { (table, body) ->
            val block = Regex("(?ms)^  indexes \\{(.*?)^  }").find(body)?.groupValues?.get(1) ?: ""
            Regex("(?m)^    (\\([^)]+\\)|\\w+) \\[([^]]+)]").findAll(block).forEach { match ->
                val flags = match.groupValues[2]
                if (flags == "pk") return@forEach
                val cols = match.groupValues[1].trim('(', ')').split(',').map { it.trim() }
                val named = Regex("name: '([^']+)'").find(flags)?.groupValues?.get(1)
                    ?: "index_${table}_${cols.joinToString("_")}"
                var found = false
                sql.query("PRAGMA index_list('$table')").use { cursor -> while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == named) {
                        found = true
                        assertEquals(if (flags.contains("unique")) 1 else 0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("partial")))
                    }
                } }
                assertTrue("Missing index $named", found)
                val actualCols = mutableListOf<String>()
                sql.query("PRAGMA index_info('$named')").use { while (it.moveToNext()) actualCols.add(it.getString(it.getColumnIndexOrThrow("name"))) }
                assertEquals(cols, actualCols)
            }
        }
        assertTrue(scalar("SELECT sql FROM sqlite_master WHERE name='uq_one_active_owner'")!!.contains("WHERE role='OWNER' AND status='ACTIVE'"))
        assertTrue(scalar("SELECT sql FROM sqlite_master WHERE name='uq_pending_invite'")!!.contains("WHERE status='PENDING'"))
    }
}
