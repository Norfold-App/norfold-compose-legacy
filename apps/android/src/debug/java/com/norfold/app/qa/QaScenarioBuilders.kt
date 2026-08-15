package com.norfold.app.qa

import android.content.Context
import com.norfold.app.data.DocsRepository
import com.norfold.app.data.NorfoldDatabase
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.domain.DocumentOwner
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.WorkspaceObjectType
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.calculation.CellFormat
import com.norfold.app.editor.calculation.ComparisonOperator
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.SpreadsheetHostRuntime
import com.norfold.app.editor.calculation.SpreadsheetWorkbookFactory
import com.norfold.app.editor.chart.ChartDataRow
import com.norfold.app.editor.chart.ChartDataSource
import com.norfold.app.editor.chart.ChartDocument
import com.norfold.app.editor.chart.ChartSeries
import com.norfold.app.editor.chart.ChartType
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentIds
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.GeometryInspector
import com.norfold.app.editor.flow.FlowBlockKind
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.flow.FlowTableCursor
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.relationship.CommentAuthorRef
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.editor.relationship.ThreadId

/** The deterministic starting point a harness entry asks for. */
internal enum class QaScenario { Demo, Empty, Onboarding, Offline }

/**
 * The titles the harness opens by.
 *
 * Ids are not listed here on purpose: every artifact is created by the production writer, which allocates
 * its own row ids, so a scenario resolves what it opens by title instead of asserting an id it did not mint.
 */
internal object QaTitles {
    const val FlowFieldNotes = "QA Field Notes"
    const val FlowDailyLog = "Daily Log"
    const val FlowArchitecture = "Architecture Notes"
    const val FlowReading = "Project Aurora"
    const val QuickNote = "QA Quick Note"
    const val Document = "QA Editorial Brief"
    const val Spreadsheet = "QA Launch Workbook"
    const val Legacy = "QA Legacy Positioned Document"
}

/**
 * Builds each emulator scenario the way a person would: an empty workspace, then content authored through
 * the shipping runtimes and their command registries.
 *
 * Nothing here writes editor rows by hand. The previous harness inserted a fixed corpus straight into Room,
 * which meant the scenarios could stay green while the commands that are supposed to produce that content
 * were broken — the corpus proved only that the corpus could be written. Every block below is produced by
 * the same command a finger would trigger, so a refused command fails the scenario loudly instead of being
 * papered over by a pre-baked row.
 *
 * Debug source set only; nothing in `main` references it.
 */
internal object QaScenarioBuilders {

    private val Phone = FormFactor(widthDp = 400, heightDp = 900)
    private val Owner = CommentAuthorRef(stableId = "qa.owner", displayName = "QA Owner")
    private val Reviewer = CommentAuthorRef(stableId = "mira.reviewer", displayName = "Mira Reviewer")

    // Table structure hooks are addressed by id because the direction-to-id mapping is private to the
    // command file. An id that ever stops resolving surfaces as a refused command, not as a silent no-op.
    private const val InsertRowBelow = FlowIds.TABLE_PREFIX + "insertRowBelow"
    private const val InsertColumnRight = FlowIds.TABLE_PREFIX + "insertColumnRight"

    /**
     * Empties the database, lets the app set itself up, then authors the scenario's content.
     *
     * Returns the workspace id the app chose. The harness used to wait for a hardcoded `1L`, which only held
     * because the corpus inserted that row itself; now the id belongs to production setup and is reported
     * back rather than assumed.
     */
    suspend fun prepare(context: Context, scenario: QaScenario): Long =
        prepare(NorfoldDatabase.get(context), scenario)

    /**
     * The same scenario against an explicit database.
     *
     * The harness always hands over the app's own database. A test hands over an in-memory one, which is the
     * only way to assert what a scenario actually authored without erasing the device's real workspace.
     */
    suspend fun prepare(database: NorfoldDatabase, scenario: QaScenario): Long {
        database.clearAllTables()
        // Room's clear leaves the AUTOINCREMENT counters where they were, so ids would keep climbing across
        // runs and no scenario would be byte-identical to the last one.
        database.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
        val repository = DocsRepository(database)
        // Production first-run setup: workspace, device identity, default task board, editor epoch marks —
        // and, since Stage 0a, no content whatsoever.
        repository.ensureSeedData()
        repository.updateSettings(
            repository.settingsValue().copy(
                onboardingComplete = scenario != QaScenario.Onboarding,
                workspaceName = if (scenario == QaScenario.Empty) "Empty QA Workspace" else "Norfold QA Workspace",
                syncUserName = "qa.owner",
                syncPublicName = "QA Owner",
                // Every scenario runs with no account, no cloud and no network. A scheduled sync waking up
                // mid-session is the classic source of a QA run that cannot be reproduced.
                autoSync = false,
                backgroundSync = false,
                autoBackup = false,
                syncOnMobileData = false,
                notificationPush = false,
                lastSyncStatus = when (scenario) {
                    QaScenario.Offline -> "Offline — changes are saved on this device"
                    else -> "Local QA workspace — sync is off"
                },
            ),
        )
        if (scenario == QaScenario.Demo || scenario == QaScenario.Offline) authorCorpus(repository, database)
        return repository.settingsValue().activeWorkspaceId
    }

    private suspend fun authorCorpus(repository: DocsRepository, database: NorfoldDatabase) {
        buildFieldNotes(repository)
        buildDailyLog(repository)
        buildArchitectureNotes(repository)
        buildReadingSample(repository)
        buildQuickNote(repository)
        val briefNoteId = buildEditorialBrief(repository)
        buildLaunchWorkbook(repository)
        buildLegacyPositioned(repository)
        addWorkspaceReview(repository, database, briefNoteId)
    }

    /**
     * One Flow artifact under construction, driven only through [FlowHostRuntime].
     *
     * Every helper is a thin wrapper over a command or a runtime route, and every one of them checks its
     * result: a scenario that cannot be authored is a bug in the editor, so it must stop here rather than
     * quietly produce a thinner document than the entry claims.
     */
    private class FlowScript(val owner: DocumentOwner, document: BlockDocument) {
        val runtime = FlowHostRuntime(
            controller = FlowEditController(EditTarget(owner.documentId), document),
            initialFormFactor = Phone,
            chartStudioEnabled = true,
            relationshipsEnabled = true,
            importExportRawEnabled = true,
        )

        fun document(): BlockDocument = runtime.controller.document()

        /** Runs a registry command from the dock and refuses to continue if it was not accepted. */
        fun run(commandId: String) {
            val result = runtime.invoke(commandId, CommandSurface.DOCK)
            check(result is CommandResult.Success) { "QA scenario: command $commandId was refused ($result)" }
        }

        /** Inserts [kind] through the `+` palette and returns the id the command put the caret inside. */
        fun insert(kind: FlowBlockKind): String {
            run(FlowIds.insert(kind))
            return runtime.scope.anchorBlockId()
                ?: error("QA scenario: inserting ${kind.label} left no caret to address")
        }

        /** Inserts a text-bearing block and types [value] into it. */
        fun text(kind: FlowBlockKind, value: String): String {
            val id = insert(kind)
            type(id, value)
            return id
        }

        fun type(blockId: String, value: String, itemId: String? = null, before: String = "") {
            check(runtime.editText(blockId, before, value, itemId)) {
                "QA scenario: typing into $blockId${itemId?.let { "/$it" }.orEmpty()} changed nothing"
            }
        }

        /**
         * Inserts a list and types [items] into it, one `Enter` at a time.
         *
         * The first item exists already because the insert command creates a list with one empty item; the
         * rest arrive the only way a person can produce them, by splitting the last item at its end.
         */
        fun list(kind: FlowBlockKind, items: List<String>): String {
            val id = insert(kind)
            items.forEachIndexed { index, value ->
                if (index > 0) {
                    val previous = itemIdsOf(id).last()
                    runtime.setCaret(id, items[index - 1].length, previous)
                    check(runtime.newLine()) { "QA scenario: ${kind.label} refused a new item after $previous" }
                }
                val itemId = itemIdsOf(id).getOrNull(index)
                    ?: error("QA scenario: ${kind.label} has no item at index $index")
                type(id, value, itemId)
            }
            return id
        }

        /** Ticks a to-do item by its position, through the same route the checkbox uses. */
        fun tick(blockId: String, index: Int) {
            val itemId = itemIdsOf(blockId).getOrNull(index) ?: error("QA scenario: no to-do item $index")
            check(runtime.toggleTodo(blockId, itemId)) { "QA scenario: to-do $itemId refused a toggle" }
        }

        /** A quote, whose text lives in its child paragraph rather than on the block itself. */
        fun quote(value: String): String {
            val id = insert(FlowBlockKind.QUOTE)
            val child = (document().findById(id) as? QuoteBlock)?.children?.firstOrNull()
                ?: error("QA scenario: quote $id has no body paragraph")
            type(child.id, value)
            return id
        }

        /** A callout, whose title replaces the default one and whose body is its first child paragraph. */
        fun callout(title: String, body: String, collapsed: Boolean = false): String {
            val id = insert(FlowBlockKind.CALLOUT)
            type(id, title, before = "Note")
            val child = (document().findById(id) as? CalloutBlock)?.children?.firstOrNull()
                ?: error("QA scenario: callout $id has no body paragraph")
            type(child.id, body)
            if (collapsed) check(runtime.toggleCollapsed(id)) { "QA scenario: callout $id refused to collapse" }
            return id
        }

        /** Bolds the first occurrence of [phrase] in [blockId], selection first, exactly like the text dock. */
        fun bold(blockId: String, phrase: String) {
            val text = document().findById(blockId)?.plainText().orEmpty()
            val start = text.indexOf(phrase)
            check(start >= 0) { "QA scenario: \"$phrase\" is not in $blockId" }
            runtime.setTextSelection(blockId, start, start + phrase.length)
            run(FlowIds.BOLD)
            runtime.setCaret(blockId, text.length)
        }

        /**
         * Grows the inserted 2×1 table to fit [headers] and [rows], then fills it cell by cell.
         *
         * Row `-1` is the header row, which is why the column grows are addressed there: the header always
         * exists, and `insert row above` is refused from it by design.
         */
        fun table(headers: List<String>, rows: List<List<String>>): String {
            val id = insert(FlowBlockKind.TABLE)
            runtime.scope.selectBlocks(listOf(id))
            repeat((headers.size - 2).coerceAtLeast(0)) {
                runtime.setTableCursor(FlowTableCursor(id, row = -1, column = columnCount(id) - 1))
                run(InsertColumnRight)
            }
            repeat((rows.size - 1).coerceAtLeast(0)) {
                runtime.setTableCursor(FlowTableCursor(id, row = rowCount(id) - 1, column = 0))
                run(InsertRowBelow)
            }
            headers.forEachIndexed { column, value -> cell(id, row = -1, column = column, value = value) }
            rows.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, value -> cell(id, row = row, column = column, value = value) }
            }
            runtime.setTableCursor(null)
            return id
        }

        private fun cell(blockId: String, row: Int, column: Int, value: String) {
            val result = runtime.editTableCell(FlowTableCursor(blockId, row, column), value)
            check(result.accepted) {
                "QA scenario: cell ($row,$column) refused \"$value\": ${result.error ?: result.warning}"
            }
        }

        /** A chart block plus its data, which is how Chart Studio commits an edit. */
        fun chart(data: ChartDocument): String {
            val id = insert(FlowBlockKind.CHART)
            check(runtime.updateChart(id, data)) { "QA scenario: chart $id refused its data" }
            return id
        }

        /**
         * Wraps [blockId] in a column container, then nests the block below it inside the same container.
         *
         * Two commands in the order the dock exposes them: `Wrap in column` leaves the new container
         * selected, and `Nest` requires a container directly above whatever is being nested. Both address
         * their target as a selected block rather than a caret, so a container can be wrapped again to build
         * a real nested column — which is what the phone golden has to stack back into one stream.
         *
         * The container is left selected so the next insertion lands beside it instead of inside it.
         */
        fun group(blockId: String, nestedId: String): String {
            runtime.scope.selectBlocks(listOf(blockId))
            run(FlowIds.WRAP_COLUMN)
            val container = runtime.scope.primaryBlockId()
                ?: error("QA scenario: wrapping $blockId left nothing selected")
            runtime.scope.selectBlocks(listOf(nestedId))
            run(FlowIds.NEST)
            runtime.scope.selectBlocks(listOf(container))
            return container
        }

        /**
         * Anchors a discussion to [phrase] inside [blockId] and returns the thread it created.
         *
         * The selection is made first because that is what decides the anchor: with a text range the thread
         * anchors to the quoted span, and without one it falls back to the whole block.
         */
        fun comment(blockId: String, phrase: String, author: CommentAuthorRef, body: String): ThreadId {
            val text = document().findById(blockId)?.plainText().orEmpty()
            val start = text.indexOf(phrase)
            check(start >= 0) { "QA scenario: \"$phrase\" is not in $blockId" }
            runtime.setTextSelection(blockId, start, start + phrase.length)
            check(runtime.addComment(author, body)) { "QA scenario: $blockId refused a comment" }
            return runtime.relationshipReview.state().threads.last().id
        }

        fun reply(thread: ThreadId, author: CommentAuthorRef, body: String) {
            checkNotNull(runtime.relationshipReview.reply(thread, author, body)) {
                "QA scenario: thread ${thread.value} refused a reply"
            }
        }

        fun resolve(thread: ThreadId, author: CommentAuthorRef) {
            check(runtime.relationshipReview.resolve(thread, author)) {
                "QA scenario: thread ${thread.value} refused to resolve"
            }
        }

        /** A review snapshot through its registry command, which needs a primary target like every other. */
        fun snapshot(blockId: String) {
            runtime.scope.selectBlocks(listOf(blockId))
            run(RelationshipCommandIds.CREATE_SNAPSHOT)
        }

        private fun columnCount(blockId: String): Int = tableOf(blockId).headers.size

        private fun rowCount(blockId: String): Int = tableOf(blockId).rows.size

        private fun tableOf(blockId: String): TableBlock =
            document().findById(blockId) as? TableBlock ?: error("QA scenario: $blockId is not a table")

        private fun itemIdsOf(blockId: String): List<String> = when (val block = document().findById(blockId)) {
            is BulletListBlock -> block.items.map { it.id }
            is NumberedListBlock -> block.items.map { it.id }
            is TodoListBlock -> block.items.map { it.id }
            else -> emptyList()
        }
    }

    /**
     * Creates one Flow note the way the app does, authors it with [author], then saves it once.
     *
     * The save is the production autosave route, so the layout mode and artifact kind chosen at creation
     * carry through instead of being restated by the harness.
     */
    private suspend fun flowArtifact(repository: DocsRepository, title: String, author: FlowScript.() -> Unit): Long {
        val noteId = repository.createNote(
            title = title,
            body = "",
            layoutMode = DocOverlapMode.Reflow,
            artifactKind = DocumentArtifactKind.Flow,
        )
        val owner = DocumentOwner.note(noteId)
        val stored = repository.documentByOwner(owner) ?: error("QA scenario: no document row for $title")
        val script = FlowScript(owner, stored.document)
        script.author()
        repository.saveDocument(owner, script.document(), artifactKind = DocumentArtifactKind.Flow)
        return noteId
    }

    /** Every Flow block kind that has an insertion command, in one document. */
    private suspend fun buildFieldNotes(repository: DocsRepository) =
        flowArtifact(repository, QaTitles.FlowFieldNotes) {
            text(FlowBlockKind.HEADING_1, "Field notes")
            val intro = text(FlowBlockKind.PARAGRAPH, "Recorded on site during the second pilot week.")
            bold(intro, "second pilot week")
            text(FlowBlockKind.HEADING_2, "Observations")
            list(
                FlowBlockKind.BULLET_LIST,
                listOf(
                    "The scanner wakes in under a second",
                    "Two operators shared one bench",
                    "Labels peeled after washing",
                ),
            )
            text(FlowBlockKind.HEADING_3, "Follow-ups")
            list(
                FlowBlockKind.NUMBERED_LIST,
                listOf("Re-test the label adhesive", "Measure bench turnaround", "Drain the offline queue"),
            )
            val todo = list(FlowBlockKind.TODO_LIST, listOf("File the pilot summary", "Book the follow-up visit"))
            tick(todo, index = 0)
            // All six heading levels appear because each one carries its own type token. A content-scale rule
            // that is only ever checked at heading 1 is not really checked at all.
            text(FlowBlockKind.HEADING_4, "Bench detail")
            text(FlowBlockKind.HEADING_5, "Rota")
            text(FlowBlockKind.HEADING_6, "Sign-off")
            quote("The bench is the bottleneck, not the scanner.")
            callout("Bench rota", "Two operators, one bench, alternating half-hours.")
            callout("Archive detail", "Kept for the record, collapsed so the page stays readable.", collapsed = true)
            text(FlowBlockKind.CODE, "queue.drain(timeout = 30.seconds)\n    .onFailure(::park)")
            text(FlowBlockKind.MATH, "t_{turnaround} = \\frac{n_{items}}{r_{scan}}")
            text(FlowBlockKind.MERMAID, "flowchart TD\n  Intake --> Bench\n  Bench --> Dispatch")
            insert(FlowBlockKind.DIVIDER)
            table(
                headers = listOf("Day", "Items", "Minutes"),
                rows = listOf(
                    listOf("Monday", "184", "96"),
                    listOf("Tuesday", "201", "102"),
                    listOf("Wednesday", "173", "88"),
                ),
            )
            chart(throughputChart())
            val left = text(FlowBlockKind.PARAGRAPH, "Bench A held the pace all week.")
            val right = text(FlowBlockKind.PARAGRAPH, "Bench B lost time to relabelling.")
            group(left, right)

            // Two threads, one open and one resolved, so the review surface has both states to render, plus a
            // snapshot taken after them: a restore has to be able to land on review state that already exists.
            val open = comment(intro, "second pilot week", Reviewer, "Was this the same crew as week one?")
            reply(open, Owner, "Same crew, one extra bench.")
            val settled = comment(left, "held the pace", Reviewer, "Worth calling out in the summary.")
            reply(settled, Owner, "Added to the follow-ups.")
            resolve(settled, Owner)
            snapshot(intro)
        }

    /** Nested columns, for the phone golden that has to stack them into one writing-first stream. */
    private suspend fun buildDailyLog(repository: DocsRepository) =
        flowArtifact(repository, QaTitles.FlowDailyLog) {
            text(FlowBlockKind.HEADING_1, "Daily log")
            text(FlowBlockKind.PARAGRAPH, "Two columns on a wide screen, one stream on a phone.")
            val morning = text(FlowBlockKind.HEADING_2, "Morning")
            val morningBody = text(FlowBlockKind.PARAGRAPH, "Intake cleared by ten. Nothing waiting at the bench.")
            val inner = group(morning, morningBody)
            val afternoon = text(FlowBlockKind.PARAGRAPH, "Relabelling took the last hour of the afternoon.")
            group(inner, afternoon)
            text(FlowBlockKind.PARAGRAPH, "Signed off by the bench lead.")
        }

    /** A single source-or-preview representation area, which is all this golden is about. */
    private suspend fun buildArchitectureNotes(repository: DocsRepository) =
        flowArtifact(repository, QaTitles.FlowArchitecture) {
            text(FlowBlockKind.HEADING_1, "Architecture notes")
            text(FlowBlockKind.PARAGRAPH, "One diagram, shown as source or as preview but never as both.")
            text(
                FlowBlockKind.MERMAID,
                "flowchart LR\n  Editor --> Runtime\n  Runtime --> Repository\n  Repository --> Room",
            )
        }

    /** Prose only: the reading-mode golden must show content until an explicit edit interaction. */
    private suspend fun buildReadingSample(repository: DocsRepository) =
        flowArtifact(repository, QaTitles.FlowReading) {
            text(FlowBlockKind.HEADING_1, "Project Aurora")
            text(
                FlowBlockKind.PARAGRAPH,
                "Aurora replaces the paper bench sheet with one record per item, written once at intake.",
            )
            text(FlowBlockKind.HEADING_2, "Why now")
            text(
                FlowBlockKind.PARAGRAPH,
                "The bench sheet is copied three times before anyone reads it, and each copy loses something.",
            )
            quote("Write it once, where the work happens.")
            list(
                FlowBlockKind.BULLET_LIST,
                listOf("One record per item", "Written at intake", "Read anywhere"),
            )
        }

    /** The short note every workspace accumulates, and the smallest artifact the list has to render. */
    private suspend fun buildQuickNote(repository: DocsRepository) =
        flowArtifact(repository, QaTitles.QuickNote) {
            text(FlowBlockKind.PARAGRAPH, "Bench key is in the second drawer.")
        }

    private fun throughputChart(): ChartDocument = ChartDocument(
        type = ChartType.BAR,
        title = "Items scanned per day",
        source = ChartDataSource.Manual(
            rows = listOf(
                ChartDataRow(id = "row-monday", values = listOf("Monday", "184")),
                ChartDataRow(id = "row-tuesday", values = listOf("Tuesday", "201")),
                ChartDataRow(id = "row-wednesday", values = listOf("Wednesday", "173")),
            ),
        ),
    )

    /**
     * The bounded, positioned document: two pages built by the document host's own insert commands.
     *
     * Objects are inserted and placed first, and text is typed afterwards, because content editing switches
     * the host into `EDIT` intent and object insertion addresses a page. Doing it in this order keeps every
     * step on the route the toolbar uses instead of forcing intent back and forth.
     *
     * Returns the note id, which the review corpus then hangs its comments on.
     */
    private suspend fun buildEditorialBrief(repository: DocsRepository): Long {
        val noteId = repository.createNote(
            title = QaTitles.Document,
            body = "",
            layoutMode = DocOverlapMode.Bounded,
            artifactKind = DocumentArtifactKind.Document,
        )
        val owner = DocumentOwner.note(noteId)
        val stored = repository.documentByOwner(owner)
            ?: error("QA scenario: no document row for ${QaTitles.Document}")
        val controller = DocumentSceneController(EditTarget(owner.documentId), stored.document)
        val runtime = DocumentHostRuntime(
            controller = controller,
            initialFormFactor = Phone,
            chartStudioEnabled = true,
            relationshipsEnabled = true,
            importExportRawEnabled = true,
        )
        // The same first-open migration the bounded editor runs, with the controller's own recovery manager
        // so the pre-image lands where a restore can find it.
        runtime.migrateLegacyDocument(
            layout = stored.freeformLayout,
            canvas = stored.canvasSpec,
            mode = stored.layoutMode,
            recovery = controller.recovery(),
        )
        val title = insertObject(runtime, DocumentIds.INSERT_TEXT, x = 54f, y = 54f, width = 470f, height = 56f)
        val body = insertObject(runtime, DocumentIds.INSERT_TEXT, x = 54f, y = 132f, width = 470f, height = 150f)
        insertObject(runtime, DocumentIds.INSERT_CHART, x = 54f, y = 306f, width = 300f, height = 200f)
        val caption = insertObject(runtime, DocumentIds.INSERT_TEXT, x = 296f, y = 470f, width = 240f, height = 70f)
        // Inserted last so it lands on top of the caption, then sent behind it: the overlap this scenario is
        // for is produced by the layering command rather than by a hand-written z value.
        insertObject(runtime, DocumentIds.INSERT_SHAPE, x = 320f, y = 440f, width = 180f, height = 140f)
        command(runtime, DocumentIds.SEND_TO_BACK)
        command(runtime, DocumentIds.PAGE_INSERT_AFTER)
        val appendix = insertObject(runtime, DocumentIds.INSERT_TEXT, x = 54f, y = 54f, width = 470f, height = 130f)
        typeObject(runtime, title, "Editorial brief")
        typeObject(runtime, body, "One positioned page, one appendix page, and one shape behind a caption.")
        typeObject(runtime, caption, "The shape sits behind this caption.")
        typeObject(runtime, appendix, "Appendix: bench readings, collected during the second pilot week.")
        repository.saveDocument(
            owner,
            controller.document(),
            layoutMode = DocOverlapMode.Bounded,
            artifactKind = DocumentArtifactKind.Document,
        )
        return noteId
    }

    private fun command(runtime: DocumentHostRuntime, commandId: String) {
        val result = runtime.invoke(commandId, CommandSurface.DOCK)
        check(result is CommandResult.Success) { "QA scenario: command $commandId was refused ($result)" }
    }

    /**
     * Inserts one scene object and places it through the geometry inspector.
     *
     * The inspector route rather than a direct geometry write: a typed coordinate and a dragged corner are
     * meant to reach history the same way, so the scenario uses the one a person has.
     */
    private fun insertObject(
        runtime: DocumentHostRuntime,
        commandId: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): String {
        command(runtime, commandId)
        val objectId = runtime.scope.primaryObjectId()
            ?: error("QA scenario: $commandId inserted nothing that could be selected")
        place(runtime, GeometryInspector.Field.WIDTH, width)
        place(runtime, GeometryInspector.Field.HEIGHT, height)
        place(runtime, GeometryInspector.Field.X, x)
        place(runtime, GeometryInspector.Field.Y, y)
        return objectId
    }

    private fun place(runtime: DocumentHostRuntime, field: GeometryInspector.Field, value: Float) {
        check(runtime.applyGeometryField(field, value.toString())) {
            "QA scenario: ${field.label} refused $value (${runtime.lastFailure ?: "unchanged"})"
        }
    }

    /** Types into a scene object the way a double-tap does: enter content edit, then edit the text block. */
    private fun typeObject(runtime: DocumentHostRuntime, objectId: String, value: String) {
        check(runtime.beginContentEdit(objectId)) { "QA scenario: $objectId refused content editing" }
        val blockId = sceneObject(runtime, objectId).content.id
        check(runtime.editObjectText(objectId, blockId, null, "", value)) {
            "QA scenario: typing into $objectId changed nothing"
        }
    }

    private fun sceneObject(runtime: DocumentHostRuntime, objectId: String): SceneObject =
        runtime.document().blocks.filterIsInstance<DocumentPageBlock>()
            .firstNotNullOfOrNull { it.objectById(objectId) }
            ?: error("QA scenario: no scene object $objectId on any page")

    /**
     * The spreadsheet artifact: created exactly as the app's "New spreadsheet" does, then filled through the
     * grid's own routes — paste, formula bar, validation, conditional format, freeze, second sheet, chart.
     */
    private suspend fun buildLaunchWorkbook(repository: DocsRepository) {
        val block = SpreadsheetWorkbookFactory.createBlock(QaTitles.Spreadsheet)
        val noteId = repository.createNote(
            title = QaTitles.Spreadsheet,
            body = "",
            layoutMode = DocOverlapMode.Reflow,
            artifactKind = DocumentArtifactKind.Spreadsheet,
        )
        val owner = DocumentOwner.note(noteId)
        val note = repository.noteById(noteId) ?: error("QA scenario: no note row for ${QaTitles.Spreadsheet}")
        repository.updateNote(
            note,
            QaTitles.Spreadsheet,
            BlockDocument(listOf(block)),
            setOf(block.id),
            artifactKind = DocumentArtifactKind.Spreadsheet,
        )
        val controller = FlowEditController(EditTarget(owner.documentId), BlockDocument(listOf(block)))
        val runtime = SpreadsheetHostRuntime(controller, tableBlockId = block.id)
        val mainSheet = runtime.state().workbook.sheets.first().id

        runtime.select(GridAddress(0, 0))
        pasteGrid(
            runtime,
            listOf(
                listOf("Area", "Owner", "Score", "Status"),
                listOf("Intake", "Mira", "94", "Ready"),
                listOf("Bench", "Ade", "88", "At risk"),
                listOf("Labelling", "Jo", "72", "Blocked"),
                listOf("Dispatch", "Sam", "91", "Ready"),
                listOf("Returns", "Kit", "85", "At risk"),
            ),
        )
        runtime.select(GridAddress(6, 2))
        check(runtime.commitFormulaBar("=SUM(C2:C6)").accepted) { "QA scenario: the total formula was refused" }
        runtime.select(GridAddress(7, 2))
        check(runtime.commitFormulaBar("=AVERAGE(C2:C6)").accepted) { "QA scenario: the mean formula was refused" }

        selectRange(runtime, GridAddress(1, 2), GridAddress(5, 2))
        check(runtime.defineNamedRange("ReadinessScores")) { "QA scenario: the named range was refused" }
        check(
            runtime.applyComparisonConditional(
                ComparisonOperator.GREATER_OR_EQUAL,
                "90",
                CellFormat(fillColor = ColorValue(red = 184, green = 216, blue = 184)),
            ),
        ) { "QA scenario: the conditional format was refused" }

        // Validation is applied after the paste, not before: a rule written first would reject the very
        // values this scenario is seeding.
        selectRange(runtime, GridAddress(1, 3), GridAddress(5, 3))
        check(runtime.applyListValidation(listOf("Ready", "At risk", "Blocked"))) {
            "QA scenario: list validation was refused"
        }

        runtime.select(GridAddress(1, 0))
        check(runtime.freezeAtSelection()) { "QA scenario: the freeze was refused" }

        check(runtime.addSheet()) { "QA scenario: the second sheet was refused" }
        check(runtime.renameActiveSheet("Lookups")) { "QA scenario: renaming the second sheet was refused" }
        runtime.select(GridAddress(0, 0))
        pasteGrid(
            runtime,
            listOf(
                listOf("Status", "Weight"),
                listOf("Ready", "1"),
                listOf("At risk", "0.5"),
                listOf("Blocked", "0"),
            ),
        )

        runtime.activateSheet(mainSheet)
        selectRange(runtime, GridAddress(0, 0), GridAddress(5, 2))
        checkNotNull(runtime.createChartFromSelection()) { "QA scenario: the chart binding was refused" }
        repository.saveDocument(
            owner,
            controller.document(),
            layoutMode = DocOverlapMode.Reflow,
            artifactKind = DocumentArtifactKind.Spreadsheet,
        )
    }

    private fun selectRange(runtime: SpreadsheetHostRuntime, from: GridAddress, to: GridAddress) {
        runtime.select(from)
        runtime.select(to, extend = true)
    }

    /** Bulk cell entry through the grid's paste route, which is the only bulk entry a person has. */
    private fun pasteGrid(runtime: SpreadsheetHostRuntime, rows: List<List<String>>) {
        val report = runtime.paste(rows.joinToString("\n") { it.joinToString("\t") })
        check(report.rejectedCount == 0) { "QA scenario: ${report.rejectedCount} pasted cells were refused" }
    }

    /**
     * A legacy positioned artifact: blocks authored by command, then persisted with the absolute layout
     * columns that only backup restore and shared-document import still supply.
     *
     * Nothing in the editor writes `layout_json` any more, so this is deliberately built the way an old
     * artifact arrives rather than the way a new one is made — that arrival path is exactly what the
     * migration golden has to prove it can open, migrate, and leave unchanged on disk until an edit.
     */
    private suspend fun buildLegacyPositioned(repository: DocsRepository) {
        val noteId = repository.createNote(
            title = QaTitles.Legacy,
            body = "",
            layoutMode = DocOverlapMode.Overlap,
            artifactKind = DocumentArtifactKind.Document,
        )
        val owner = DocumentOwner.note(noteId)
        val stored = repository.documentByOwner(owner) ?: error("QA scenario: no document row for ${QaTitles.Legacy}")
        val script = FlowScript(owner, stored.document)
        val heading = script.text(FlowBlockKind.HEADING_1, "Positioned brief")
        val body = script.text(
            FlowBlockKind.PARAGRAPH,
            "Placed by absolute coordinates before reflow existed. Overlaps the heading on purpose.",
        )
        repository.saveDocument(
            owner,
            script.document(),
            layoutMode = DocOverlapMode.Overlap,
            freeformLayout = mapOf(
                heading to FreeformPlacement(x = 48f, y = 56f, width = 420f, height = 72f, z = 0),
                body to FreeformPlacement(x = 96f, y = 104f, width = 380f, height = 180f, z = 1),
            ),
            canvasSpec = DocCanvasSpec(),
            artifactKind = DocumentArtifactKind.Document,
        )
    }

    /**
     * Workspace-level discussion on the brief, through the repository route the workspace review panel uses.
     *
     * The workspace object id is read back rather than assumed: [DocsRepository.createNote] mints the object
     * when the note is created, and reading its id is not the same as writing content behind the commands.
     * Each comment also records its own activity and history row, so no row here is hand-inserted.
     */
    private suspend fun addWorkspaceReview(repository: DocsRepository, database: NorfoldDatabase, noteId: Long) {
        val workspaceId = repository.settingsValue().activeWorkspaceId
        val objectId = database.dao().workspaceObjectIdForSource(workspaceId, WorkspaceObjectType.Note.name, noteId)
            ?: error("QA scenario: note $noteId has no workspace object to discuss")
        repository.addWorkspaceComment(objectId, "Ready for a copy pass on the appendix.", Reviewer.stableId, Reviewer.displayName)
        repository.addWorkspaceComment(objectId, "Appendix rewritten — take another look.", Owner.stableId, Owner.displayName)
    }
}

