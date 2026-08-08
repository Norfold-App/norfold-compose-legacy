package com.norfold.app.editor.relationship

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.RelationshipDataBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.relationshipDataBlock
import com.norfold.app.domain.pages
import com.norfold.app.domain.withRelationshipData
import com.norfold.app.editor.document.DocumentBackResult
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.document.LegacyDocumentMigration
import com.norfold.app.editor.document.WorkingIntent
import com.norfold.app.editor.flow.FlowBackResult
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipPhase7AdversarialTest {
    private val author = CommentAuthorRef("member:amina", "Amina")

    private fun paragraph(id: String, text: String) = ParagraphBlock(id, listOf(InlineText(text)))
    private fun endpoint(id: String) = RelationshipEndpoint(RelationshipAnchor.Block("note", id))

    private data class FlowFixture(
        val controller: FlowEditController,
        val relationships: RelationshipReviewRuntime,
    )

    private fun flowFixture(): FlowFixture {
        var serial = 0
        val controller = FlowEditController(EditTarget("note"), BlockDocument(listOf(paragraph("a", "Alpha"), paragraph("b", "Beta"), paragraph("c", "Gamma"))))
        val relationships = RelationshipReviewRuntime(
            artifactId = "note",
            enabled = true,
            documentProvider = controller::document,
            mutateDocument = controller::mutateDocument,
            protectBeforeRestore = { controller.snapshotBeforeDestructiveEdit(it) },
            clock = { 1_000L + serial },
            idGenerator = { prefix -> "$prefix-${serial++}" },
        )
        controller.setDocumentInvariant(relationships::reconcileEndpointsIn)
        return FlowFixture(controller, relationships)
    }

    private fun documentHost(): DocumentHostRuntime {
        val page = DocumentPageBlock(
            id = "page",
            geometry = PageGeometry.a4(),
            objects = listOf(
                SceneObject("one", geometry = SceneGeometry(0f, 0f, 100f, 80f), content = paragraph("text-one", "One")),
                SceneObject("two", geometry = SceneGeometry(130f, 0f, 100f, 80f), content = paragraph("text-two", "Two")),
            ),
        ).normalized()
        return DocumentHostRuntime(
            DocumentSceneController(EditTarget("document"), BlockDocument(listOf(page))),
            FormFactor(900, 700),
            relationshipsEnabled = true,
        )
    }

    @Test fun canonicalMetadataStoreCollapsesDuplicatesAndKeepsFixedIdentity() {
        val document = BlockDocument(
            listOf(
                paragraph("a", "A"),
                RelationshipDataBlock("legacy-one", "{}"),
                RelationshipDataBlock("legacy-two", "{}"),
            ),
        ).withRelationshipData("{\"v\":1}")
        assertEquals(1, document.blocks.count { it is RelationshipDataBlock })
        assertEquals(RelationshipDataBlock.BLOCK_ID, document.relationshipDataBlock()!!.id)
    }

    @Test fun legacyDocumentMigrationPreservesRelationshipMetadataOutsidePages() {
        val original = BlockDocument(listOf(paragraph("a", "A"))).withRelationshipData("{\"v\":1}")
        val migrated = LegacyDocumentMigration.migrate(original).document
        assertNotNull(migrated.relationshipDataBlock())
        assertEquals(1, migrated.blocks.count { it is DocumentPageBlock })
        assertNull(migrated.pages().single().objects.firstOrNull { it.content is RelationshipDataBlock })
    }

    @Test fun flowDeleteMarksEndpointBrokenInSameTransactionAndUndoRestoresBoth() {
        val f = flowFixture()
        f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        val depth = f.controller.undoDepth()
        assertTrue(f.controller.mutateDocument("Delete") { it.copy(blocks = it.blocks.filterNot { block -> block.id == "b" }) })
        assertEquals(depth + 1, f.controller.undoDepth())
        assertTrue(f.relationships.state().relationships.single().target.broken)
        assertTrue(f.controller.undo())
        assertNotNull(f.controller.document().findById("b"))
        assertFalse(f.relationships.state().relationships.single().target.broken)
        assertTrue(f.controller.redo())
        assertTrue(f.relationships.state().relationships.single().target.broken)
    }

    @Test fun documentDeleteMarksEndpointBrokenAndUndoRestoresExactIdentity() {
        val host = documentHost()
        val relationship = host.relationshipReview.createRelationship(
            RelationshipEndpoint(RelationshipAnchor.SceneObject("document", "page", "one")),
            RelationshipEndpoint(RelationshipAnchor.SceneObject("document", "page", "two")),
        )!!
        val depth = host.controller.undoDepth()
        assertTrue(host.controller.deleteObjects(listOf("two")))
        assertEquals(depth + 1, host.controller.undoDepth())
        assertTrue(host.relationshipReview.state().relationships.single().target.broken)
        assertTrue(host.relationshipReview.state().relationships.single().textualDescription().contains("broken relationship"))
        assertTrue(host.controller.undo())
        assertEquals(relationship.id, host.relationshipReview.state().relationships.single().id)
        assertFalse(host.relationshipReview.state().relationships.single().target.broken)
    }

    @Test fun brokenEndpointRebindUsesTemporaryConnectMode() {
        val f = flowFixture()
        val relationship = f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        f.controller.mutateDocument("Delete") { it.copy(blocks = it.blocks.filterNot { block -> block.id == "b" }) }
        val broken = f.relationships.state().relationships.single().target
        assertTrue(f.relationships.beginRebind(relationship.id, broken.stableId))
        assertNotNull(f.relationships.commitConnectTarget(endpoint("c")))
        assertNull(f.relationships.connectMode)
        assertFalse(f.relationships.state().relationships.single().target.broken)
    }

    @Test fun externalArtifactEndpointIsNotDeclaredBrokenWithoutLoadingIt() {
        val f = flowFixture()
        val external = RelationshipEndpoint(RelationshipAnchor.Artifact("other-document"))
        f.relationships.createRelationship(endpoint("a"), external)!!
        f.controller.mutateDocument("Edit") { document -> document }
        assertFalse(f.relationships.state().relationships.single().target.broken)
        assertEquals(1, f.relationships.relationshipsForArtifact("other-document").size)
    }

    @Test fun continuousConnectPolicyCreatesSeveralLinksUntilExplicitExit() {
        val f = flowFixture()
        assertTrue(f.relationships.enterConnectMode(endpoint("a"), policy = ConnectModePolicy.REMAIN_FOR_MULTIPLE))
        assertNotNull(f.relationships.commitConnectTarget(endpoint("b")))
        assertNotNull(f.relationships.connectMode)
        f.relationships.exitConnectMode()
        assertNull(f.relationships.connectMode)
    }

    @Test fun flowBackExitsConnectBeforeLeavingEditorAndDoesNotMutate() {
        val host = FlowHostRuntime(
            FlowEditController(EditTarget("note"), BlockDocument(listOf(paragraph("a", "A"), paragraph("b", "B")))),
            FormFactor(400, 800),
            relationshipsEnabled = true,
        )
        host.scope.selectBlocks(listOf("a"))
        val before = host.controller.document()
        assertTrue(host.relationshipReview.enterConnectMode(endpoint("a")))
        assertEquals(FlowBackResult.ExitedConnectMode, host.back())
        assertEquals(before, host.controller.document())
    }

    @Test fun documentBackExitsConnectBeforeAnyEditorNavigation() {
        val host = documentHost()
        host.selectObjects(listOf("one"))
        assertTrue(host.accessibilityActions(host.controller.findObject("one")!!.second).any { it.first == RelationshipCommandIds.ENTER_CONNECT })
        assertTrue(host.relationshipReview.enterConnectMode(RelationshipEndpoint(RelationshipAnchor.SceneObject("document", "page", "one"))))
        assertEquals(DocumentBackResult.ExitedConnectMode, host.back())
        assertNull(host.relationshipReview.connectMode)
    }

    @Test fun relationshipManagerOperationsAreAtomicAndDirectionAware() {
        val f = flowFixture()
        val relationship = f.relationships.createRelationship(endpoint("a"), endpoint("b"), RelationshipKinds.DEPENDS_ON)!!
        assertTrue(f.relationships.rename(relationship.id, "Launch prerequisite"))
        assertTrue(f.relationships.changeType(relationship.id, RelationshipKinds.BLOCKS))
        assertTrue(f.relationships.reverse(relationship.id))
        val changed = f.relationships.state().relationships.single()
        assertEquals("Launch prerequisite", changed.label)
        assertEquals(RelationshipKinds.BLOCKS.id, changed.kind.id)
        assertEquals(endpoint("b").stableId, changed.source.stableId)
    }

    @Test fun undirectedRelationshipRefusesMeaninglessReverseAsNoOp() {
        val f = flowFixture()
        val relationship = f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        assertFalse(f.relationships.reverse(relationship.id))
        assertEquals(endpoint("a").stableId, f.relationships.state().relationships.single().source.stableId)
    }

    @Test fun commentsEnforceLocalEditAndDeleteOwnership() {
        val f = flowFixture()
        val thread = f.relationships.addThread(CommentAnchor.Block("note", "a"), author, "Original")!!
        val comment = thread.comments.single()
        assertFalse(f.relationships.editComment(comment.id, "someone-else", "Tampered"))
        assertFalse(f.relationships.deleteComment(comment.id, "someone-else"))
        assertTrue(f.relationships.editComment(comment.id, author.stableId, "Edited"))
        assertEquals("Edited", f.relationships.state().threads.single().comments.single().body)
        assertTrue(f.relationships.deleteComment(comment.id, author.stableId))
        assertTrue(f.relationships.state().threads.isEmpty())
    }

    @Test fun resolvedThreadFilteringAndNextPreviousNavigationAreDeterministic() {
        val f = flowFixture()
        val first = f.relationships.addThread(CommentAnchor.Block("note", "a"), author, "First")!!
        val second = f.relationships.addThread(CommentAnchor.Block("note", "b"), author, "Second")!!
        f.relationships.resolve(first.id, author)
        f.relationships.openReviewMode(includeResolved = false)
        assertEquals(second.id, f.relationships.nextThread()!!.id)
        f.relationships.setIncludeResolved(true)
        assertEquals(first.id, f.relationships.nextThread()!!.id)
        assertEquals(second.id, f.relationships.nextThread(forward = false)!!.id)
    }

    @Test fun structuredMentionKeepsStableIdentitySeparateFromVisibleText() {
        val f = flowFixture()
        val mention = CommentMention("account-42", "Rafi Hasan")
        f.relationships.addThread(CommentAnchor.Block("note", "a"), author, "Review with @Rafi Hasan", listOf(mention))
        val stored = f.relationships.state().threads.single().comments.single()
        assertEquals("account-42", stored.mentions.single().stableUserId)
        assertTrue(stored.body.contains("@Rafi Hasan"))
    }

    @Test fun tableChartAndImageAnchorsRoundTripSemantically() {
        val anchors = listOf<CommentAnchor>(
            CommentAnchor.TableRange("note", "table", "row:1/col:2"),
            CommentAnchor.Chart("note", "chart", "series:sales"),
            CommentAnchor.Image("note", "image"),
        )
        val state = Phase7ArtifactState(threads = anchors.mapIndexed { index, anchor ->
            val id = ThreadId("thread-$index")
            DiscussionThread(id, anchor, comments = listOf(Comment(CommentId("comment-$index"), id, author, index.toLong(), body = "Body")))
        })
        assertEquals(anchors, Phase7ArtifactCodec.decode(Phase7ArtifactCodec.encode(state)).threads.map { it.anchor })
    }

    @Test fun completeAnchorDeletionDetachesAndUndoRestoresInOneTypingStep() {
        val controller = FlowEditController(EditTarget("note"), BlockDocument(listOf(paragraph("a", "hello world"))))
        val host = FlowHostRuntime(controller, FormFactor(400, 800), relationshipsEnabled = true)
        host.scope.selectBlocks(listOf("a"))
        val thread = host.relationshipReview.addThread(CommentAnchor.Text("note", "a", 6, 11, "world"), author, "Pinned")!!
        val depth = controller.undoDepth()
        assertTrue(host.editText("a", "hello world", "hello "))
        assertEquals(depth + 1, controller.undoDepth())
        assertEquals(AnchorState.DETACHED, (host.relationshipReview.state().threads.first { it.id == thread.id }.anchor as CommentAnchor.Text).state)
        assertTrue(host.undo())
        assertEquals(AnchorState.ATTACHED, (host.relationshipReview.state().threads.first { it.id == thread.id }.anchor as CommentAnchor.Text).state)
    }

    @Test fun documentTextSelectionCreatesTextAnchorWhenIdentityIsStable() {
        val host = documentHost()
        host.setIntent(WorkingIntent.EDIT)
        host.selectObjects(listOf("one"))
        host.selection.setTextRange(com.norfold.app.editor.selection.TextRange("text-one", 0, 3))
        assertTrue(host.addComment(author, "Object text"))
        val anchor = host.relationshipReview.state().threads.single().anchor as CommentAnchor.Text
        assertEquals("One", anchor.quote)
    }

    @Test fun readModeCannotCreateCommentsOrKeepConnectModeAlive() {
        val host = documentHost()
        host.selectObjects(listOf("one"))
        host.relationshipReview.enterConnectMode(RelationshipEndpoint(RelationshipAnchor.SceneObject("document", "page", "one")))
        host.setIntent(WorkingIntent.READ)
        assertNull(host.relationshipReview.connectMode)
        assertFalse(host.addComment(author, "Must not persist"))
        assertTrue(host.relationshipReview.state().threads.isEmpty())
    }

    @Test fun snapshotRestoreRestoresRelationshipsButPreservesNewerDiscussion() {
        val f = flowFixture()
        val baseline = f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        val snapshot = f.relationships.createReviewSnapshot("Baseline", author)!!
        f.relationships.removeRelationship(baseline.id)
        val newer = f.relationships.addThread(CommentAnchor.Block("note", "a"), author, "Newer")!!
        assertTrue(f.relationships.restoreReviewSnapshot(snapshot.id))
        assertEquals(baseline.id, f.relationships.state().relationships.single().id)
        assertTrue(f.relationships.state().threads.any { it.id == newer.id })
    }

    @Test fun snapshotRestoreDetachesNewerCommentWhoseTargetDidNotExistAtSnapshot() {
        val f = flowFixture()
        f.controller.mutateDocument("Delete") { it.copy(blocks = it.blocks.filterNot { block -> block.id == "b" }) }
        val snapshot = f.relationships.createReviewSnapshot("After deletion", author)!!
        val newer = f.relationships.addThread(CommentAnchor.Block("note", "b"), author, "Still retained")!!
        assertTrue(f.relationships.restoreReviewSnapshot(snapshot.id))
        val restored = f.relationships.state().threads.first { it.id == newer.id }
        assertEquals(AnchorState.DETACHED, restored.anchor.state)
    }

    @Test fun futurePayloadIsReadOnlyAndNeverSilentlyOverwritten() {
        val future = "{\"v\":88,\"futureData\":true}"
        val controller = FlowEditController(EditTarget("note"), BlockDocument(listOf(paragraph("a", "A"))).withRelationshipData(future))
        val runtime = RelationshipReviewRuntime("note", true, controller::document, controller::mutateDocument, { _ -> })
        assertNull(runtime.createRelationship(endpoint("a"), RelationshipEndpoint(RelationshipAnchor.Artifact("other"))))
        assertEquals(future, controller.document().relationshipDataBlock()!!.payload)
    }

    @Test fun connectPreviewAndConnectorLayoutNeverCreateHistory() {
        val f = flowFixture()
        f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        val depth = f.controller.undoDepth()
        assertTrue(f.relationships.enterConnectMode(endpoint("a")))
        assertTrue(f.relationships.previewConnectTarget(endpoint("b")))
        f.relationships.connectorGeometryInvalidated(setOf(endpoint("a").stableId))
        assertEquals(depth, f.controller.undoDepth())
    }

    @Test fun movingUnrelatedObjectInvalidatesNoConnector() {
        val f = flowFixture()
        f.relationships.createRelationship(endpoint("a"), endpoint("b"))!!
        assertTrue(f.relationships.connectorGeometryInvalidated(setOf("artifact:note/block:unrelated")).isEmpty())
        assertEquals(0, f.relationships.metrics.affectedConnectorsRecalculated)
    }

    @Test fun emptyAndMalformedCommentOperationsAreSafeNoOps() {
        val f = flowFixture()
        assertNull(f.relationships.addThread(CommentAnchor.Block("note", "a"), author, "   "))
        assertNull(f.relationships.reply(ThreadId("missing"), author, "reply"))
        assertFalse(f.relationships.resolve(ThreadId("missing"), author))
        assertTrue(f.relationships.state().threads.isEmpty())
    }

    @Test fun anchorTypesReferToCompletedDomainIdentitiesNotCoordinates() {
        val document = BlockDocument(listOf(TableBlock(id = "table"), ChartBlock(id = "chart"), ImageBlock(id = "image")))
        val controller = FlowEditController(EditTarget("note"), document)
        val runtime = RelationshipReviewRuntime("note", true, controller::document, controller::mutateDocument, { _ -> })
        assertNotNull(runtime.createRelationship(
            RelationshipEndpoint(RelationshipAnchor.TableRange("note", "table", "whole")),
            RelationshipEndpoint(RelationshipAnchor.Chart("note", "chart")),
        ))
        assertNotNull(runtime.createRelationship(
            RelationshipEndpoint(RelationshipAnchor.Image("note", "image")),
            RelationshipEndpoint(RelationshipAnchor.Artifact("external")),
        ))
    }
}
