package com.norfold.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exported schema directory has three holes in it: versions 40, 41 and 49 were never
 * committed. Regenerating them from today's entities would emit today's schema under an old
 * version number, with an identityHash no build ever produced, so the holes are deliberate and
 * permanent.
 *
 * That makes the migration chain the only remaining record of what happened across those versions,
 * and these tests are what keep it a record rather than a claim. They read the database source and
 * the exported schemas as text and prove three things: the chain is complete and reaches the
 * current version, the schema delta on each side of a hole is exactly what the migrations in that
 * hole say it should be, and no `schemaMigration` hop can drop a column — which is what lets the
 * missing versions be reconstructed from their neighbours at all.
 *
 * If one of these fails after a legitimate schema change, the fix is to update the expected delta
 * here and the reasoning in the internal schema-gap document together. They are two halves of one
 * statement.
 */
class SchemaMigrationChainTest {

    @Test fun `every declared migration is registered exactly once and none is invented`() {
        val source = databaseSource()
        val registered = registeredMigrations(source)
        val declared = Regex("""val MIGRATION_(\d+)_(\d+)\s*=""")
            .findAll(source)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

        assertEquals(
            "a migration is declared but never registered, so it would never run",
            emptyList<Pair<Int, Int>>(),
            (declared.toSet() - registered.toSet()).sortedBy { it.first },
        )
        assertEquals(
            "a migration is registered but not declared in this file",
            emptyList<Pair<Int, Int>>(),
            (registered.toSet() - declared.toSet()).sortedBy { it.first },
        )
        assertEquals(
            "the same migration is registered more than once",
            registered.distinct().size,
            registered.size,
        )
    }

    @Test fun `every startable version reaches the current database version`() {
        val source = databaseSource()
        val current = Regex("""version = (\d+)""").find(source)?.groupValues?.get(1)?.toInt()
            ?: error("could not read the database version")
        val edges = registeredMigrations(source).groupBy({ it.first }, { it.second })

        val stranded = edges.keys.filterNot { start ->
            val seen = mutableSetOf(start)
            val pending = ArrayDeque(listOf(start))
            var arrived = false
            while (pending.isNotEmpty() && !arrived) {
                val at = pending.removeFirst()
                if (at == current) arrived = true
                edges[at].orEmpty().forEach { if (seen.add(it)) pending.addLast(it) }
            }
            arrived
        }
        assertEquals("these versions cannot upgrade to $current", emptyList<Int>(), stranded.sorted())
    }

    @Test fun `the three missing versions are the only holes and are not silently restored`() {
        val exported = schemaDirectory().listFiles().orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

        val holes = (exported.first()..exported.last()).filterNot { exported.contains(it) }
        assertEquals(
            "the set of missing exported schemas changed; see the internal schema-gap document " +
                "before adding one, because a regenerated export is a forgery",
            listOf(40, 41, 49),
            holes,
        )
    }

    @Test fun `the delta across the 40 and 41 hole is exactly what those migrations add`() {
        assertEquals(
            listOf(
                "chat_channels.pinned",
                "chat_messages.editedAt",
                "chat_messages.pinned",
                "chat_messages.reactionsJson",
                "chat_messages.receiptStatus",
                "chat_messages.replyPreview",
            ),
            addedColumnsBetween(39, 42),
        )
        assertEquals("no table may appear or vanish across this hole", emptyList<String>(), tableDeltaBetween(39, 42))

        // Attribution: which migration in the hole accounts for which column.
        val source = databaseSource()
        assertTrue(migrationBody(source, 39, 40).contains("chat_channels ADD COLUMN pinned"))
        assertTrue(migrationBody(source, 40, 41).contains("chat_messages ADD COLUMN receiptStatus TEXT"))
        // The remaining four arrive with the whole-schema hop out of 41.
        assertTrue(source.contains("private val MIGRATION_41_42 = schemaMigration(41, 42)"))
    }

    @Test fun `the delta across the 49 hole is exactly what those migrations add`() {
        assertEquals(
            listOf(
                "chat_channels.defaultLifespanSeconds",
                "chat_messages.expiresAt",
                "chat_messages.originPayloadJson",
                "secure_chat_outbox.expiresAt",
            ),
            addedColumnsBetween(48, 50),
        )
        assertEquals(listOf("+secure_chat_user_hidden"), tableDeltaBetween(48, 50))

        val source = databaseSource()
        val to49 = migrationBody(source, 48, 49)
        assertTrue(to49.contains("chat_messages ADD COLUMN originPayloadJson"))
        assertTrue(to49.contains("CREATE TABLE IF NOT EXISTS secure_chat_user_hidden"))
        val to50 = migrationBody(source, 49, 50)
        assertTrue(to50.contains("chat_messages ADD COLUMN expiresAt"))
        assertTrue(to50.contains("chat_channels ADD COLUMN defaultLifespanSeconds"))
        assertTrue(to50.contains("secure_chat_outbox ADD COLUMN expiresAt"))
    }

    @Test fun `a whole-schema migration hop cannot drop or rewrite anything`() {
        val body = ensureCurrentSchemaBody()

        // Reconstructing a missing version from its neighbours is only valid if a column present at
        // version n survives to n+1. schemaMigration is the hop that has to guarantee that.
        assertEquals("DROP", 0, Regex("""\bDROP\b""").findAll(body).count())
        assertEquals("TRUNCATE", 0, Regex("""\bTRUNCATE\b""").findAll(body).count())
        assertEquals("RENAME", 0, Regex("""\bRENAME\b""").findAll(body).count())
        assertEquals("ALTER TABLE", 0, Regex("""ALTER TABLE""").findAll(body).count())

        // Every create is guarded, so re-running the hop on an already-migrated database is inert.
        val guarded = Regex("""CREATE TABLE IF NOT EXISTS""").findAll(body).count()
        val allCreates = Regex("""CREATE TABLE""").findAll(body).count()
        assertEquals("an unguarded CREATE TABLE would fail on re-entry", guarded, allCreates)
        assertTrue("expected the full table set to be created here", guarded >= 28)

        // The only DELETE-looking text allowed is a foreign-key clause or an identifier, never a
        // DELETE statement.
        assertEquals(0, Regex("""DELETE\s+FROM""").findAll(body).count())

        // Column additions go through the add-only helper, which returns early when present.
        val source = databaseSource()
        val helper = source.substringAfter("fun SupportSQLiteDatabase.addColumnIfMissing")
            .substringBefore("\n        }")
        assertTrue(helper.contains("PRAGMA table_info"))
        assertTrue(helper.contains("return"))
        assertTrue(helper.contains("ADD COLUMN"))
    }

    // ---- reading the tree ------------------------------------------------------------------

    private fun addedColumnsBetween(low: Int, high: Int): List<String> {
        val before = columnsAt(low)
        val after = columnsAt(high)
        return after.keys.intersect(before.keys).flatMap { table ->
            (after.getValue(table) - before.getValue(table)).map { "$table.$it" }
        }.sorted()
    }

    private fun tableDeltaBetween(low: Int, high: Int): List<String> {
        val before = columnsAt(low).keys
        val after = columnsAt(high).keys
        return ((after - before).map { "+$it" } + (before - after).map { "-$it" }).sorted()
    }

    /**
     * Reads table and column names out of an exported schema without a JSON parser: the export
     * lists each entity's `tableName` followed by its fields, so the order of those keys is enough.
     */
    private fun columnsAt(version: Int): Map<String, Set<String>> {
        val text = File(schemaDirectory(), "$version.json").readText()
        val tables = linkedMapOf<String, MutableSet<String>>()
        var current: String? = null
        Regex(""""(tableName|columnName)":\s*"([^"]+)"""").findAll(text).forEach { match ->
            when (match.groupValues[1]) {
                "tableName" -> {
                    // Views and indices repeat tableName; first occurrence wins for each entity.
                    current = match.groupValues[2]
                    tables.getOrPut(current!!) { mutableSetOf() }
                }
                "columnName" -> current?.let { tables.getValue(it).add(match.groupValues[2]) }
            }
        }
        check(tables.isNotEmpty()) { "no tables read from schema $version" }
        return tables
    }

    private fun registeredMigrations(source: String): List<Pair<Int, Int>> {
        val block = source.substringAfter(".addMigrations(").substringBefore("\n                    )")
        return Regex("""MIGRATION_(\d+)_(\d+)""").findAll(block)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
            .also { check(it.size > 40) { "read only ${it.size} registered migrations; parsing broke" } }
    }

    private fun migrationBody(source: String, from: Int, to: Int): String =
        source.substringAfter("val MIGRATION_${from}_$to = object : Migration($from, $to)")
            .substringBefore("\n        }")

    /** Bounded by brace balance, because the body is hundreds of lines of embedded SQL. */
    private fun ensureCurrentSchemaBody(): String {
        val lines = databaseSource().lines()
        val start = lines.indexOfFirst { it.contains("fun SupportSQLiteDatabase.ensureCurrentSchema") }
        check(start >= 0) { "ensureCurrentSchema not found" }
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '{' } - lines[index].count { it == '}' }
            if (index > start && depth == 0) {
                return lines.subList(start, index + 1).joinToString("\n")
            }
        }
        error("ensureCurrentSchema is unbalanced")
    }

    private fun databaseSource(): String =
        repositoryFile("apps/android/src/main/java/com/norfold/app/data/NorfoldDatabase.kt").readText()

    private fun schemaDirectory(): File =
        repositoryFile("apps/android/schemas/com.norfold.app.data.NorfoldDatabase")

    private fun repositoryFile(path: String): File {
        val candidates = listOf(File(path), File("../../$path"))
        return candidates.firstOrNull { it.exists() } ?: error("Repository file not found: $path")
    }
}
