# DocumentHost Phase 2 Validation Report

**Date:** 2026-07-31
**Flag:** `EditorFeatureFlag.DOCUMENT_HOST` — default **off**
**Build:** `BUILD SUCCESSFUL` · 782 tests, 0 failures, 0 errors, 0 skipped

## Verdict: GATE PASSED (with one scope note)

Every gate item is now represented in the model, correct in the runtime, **reachable in Compose**, and
covered by regression tests. The six findings from the previous report (F1–F6) are closed, and so are the
gaps the closure pass surfaced that were not in that list.

The scope note is stated plainly rather than buried: **nothing here has been run on a device.** Every claim
below is read from source and pinned by unit tests. Item 22's rendering claims in particular deserve a device
check before anyone relies on them.

## Item-by-item

| Gate item | Status | Evidence |
| --- | --- | --- |
| Four combinations independently reachable | ✅ | Separate axes; two independent chips in `DocumentTopBar` |
| Page Control protects structure, content stays editable | ✅ | `SetPageStructureLockCommand` enforces; content now genuinely editable — see below |
| Scene model: typed objects, transforms, z, locks, capabilities | ✅ | `DocumentScene.kt`; `DocumentSceneControllerTest` |
| Direct manipulation — move + snap + cancel-during-drag | ✅ | `onDragCancel` → `cancelManipulation`; exact-cancel tests |
| Direct manipulation — **4-corner resize** | ✅ **was F1** | `ResizeCorner` enum; all four handles; anchor + inversion tests |
| Direct manipulation — **rotate** | ✅ **was F2** | `RotationHandle`; `rotate()` draw + `containsRotated` hit test |
| Direct manipulation — **exact x/y/w/h/angle entry** | ✅ **was F3** | `GeometryInspector` + `GeometryInspectorBar` |
| **Scene object content renders** | ✅ **was F4** | `SceneObjectContent.kt` — real blocks, not `displayName()` |
| **Scene-object virtualization** | ✅ **was F5** | `PageVirtualizer.visibleObjects`, preserves selected/previewed |
| **Keyboard parity** | ✅ **was F6** | `DocumentKeyboardMap` + `DocumentHostKeys`; every chord → registered id |
| Overlap → scene migration, snapshot-first, non-destructive | ✅ | Plus a real fallback to the legacy editor on refusal |
| Page-size safety bounds (2000 mm **and** 2 m²) | ✅ | `PageLimits.fit`; custom-size test asserts both |
| Accessibility parity | ✅ | Per-object custom actions from the same command ids |
| Page virtualization | ✅ | `PageVirtualizer.window`, from real geometry |
| Object multi-select, visible, with count + Cancel/Done | ✅ | `SelectionBar`; long-press is accelerator only |
| Page multi-select populating `selectedPageIds` | ✅ | `PageListSheet`; the scope was previously unfillable |
| Complete Page Control (incl. custom size, bleed, header/footer, numbering) | ✅ | `PAGE_VALUE_CONTROLS`; catalogue-completeness test |
| Durable "New pages by default" | ✅ | `DocumentSettingsBlock`; survives serialize + session rebuild |
| Object insertion (text frame, shape) | ✅ | `InsertSceneObjectCommand`; explicit page, fresh ids |
| Atomic multi-object duplicate/reorder | ✅ | `duplicateObjects`/`reorderObjects`; `undoDepth` +1 exactly |
| SurfaceCoordinator integration for Page Control | ✅ | `openSurface`/`dismiss`/`back`; no Compose-local sheet flag |
| `onFormFactorChanged` preserves state | ✅ | Selection, history, axes, zoom, open surface, live gesture |
| Page metadata renders | ✅ | `PageMetadata`; bleed/safe-area/custom guides drawn |
| Title editing via existing coordinator | ✅ | `DocumentTitleField` → the same `FlowSaveCoordinator` |
| Build + tests green | ✅ | 782/782 |

## What the closure pass changed

**Content is real.** `SceneObjectContent.kt` renders the same block vocabulary Flow does — headings keep their
level, lists keep markers and numbering, to-dos keep checkboxes, code keeps its slab, and inline styling goes
through the *same* `FlowInlineText` run model, so a paragraph looks identical whether it is written in Flow or
placed on a page. Editing is a deliberate second tap: one tap selects so the frame can be moved, and only a
second tap on an already-selected frame opens a caret. Entering on the first tap would have made every attempt
to drag a text frame drop a caret into it instead.

**Resize retires the single corner.** `SceneGeometry.resizedTo(corner, px, py)` anchors the corner *opposite*
the one being dragged, so a top-left drag moves the origin as well as the size. Each axis clamps at `MIN_SIZE`
against that anchor rather than passing through it, so a corner dragged past its opposite pins at minimum
instead of inverting into a negative-width box the renderer would draw inside-out and hit testing would miss.
Only the **primary** object exposes handles — a secondary selected object drawing corner targets would promise
an individual resize the group operation does not perform.

**Rotation agrees with itself.** The draw uses `rotate()` inside `drawBehind` about the box's centre; hit
testing un-rotates the tap about that same centre via `containsRotated`. A bare `Modifier.rotate()` would have
rotated the layout node — including its touch area — leaving the model and the visual disagreeing. Z order is
untouched by rotation, so overlap cycling stays stable.

**Durability is real, and that changed a contract.** "New pages by default" is now a `DocumentSettingsBlock`,
a top-level block, so it gets a persisted row, an undoable representation and a round trip from machinery that
already works. That makes it *one undo step* — a deliberate change from the previous in-memory version, whose
preference silently vanished on session recreation. `DocumentHostRuntimeTest` was updated to assert the new
contract rather than the old one.

**A refused migration now falls back.** `requiresLegacyFallback()` is checked before the host renders, and the
route hands the user the legacy editor with their document intact plus a dismissible explanation naming the
orphaned placements. Previously a refusal produced a runtime with no pages that rendered as a blank document.
`allowLossy` is still never set automatically anywhere.

## Defects found and fixed during self-review

These were not on any list; they were found by reading the code written in this pass:

1. **Inspector vs. live gesture.** A typed value committed while a drag was in flight would be overwritten on
   release, because the drag commits from the geometry it *started* from. `applyGeometryField` now settles the
   gesture first and re-reads the object — the re-read matters, since settling is exactly what changed it.
2. **Caret and drag owning one object.** Entering content editing mid-gesture, or beginning a gesture while
   editing, left both live. Each now yields to the other, and entering the caret *cancels* rather than commits
   the stray movement made on the way to the tap.
3. **Caret surviving its object.** `editingObjectId` outlived the object being deleted or undone away, leaving
   a keyboard open over content that no longer existed. Now pruned in `pruneSelection`, which undo/redo call.
4. **Virtualization culling an unmeasured page.** The degenerate-band check ran *after* overscan was applied,
   turning an unmeasured page into a small real one and culling everything outside the overscan. Now tested on
   the raw band. This one was caught by a test that failed for the right reason.

## Verification performed

- `:apps:android:testDebugUnitTest` — **782 tests, 0 failures/errors/skips**
- `:apps:android:compileDebugKotlin` — clean
- `:apps:android:compileDebugAndroidTestKotlin` — clean
- `:apps:android:assembleDebug` — APK produced
- 54 new regression tests in `DocumentHostInteractionTest`, plus 4 in `DocumentSceneControllerTest`
- Static review greps for the named defect classes: no bare `Modifier.rotate()`, no `items.take(N)`
  truncation in the dock, no automatic `allowLossy`, no Compose-local state that should be runtime-owned

**Not verified:** no device or emulator run, and there is still no `androidTest` coverage referencing
DocumentHost. Every UI claim is read from source and pinned by pure-JVM tests, not observed running. The flag
stays off, so none of this is user-visible today.
