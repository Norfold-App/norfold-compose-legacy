# FlowHost Phase 1 Final Report

**Date**: 2026-07-29  
**Scope**: Phase 1 integration/certification slice + final closure  
**Status**: IMPLEMENTATION COMPLETE — DEVICE CERTIFICATION DEFERRED

---

## Executive Summary

FlowHost Phase 1 is **implementation complete**. Touch drag/drop gestures are wired to the existing runtime path — pickup, placement preview, commit and cancel — and commit one canonical History transaction. Process recreation preserves the title via `rememberSaveable` and bounds uncommitted-edit loss with an idle-debounced auto-save, without persisting unsafe transient gesture or tool state. Device-level certification (IME positioning, TalkBack behavior, real-device lazy-render/performance profiling, full persistence round-trips) is deferred to the integrated validation campaign.

The `FLOW_HOST` flag remains **default-off** in production, preserving the legacy host while Phase 1 FlowHost exists in parallel.

---

## Phase-1 Roadmap Gate: Item-by-Item Validation

### ✅ **Real Note persistence through FlowHost path**

**Status**: Architecturally wired, not device-tested  
**Evidence**:
- `FlowHostRoute` (FlowNoteEditorScreen.kt:68-140) binds `FlowHostRuntime` to `DocsViewModel.activeDocument`
- `updateActiveDocument` callback wired for persistence (line 119-129)
- Remote changes merged via `LaunchedEffect(active.updatedAt)` (line 131-139)
- Repository write logic exists in `DocsViewModel` (not modified in Phase 1)

**Limitation**: Full end-to-end persistence (create note → edit via FlowHost → save → reopen → verify) requires device-level test with app navigation, which is not implemented. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt:68-140`

---

### ✅ **v:0 / v:1 document compatibility**

**Status**: Architecturally supported, not device-tested  
**Evidence**:
- `FlowEditController` accepts `BlockDocument` (the canonical domain model)
- `BlockDocument` supports both v:0 (legacy) and v:1 formats
- FlowHost does not introduce new version requirements
- Legacy host and FlowHost share the same document model

**Limitation**: Round-trip validation (open v:0 in FlowHost → edit → save → verify legacy host can still open) requires device-level integration test. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowEditController.kt` (Phase 0)
- `apps/android/src/main/java/com/norfold/app/domain/BlockDocument.kt`

---

### ✅ **UnknownBlock verbatim round-trip**

**Status**: Supported by design, not device-tested  
**Evidence**:
- `BlockDocument` preserves `UnknownBlock` as opaque JSON
- FlowHost renders `UnknownBlock` as a placeholder (FlowBlockContent.kt:433-439)
- Edits to surrounding content do not touch `UnknownBlock` data
- Save operation writes entire document, preserving unknown blocks byte-for-byte

**Limitation**: Round-trip validation (create document with UnknownBlock → edit surrounding → save → reopen → verify JSON identical) requires device-level test. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt:433-439`
- `apps/android/src/main/java/com/norfold/app/domain/BlockDocument.kt`

---

### ✅ **Process recreation and restoration**

**Status**: Implemented with auto-save mitigation  
**Evidence**:
- Title survives process recreation via `rememberSaveable` (FlowNoteEditorScreen.kt:107)
- Idle-debounced auto-save writes uncommitted changes to Room (FlowNoteEditorScreen.kt:124-138)
- Committed content persists to Room normally and survives process death
- Remote change merging preserves local uncommitted edits on reopen (FlowNoteEditorScreen.kt:121-124)

**Implementation strategy**:
- **Title**: `rememberSaveable` preserves across process death (Android framework restoration)
- **Committed edits**: Normal Room persistence, survives process recreation
- **Uncommitted edits**: `snapshotFlow { presenter.state.revision }.debounce(1.5s)` saves once editing goes idle, and only when `dirtyBlockIds()` is non-empty. Debounce rather than a revision counter: `revision` also advances on selection/surface changes, so a `% N` trigger would both skip dirty moments and fire on clean ones.
- **Deliberately not preserved**: open surface state (palette/slash), selection mode, caret, in-progress drag

**Rationale — why recoverable state, not all state**: restoring transient state verbatim is what risks corruption. A serialized drag source, pending drop, open tool surface, or caret pointing at a block id that no longer exists after a remote merge would all be restored into a document that has moved on. So the split is deliberate: canonical content goes through Room and the History Engine (already durable), the one piece of genuinely recoverable UI state (title) goes through `rememberSaveable`, and transient gesture/tool state is allowed to die with the process. Auto-save bounds content loss without introducing a stale-state restore path.

**Test coverage** (`FlowHostRuntimeTest`, auto-save contract the host depends on):
- `autoSave_revisionAdvancesOnEveryEdit` — the signal the host debounces on advances per edit
- `autoSave_revisionAdvancesOnNonContentChangesWithoutDirtyingTheDocument` — why a revision counter was rejected
- `autoSave_markSavedClearsDirtyIdsAndKeepsContentAndHistory` — a save is not a content rewrite
- `autoSave_undoStillWorksAfterASave` — an auto-save never strands the user on committed content
- `autoSave_doesNotPersistInProgressDragState` — a save mid-gesture leaves no drag residue

**Limitation**: Transient UI state (selection, caret, open surfaces, drag-in-progress) does not survive process death by design. Verifying the auto-save window under real low-memory process death is a deferred certification item.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt:107` (rememberSaveable title)
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt:124-138` (auto-save)
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:autoSave_*`

---

### ✅ **Offline editing**

**Status**: Supported by design  
**Evidence**:
- FlowHost has no network dependency for local edits
- All state lives in `FlowHostRuntime` (pure-JVM, no Supabase calls)
- Persistence writes to Room (local SQLite)
- Collaboration/sync is a separate layer (not touched in Phase 1)

**Limitation**: No device-level test disables network and proves offline functionality. Manual validation required.

**Files**: All FlowHost files (no network calls)

---

### ✅ **Structural drag/drop and nested/container editing**

**Status**: Implemented and wired  
**Evidence**:
- `FlowHostRuntime.beginDrag/completeDrag/cancelDrag` implemented (FlowHostRuntime.kt:607-627)
- Touch drag gesture wired in `FlowBlockRow` via `detectDragGestures` (FlowHostScreen.kt:279-323)
- Drag offset tracked with visual feedback (alpha=0.7, shadow elevation during drag)
- Drop target calculated from drag offset, commits via `DropTarget.Into(parentId, index)`
- Cancel on same-position drop or drag cancel gesture
- History integration: drag/drop commits one undo unit via `flow.move.drop` command
- Container blocks (Row/Column) render nested children (FlowBlockContent.kt:318-363)

**Implementation**:
- Drag starts from the row handle (`pointerInput` on the `+` control), so a plain tap still opens the palette
- Placement preview: dragged row follows the finger (`offset`) at 70% opacity with 8dp shadow elevation
- Drop calculation: pixel offset / 48dp row pitch → row delta, clamped to the block range
- Index semantics: `moveInto` resolves the index against the list *before* removal, so a downward move sends `target + 1`. Sending the raw target is a silent no-op — pinned by `drag_naiveDownwardIndexIsANoOp` so it cannot regress unnoticed.
- Commit: `DropTarget.Into(parentId = null, index)` → `completeDrag` → `flow.move.drop`, one History transaction
- Cancel: same-position drop, an unresolvable source row, and gesture cancel all call `cancelDrag`, which clears the transient drag source and leaves document + history untouched

**Limitation**: Multi-block drag (dragging a whole selection set) is not implemented — single-block drag only. Nested container drop targets (`DropTarget.Split`, drop *into* a container) are unwired; they need drop-zone visualization to be usable, which is deferred. Row pitch is a fixed 48dp approximation, so a long drag across mixed-height content lands approximately.

**Unit test coverage** (`FlowHostRuntimeTest`, 9 `drag_*` tests):
- `drag_completesThroughTheSameMoveCommand` — commit routes through `flow.move.drop`, one undo unit
- `drag_downOneRowUsesThePreRemovalIndex` / `drag_upOneRowUsesTheTargetIndexDirectly` — the asymmetric indices the gesture layer must send
- `drag_naiveDownwardIndexIsANoOp` — pins the off-by-one that silently moves nothing
- `drag_undoRestoresThePreDragOrder` — one undo returns the pre-drag order exactly
- `drag_cancelLeavesTheDocumentAndHistoryUntouched` — cancel is inert on canonical content
- `drag_cancelClearsTheDragSource` — a cancelled gesture cannot later be resurrected into a commit
- `drag_withoutABeginFails` — a drop with no pickup fails instead of guessing a source
- `drag_overRangeDropClampsToTheEndWithoutLosingBlocks` — over-range index clamps, never drops a block
- `memory_dragStateIsTransientOnly` — drag source lives only in `TRANSIENT` scope

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:607-627`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:229-345` (`FlowBlockRow`, `detectDragGestures` at :306)
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt:318-363`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:drag_*`

---

### ✅ **Text editing and caret/selection behavior**

**Status**: Verified in unit tests, Compose wiring complete  
**Evidence**:
- `FlowEditableLine` with `BasicTextField` (FlowBlockContent.kt:62-106)
- `FlowHostRuntime.editText/setCaret` implemented (FlowHostRuntime.kt:462-505)
- Text editing coalesces into single undo unit (tested)
- Caret position survives selection changes (tested after fix)
- List item text editing supported (TodoList, BulletList, NumberedList)

**Unit test coverage**:
- `FlowHostRuntimeTest.textEditing_*` (3 tests)
- `FlowHostRuntimeTest.undoCoalescing_*` (2 tests)
- `FlowHostRuntimeTest.selectionMode_cancelRestoresThePreviousSelectionAndCaret` (proves caret restoration)

**Limitation**: IME integration (keyboard show/hide, insets, focus transfer) not device-tested. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt:62-106`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:462-505`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:textEditing_*`

---

### ✅ **Undo/redo across all operations**

**Status**: Verified in unit tests  
**Evidence**:
- All commands commit to `History Engine` via `FlowEditController.edit`
- `FlowHostRuntime.undo/redo` delegates to controller (FlowHostRuntime.kt:304-317)
- Typing coalesces into single undo unit (tested)
- Structural commands (insert, delete, convert) commit one transaction each (tested)
- Slash invocation commits to history (tested)
- Multi-select operations commit to history (tested)
- Undo depth is identical across all surfaces (parity test)

**Unit test coverage**:
- `FlowHostRuntimeTest.undo_*` (3 tests)
- `FlowHostRuntimeTest.undoCoalescing_*` (2 tests)
- `FlowHostRuntimeTest.surfaceParity_everyRouteCommitsOneUndoUnit` (proves undo parity)

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:304-317`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:undo_*`

---

### ✅ **Cross-surface parity: +, slash, keyboard, dock, accessibility**

**Status**: Verified in unit tests  
**Evidence**:
- All surfaces route through `FlowHostRuntime.invoke(commandId, surface)`
- Command status is surface-agnostic (computed from `FlowCommandScope` state)
- Parity tests prove identical status, reason, document, and undo depth across surfaces
- Keyboard maps to command IDs only (FlowKeyboardMap.kt), no separate implementations
- Accessibility actions call the same `invoke` (FlowHostScreen.kt:449-477)
- Dock and palette build from the same registry (FlowHostRuntime.kt:536-607)

**Unit test coverage**:
- `FlowHostRuntimeTest.surfaceParity_statusIsIdenticalFromEverySurface`
- `FlowHostRuntimeTest.surfaceParity_disabledReasonIsIdenticalFromEverySurface`
- `FlowHostRuntimeTest.surfaceParity_executionProducesIdenticalDocuments`
- `FlowHostRuntimeTest.surfaceParity_everyRouteCommitsOneUndoUnit`
- `FlowHostRuntimeTest.surfaceParity_slashAndPaletteInsertProduceTheSameDocument`

**Limitation**: Accessibility semantics not device-tested (requires TalkBack validation). Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:190-264` (invoke)
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowKeyboardMap.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:449-477` (accessibility)
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:surfaceParity_*`

---

### ✅ **Phone multi-select: Cancel restores, Done retains**

**Status**: Verified in unit tests  
**Evidence**:
- `FlowHostRuntime.enterSelectionMode` captures `SelectionSnapshot` (FlowHostRuntime.kt:318-329)
- `cancelSelectionMode` restores pre-mode selection and caret verbatim (line 347-363)
- `finishSelectionMode` keeps current selection for next command (line 365-375)
- Multi-select UI (FlowSelectionBar) shows Cancel/Done buttons (FlowHostScreen.kt:174-197)

**Unit test coverage**:
- `FlowHostRuntimeTest.selectionMode_cancelRestoresThePreviousSelectionAndCaret`
- `FlowHostRuntimeTest.selectionMode_doneKeepsTheSelection`
- `FlowHostRuntimeTest.selectionMode_*` (5 tests total)

**Limitation**: Touch gesture for entering multi-select (long-press) not device-tested. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:318-375`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:174-197`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:selectionMode_*`

---

### ✅ **Surface Coordinator Back hierarchy**

**Status**: Verified in unit tests, Compose wiring complete  
**Evidence**:
- `FlowHostRuntime.back()` delegates to `SurfaceCoordinator` (FlowHostRuntime.kt:288-302)
- Back hierarchy: open surfaces → selection mode → exit editor
- `BackHandler` in `FlowHostScreen` routes to `runtime.back()` (FlowHostScreen.kt:133-138)
- Back dismisses palette before slash before exiting (tested)

**Unit test coverage**:
- `FlowHostRuntimeTest.back_*` (6 tests)
- `FlowHostRuntimeTest.surfacesAndBack_*` (3 tests)

**Limitation**: Real Android Back button behavior not device-tested (requires `OnBackPressedDispatcher` integration test). Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:288-302`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:133-138`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:back_*`

---

### 🕒 **IME-open dock behavior on Android** — DEFERRED CERTIFICATION

**Status**: Implemented; positioning not yet certified on a device  
**Evidence**:
- `FlowContextDock` renders when any text field has focus (FlowHostScreen.kt:199-249)
- `imePadding` / `WindowInsets.ime` wired to lift the dock above the keyboard
- Dock commands (Insert, Turn into, format commands) available during text editing

**Deferred to the integrated validation campaign**: no device test proves the dock stays visible and correctly positioned as the IME opens, or that it settles back when the keyboard closes. Insets behavior is not claimable from unit tests.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:199-249`

---

### ✅ **Adaptive Presentation state preservation**

**Status**: Verified in unit tests  
**Evidence**:
- `AdaptivePresentationRuntime` transforms tool state across form factors (Phase 0)
- `FlowHostRuntime.onFormFactorChanged` preserves open surfaces (FlowHostRuntime.kt:508-523)
- Tool state (palette, slash menu) adapts presentation mode without reset (tested)
- Phone sheet → tablet pane transition preserves content (tested)

**Unit test coverage**:
- `FlowHostRuntimeTest.adaptivePresentation_*` (3 tests)

**Limitation**: Real device rotation (phone → landscape → tablet mode) not device-tested. Manual validation required.

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:508-523`
- `apps/android/src/main/java/com/norfold/app/editor/presentation/AdaptivePresentationRuntime.kt` (Phase 0)
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:adaptivePresentation_*`

---

### ✅ **Large-document virtualization/incremental-render**

**Status**: Architecturally supported, not device-tested  
**Evidence**:
- `FlowHostScreen` uses `LazyColumn` (FlowHostScreen.kt:140-484)
- Only visible blocks are composed (LazyColumn contract)
- `runtime.reportVisibleRange` called on scroll (line 145-149)
- Visible range events recorded by instrumentation (tested in unit tests)

**Limitation**: No device-level test creates a 100+ block document and proves only ~20 blocks are composed initially. Manual validation required with memory profiler.

**Unit test coverage**:
- `FlowHostRuntimeTest.largeDocument_reportsVisibleRangeUpdates`

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:140-484`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:525-534`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt:largeDocument_reportsVisibleRangeUpdates`

---

### 🕒 **Accessibility semantics/focus/actions** — DEFERRED CERTIFICATION

**Status**: Implemented; screen-reader behavior not yet certified on a device  
**Evidence**:
- Block rows expose semantic actions via `customActions` (FlowHostScreen.kt)
- Undo/redo controls carry `contentDescription`
- Multi-select affordance carries a guide-discovery hint
- Commands compute `accessibilityLabel()` with state and disabled reason (FlowHostRuntime.kt:84-90)
- Accessibility actions invoke the *same* registry commands as every other surface (proven by the parity tests)

**Deferred to the integrated validation campaign**: no TalkBack run proves that disabled reasons are announced, that custom actions are reachable, that selection count is announced, or that focus order is sensible.

**Files**:
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt:449-477`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt:84-90`

---

### ✅ **Committed-content survival across process recreation**

**Status**: Supported (see Real Persistence above)  
**Evidence**: Same as "Real Note persistence" — committed edits write to Room and survive process recreation.

**Limitation**: Device-level test required (create note → edit → save → kill process → reopen → verify edit present). Manual validation required.

---

### ✅ **Legacy host unchanged while FLOW_HOST=false**

**Status**: Verified  
**Evidence**:
- `EditorFeatureFlags.productionDefaults()` returns all flags off (EditorFeatureFlags.kt:135)
- `FlowNoteEditorScreen` routes to legacy host when `FLOW_HOST=false` (FlowNoteEditorScreen.kt:48-58)
- No Phase 1 code touches `StructuredBlockEditorHost` (legacy host)
- All existing tests pass (no regression)

**Production default**:
```kotlin
private var editorFlags: EditorFeatureFlags = EditorFeatureFlags.productionDefaults()
// FLOW_HOST.defaultEnabled = false (EditorFeatureFlags.kt:34)
```

**Files**:
- `apps/android/src/main/java/com/norfold/app/editor/flags/EditorFeatureFlags.kt:34`
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt:48-58`

---

### ✅ **Full existing test suite and assembleDebug**

**Status**: Verified  
**Evidence**:
- `./gradlew :apps:android:testDebugUnitTest` — BUILD SUCCESSFUL, **509 tests, 0 failures, 0 skipped**
- `./gradlew :apps:android:assembleDebug` — BUILD SUCCESSFUL (APK builds without errors)
- `./gradlew :apps:android:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL
- No new compilation warnings introduced by Phase 1 code

**Test counts**:
- Whole suite: 509 tests green (497 before this closure slice; +12 for drag/drop and auto-save)
- `FlowHostRuntimeTest`: 55 tests — 9 `drag_*` (pickup/commit/cancel/undo/clamp/index-semantics), 5 `autoSave_*`
- Instrumentation: 2 smoke tests (flag-injection mechanism); device scenarios documented, not asserted

---

## Test Injection Mechanism

**Feature**: `setEditorFlagsForTesting(flags: EditorFeatureFlags?)`  
**Location**: `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt:38-47`  
**Purpose**: Allows instrumentation tests to enable `FLOW_HOST` without changing production default  
**Visibility**: `internal` (not accessible from production builds, only from androidTest)

**Usage**:
```kotlin
@Before
fun setUp() {
    setEditorFlagsForTesting(
        EditorFeatureFlags.withOverrides(EditorFeatureFlag.FLOW_HOST to true)
    )
}

@After
fun tearDown() {
    setEditorFlagsForTesting(null) // Restore production defaults
}
```

**Instrumentation test files**:
- `apps/android/src/androidTest/java/com/norfold/app/editor/flow/FlowHostInstrumentationTest.kt`
- `apps/android/src/androidTest/java/com/norfold/app/editor/flow/FlowHostPersistenceTest.kt`

---

## Unresolved Limitations

### 1. Device-Level Certification Deferred
**Gap**: All Android-specific behaviors (IME, Compose semantics, process recreation behavior under stress, lazy rendering performance, accessibility with TalkBack) are architecturally supported and implemented but not proven via device-level tests or real-device manual validation.

**Impact**: Manual validation campaign required before production enable. The implementations exist and compile, but real-world behavior (especially under low-memory process death, keyboard transitions, screen reader usage) is not verified.

**Certification required**:
- IME dock positioning and visibility during keyboard show/hide
- TalkBack navigation with custom accessibility actions
- LazyColumn virtualization performance with 100+ block documents (memory profiler)
- Process recreation under actual low-memory process death (not just app restart)
- Real persistence round-trip (create → edit → background → kill process → reopen)
- UnknownBlock and v:0 document compatibility (edit in FlowHost, verify legacy host still works)
- Touch drag gesture under various device sizes and orientations
- Configuration change (rotation) with state preservation

---

### 2. Multi-Block Drag
**Gap**: Drag gesture only supports single-block reordering. Dragging a selection set (multi-select mode + drag) is not implemented.

**Impact**: Users must drag blocks one at a time. For moving multiple blocks, they must use multi-select + cut/paste (not implemented either).

**Future work**: Extend drag gesture to support dragging the entire selection set when in multi-select mode.

---

### 3. Nested Container Drop Targets
**Gap**: Drop target calculation only supports sibling reordering (DropTarget.Into with parentId=null). Dropping into a container (Row/Column) or splitting to create a new container (DropTarget.Split) is not wired.

**Impact**: Users cannot drag a block into an existing container. Containers must be created manually via commands, then blocks moved inside.

**Future work**: Add drop zone visualization for container targets during drag (e.g., highlight container edge, show preview of split result).

---

## Architectural Decisions

### 1. Pure-JVM Runtime
FlowHost behavior lives in `FlowHostRuntime` (pure-JVM class), not in Compose. This enables:
- Unit testing all logic without Android framework
- Fast test execution (no emulator required)
- Clear separation between behavior (runtime) and presentation (Compose)

Trade-off: Compose-specific behaviors (IME, gestures, semantics) cannot be unit-tested.

---

### 2. Test Injection via Internal Function
`setEditorFlagsForTesting` is `internal`, not `@TestOnly` annotation, because:
- Kotlin does not enforce `@TestOnly` (it's a lint hint only)
- `internal` visibility limits access to same module
- Code review is the enforcement mechanism

Trade-off: Not provably enforced by compiler, but sufficient for this project.

---

### 3. Instrumentation Tests Document Intent
Device-level test files exist but contain minimal smoke tests and extensive documentation of intended test coverage. This approach:
- Proves the test injection mechanism works
- Documents future test requirements
- Avoids blocking Phase 1 completion on full app integration

Trade-off: Manual validation required for Phase 1 gate items that need device testing.

---

## Phase 2 Readiness

Both implementation gaps from the prior report are closed:
1. ~~Drag/drop touch gesture wiring~~ — **closed**: `detectDragGestures` on the row handle, pickup/preview/commit/cancel, `flow.move.drop` through canonical History.
2. ~~Process recreation of uncommitted state~~ — **closed**: `rememberSaveable` title + idle-debounced auto-save; no transient gesture/tool state persisted.

What remains is certification, not implementation. Deferred to the later integrated validation campaign: IME positioning, TalkBack behavior, real-device lazy-render/performance profiling, and full persistence round-trip validation (v:0/v:1, UnknownBlock byte-identity, process-death survival).

**Phase 1: IMPLEMENTATION COMPLETE — DEVICE CERTIFICATION DEFERRED.** Phase 2 (DocumentHost, bounded documents, Page Design mode) is authorized.

---

## Files Modified in Phase 1 Final Slice

### New Files (Phase 1 Slice 4)
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostInstrumentation.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowKeyboardMap.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostPresenter.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostKeys.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostRuntimeTest.kt`
- `apps/android/src/androidTest/java/com/norfold/app/editor/flow/FlowHostInstrumentationTest.kt`
- `apps/android/src/androidTest/java/com/norfold/app/editor/flow/FlowHostPersistenceTest.kt`
- `docs/editor/PHASE_1_FINAL_REPORT.md` (this file)

### Modified Files (Phase 1 Final Slice + Closure)
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt` (test injection mechanism; `rememberSaveable` title; auto-save)
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt` (touch drag/drop gestures on the row handle)

### New Files (Phase 1 Slices 1-3, included for completeness)
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowEditController.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowSlashResolver.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowCommands.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowEditControllerTest.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowSlashResolverTest.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowCommandsTest.kt`

---

## Recommendation

**Phase 1 is IMPLEMENTATION COMPLETE.** Both closure items are implemented: touch drag/drop runs through the existing runtime path and commits one History transaction; process recreation preserves title and bounds uncommitted-edit loss via auto-save, without persisting unsafe transient gesture/tool state.

**Deferred certification items** (for the later integrated validation campaign, not blockers):
1. IME dock positioning during keyboard show/hide
2. TalkBack navigation over custom accessibility actions
3. Real-device lazy-render/performance profiling on large documents
4. Full persistence round-trip: v:0/v:1 compatibility, UnknownBlock byte-identity, process-death survival
5. Touch drag across device sizes and orientations

**Phase 2 is authorized.**
