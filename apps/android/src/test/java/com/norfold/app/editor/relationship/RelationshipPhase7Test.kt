package com.norfold.app.editor.relationship

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceLinkType
import com.norfold.app.domain.WorkspaceObjectLink
import com.norfold.app.domain.findById
import com.norfold.app.domain.relationshipDataBlock
import com.norfold.app.domain.withRelationshipData
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentBackResult
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.LegacyDocumentMigration
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowBackResult
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipPhase7Test {
    private val author = CommentAuthorRef("user-1", "Amina")

    private data class Fixture(
        val controller: FlowEditController,
        val runtime: RelationshipReviewRuntime,
    )

    private fun fixture(document: BlockDocument = BlockDocument(listOf(paragraph("a", "Alpha"), paragraph("b", "Beta")))): Fixture {
        var now = 100L
        var id = 0
        val controller = FlowEditController(EditTarget("note:1"), document)
        val runtime = RelationshipReviewRuntime(
                artifactId = "note:1",
                enabled = true,
                documentProvider = controller::document,
                mutateDocument = controller::mutateDocument,
                protectBeforeRestore = { controller.snapshotBeforeDestructiveEdit(it) },
                clock = { now++ },
                idGenerator = { prefix -> "$prefix-${id++}" },
            )
        controller.setDocumentInvariant(runtime::reconcileEndpointsIn)
        return Fixture(controller, runtime)
    }

    private fun paragraph(id: String, text: String) = ParagraphBlock(id, listOf(InlineText(text)))
    private fun endpoint(block: String) = RelationshipEndpoint(RelationshipAnchor.Block("note:1", block))

    @Test fun relationshipIdentityDirectionCustomTypeAndDeterministicRoundTrip() {
        val f = fixture()
        val first = f.runtime.createRelationship(endpoint("a"), endpoint("b"), RelationshipKinds.DEPENDS_ON)!!
        val custom = RelationshipKinds.custom("Supports launch", directed = true)
        val second = f.runtime.createRelationship(endpoint("b"), endpoint("a"), custom)!!
        assertNotEquals(first.id, second.id)
        assertEquals(RelationshipDirection.DIRECTED, first.kind.direction)
        assertTrue(second.kind.custom)
        val encoded = f.controller.document().relationshipDataBlock()!!.payload
        assertEquals(encoded, Phase7ArtifactCodec.encode(Phase7ArtifactCodec.decode(encoded)))
        assertEquals(first.source.stableId, Phase7ArtifactCodec.decode(encoded).relationships.first().source.stableId)
    }

    @Test fun futureAndUnknownPayloadsArePreservedExactly() {
        val future = "{\"v\":999,\"future\":{\"keep\":true}}"
        assertEquals(future, Phase7ArtifactCodec.encode(Phase7ArtifactCodec.decode(future)))
        val state = Phase7ArtifactState(unknownTopLevel = buildJsonObject { put("futureHint", JsonPrimitive("kept")) })
        val encoded = Phase7ArtifactCodec.encode(state)
        assertEquals("kept", Phase7ArtifactCodec.decode(encoded).unknownTopLevel["futureHint"]?.let { (it as JsonPrimitive).content })
    }

    @Test fun indexedSourceTargetAndArtifactLookupDoNotDuplicateResults() {
        val f = fixture()
        f.runtime.createRelationship(endpoint("a"), endpoint("b"))
        assertEquals(1, f.runtime.relationshipsFor(RelationshipAnchor.Block("note:1", "a")).size)
        assertEquals(1, f.runtime.relationshipsFor(RelationshipAnchor.Block("note:1", "b")).size)
        assertEquals(1, f.runtime.relationshipsForArtifact().size)
        assertEquals(5, f.runtime.metrics.indexLookups)
    }

    @Test fun connectModeIsTemporaryContextualAndCancelMutatesNothing() {
        val f = fixture()
        val before = f.controller.document()
        assertTrue(f.runtime.enterConnectMode(endpoint("a")))
        assertNull(f.runtime.commitConnectTarget(endpoint("a")))
        f.runtime.exitConnectMode()
        assertEquals(before, f.controller.document())
        assertNull(f.runtime.connectMode)
        assertFalse(f.controller.canUndo())
    }

    @Test fun connectCommitCreatesOneRelationshipAndExitsWithoutPermanentPortState() {
        val f = fixture()
        assertTrue(f.runtime.enterConnectMode(endpoint("a"), RelationshipKinds.BLOCKS))
        val created = f.runtime.commitConnectTarget(endpoint("b"))!!
        assertEquals(RelationshipKinds.BLOCKS.id, created.kind.id)
        assertNull(f.runtime.connectMode)
        assertEquals(1, f.runtime.state().relationships.size)
        assertEquals(1, f.controller.undoDepth())
    }

    @Test fun duplicateRelationshipIsRefused() {
        val f = fixture()
        assertNotNull(f.runtime.createRelationship(endpoint("a"), endpoint("b")))
        assertNull(f.runtime.createRelationship(endpoint("a"), endpoint("b")))
        assertEquals(1, f.runtime.state().relationships.size)
    }

    @Test fun brokenEndpointIsExplicitRemovableAndRebindable() {
        val f = fixture()
        val relationship = f.runtime.createRelationship(endpoint("a"), endpoint("b"))!!
        f.controller.mutateDocument("Delete target") { it.copy(blocks = it.blocks.filterNot { block -> block.id == "b" }) }
        assertTrue(f.runtime.state().relationships.single().target.broken)
        assertTrue(f.runtime.rebind(relationship.id, endpoint("b").stableId, endpoint("a")))
        assertFalse(f.runtime.state().relationships.single().target.broken)
        assertTrue(f.runtime.removeRelationship(relationship.id))
        assertTrue(f.runtime.state().relationships.isEmpty())
    }

    @Test fun relationshipIdentitySurvivesFlowMoveAndDocumentObjectTransform() {
        val f = fixture()
        val relation = f.runtime.createRelationship(endpoint("a"), endpoint("b"))!!
        f.controller.mutate("Move") { session ->
            session.move("a", com.norfold.app.domain.DropTarget.Into(null, 2))
            com.norfold.app.domain.BlockCursor("a", 0)
        }
        assertEquals(relation.source.stableId, f.runtime.state().relationships.single().source.stableId)

        val page = DocumentPageBlock(
            id = "p1",
            geometry = PageGeometry.a4(),
            objects = listOf(SceneObject("o1", geometry = SceneGeometry(1f, 2f, 100f, 60f), content = paragraph("content", "Text"))),
        ).normalized()
        val host = DocumentHostRuntime(DocumentSceneController(EditTarget("doc:1"), BlockDocument(listOf(page))), FormFactor(900, 700), relationshipsEnabled = true)
        host.selectObjects(listOf("o1"))
        assertTrue(host.relationshipReview.enterConnectMode(RelationshipEndpoint(RelationshipAnchor.SceneObject("doc:1", "p1", "o1"))))
        host.relationshipReview.exitConnectMode()
        assertTrue(host.controller.moveObjects(listOf("o1"), 20f, 30f))
        assertEquals("o1", host.controller.findObject("o1")!!.second.id)
    }

    @Test fun connectorInvalidationTouchesOnlyMovedEndpoints() {
        val f = fixture()
        val first = f.runtime.createRelationship(endpoint("a"), endpoint("b"))!!
        f.runtime.createRelationship(endpoint("b"), RelationshipEndpoint(RelationshipAnchor.Artifact("note:1")))
        val affected = f.runtime.connectorGeometryInvalidated(setOf(first.source.stableId))
        assertEquals(listOf(first.id), affected)
        assertEquals(1, f.runtime.metrics.affectedConnectorsRecalculated)
    }

    @Test fun discussionSupportsStableIdsRepliesMentionsEditResolveAndReopen() {
        val f = fixture()
        val mention = CommentMention("user-2", "@Rafi")
        val thread = f.runtime.addThread(CommentAnchor.Block("note:1", "a"), author, "Please check", listOf(mention))!!
        val reply = f.runtime.reply(thread.id, CommentAuthorRef("user-2", "Rafi"), "Looks good", replyTo = thread.comments.first().id)!!
        assertNotEquals(thread.comments.first().id, reply.id)
        assertEquals("user-2", f.runtime.state().threads.single().comments.last().author.stableId)
        assertEquals(mention, f.runtime.state().threads.single().comments.first().mentions.single())
        assertTrue(f.runtime.editComment(thread.comments.first().id, author.stableId, "Please check this"))
        assertTrue(f.runtime.resolve(thread.id, author))
        assertEquals(CommentStatus.RESOLVED, f.runtime.state().threads.single().status)
        assertTrue(f.runtime.reopen(thread.id))
        assertEquals(CommentStatus.OPEN, f.runtime.state().threads.single().status)
    }

    @Test fun textAnchorsShiftShrinkDetachAndRepair() {
        val anchor = CommentAnchor.Text("note:1", "a", 6, 11, "world", prefix = "hello ")
        assertEquals(8, TextAnchorRepair.apply(anchor, TextEdit(0, 0, "++"), "++hello world").start)
        assertEquals(4, TextAnchorRepair.apply(anchor, TextEdit(0, 2, ""), "llo world").start)
        assertEquals(AnchorState.DETACHED, TextAnchorRepair.apply(anchor, TextEdit(0, 11, ""), "").state)
        val repaired = TextAnchorRepair.repairByContext(anchor.copy(state = AnchorState.DETACHED), "again world here")
        assertEquals(AnchorState.ATTACHED, repaired.state)
        assertEquals(6, repaired.start)
    }

    @Test fun textAnchorRepairIsFoldedIntoTheSameTypingHistoryStep() {
        val f = fixture(BlockDocument(listOf(paragraph("a", "hello world"))))
        val thread = f.runtime.addThread(CommentAnchor.Text("note:1", "a", 6, 11, "world"), author, "Anchor")!!
        val host = FlowHostRuntime(f.controller, FormFactor(400, 800), relationshipsEnabled = true)
        val depth = f.controller.undoDepth()
        assertTrue(host.editText("a", "hello world", "hello brave world"))
        assertEquals(depth + 1, f.controller.undoDepth())
        val repaired = host.relationshipReview.state().threads.first { it.id == thread.id }.anchor as CommentAnchor.Text
        assertEquals("world", repaired.quote)
        assertEquals(12, repaired.start)
    }

    @Test fun reviewSnapshotCapturesListsAndRestoresWithRecoveryAndNewerDiscussion() {
        val f = fixture(BlockDocument(listOf(paragraph("a", "Before"))))
        val snapshot = f.runtime.createReviewSnapshot("Baseline", author)!!
        assertEquals(1, f.runtime.state().snapshots.size)
        f.controller.mutateDocument("Edit") { it.copy(blocks = it.blocks.map { block -> if (block.id == "a") paragraph("a", "After") else block }) }
        val newer = f.runtime.addThread(CommentAnchor.Block("note:1", "a"), author, "Newer discussion")!!
        val depth = f.controller.undoDepth()
        assertTrue(f.runtime.restoreReviewSnapshot(snapshot.id))
        assertEquals("Before", f.controller.document().findById("a")!!.plainText())
        assertTrue(f.runtime.state().threads.any { it.id == newer.id })
        assertEquals(depth + 1, f.controller.undoDepth())
        assertTrue(f.controller.recoverySnapshots().isNotEmpty())
        assertTrue(f.runtime.snapshotSummary(snapshot.id)!!.contains("Baseline"))
    }

    @Test fun corruptReviewSnapshotIsRefusedWithoutMutation() {
        val f = fixture()
        val good = f.runtime.createReviewSnapshot()!!
        val current = f.runtime.state()
        val bad = good.copy(contentHash = "bad")
        f.controller.mutateDocument("Inject corrupt snapshot") {
            it.withRelationshipData(Phase7ArtifactCodec.encode(current.copy(snapshots = listOf(bad))))
        }
        val before = f.controller.document()
        assertFalse(f.runtime.restoreReviewSnapshot(bad.id))
        assertEquals(before, f.controller.document())
    }

    @Test fun workspaceLinkBridgePreservesOneIdentityAndNoDuplicateBacklink() {
        val legacy = WorkspaceObjectLink(42, 10, 20, WorkspaceLinkType.Reference, "spec", 5, 3)
        val first = WorkspaceRelationshipBridge.relationship(legacy)
        val second = WorkspaceRelationshipBridge.relationship(legacy)
        assertEquals(first.id, second.id)
        assertEquals(42L, first.metadata.legacyWorkspaceLinkId)
        assertEquals(legacy, WorkspaceRelationshipBridge.workspaceLink(first, 3))
    }

    @Test fun workspaceCommentBridgeKeepsLegacyCommentReadable() {
        val legacy = WorkspaceComment(8, 10, "amina", "Amina", "Legacy", false, 1, 2, 3)
        val thread = WorkspaceRelationshipBridge.thread(legacy)
        assertEquals("Legacy", thread.comments.single().body)
        assertEquals(8L, thread.comments.single().legacyWorkspaceCommentId)
        assertEquals(legacy.copy(updatedAt = 2), WorkspaceRelationshipBridge.workspaceComment(thread, 3))
    }

    @Test fun featureFlagOffHidesRoutesAndOnRegistersSemanticParity() {
        val controllerOff = FlowEditController(EditTarget("off"), BlockDocument(listOf(paragraph("a", "A"))))
        val off = FlowHostRuntime(controllerOff, FormFactor(400, 800), relationshipsEnabled = false)
        off.scope.selectBlocks(listOf("a"))
        assertNull(off.registry.get(RelationshipCommandIds.ENTER_CONNECT))
        assertTrue(off.relationshipReview.state().relationships.isEmpty())

        val controllerOn = FlowEditController(EditTarget("on"), BlockDocument(listOf(paragraph("a", "A"), paragraph("b", "B"))))
        val on = FlowHostRuntime(controllerOn, FormFactor(400, 800), relationshipsEnabled = true)
        on.scope.selectBlocks(listOf("a"))
        assertNotNull(on.registry.get(RelationshipCommandIds.ENTER_CONNECT))
        assertNotNull(on.registry.get(RelationshipCommandIds.ADD_COMMENT))
        assertNotNull(on.registry.get(RelationshipCommandIds.RESTORE_SNAPSHOT))
        assertTrue(on.accessibilityActions().any { it.id == RelationshipCommandIds.ENTER_CONNECT })
    }

    @Test fun documentHostConnectSelectionCommitsSemanticSceneEndpoints() {
        val page = DocumentPageBlock(
            id = "p",
            geometry = PageGeometry.a4(),
            objects = listOf(
                SceneObject("one", geometry = SceneGeometry(0f, 0f, 100f, 100f), content = paragraph("c1", "One")),
                SceneObject("two", geometry = SceneGeometry(140f, 0f, 100f, 100f), content = paragraph("c2", "Two")),
            ),
        ).normalized()
        val host = DocumentHostRuntime(DocumentSceneController(EditTarget("doc"), BlockDocument(listOf(page))), FormFactor(900, 700), relationshipsEnabled = true)
        host.selectObjects(listOf("one"))
        assertTrue(host.relationshipReview.enterConnectMode(RelationshipEndpoint(RelationshipAnchor.SceneObject("doc", "p", "one"))))
        host.selectObjects(listOf("two"))
        val relation = host.relationshipReview.state().relationships.single()
        assertEquals("one", (relation.source.anchor as RelationshipAnchor.SceneObject).objectId)
        assertEquals("two", (relation.target.anchor as RelationshipAnchor.SceneObject).objectId)
        assertNull(host.relationshipReview.connectMode)
    }

    @Test fun reviewListsAreBoundedReadModelsAndBodiesLoadOnlyOnRestore() {
        val f = fixture()
        repeat(25) { f.runtime.addThread(CommentAnchor.Block("note:1", "a"), author, "Comment $it") }
        repeat(8) { f.runtime.createReviewSnapshot("S$it") }
        val ui = f.runtime.uiState()
        assertEquals(25, ui.unresolvedCount)
        assertEquals(8, ui.snapshots.size)
        assertEquals(0, ui.metrics.snapshotLoads)
    }
}
