package com.norfold.app.editor.document

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.selection.LockScope
import com.norfold.app.editor.selection.SelectionGranularity
import com.norfold.app.editor.selection.SelectionTarget

/**
 * Maps document scene objects and pages onto the shared [Capability] vocabulary (spec §4.3).
 *
 * Capability-based eligibility is what lets a locked object stay selectable while the commands that would
 * move it correctly refuse: the lock removes [Capability.MOVEMENT] from what the selection *offers*, so
 * every surface — dock, keyboard, accessibility — reaches the same conclusion from the same fact, rather
 * than each one re-checking the lock and one of them eventually forgetting.
 */
object DocumentCapabilities {

    fun of(obj: SceneObject): Set<Capability> = buildSet {
        // Always true, even when locked: a locked object is selectable and inspectable (§4.13).
        add(Capability.SELECTION)
        add(Capability.LOCKING)
        add(Capability.EXPORT)
        // Ordering is a page-level concern, unaffected by the object's own position/size locks.
        add(Capability.ORDERING)
        if (obj.canMove) add(Capability.MOVEMENT)
        if (obj.canResize) add(Capability.RESIZE)
        if (obj.canRotate) add(Capability.ROTATION)
        if (obj.canEditContent) {
            add(Capability.DUPLICATION)
            when (obj.kind) {
                SceneObjectKind.TextFrame -> add(Capability.TEXT_EDITING)
                SceneObjectKind.Table -> {
                    add(Capability.TABLE_STRUCTURE)
                    add(Capability.TEXT_EDITING)
                }
                SceneObjectKind.Shape -> {
                    add(Capability.FILL)
                    add(Capability.STROKE)
                    add(Capability.TEXT_EDITING)
                }
                SceneObjectKind.Image -> add(Capability.CROP)
                SceneObjectKind.Chart -> add(Capability.DATA_BINDING)
                SceneObjectKind.Media -> Unit
                // Preserved content is placeable but not interpretable by this build, so it offers no
                // content-shaped capability at all. That is exactly what keeps its payload verbatim.
                SceneObjectKind.Preserved -> Unit
            }
        }
    }

    /** Capabilities a page offers. Structure lock is what gates geometry, so it is reflected here. */
    fun ofPage(page: DocumentPageBlock): Set<Capability> = buildSet {
        add(Capability.SELECTION)
        add(Capability.LOCKING)
        add(Capability.EXPORT)
        add(Capability.DUPLICATION)
        add(Capability.MOVEMENT)
        if (!page.geometry.structureLocked) add(Capability.RESIZE)
    }

    /** The union offered by a multi-object selection — what a command's eligibility is resolved against. */
    fun ofSelection(objects: Collection<SceneObject>): Set<Capability> =
        objects.flatMapTo(linkedSetOf()) { of(it) }

    /** The [SelectionTarget] for [obj], carrying its lock so the Selection Engine reports it honestly. */
    fun targetOf(obj: SceneObject): SelectionTarget = SelectionTarget(
        id = obj.id,
        granularity = SelectionGranularity.DOC_OBJECT,
        locked = when (obj.lock) {
            com.norfold.app.domain.SceneLock.None -> LockScope.NONE
            com.norfold.app.domain.SceneLock.Position -> LockScope.POSITION
            com.norfold.app.domain.SceneLock.Size -> LockScope.SIZE
            com.norfold.app.domain.SceneLock.Content -> LockScope.CONTENT
            com.norfold.app.domain.SceneLock.Full -> LockScope.FULL
        },
        hidden = !obj.visible,
    )

    fun targetOfPage(page: DocumentPageBlock): SelectionTarget = SelectionTarget(
        id = page.id,
        granularity = SelectionGranularity.DOC_PAGE,
        locked = if (page.geometry.structureLocked) LockScope.SIZE else LockScope.NONE,
    )
}
