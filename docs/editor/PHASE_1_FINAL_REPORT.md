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

---

# Addendum: Phase 1 Final Corrective Pass — Interaction Correctness Gate

**Date**: 2026-07-30
**Scope**: Close the gap between runtime correctness and human-visible Compose behavior.
**Status**: IMPLEMENTATION COMPLETE — DEVICE/OEM CERTIFICATION DEFERRED

The section above claimed Phase 1 complete on the strength of compilation, unit tests and "the logic
exists". This pass audited the same slice at the host/presentation boundary and found real defects: a caret
the runtime moved that no field followed, an Enter key the text field turned into a `\n`, a drag that
divided pixel offsets by a guessed 48dp row pitch, accessibility actions resolved against the wrong block,
rich inline content flattened to `plainText()`, and a save that cleared dirty state before the repository
had accepted anything. Each is fixed below.

## Validation table

| # | Item | Status | Implementation | Evidence | Remaining limitation |
|---|---|---|---|---|---|
| 1 | Caret/selection ↔ field focus two-way | PASS | `FlowCaretRequest` + `FlowHostRuntime.requestCaret` / `publishCaretRequest` / `afterHistoryStep` / `textLengthOf` / `syncSelectionToDocument`; `FlowEditableLine` focus application per `stamp` | `FlowHostCorrectionTest`: `caretRequest_isPublishedWhenACommandMovesTheCaret`, `_isNotRepublishedByACommandThatDoesNotMoveTheCaret`, `_stampIsMonotonic`, `_isNotAdvancedByAFieldReportedCaret`, `undo_publishesAClampedCaretRequest`, `undo_retargetsTheCaretWhenItsBlockDisappears`, `caretItemId_isDroppedWhenTheItemDisappears`; `FlowHostInstrumentationTest`: `caretRequest_movesFocusToTheRequestedField`, `_isAppliedOncePerStamp` | Instrumentation tests compile but were not executed (device testing deferred at owner's instruction) |
| 2 | Enter/Backspace writing semantics | PASS | `FlowHostRuntime.newLine` / `backspaceAtStart`; `BlockEditorSession.splitTodoItem` / `exitEmptyTodoItem` / `mergeTodoItemWithPrevious`; `FlowEditableLine.onPreviewKeyEvent`. Ordinary typing inside a to-do routes through `editTodoItem` — `editListItem` cannot see `TodoItem`, so every keystroke in a checklist was previously discarded | `FlowHostCorrectionTest`: `newLine_splitsAParagraphAtTheCaret`, `_splitsAListItemIntoTwo`, `_onAnEmptyListItemLeavesTheList`, `_splitsATodoItem`, `_declinesInsideACodeBlock`, `_usesTheSameCommandAsTheDockSplit`, `backspaceAtStart_mergesWithThePreviousBlock`, `_mergesListItems`, `_returnsFalseAtTheTopOfTheDocument`, `_returnsFalseWhenTheVocabularyDeclines`, `editText_coalescesPerItemNotPerBlock`; `FlowHostBugfixTest`: `todoTyping_mutatesTheDocument`, `_coalescesLikeOtherListItems`, `_preservesCheckedState`; instrumentation: `enter_splitsTheBlockInsteadOfInsertingANewline`, `backspaceAtStart_mergesInARealField` | Shift+Enter soft-break is latched from hardware modifiers only; soft keyboards send no modifier, so IME newline is treated as a structural split |
| 3 | Measured drag geometry replaces fixed pitch | PASS | `FlowDragGeometry` (new); `FlowRowBounds.documentIndex` carries the absolute document position; `FlowHostScreen` row `onGloballyPositioned` → `FlowDragState.bounds`, disposed when a row leaves composition; `detectDragGesturesAfterLongPress`; `FlowDragGeometry.edgeScroll` + the `withFrameNanos` loop keyed on `drag.sourceId` | `FlowHostCorrectionTest`: `dragGeometry_resolvesFromMeasuredBoundsNotAFixedPitch`, `_aboveTheMidpointPlacesBefore`, `_belowTheMidpointPlacesAfter`, `_coversTheWholeAxis`, `_clampsPastEitherEnd`, `_marksTheSourcePositionAsANoOp`, `_returnsNullWithNothingToPlaceAgainst`, `_indexCommitsAsARealMoveDownward`, `_indexCommitsAsARealMoveUpward`, `cancelDrag_leavesNoHistoryEntry`; `FlowHostBugfixTest`: `dragGeometry_usesAbsoluteDocumentIndices`, `_sortsRowsByDocumentIndex`, `_edgeScrollReturnsZeroInTheMiddle`, `_edgeScrollRampsNearTheTop`, `_edgeScrollRampsNearTheBottom` | Auto-scroll rate constants (96 px zone, 18 px/frame) are unverified against real finger movement without a device |
| 4 | No uncontrolled overlap during drag | PASS | `FlowBlockRow` `graphicsLayer`/`zIndex` lift + source placeholder tint; `FlowDropMarker`; haptics on pickup/target-change/drop | Same as item 3 — the marker is drawn from the identical `FlowDropPlacement` the commit uses, so preview and result cannot disagree | Marker rendering itself is visual and unverified without a device |
| 5 | Multi-select disables ordinary text interaction | PASS | `FlowBlockContext.selectionMode`; `FlowEditableLine` renders static `Text` (no field at all) in selection mode; to-do `Checkbox`, callout expand/collapse `IconButton`, table cell `clickable` and the row's `+` handle (button **and** its drag `pointerInput`) all disabled in selection mode, so no interactive child can steal the row's gesture | `FlowHostCorrectionTest`: `selectionMode_togglingDoesNotDisturbTheCaret`, `_cancelRestoresThePreModeSelectionAndCaret`, `_doneRetainsTheSelection`, `_caretDoesNotCollapseTheSelection`; instrumentation: `selectionMode_textFieldDoesNotTakeFocusOrTheCaret`, `_fieldRejectsInput` | Child-gesture suppression is structural (`enabled = !selectionMode` + early-return from `pointerInput`); that each disabled child genuinely forwards the tap to the row is a Compose fact, unverified without a device |
| 6 | Slash suppression resolved from the addressed block | PASS | `FlowHostRuntime.slashInputFor` now derives **both** suppression facts from the document: the block-kind context via `findById` on the addressed id (failing closed to `CODE` when the id is gone), and `literalInline` via `FlowInlineText.literalInlineAt`, which traverses inline wrappers recursively. The surface no longer supplies a classification it could get wrong — `slashContextOf` is deleted | `FlowSlashContextTest`: cases A–O (see matrix below), 18 tests; `FlowHostCorrectionTest`: `literalInline_isTrueInsideInlineCode`, `slashInputFor_carriesTheLiteralInlineFactFromTheDocument`, `slashMenu_staysClosedForASlashInsideInlineCode`, `slashMenu_opensForASlashInOrdinaryProse` | Atomic inline nodes other than `CodeInline`/`MathInline` (emoji, tag, mention) keep their previous non-literal treatment |
| 7 | Table cell establishes complete command context | PASS | `FlowHostRuntime.setTableCursor` now also selects the table and sets the caret; `tableCursor` in `FlowHostUiState`; `FlowTablePreview` highlights from that cursor | `FlowHostCorrectionTest`: `setTableCursor_makesTheTableThePrimaryTarget`, `_makesDirectionalCommandsImmediatelyInvokable`, `_isSurfacedInTheSnapshotForHighlighting`, `_ignoresACellInABlockThatDoesNotExist` | Cell *text* editing remains Phase-4 work (addressing only, as originally scoped) |
| 8 | Dock/surface overlap with editable content | PASS | `FlowHostScreen` measures the lower-surface stack via `onSizeChanged` and feeds it into `LazyColumn` `contentPadding`; caret-request `LaunchedEffect` scrolls the target above the measured limit | Static: bottom inset is derived from measured height, not a constant. No automated assertion. | **Presentation-only, unverified without a device.** Measured-inset logic is correct by construction but IME/dock positioning across form factors is a certification item |
| 9 | Bounded compact command surfaces | PASS | `FlowSlashMenu` and `FlowPaletteList` use `LazyColumn` with `heightIn(max = FLOW_COMPACT_LIST_MAX_HEIGHT)`; tablet pane unbounded | Static: no unbounded `Column` of results remains | Same as item 8 — row-count-on-device is a certification matter |
| 10 | Large-screen writing measure | PASS | `FlowHostScreen`: the list item is the full-width, `TopCenter`-aligned `Box`; the width cap sits on the *inner* row, so `fillMaxWidth()` no longer defeats `widthIn` | Static: modifier order corrected; cap is no longer on a `fillMaxWidth()` sibling | Visual centering unverified without a device |
| 11 | Genuinely adaptive phone header | PASS | `FlowTopBar(compact = …)` moves Select into a `DropdownMenu` below `FLOW_COMPACT_WIDTH_DP`; title is the only element taking remaining space | Static: essential nav + Undo/Redo always present; secondary control in a labelled overflow, not a hidden gesture | Large font scale / RTL overflow behavior is a certification item |
| 12 | Title editing reachable | PASS | `FlowTitleField` — a real `BasicTextField`, re-seeded only when the *owner's* value changes; newlines flattened | Static: `onTitleChange` is now reachable by the user rather than wired to nothing | — |
| 13 | Persistence/save contract | PASS | `FlowSaveCoordinator` (new) is the single authoritative save path for body edits, structural edits, deletions, title renames and the exit flush. It debounces (1.5 s idle), serializes (one write in flight for the document, asserted), retries a failure on bounded backoff (1 s → 30 s cap) **without waiting for another edit**, captures document/title/dirty/deleted/baseline-generation as one snapshot per attempt, and advances the baseline only via `FlowEditController.markSavedIfCurrent` — so a completion that raced a remote merge is discarded rather than applied. It runs on `viewModelScope`, so a pending retry outlives the composition; the title fire-and-forget path is gone | `FlowSaveCoordinatorTest`: cases A–W (see matrix below), 25 tests | Durability is only as authoritative as the repository's own write; a persistent Room failure surfaces as an indefinitely-retried pending state, not as a user-visible error |
| 14 | Accessibility actions resolve per row | PASS | `FlowHostRuntime.accessibilityActionsFor` / `invokeAccessibilityOn` / `withTemporarySelection`; `FlowBlockRow.semantics` resolves per block | `FlowHostCorrectionTest`: `accessibilityActionsFor_describeTheRequestedRow`, `_restoresTheSelectionItBorrowed`, `_keepsDisabledEntriesWithReasons`, `invokeAccessibilityOn_operatesOnTheRowTheActionsDescribed`, `_isTheSameCommandAsTheDockRoute` | TalkBack traversal itself remains deferred certification; the semantics tree and command context are structurally correct |
| 15 | Inline formatting visibly preserved | PASS | `FlowInlineText.runsOf` (new); `flowInlineSpans` + `FlowInlineTransformation` in `FlowBlockContent`, reusing the legacy `highlightColor` / `NorfoldFontFamilies` | `FlowHostCorrectionTest`: `runsOf_reportsNestedStylesInPlainTextOffsets`, `_tilesTheWholeTextWithoutGapsOrOverlap`, `_carriesEveryInlineAttributeTheDocumentSupports`, `editText_preservesInlineStylingOutsideTheEditedRange`; instrumentation: `inlineFormatting_isRenderedRatherThanFlattenedAway` | Inline styling is **rendered, not editable** — applying/removing marks is Phase-3 work as originally scoped. This is the least-misleading preserved rendering, not a flatten |
| 16 | Collapsed/nested content reachable | PASS | `CalloutBlock` branch gains a labelled expand/collapse `IconButton` → `FlowHostRuntime.toggleCollapsed` (through History); **`BlockTree.containerChildren`/`withChildren` widened to `QuoteBlock` and `CalloutBlock`** | `FlowHostCorrectionTest`: `toggleCollapsed_expandsAndCollapsesThroughHistory`, `_ignoresBlocksThatDoNotCollapse`, `nestedContent_isReachableByTheStructuralCommands`; instrumentation: `collapsedCallout_canBeExpandedByTappingTheControl` | — |
| 17 | No invented non-goals | PASS | Deterministic drag placement, drop feedback, Enter/Backspace, nested/container editing, caret/focus sync and touch selection are all implemented above rather than deferred | The table itself | Deferred items are named as device/OEM certification only |
| 18 | Correct architecture preserved | PASS | No engine replaced. `FlowHostRuntime`, `CommandRegistry`, `SelectionEngine`, `HistoryEngine`, `SurfaceCoordinator`, `AdaptivePresentationRuntime`, `InteractionMemory`, `FlowEditController`, stable-ID model and default-off `FLOW_HOST` all intact; fixes are at integration seams and presentation | 619 unit tests pass, including the whole pre-existing Phase-0/Phase-1 suite | — |
| 19 | Repository-scope discipline | PASS | Only FlowHost, its domain seams and directly necessary tests/docs touched | See file list below | — |
| 20 | Validation | PARTIAL | — | 619 unit tests pass (`testDebugUnitTest`, 0 failures, up from 509 at HEAD 699c746); `assembleDebug` succeeds; `compileDebugAndroidTestKotlin` succeeds. Six mutations were applied to the fixed code and each was confirmed to break specific tests: reverting the mergeRemote baseline (3 fail), removing automatic retry (6 fail), ignoring the stale-completion epoch (1 fail), dropping title dirtiness (2 fail), reverting recursive literal detection (5 fail), and classifying slash context from the top-level row (4 fail). The Todo-typing and drag-index tests were mutation-verified in the prior pass | **Instrumentation tests were compiled but not executed.** Device testing was explicitly deferred by the owner for the end of the phase programme |

## Root-cause note: `BlockTree` recursion

The nested-content defect was not presentational. `DocumentBlock.containerChildren()` recognised only
`ContainerBlock`, but `QuoteBlock` and `CalloutBlock` also store `List<DocumentBlock>`. Since that helper is
the *only* recursion `findById`, `allIds`, `locate`, `updateBlock` and every structural command are built on,
a paragraph inside a quote or callout was unaddressable — visible on screen, unreachable by the commands
operating on it. Both helpers are used exclusively inside `BlockTree.kt`, so widening them fixed the whole
tree vocabulary coherently rather than patching one call site.

## Final bug-fix pass (2026-07-30)

Direct code review of the committed corrective pass found seven defects the pass itself had introduced or
missed. All seven are fixed; the table above reflects the corrected state.

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | A deletion-only change was never persisted | `saveIfDirty` returned early on `dirtyBlockIds().isEmpty()`, but `dirtyBlockIds()` deliberately excludes removals — those live in `deletedBlockIds()`. A note whose only change was deleting a top-level block therefore scheduled no write, and the block reappeared on reopen | Schedule when *either* set is non-empty. `persistDocument` already derives row deletions from the document itself, so the existing repository contract needed no change |
| 2 | A failed write was treated as a successful one | `runCatching { …join() }` cannot observe the failure: `join()` waits for completion and returns `Unit` whether the coroutine finished or threw, so `isSuccess` was true for a write that had thrown, and `markSaved` cleared the baseline — losing the edit permanently, because the next tick saw nothing dirty | Await a `CompletableDeferred<Result<Unit>>` that the Job's `invokeOnCompletion` handler resolves; that handler *does* receive the exception. `markSaved` only on genuine success, so a failure leaves the dirty/deleted baseline intact and retryable |
| 3 | Typing inside a to-do did nothing | `editText` sent every item edit to `BlockEditorSession.editListItem`, which resolves items through a private `listItems()` helper covering only `BulletListBlock`/`NumberedListBlock`. `TodoListBlock` holds `TodoItem`, so the call returned a cursor while changing nothing — every keystroke in a checklist was silently discarded | Dispatch on the block type: `editTodoItem` for to-dos, `editListItem` otherwise. Also added a `document() == previous` guard so a no-op never reports success |
| 4 | Drops landed at the wrong index in a long note | `FlowRowBounds` carried no document position, so `placementFor` returned an index into the *measured* rows. A virtualized window showing document blocks 80–90 measures eleven rows at positions 0–10, so a drop near block 85 committed at index 5 | `FlowRowBounds.documentIndex` carries the absolute position and every returned index is derived from it. Rows are sorted by that index rather than trusted in layout order, and bounds are removed on `onDispose` so a scrolled-away row stops claiming a screen position |
| 5 | Drag could not reach beyond the initial viewport | No edge auto-scroll existed, so a reorder across a long note was impossible — the previous report listed this as a known gap rather than a defect | `FlowDragGeometry.edgeScroll` (pure, unit-tested) returns a per-frame scroll that ramps with depth into the edge zone; a `withFrameNanos` loop keyed on `drag.sourceId` applies it, re-measures the placement each frame, and stops when the drag ends. `pointerY` tracks raw touch deltas only and `lift` is recomputed from it, so auto-scroll cannot double-count as finger travel |
| 6 | Child controls stole the gesture in selection mode | Only the text field and the to-do checkbox were suppressed. The `+` handle (button *and* its long-press drag detector), table cell clicks and the callout expand/collapse control were all still live, so a tap meant to select a block inserted, addressed a cell, or collapsed a callout instead | `enabled = !selectionMode` on each interactive child, and an early return from the handle's `pointerInput` so the drag detector is not installed at all. Non-selection behaviour is unchanged |
| 7 | A stale title could be re-persisted | The autosave `LaunchedEffect(owner.documentId)` and exit `DisposableEffect(owner.documentId)` captured `title` at composition. Neither restarts on a rename, so both kept writing the title as it was when the note opened — a rename followed by any later body edit persisted the *old* title over the new one | Both effects read the value through `rememberUpdatedState`, which updates the captured reference without restarting the effect, so the debounce is not reset on every keystroke in the title |

Two of these were confirmed as genuine regressions by re-running the new tests against the pre-fix code:
`todoTyping_mutatesTheDocument` / `_coalescesLikeOtherListItems` and `dragGeometry_usesAbsoluteDocumentIndices`
/ `_sortsRowsByDocumentIndex` all fail without their fixes.

## Commits

Committed as coherent behavior-sized slices (see `git log`), not micro-commits.

## Files changed

Modified:
- `apps/android/build.gradle.kts` (Compose UI test artifacts)
- `apps/android/src/main/java/com/norfold/app/domain/BlockTree.kt`
- `apps/android/src/main/java/com/norfold/app/domain/BlockEditorSession.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowCommandScope.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowEditController.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowHostRuntime.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/FlowNoteEditorScreen.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowBlockContent.kt`
- `apps/android/src/main/java/com/norfold/app/ui/editor/flow/FlowHostScreen.kt`
- `apps/android/src/androidTest/java/com/norfold/app/editor/flow/FlowHostInstrumentationTest.kt` (replaced a placeholder that asserted only the flag-injection mechanism)
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowEditControllerTest.kt`

New:
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowDragGeometry.kt`
- `apps/android/src/main/java/com/norfold/app/editor/flow/FlowInlineText.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostCorrectionTest.kt`
- `apps/android/src/test/java/com/norfold/app/editor/flow/FlowHostBugfixTest.kt` (the 2026-07-30 bug-fix pass)

## Build results

- `:apps:android:testDebugUnitTest` — BUILD SUCCESSFUL, **619 tests, 0 failures**
- `:apps:android:assembleDebug` — BUILD SUCCESSFUL
- `:apps:android:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL

## Scope confirmations

- **No unrelated collaboration, website, Telegram, Supabase, infrastructure, provider or agent-skill files
  were changed.** The frozen collaboration foundation was not modified. Pre-existing unrelated working-tree
  changes were left untouched and out of these commits.
- **Phase 2 was not started.** No `DocumentHost`, no Scene model, no CRDT selection, no collaboration
  binding, no room partitioning, no transport changes, no roadmap rewrite.

## Honest status

Items 1–19 are implemented, including the seven defects found on 2026-07-30. Item 20 is **partial**: every
statically-testable behavior is covered by automated tests that pass, but the Compose instrumentation tests
are compiled-only — the owner deferred all device execution to the end of the phase programme. Items 8, 9,
10 and 11 are presentation fixes whose *logic* is correct by construction (measured insets, bounded lists,
corrected modifier order, overflow menu) but whose on-screen result has not been observed. Edge auto-scroll
is implemented and its placement rule is unit-tested, but its *feel* — the 96 px zone and 18 px/frame rate —
has not been checked against a real finger.

**Phase 1 implementation gate: satisfied. Device certification: outstanding, by owner instruction.**

---

# Data-safety + nested-context closure (2026-07-30, second pass)

The seven fixes above were accepted. Direct review of the committed implementation then found five further
defects, all of the same character: code that *looks* safe and is not. They are closed here.

## Root causes

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | A failed write was retryable but never retried | The failure left the dirty/deleted baseline intact and relied on "the next tick" — but the only tick was a debounce on `presenter.state.revision`. If the user stopped typing, no revision ever arrived, so the write was never attempted again. "Retryable" is not "will retry" | `FlowSaveCoordinator` re-arms after every failure on bounded backoff (1 s, 2 s, 4 s … capped at 30 s), with no user activity required, and resets the backoff on success |
| 2 | Title saves bypassed the save contract | `onTitleChange` called `viewModel.updateActiveDocument(...)` directly on every keystroke, fire-and-forget: not debounced, not serialized against a body write, not retried, and its failure unobservable. Title dirtiness was also untracked, because the title lives outside the document the controller diffs | Title editing updates `desiredTitle` and calls `requestSave()`. The coordinator tracks `desiredTitle` vs `persistedTitle` explicitly, so a title-only rename schedules a write even with an empty dirty set — verified against the repository, where `updateNote` writes the title through `dao.updateNoteContent` regardless of `dirtyBlockIds` |
| 3 | Remote merge marked unsaved local content as persisted | `mergeRemote` set `lastSavedDocument = merged`. The merge result *contains* the local dirty blocks it protected from the remote snapshot, so those blocks went clean without ever having been written — and a process death then lost content the server had never seen | The baseline becomes the accepted **remote** snapshot, which is the only document there is evidence for; the working document stays the merge result. Diffing the two re-derives the protected changes as dirty, leaves untouched remote changes clean, and legitimately clears a dirty block whose content the remote now genuinely carries |
| 4 | A stale completion could overwrite a newer baseline | Nothing tied a write's result to the baseline it was measured against. A write of the pre-merge snapshot completing after a remote merge baselined that snapshot — which made the remote's own block look like a local edit, so the editor wrote the remote's content back as though the user had typed it | `FlowEditController.baselineGeneration()` is captured in each `FlowSaveRequest`; `markSavedIfCurrent` applies the baseline only if the generation still matches, and declines otherwise. Declining leaves content dirty for the next attempt — one redundant write is far cheaper than one lost edit |
| 5 | A nested code block was treated as prose | `slashContextOf(block)` was computed in `FlowBlockRow` from the **top-level row's** type. Quotes, callouts and containers render children recursively while reusing the outer row's callbacks, so a `ContainerBlock → CodeBlock` reported `PROSE` and the command menu opened on a `/` the user was typing into code | Authority moved into `FlowHostRuntime.slashInputFor`, which resolves the addressed `blockId` against the document and classifies *that* block. Unknown ids fail closed to `CODE`. The surface has no classification to disagree with |
| 6 | Wrapped inline literals evaded suppression | `literalInlineAt` asked whether the *top-level* node was `CodeInline`/`MathInline`. The schema permits wrappers, so ``**`a/b`**`` is `BoldInline(CodeInline)` — the check saw only the bold and called it prose | The traversal recurses through stylistic wrappers (bold, italic, underline, strike, highlight, font family/size, link) while keeping flattened plain-text offsets, so a literal payload is literal at any depth. Wrapped *plain text* stays prose |

## Persistence regression matrix

All in `FlowSaveCoordinatorTest`.

| Case | Behavior | Result | Test |
|---|---|---|---|
| A | ordinary body edit succeeds | PASS | `caseA_ordinaryBodyEditIsPersisted` |
| B | deletion-only change succeeds | PASS | `caseB_deletionOnlyChangeIsPersisted` |
| C | title-only rename succeeds | PASS | `caseC_titleOnlyRenameIsPersisted` |
| D | title + body succeeds | PASS | `caseD_titleAndBodyPersistTogether` |
| E | title + deletion succeeds | PASS | `caseE_titleAndDeletionPersistTogether` |
| F | first write fails → retries with no further edit | PASS | `caseF_firstFailureRetriesWithoutAnotherEdit` |
| G | repeated failures back off rather than spin | PASS | `caseG_repeatedFailuresBackOffRatherThanSpin`, `caseG_backoffIsCapped` |
| H | success resets retry/backoff | PASS | `caseH_successResetsTheBackoff` |
| I | failure keeps body dirty | PASS | `caseI_J_K_failureKeepsBodyDeletionAndTitleDirty` |
| J | failure keeps deletion pending | PASS | `caseI_J_K_failureKeepsBodyDeletionAndTitleDirty` |
| K | failure keeps title dirty | PASS | `caseI_J_K_failureKeepsBodyDeletionAndTitleDirty` |
| L | edit during save stays dirty after old snapshot succeeds | PASS | `caseL_editDuringSaveStaysDirtyAfterTheOldSnapshotSucceeds` |
| M | rename during save stays pending after old title succeeds | PASS | `caseM_renameDuringSaveStaysPendingAfterTheOldTitleSucceeds` |
| N | deletion during save remains pending | PASS | `caseN_deletionDuringSaveRemainsPending` |
| O | old completion after remote merge cannot overwrite newer baseline | PASS | `caseO_staleCompletionAfterRemoteMergeCannotRewriteRemoteContent` |
| P | clean remote block update does not become local dirty | PASS | `caseP_cleanRemoteUpdateDoesNotBecomeALocalEdit` |
| Q | local dirty block survives remote merge and stays dirty | PASS | `caseQ_localDirtyBlockSurvivesRemoteMergeAndRemainsDirty` |
| R | local deletion survives remote merge and stays pending | PASS | `caseR_localDeletionSurvivesRemoteMergeAndRemainsPending` |
| S | remote snapshot matching local dirty content clears it | PASS | `caseS_remoteSnapshotMatchingLocalDirtyContentClearsThatDirtyState` |
| T | no more than one persistence call in flight | PASS | `caseT_onlyOneWriteIsEverInFlight` |
| U | exit during debounce requests immediate flush | PASS | `caseU_exitDuringDebounceRequestsAnImmediateFlush` |
| V | exit during retry does not mark unsaved data saved | PASS | `caseV_exitDuringRetryDoesNotMarkUnsavedDataSaved` |
| W | repository failure is observed, not inferred from `join()` | PASS | `caseW_repositoryFailureIsObservedNotInferred` |
| 5.9 | remote echo of our own write does not loop | PASS | `remoteEchoOfOurOwnWriteDoesNotLoop` |
| — | loop drains pending work then exits after close | PASS | `loopDrainsPendingWorkThenExitsAfterClose` |

## Slash regression matrix

All in `FlowSlashContextTest`.

| Case | Behavior | Result | Test |
|---|---|---|---|
| A | Paragraph `/foo` → allowed | PASS | `caseA_paragraphAllowsTheResolver` |
| B | top-level CodeBlock → suppressed | PASS | `caseB_topLevelCodeBlockSuppresses` |
| C | Container → CodeBlock → suppressed | PASS | `caseC_containerNestedCodeBlockSuppresses` |
| D | Callout → CodeBlock → suppressed | PASS | `caseD_calloutNestedCodeBlockSuppresses` |
| E | Quote → CodeBlock → suppressed | PASS | `caseE_quoteNestedCodeBlockSuppresses` |
| F | Container → Paragraph → allowed | PASS | `caseF_containerNestedParagraphAllows` |
| G | Callout → Paragraph → allowed | PASS | `caseG_calloutNestedParagraphAllows` |
| H | Quote → Paragraph → allowed | PASS | `caseH_quoteNestedParagraphAllows` |
| I | `CodeInline` → suppressed | PASS | `caseI_bareCodeInlineSuppresses` |
| J | Bold → `CodeInline` → suppressed | PASS | `caseJ_boldWrappedCodeInlineSuppresses` |
| K | Link → Italic → `CodeInline` → suppressed | PASS | `caseK_linkItalicWrappedCodeInlineSuppresses` |
| L | Bold → `MathInline` → suppressed | PASS | `caseL_boldWrappedMathInlineSuppresses` |
| M | Bold → `InlineText` → allowed | PASS | `caseM_boldWrappedPlainTextRemainsProse` |
| N | URL / path / date / fraction suppression | PASS | `caseN_urlPathDateAndFractionRemainSuppressed` |
| O | `//` behavior | PASS | `caseO_doubledSlashRemainsSuppressed` |
| — | unknown block id fails closed | PASS | `unknownBlockIdFailsClosed` |
| — | boundary offsets around a literal span | PASS | `literalBoundaries_areExactAtEveryProbe` |
| — | wrapped literal suppresses the resolver end to end | PASS | `wrappedCodeInlineSuppressesTheResolverEndToEnd` |

## Mutation proof

Each fix was reverted in place and the suite re-run, to prove the tests detect the defect rather than restate
current behavior. No mutation was committed.

| Mutation | Tests that failed |
|---|---|
| `mergeRemote` baselines the merged working document again | 3 — cases O, Q, S |
| failure returns instead of scheduling a retry | 6 — cases F, G, H, I/J/K, V, W |
| completion baselines unconditionally (no epoch check) | 1 — case O |
| `hasPendingWork` ignores title dirtiness | 2 — cases C, M |
| `literalInlineAt` checks only the top-level node | 5 — cases J, K, L, boundary, end-to-end |
| slash context classified from the top-level row | 4 — cases C, D, E, fail-closed |

The first draft of case O asserted only final convergence, which held with *and* without the epoch guard — the
mutation run caught that the test was too weak, and it was rewritten to assert the dirty set left by the stale
completion, where the harm actually shows.

## Race safety

| Scenario | Behavior |
|---|---|
| edit during save | State becomes `DIRTY_WHILE_SAVING`. The completion baselines only its own snapshot, so the newer edit stays dirty; the loop continues rather than returning, and writes it immediately. |
| title during save | Identical. `persistedTitle` advances to the *request's* title, not the current one, so a rename made mid-write stays pending. |
| delete during save | Identical; `deletedBlockIds` is re-read per attempt. |
| failure then idle | Backoff timer fires the next attempt. No user activity required — this is the defect that was fixed. |
| repeated failure | 1 s → 2 s → 4 s … capped at 30 s, one attempt at a time, no coroutine per attempt. |
| remote during save | The merge advances the baseline generation. The in-flight write is not cancelled. |
| stale completion after remote | `markSavedIfCurrent` declines, content stays dirty, the loop reconciles against the current baseline. |
| remote echo of our own write | `mergeRemote` skips baselining when the remote snapshot already equals the baseline, so an echo neither churns the generation nor invalidates an in-flight write, and cannot start a save/merge/save cycle. |
| screen disposal during pending work | `flush()` skips the debounce and marks the loop to close *after* draining. The loop is on `viewModelScope`, so retries continue after the composable is gone; once everything pending is accepted it exits, leaving no parked coroutine per note. |

## Deliberate design notes

- **`NonCancellable` removed.** It was papering over ownership: the retry lifecycle now belongs to
  `viewModelScope`, which is the scope that genuinely outlives the composition, so nothing needs to be made
  uncancellable to survive teardown.
- **`markSaved(document)` retained** for the non-racing paths (tests, initial bind); `markSavedIfCurrent` is
  what the coordinator uses.
- **`mergeRemote` return value narrowed** to "the working document changed" — the baseline may advance without
  it, which is what lets a remote snapshot matching local content clear that dirty state.
- **One assertion, not a mutex.** Serialization is structural (one loop, one sequential `await`), but
  `check(!writing)` makes a future refactor that breaks it fail loudly rather than silently reorder baselines.

## Test results

- Targeted: `FlowSaveCoordinatorTest` 25, `FlowSlashContextTest` 18 — all pass
- Full unit suite: `:apps:android:testDebugUnitTest` — **619 tests, 0 failures** (up from 577)
- `:apps:android:assembleDebug` — BUILD SUCCESSFUL
- `:apps:android:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL (compiled, **not executed**)

## Files changed

| File | Change |
|---|---|
| `editor/flow/FlowSaveCoordinator.kt` | **New.** The persistence state machine, `FlowSaveRequest`, `FlowPersister` |
| `editor/flow/FlowEditController.kt` | `baselineGeneration()`, `markSavedIfCurrent`, `setBaseline`; `mergeRemote` baselines the remote snapshot |
| `editor/flow/FlowHostRuntime.kt` | `slashInputFor` derives block context from the addressed block; `context` parameter removed |
| `editor/flow/FlowInlineText.kt` | `literalInlineAt` recurses through inline wrappers; `styleChildren` helper |
| `ui/editor/FlowNoteEditorScreen.kt` | Wires the coordinator on `viewModelScope`; title routes through it; `saveIfDirty`, `NonCancellable` and the debounce effect removed |
| `ui/editor/flow/FlowHostScreen.kt` | `slashContextOf` deleted; four now-dead imports removed |
| `test/…/FlowSaveCoordinatorTest.kt` | **New.** Persistence matrix A–W |
| `test/…/FlowSlashContextTest.kt` | **New.** Slash matrix A–O |
| `test/…/FlowHostCorrectionTest.kt` | Three `slashInputFor` call sites updated to the new signature |
| `docs/editor/PHASE_1_FINAL_REPORT.md` | This section; rows 6, 13, 18, 20 updated; counts 577 → 619 |

## Scope check

- Phase 2 not started; no `DocumentHost`, no Scene model, no Page Control
- Collaboration untouched; no CRDT, no merge engine, no room partitioning, no transport changes
- No Supabase / Ably / Cloudflare / Telegram / website / roadmap changes
- No `BlockDocument` schema version bump; no Room schema change
- `FLOW_HOST` still default-off
- No AI attribution anywhere in the diff or the commits

## Status

| Gate | State |
|---|---|
| Phase 1 implementation | COMPLETE |
| Static / data-safety audit | PASS |
| Nested-context audit | PASS |
| Device certification | DEFERRED (owner instruction) |
| `FLOW_HOST` | DEFAULT OFF |

Every regression-matrix case passes. A failed save retries itself; title saves go through the coordinator;
`mergeRemote` cannot launder unsaved local content into the baseline; a stale completion cannot overwrite a
newer baseline; only one write races for a document; a nested code block is never prose; and a wrapped
`CodeInline`/`MathInline` never escapes suppression.

What remains outstanding is unchanged and is device work, not logic: drag feel, IME animation, TalkBack
navigation, large font / RTL, and performance profiling.
