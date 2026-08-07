# Phase 4 Final Report — Shared Calculation Engine and SpreadsheetHost

**Release stage:** PRE_BETA
**Branch:** `main`
**Starting SHA:** `ab4df0a09056ce45e1ec9bde4e02e8b01673ad69`
**Closure SHA:** `e1762a6` (`fix(editor): close remaining phase 4 workflows`)
**Validation date:** 2026-08-04, reconciled 2026-08-07
**JDK:** Eclipse Adoptium 21 (JDK 25 breaks this build)
**Gradle / Kotlin:** 9.4.1 / 2.3.0

**PHYSICAL TABLE/SPREADSHEET DEVICE CERTIFICATION: DEFERRED**

No emulator, physical device, screenshot, TalkBack session, or live deployment was used. Every claim below is
backed by a compiled source path plus an automated test or a measured instrumentation counter. Accessibility
rows state the implemented semantics; they are **not** a claim of assistive-technology certification, which
requires the deferred device pass.

---

## 36-row implementation ledger

| Item | Status | Real implementation path | Automated/source evidence | Remaining limitation |
| --- | --- | --- | --- | --- |
| 1. Shared pure-JVM calculation domain | Complete | `editor/calculation/CalculationModel.kt` | `CalculationEnginePhase4Test` (15 tests); no Compose/Android/network import in the package | None |
| 2. Typed formula AST | Complete | `editor/calculation/FormulaAst.kt` | `Phase4RequiredValidationTest` cases 1–20 | None |
| 3. Bounded lexer/Pratt parser | Complete | `editor/calculation/FormulaParser.kt` | cases 10–12, 34–37; `resourceBudgetsFailSafely…` | None |
| 4. Offline evaluator | Complete | `editor/calculation/CalculationEngine.kt` | cases 1–42 | None |
| 5. Function catalogue (63 names) | Complete | `CalculationEngine.function()` | cases 21–33; `CalculationFunctionBaselineTest` (33 tests). Catalogue: ABS AND AVERAGE AVERAGEIF AVERAGEIFS COLUMN COLUMNS CONCAT CONCATENATE COUNT COUNTA COUNTIF COUNTIFS DATE DATEVALUE DAY FIND HLOOKUP HOUR IF IFERROR IFNA IFS INDEX INT LEFT LEN LOWER MATCH MAX MID MIN MINUTE MOD MONTH NOT NOW OR POWER PRODUCT RIGHT ROUND ROUNDDOWN ROUNDUP ROW ROWS SEARCH SECOND SIGN SQRT SUBSTITUTE SUM SUMIF SUMIFS TEXTJOIN TIME TODAY TRIM UPPER VALUE VLOOKUP XLOOKUP YEAR | 31 names added at closure (CONCATENATE is an alias of CONCAT). Still the authorized baseline, not Excel parity |
| 6. Structured typed errors | Complete | `CalculationError`, `CalculationErrorKind` | cases 34–37; `countFunctionsSurfaceRangeFailures…` | None |
| 7. Stable identity vs display name | Complete | `SheetId`/`RowId`/`ColumnId`/`CellId` + `canonicalName` | cases 79, 86, 89 (rename rewrites formulas) | None |
| 8. Sparse durable cell storage | Complete | `CalculationSheet.cells` + `isDurablyEmpty()` | case 169: a 1,000×200 sheet holds 0 cells | None |
| 9. Incremental dependency graph | Complete | `dependencies`/`dependents`, `dependencyClosure`, `replaceWorkbook(next, changed)` | case 174; measured: one unrelated edit recalculated **0** of 50 formulas; a dependency edit recalculated exactly **50** | First write to a previously sparse coordinate rebuilds once, by design |
| 10. Cycle detection and recovery | Complete | `calculateCell` stack + `CalculationErrorKind.CIRCULAR` | cases 40–41 | None |
| 11. Structural row/column operations | Complete | `editor/calculation/CalculationMutations.kt` | cases 58–77 | None |
| 12. Formula-aware reference rewriting | Complete | `FormulaReferenceTransform` via typed AST | cases 68–75; case 135 is behavioural (`=A1*2` → `=A2*2` after insert) | None |
| 13. Sheet lifecycle | Complete | `addSheet`/`renameSheet`/`reorderSheet`/`duplicateSheet`/`deleteSheet` | cases 78–84; `activatingAHiddenSheetRevealsIt…`, `deletingTheLastVisibleSheetRevealsASurvivor`; `deletingTheSheetThatCarriesTheEmbeddedTableIsRefused…` | Deleting the sheet the host is embedded in is refused with a reason rather than performed |
| 14. Named ranges | Complete | `NamedRange` + host routes `namedRangeSummaries`/`goToNamedRange`/`renameNamedRange`/`deleteNamedRange`; `NamedRangeManager` in `SpreadsheetHostScreen.kt` | cases 85–88; `NamedRangeWorkflowTest` (13 tests) | A range whose coordinates no longer resolve is listed and deletable, but Go-to is disabled for it |
| 15. Structured table references | Complete | `FormulaExpression.StructuredReference` | case 89 | None |
| 16. Merge / split | Complete | `CalculationMutations.merge`/`split` | cases 122–123: cell records are byte-identical before and after | Merge is presentational; no cell data is touched, so split is exactly reversible |
| 17. Paste, fill, series | Complete | `pasteGrid`, `fill`, `fillSeries`, `pasteStructured` | cases 98, 124–126; `SpreadsheetClipboardTest` (10 tests); one mutation batch per operation | A copy taken in another app takes the plain-text path, matched by comparing the system text against `SpreadsheetClipboard.exportedText` |
| 18. Data validation | Complete | `ValidationRule` + `CalculationEngine.validate`; host routes `validationRules`/`deleteValidationRule`/`setValidationWarnOnly`; `RuleManager` in `SpreadsheetHostScreen.kt` | cases 90–98; `RuleManagementWorkflowTest` (19 tests) | Relaxing a rule to warn-only is confirm-once per cell, not silent acceptance |
| 19. Derived conditional formatting | Complete | `conditionalFormat()`; host routes `conditionalRules`/`setConditionalRuleEnabled`/`moveConditionalRule`/`deleteConditionalRule` | cases 99–106; case 105 proves nothing is persisted; `RuleManagementWorkflowTest` | Reordering renumbers priorities densely from 1, so repeated moves cannot drift into duplicate or sparse priorities |
| 20. Sort and filter | Complete | `CalculationMutations.sort`/`filter`/`clearFilters` | cases 127–128: filtering hides rows without editing cells | None |
| 21. Table styles with local override | Complete | `editor/calculation/TableStyleResolver.kt` | cases 107–131, esp. 131 | None |
| 22. Resource budgets | Complete | `CalculationLimits`, `EvaluationBudget` | case 42; measured: a 100,000-cell range evaluates, 99,999 refuses with `#LIMIT!` | None |
| 23. Number-width safety | Complete | `parseFiniteDecimal`, `isRenderableDecimal`, `calculatedNumber` | `numbersTooWideToRenderBecomeErrorsRatherThanCrashes` | Bound is 1e±100,000; wider values become text or a `RESOURCE_LIMIT` cell error |
| 24. Instrumentation counters | Complete | `CalculationInstrumentation` | case 178; used for every performance number in this report | Counters are process-local, not persisted |
| 25. Additive typed persistence | Complete | `TableBlock.calculationPayload/calculationTableId/calculationSchemaVersion` with `EncodeDefault.NEVER` | cases 151, 158–165; legacy JSON round-trips byte-identical | None |
| 26. Copy-on-write legacy upgrade | Complete | `TableCalculationBridge.upgrade` | cases 152–155; idempotent per 155 | None |
| 27. Legacy recovery pre-image | Complete | `LegacyTableRecovery` + `restoreLegacy` | cases 156–157 | None |
| 28. Corrupt-payload safety | Complete | `TableCalculationBridge.view`/`upgrade` | `aCorruptTypedPayloadIsViewableButCanNeverBeOverwritten` | A corrupt payload opens read-only; edits are refused rather than overwriting the sole copy |
| 29. Legacy row-index fidelity | Complete | `TableCalculationBridge.legacyWorkbook` | `aHeaderlessTableKeepsItsRowIndices…` | None |
| 30. Flow table integration | Complete | `editor/flow/FlowHostRuntime.editTableCell`, `FlowTableOps`; draft held in `FlowCommandScope.tableDraft` | cases 132–135, all behavioural; `TableDraftLifecycleTest` (16 tests) | None |
| 31. Document table integration | Complete | `editor/document/DocumentHostRuntime.editObjectTableCell` + `editingTableCell`/`editingTableDraft` | cases 136–140; `documentTableCommitsOnlyInEditIntentAndReadModeRefuses`; `TableDraftLifecycleTest` | Leaving edit intent discards the draft rather than writing it |
| 32. SpreadsheetHost runtime | Complete | `editor/calculation/SpreadsheetHostRuntime.kt` | cases 141–150; `spreadsheetHostIsDistinct…` | None |
| 33. Spreadsheet UI | Complete | `ui/editor/spreadsheet/SpreadsheetHostScreen.kt` (1,145 lines) | cases 143–147, 170–173; `SpreadsheetUiStateOwnershipTest` (12 tests) | Interaction feel is device-deferred; behaviour is covered. The cell draft, formula bar and scroll position are runtime state, so no recomposition can discard them |
| 34. Shared Command Registry routing | Complete | `editor/calculation/SpreadsheetCommands.kt` (37 ids) | case 62 invokes through the registry and asserts the structural effect; dock buttons route via `run(SpreadsheetCommandIds.…)` | Four insertion ids reuse the existing `flow.table.*` strings by design (§21) |
| 35. Shared Color Studio for cell fill | Complete | `SpreadsheetHostRuntime.colorStudio` + `rememberSpreadsheetColorActions` | `openCellFillStudio` opens on common fill or MIXED; preview paints without history, commit is one transaction | Gradients are not offered for cell fill; a fill is a flat colour |
| 36. Read-only Phase-5 range seam | Complete | `editor/calculation/CalculationRangeSeam.kt` | `phaseFiveRangeSeamReadsCalculatedValues…` | Deliberately no chart model, renderer, command, or UI |

---

## Final gate

Run once, in this order, after both review passes:

```
./gradlew :apps:android:testDebugUnitTest            BUILD SUCCESSFUL
./gradlew :apps:android:compileDebugKotlin           BUILD SUCCESSFUL
./gradlew :apps:android:assembleDebug                BUILD SUCCESSFUL
./gradlew :apps:android:lintDebug                    BUILD SUCCESSFUL
./gradlew :apps:android:compileDebugAndroidTestKotlin BUILD SUCCESSFUL
```

| Field | Value at closure (2026-08-07) | At first gate (2026-08-04) |
| --- | --- | --- |
| Total tests | 1212 | 1100 |
| Failures | 0 | 0 |
| Errors | 0 | 0 |
| Skips | 0 | 0 |
| Compiler errors | 0 | 0 |
| Compiler warnings in Phase-4 sources | 0 | 0 |
| Lint | 0 errors, 126 warnings, 16 hints | not run |
| APK size | 56,015,962 bytes | 56,015,962 bytes |
| APK SHA-256 | `4b5a6237314a565eab15033c4cd8f2471d922dac4605c24abdb312b9f2f586a8` | `2bc315e6a3c6a4d733a1959a674cc2043f5f03bc74e9f1a8d7580057c6f5f8d4` |
| `git diff --check` | clean | clean |

APK path: `apps/android/build/outputs/apk/debug/android-debug.apk`.

Phase-4 test classes: `CalculationEnginePhase4Test` 15, `TableAndHostPhase4Test` 30,
`Phase4RequiredValidationTest` 8 (ledger cases 1–178), plus the closure suites —
`CalculationFunctionBaselineTest` 33, `RuleManagementWorkflowTest` 19, `TableDraftLifecycleTest` 16,
`NamedRangeWorkflowTest` 13, `SpreadsheetUiStateOwnershipTest` 12, `SpreadsheetClipboardTest` 10,
`ConsequenceReviewTest` 9.

### Measured performance evidence

Produced by the real viewport planner and the engine's own counters.

| Measurement | Result |
| --- | --- |
| 1,000-row bounded viewport | 200,000 addressable cells; **520 composed**; rows 331–368, columns 39–49 |
| Wide-sheet column window | 16,384 columns addressable; window 9,373–9,383; **396 composed** |
| One-cell edit (no dependents) | **0** formulas recalculated, 1 cell invalidated, of 52 durable cells |
| Edit with 50 dependents | exactly **50** recalculated, 50 dependency edges |
| Scrolling (20 viewport changes) | **0** formulas recalculated |
| 100,000-cell range | evaluates; `=COUNT(A1:A100000)` → `0` |
| 100,001-cell range (limit 99,999) | refused: `RESOURCE_LIMIT`, displays `#LIMIT!` — not a plausible `0` |
| Parser totals | 32 functions; bounded at 4,096 tokens / depth 128 / 250,000 evaluation steps |

---

## Flags, migration, recovery, security

| Field | Value |
| --- | --- |
| `SPREADSHEET_HOST` default | **OFF** (`editor/flags/EditorFeatureFlags.kt:41`) |
| `FLOW_HOST` / `DOCUMENT_HOST` defaults | unchanged, both OFF |
| Gated entry points | route (`SpreadsheetEditorScreen.kt:35`), sidebar (`SidebarScreen.kt:647`), Flow block (`FlowBlockContent.kt:416`), Document object in edit intent only (`SceneObjectContent.kt:616`) — 4 of 4 |
| Migration trigger | first edit of a legacy `TableBlock`, copy-on-write |
| Pre-migration snapshot | `snapshotBeforeMigration` in all three hosts; case 156 |
| Recovery | `LegacyTableRecovery` retained permanently; `restoreLegacy` returns the byte-identical original (case 157) |
| Schema version | 1 |
| Unknown-block preservation | raw JSON round-trips verbatim (case 165) |
| Network access | none — no `java.net`, OkHttp, Retrofit, URL, or Socket reference in the package |
| Dynamic code / reflection | none — no `Class.forName`, `ClassLoader`, `kotlin.reflect`, `Runtime.exec`, or `ProcessBuilder` |
| Filesystem access from formulas | none; `=READFILE(...)`, `=IMPORTDATA(...)`, `=HTTP(...)`, `=WEBSERVICE(...)` all return `#NAME?` (cases 38–40) |
| Collaboration / CRDT / E2EE | not claimed and not implemented |
| Phase-5 scope | read-only range seam only; no `ChartDocument`, chart renderer, or Chart Studio |
| Supabase / chat / `apps/web` / infrastructure | untouched |

---

## Review passes

### Pass C — first-party consequence review

Four §50 violations found and repaired:

1. Spreadsheet entry points were unflagged at three of four sites, including Document **read** mode.
2. Row/column headers and the range handle conveyed selected state by colour alone, with no non-gesture route.
3. Cell fill used a private hex field instead of the shared Phase-3 Color Studio.
4. The Command Registry covered 10 of ~25 required semantics; now 37 ids, with dock buttons routed through it.

### Pass D — adversarial review

The fresh-eyes reviewer subagent terminated on an API stream timeout, not on a finding. Per §51's stated
fallback the second adversarial pass was performed from a clean §50/§51 checklist without relying on
implementation notes. It found six additional defects, all repaired and all mutation-checked:

1. **Phantom leading row.** A headerless legacy table gained an empty row 0 while `headerRow` stayed false, so
   every legacy row index resolved one row too high: editing data row 1 overwrote row 0, `=A1` addressed a
   blank, and the first save grew the table by a row the user never typed.
2. **Full recalculation per keystroke.** The session discarded its engine on every persist, and all three hosts
   constructed a fresh engine per cell edit — a complete dependency-graph rebuild plus recalculation of every
   formula, for a one-cell change. The UI also built a *second* engine keyed on the workbook.
3. **Contradictory hidden/active sheet state.** `activateSheet` allowed a hidden sheet to become active, so the
   grid rendered a sheet the model called hidden. Deleting the last visible sheet could leave every survivor
   hidden with one of them active.
4. **`OutOfMemoryError` from one keystroke.** `1e2147483000` parsed as a valid `BigDecimal`; the next
   `toPlainString()` allocated a two-billion-character string and crashed the app.
5. **Compounding scale.** `=A1*A1` doubles the scale each step, so a dozen ordinary squarings from an accepted
   value reached a 92,160,002-character display string. Guarding input alone was insufficient.
6. **Swallowed resource limit.** `COUNT`/`COUNTA` filtered error values out, so a range refused for exceeding
   the resource limit reported a confident `0` instead of `#LIMIT!`.

Also corrected during this pass: an operator-precedence bug placing the range handle 24dp off inside frozen
panes, and four ledger cases (132, 134, 135, 136) that were source-string greps — each would have passed while
the feature was deleted, and each is now behavioural.

### Pass E — closure consequence review

The closure pass moved editing state out of the composition in three surfaces and added four workflows. Its own
consequence review found three defects, each repaired with a test that fails without the fix
(`ConsequenceReviewTest`, 9 tests):

1. **Stale draft over a changed value.** `resync()` runs on undo, redo and remote merge but did not clear the
   formula-bar draft or the in-cell edit. After undoing the edit being typed over, the bar still showed the
   abandoned text while the cell underneath held a different value, and the next commit would have written the
   stale string back over the restored one.
2. **Crash when deleting the host's own sheet.** `deleteSheet` dropped the sheet carrying the embedded table,
   after which `persist` hard-errored on the missing identity — reachable from the sheet tab menu in a
   two-sheet workbook. The invariant is now enforced in the mutation layer and refused in the host with a
   reason.
3. **`LocalDate.EPOCH` on minSdk 26.** `TIME()` used a constant added in API 34, so every call would have
   thrown `NoSuchFieldError` on the majority of supported devices. Lint caught this at the closure gate; the
   date is written out instead. Unit tests could not have caught it — they run on the desktop JDK, where the
   field exists.

Two ledger cases (132, 136) were source-string greps that broke when draft seeding moved into the runtime.
Rather than update the strings, both are now behavioural, and case 140 greps the surviving symbol.

### §49 mutation verification

Each critical seam was re-broken and the relevant test confirmed failing, then reverted. No mutation code is
committed (`grep -rn MUTATION` over the main sources returns nothing).

| Seam | Mutation | Result |
| --- | --- | --- |
| Missing-sheet fallback | restore silent current-sheet fallback | 2 tests failed |
| Name/parser collision | restore unbounded A1-lookalike rule | 9+ tests failed |
| Corrupt-payload refusal | drop `require(resolved.upgraded)` | 1 test failed |
| Phantom leading row | restore unconditional header prepend | 2 tests failed |
| Hidden-active sheet | drop the reveal-on-activate | 1 test failed |
| Engine reuse across persist | rebuild the engine unconditionally | 1 test failed |
| Last-visible-sheet deletion | drop the survivor reveal | 1 test failed |
| Number-width bound | raise to `Int.MAX_VALUE` | 1 test failed |
| COUNT error propagation | restore the filtering `count {}` | 1 test failed |

---

## Honest limitations

- Physical device certification is deferred, as stated at the top. Touch-target sizes (48dp), `stateDescription`
  announcements, `CustomAccessibilityAction` alternatives, and `CollectionInfo`/`CollectionItemInfo` are
  implemented and present in source, but no assistive technology has exercised them.
- The function catalogue is the authorized baseline (63 dispatch names), not Excel parity.
- Cell fill is a flat colour; gradients are not offered for that role.
- The structured clipboard is process-local. Copying in Norfold and pasting into Norfold after the app is
  killed takes the plain-text path, because only the system clipboard survives.
- Scroll position is per sheet and per session; it is runtime state, not document state, so it is deliberately
  not persisted and not restored across process death.
- Duplicating a `TableBlock` refreshes the block id and copies the payload verbatim. This is deliberate and
  proven safe by `duplicatingATableBlockIsolatesItsWorkbook…`: each block decodes its own object graph, so
  identical internal ids never collide. Deep re-identification would require rewriting every formula's stable
  references — a transform that loses data if incomplete.
- Instrumentation counters are process-local and not persisted.
