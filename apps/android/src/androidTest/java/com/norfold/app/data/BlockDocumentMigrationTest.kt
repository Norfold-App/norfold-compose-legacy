package com.norfold.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.MarkdownBlockCodec
import com.norfold.app.domain.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockDocumentMigrationTest {
    private val databaseName = "block-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NorfoldDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun markdownNotesBecomeOrderedBlockRowsWithoutRawMarkdownColumn() {
        helper.createDatabase(databaseName, 25).apply {
            val markdown = """
                # Guide

                A **bold** paragraph with [the product](https://norfold.app).

                - Parent
                  - Nested child

                - [x] Kept

                ![Workspace diagram](content://norfold/demo.png)

                | A | B |
                | --- | --- |
                | 1 | 2 |

                ```kotlin
                data class Note(val id: Long)
                ```
            """.trimIndent()
            execSQL(
                """
                INSERT INTO notes(id, title, bodyMarkdown, notebookId, coverUri, coverMimeType, pinned, starred, archived, locked, createdAt, updatedAt, workspaceId)
                VALUES(41, 'Migration guide', ?, NULL, NULL, NULL, 0, 0, 0, 0, 1, 2, 1)
                """.trimIndent(),
                arrayOf(markdown),
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 26, true, NorfoldDatabase.MIGRATION_25_26).use { db ->
            db.query("PRAGMA table_info(notes)").use { cursor ->
                val names = buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                assertTrue("searchText" in names)
                assertFalse("bodyMarkdown" in names)
            }
            db.query("SELECT searchText FROM notes WHERE id = 41").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).contains("bold paragraph"))
            }
            db.query("SELECT payloadJson FROM note_blocks WHERE noteId = 41 ORDER BY position").use { cursor ->
                val blocks = mutableListOf<com.norfold.app.domain.DocumentBlock>()
                while (cursor.moveToNext()) {
                    blocks += BlockDocumentJson.decodeBlock(cursor.getString(0))
                }
                val migrated = BlockDocument(blocks)
                assertTrue(blocks.size >= 6)
                assertTrue(blocks.any { it is TableBlock })
                assertTrue(blocks.any { it is ImageBlock })
                assertTrue(blocks.any { it is CodeBlock && it.language == "kotlin" })
                assertTrue("Migrated text: ${migrated.plainText()}\nExport: ${MarkdownBlockCodec.export(migrated)}", migrated.plainText().contains("Nested child"))
                val stable = MarkdownBlockCodec.import(MarkdownBlockCodec.export(migrated))
                assertEquals(MarkdownBlockCodec.export(migrated), MarkdownBlockCodec.export(stable))
            }
        }
    }

    @Test
    fun contextualMenuPreferencesDefaultSafelyFromVersion26() {
        val name = "menu-preference-migration-test"
        helper.createDatabase(name, 26).close()

        helper.runMigrationsAndValidate(name, 27, true, NorfoldDatabase.MIGRATION_26_27).use { db ->
            db.query("PRAGMA table_info(settings)").use { cursor ->
                val defaults = buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")).orEmpty().trim('\''),
                        )
                    }
                }
                assertEquals("Pill", defaults["contextualMenuStyle"])
                assertEquals("FollowTheme", defaults["contextualMenuColor"])
            }
        }
    }

    @Test
    fun tagsBecomeCaseInsensitiveAndBoardScopedFromVersion27() {
        val name = "board-tag-migration-test"
        helper.createDatabase(name, 27).apply {
            execSQL("INSERT INTO tags(id, name, color) VALUES(1, 'Planning', 1)")
            execSQL("INSERT INTO tags(id, name, color) VALUES(2, 'planning', 2)")
            execSQL("INSERT INTO tags(id, name, color) VALUES(3, 'task:7:#Roadmap', 3)")
            close()
        }

        helper.runMigrationsAndValidate(name, 28, true, NorfoldDatabase.MIGRATION_27_28).use { db ->
            db.query("SELECT name, scope, normalizedName FROM tags ORDER BY scope, normalizedName").use { cursor ->
                val rows = buildList {
                    while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
                assertEquals(2, rows.size)
                assertTrue(rows.contains(Triple("Roadmap", "board:7", "roadmap")))
                assertTrue(rows.any { it.second == "notes" && it.third == "planning" })
            }
        }
    }
}
