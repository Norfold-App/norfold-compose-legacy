package com.norfold.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the one-time editor reset gets its permission from: `MIGRATION_53_54`.
 *
 * The reset erases a generation of the owner's writing, so it may only run on an install that can be
 * shown to hold the old generation. Migrating is the only moment that can be shown — afterwards an
 * unstamped epoch is indistinguishable from a current-generation database whose settings row has not
 * been written yet. These tests check that the marker lands on exactly the rows that deserve it and
 * on no others, at the schema version where it happens rather than through the repository.
 */
@RunWith(AndroidJUnit4::class)
class EditorContentEpochMigrationTest {
    private val databaseName = "editor-epoch-marker-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NorfoldDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun aDatabaseFromBeforeTheEpochColumnsBecomesEligibleExactlyOnce() {
        helper.createDatabase(databaseName, 53).apply {
            // What an install that predates the epoch columns looks like at version 53: the columns
            // exist because MIGRATION_51_52 added them, and nothing has ever written to them.
            insertSettingsRow(this, editorContentEpoch = 0)
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 54, true, NorfoldDatabase.MIGRATION_53_54).use { db ->
            assertEquals(1, markerOf(db))
            // The epoch itself is untouched: the marker is permission to cross the boundary, not the
            // record of having crossed it. Conflating the two is what made an unstamped row look
            // like consent in the first place.
            assertEquals(0, epochOf(db))
        }
    }

    @Test
    fun aDatabaseAlreadyOnTheCurrentEpochIsNeverMadeEligible() {
        helper.createDatabase(databaseName, 53).apply {
            // An install that crossed the boundary under an earlier build, and — the case that
            // matters more — any database whose row was stamped when its workspace was created.
            insertSettingsRow(this, editorContentEpoch = 1)
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 54, true, NorfoldDatabase.MIGRATION_53_54).use { db ->
            assertEquals(0, markerOf(db))
            assertEquals(1, epochOf(db))
        }
    }

    @Test
    fun aDatabaseWithNoSettingsRowGainsNoEligibilityToInherit() {
        helper.createDatabase(databaseName, 53).apply { close() }

        helper.runMigrationsAndValidate(databaseName, 54, true, NorfoldDatabase.MIGRATION_53_54).use { db ->
            db.query("SELECT COUNT(*) FROM settings").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            // The row this install writes later comes from the entity, whose default is 0. An
            // install that never recorded a single setting has nothing the boundary was written for,
            // and the safe answer to "was there an old generation here?" is no.
        }
    }

    private fun markerOf(db: SupportSQLiteDatabase): Int =
        db.query("SELECT legacyEditorResetPending FROM settings WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun epochOf(db: SupportSQLiteDatabase): Int =
        db.query("SELECT editorContentEpoch FROM settings WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * Inserts one settings row, deriving every column from the table's own definition.
     *
     * The exported schema carries no SQL DEFAULT clauses — the entity's Kotlin defaults never reach
     * SQLite — so `INSERT INTO settings(id) VALUES(1)` fails on the first NOT NULL column, and a
     * hand-written column list would be a copy of the settings table that goes stale the next time
     * a preference is added. Reading `PRAGMA table_info` instead keeps this fixture about the two
     * columns under test.
     */
    private fun insertSettingsRow(db: SupportSQLiteDatabase, editorContentEpoch: Int) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        db.query("PRAGMA table_info(settings)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase()
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                val value = when {
                    name == "id" -> "1"
                    name == "editorContentEpoch" -> editorContentEpoch.toString()
                    !notNull -> "NULL"
                    type.contains("INT") || type.contains("REAL") || type.contains("NUM") -> "0"
                    else -> "''"
                }
                columns += "`$name`"
                values += value
            }
        }
        db.execSQL("INSERT INTO settings(${columns.joinToString()}) VALUES(${values.joinToString()})")
    }
}
