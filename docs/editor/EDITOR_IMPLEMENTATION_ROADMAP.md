# Norfold Editor — Repository Audit & Implementation Roadmap

**Status:** Final implementation roadmap. Phase 0 is authorized by §9; Phases 1–13 remain gated and unauthorized until their entry/validation conditions are satisfied.
**Authority source:** `Norfold_Interaction_Editor_Reference_v1.0` package (the canonical spec). Where this roadmap and the spec disagree, the spec wins.
**Date:** 28 July 2026.
**Scope:** Maps the current Norfold Android repository against the canonical spec; classifies every editor-relevant system as retain / refactor / replace / missing; identifies migration risks and architectural conflicts; defines large implementation phases with validation gates, PR/merge gates, and stop conditions.

> This is the "next package" required by spec §22 before autonomous implementation begins: repository mapping, architecture boundaries, migration mapping, domain acceptance matrices, test strategy, large implementation phases, agent handoff/stop conditions, and validation gates.

---

## 0. How to read this document

- **RETAIN FOUNDATION / EVOLVE COMPATIBLY** — the core is a genuine asset; extend it forward-compatibly, never break wire format or existing semantics.
- **RETAIN** — keep as-is; spec-neutral or already satisfying.
- **REFACTOR** — the core is sound but must be reshaped to meet a locked behavior.
- **REPLACE** — the current concept conflicts with a locked decision and must be retired behind migration.
- **MISSING** — the spec requires it and no implementation exists.
- **PRESERVE-FROZEN** — completed collaboration infrastructure; do not bind editor schema to it (spec §20.16, §14.7).

Every locked behavior cited carries its spec section. Benchmark-dependent values are never converted to constants here; they are flagged `BENCHMARK`.

**Accessibility, incremental rendering, virtualization, and performance instrumentation are requirements of every domain phase**, not a final phase. Phase 11 is certification and hardening of those properties, not their first implementation.

---

## 1. Executive finding

The repository is a **local-first structured-block note app** with a strong block-document core, an honest unknown-payload preservation story, and a **completed, isolated collaboration infrastructure layer**. It is **not** the two-host editor platform the spec describes. The single largest architectural conflict is structural and load-bearing:

> **Flow (Notes) and Documents are the same editor.** `FlowNoteEditorScreen` and `BoundedDocumentEditorScreen` both delegate to one 5,636-line `StructuredBlockEditorHost`, distinguished only by an `EditorSurface { FlowNote, Document }` enum that flips `DocOverlapMode.Reflow` vs `DocOverlapMode.Bounded` (`BlockNoteEditorScreen.kt:421`). Product identity is inferred from a layout mode.

This is precisely the pattern the spec rejects in §20.1, §17.2, and §18.2.

The good news: the **block document model**, **structured editing session**, **unknown-block round-trip**, **stable IDs**, **container/row/column layout**, **edge-drop split**, and **per-top-level-block Room persistence** are all explicitly called out in spec §17.1 as foundations to preserve or refactor. The migration is a **re-hosting and schema-hardening exercise on top of a salvageable core**, not a rewrite from zero.

The second major finding: several domains the spec marks LOCKED are prototype-grade or absent — Chart Studio (form builder, no direct manipulation), tables (block-level only, no calculation engine), image editing (no Image Focus workspace), fonts (no offline library — runtime Google Fonts fetch), page control (geometry exists but no Original-View page editing), relationships/Connect mode (absent), one canonical History Engine (absent — per-session undo stacks only), and Raw Source (absent).

---

## 2. Architecture boundary map (target)

```text
┌──────────────────────────────────────────────────────────────────┐
│ HOSTS (product identity — NOT a layout flag)                       │
│   FlowHost (Notes)            DocumentHost (bounded pages)         │
│   writing-first, responsive   composition-first, spatial           │
│                               SpreadsheetHost (full calc env)      │
└───────────────┬───────────────────────────┬──────────────────────┘
                │                           │
┌───────────────▼───────────────────────────▼──────────────────────┐
│ SHARED ENGINES                                                      │
│  • Command Registry (one semantic command; §4)                      │
│  • Selection Engine (text/block/object/table/chart/rel; §4.6)       │
│  • History Engine (one canonical history; §13)                      │
│  • Tool Lifecycle / Surface Coordinator (§5)                        │
│  • Adaptive Presentation runtime (§3, §2.6)                         │
│  • Interaction Memory runtime (§5.7)                                │
│  • Document/Block model (§17.1 retained core)                       │
│  • Scene model (Documents; §8.4)                                    │
│  • Calculation/Data engine (tables+spreadsheet; §10.3)              │
│  • Chart document model (§11.1)                                     │
│  • Color Studio / Styles / Typography (§9)                          │
│  • Relationship engine + Connect mode (§12)                         │
│  • Import/Export + Fidelity + Raw Source (§15)                      │
│  • Guide / Progressive Discovery (§6)                               │
└───────────────┬──────────────────────────────────────────────────┘
                │  (integration seam — versioned, not bound)
┌───────────────▼──────────────────────────────────────────────────┐
│ COLLABORATION INFRASTRUCTURE  (PRESERVE-FROZEN; §14, §20.16)       │
│  transport SPI · QoS · crypto boundary · outbox · merge SPI        │
└────────────────────────────────────────────────────────────────────┘
```

**Hard boundary rule (spec §14.7, §20.16):** editor domain schemas may not be bound to the preliminary `SemanticOperation` classes, and no CRDT/merge engine or room partitioning is selected during editor work. The History Engine owns undo semantics; merge engines only supply inversion primitives. The integration seam stays versioned and provisional until domain schemas are final.

**Recovery protection rule:** no migration that touches document content may execute without a functioning recovery snapshot in place. History Engine core and auto-snapshot infrastructure are therefore Phase 0 deliverables, not a later phase.

---

## 3. System-by-system audit

### 3.1 Block document model & editing core

| System | Current state | Verdict | Action |
|---|---|---|---|
| `BlockDocument` (blocks, inline nodes, containers, unknown-block round-trip, versioned envelope `v:1`) | `domain/BlockDocument.kt` (362 ln). Sealed `DocumentBlock` with 19 kinds; sealed `InlineNode` with 13 kinds; `UnknownBlock` preserves raw JSON verbatim; per-block envelope with `SCHEMA_VERSION`. | **RETAIN FOUNDATION / EVOLVE COMPATIBLY** | Spec §17.1. This is a model asset. Extend forward-compatibly; never break `v:0` or `v:1` wire format. New block/inline kinds must add safe decode/round-trip handling. Increment `SCHEMA_VERSION` only when serialized semantics require migration or changed interpretation; adding a safely round-trippable kind alone does not automatically require a global version bump. |
| `BlockEditorSession` (id-based structural ops, nested tree, typing coalescing, edge-drop split) | `domain/BlockEditorSession.kt` (1093 ln). Rich structural mutation; undo/redo as `ArrayDeque<BlockDocument>` snapshots. | **REFACTOR** | Spec §17.1. Retain mutation vocabulary. Snapshot-deque undo is superseded by the canonical History Engine (Phase 0). `mergeRemote` is not the CRDT and must not become one (§14.7). |
| Whole-document snapshot undo (`ArrayDeque<BlockDocument>`) | In-session only; cleared on remote merge. | **REPLACE** | Spec §13.1 one authoritative transaction history. Current model has no transaction records, no cross-target atomicity (§13.4), no filtered history (§13.6). |
| `DocPagination` (greedy block→page packing, pure) | `domain/DocPagination.kt` (38 ln). | **RETAIN** | Useful for Document Original-View page rendering. Aligns with §16.2 page virtualization. |
| `DocSections` (ToC section = heading + following blocks) | `domain/DocSections.kt` (72 ln). | **RETAIN** | Feeds Navigator/Outline (§8.7). |

### 3.2 The Flow == Document collapse (the central conflict)

| System | Current state | Verdict | Action |
|---|---|---|---|
| `EditorSurface { FlowNote, Document }` + single `StructuredBlockEditorHost` | `BlockNoteEditorScreen.kt:349,351` (5,636 ln). Host branches on `surface ==` in ~11 places; maps `Document→Bounded`, `FlowNote→Reflow`. | **REPLACE (highest priority)** | Spec §1.1/§1.2, §17.2, §18.2. Two hosts must become genuinely separate surfaces over shared engines. |
| `DocOverlapMode { Reflow, Bounded, Overlap }` | `domain/DocLayout.kt:16`. `Overlap` = infinite free-position canvas; persisted on `DocumentEntity.layout_mode` and legacy `NoteEntity.overlapMode`. | **REPLACE the concept; RETAIN the geometry data** | Spec §18.1, §8.9. `Overlap` mode retired as a product surface. **Migration risk (high):** existing user documents in `Overlap`/`Bounded` must have their `FreeformPlacement` data mapped into the Document scene model — never dropped (§17.4). Migration requires recovery snapshot in place first. |
| `FreeformPlacement` (x/y/w/h/z dp) | `DocLayout.kt:130`; `Note.freeformLayout` map. | **REFACTOR into scene model** | Spec §17.1 keeps "free-positioning, snap and z-order prototypes as mechanics evidence". Becomes input to Document `SceneObject` transform (§8.4/§8.5). |

### 3.3 Documents domain (pages, scene objects) — largely MISSING

| System | Current state | Verdict | Action |
|---|---|---|---|
| `DocCanvasSpec` (page presets A4/Letter/Legal/Custom, page IDs, gaps) | `DocLayout.kt:44`. Geometry + page-origin math exists. | **RETAIN as page-geometry seed** | Spec §8.1–§8.3. Real page control UI is MISSING. |
| Adaptive/Original × Read/Edit (four combinations) | Absent as independent states. `readingDocument` is a single boolean derived from `surface == Document && !editMode && documentViewMode == ReadingView`. | **MISSING** | Spec §3.1–§3.4. Four combinations are LOCKED and independent: Adaptive/Read, Adaptive/Edit, Original/Read, Original/Edit. Neither axis collapses into the other. |
| Protected Page Control (size/margins/columns/headers-footers/bleed/section-start/per-page override) | Absent. | **MISSING** | Spec §8.1–§8.2. Page structure is normally protected while content remains editable; entering Page Control is a deliberate mode. |
| Scene model: typed scene objects, transforms, z-order, containment, frames vs groups, locks, capabilities | Absent. Only `FreeformPlacement` on blocks. | **MISSING** | Spec §8.4–§8.8. Core new engine for DocumentHost. |
| Direct manipulation (4-corner resize, rotate, snap/guides, exact x/y/w/h/angle, cancel-during-drag) | Partial free-drag in Overlap path; one-corner resize noted as anti-pattern. | **REPLACE/BUILD** | Spec §2.4, §8.5, §8.6. §17.2 explicitly retires "one-corner-only resize". |
| Page size safety bounds (max 2000 mm / 2 m²) | Not enforced. | **MISSING** | Spec §8.3 locked safety boundary. |

### 3.4 Command, selection, tool-lifecycle layer — MISSING as a layer

| System | Current state | Verdict | Action |
|---|---|---|---|
| One semantic command invoked from dock/slash/keyboard/menu/inspector/a11y | Commands are ad-hoc per composable inside the mega-host. No unified registry. | **MISSING (build as Command Registry)** | Spec §4.1–§4.4 universal command contract. |
| Selection Engine (text/Flow block/Document object/table/chart/relationship; primary vs secondary; remote ≠ local state) | Text caret + block selection exist inside host; no unified selection vocabulary. | **MISSING (build)** | Spec §4.5–§4.14. |
| Tool Lifecycle / Surface Coordinator (surface classes, dismissal policies, back hierarchy) | `NorfoldPopup`, bottom sheets exist; dismissal is ad-hoc; §18.6 rejects universal close-on-click. | **MISSING as a coordinator; RETAIN rendering substrate** | Spec §5.1–§5.6. Surface classes (command menu, continuous tool, picker/browser, navigator, focus tool, decision modal) and their dismissal policies must be modeled explicitly. |
| Adaptive Presentation runtime (phone popover / tablet pane / expanded three-panel; state shared across form factors) | Ad-hoc per screen. | **MISSING as a runtime** | Spec §2.6, §3, §5.1. The same tool state must adapt physically without resetting values. |
| Interaction Memory runtime (transient / tool-session / artifact-UI / context-preference / user-preference / guide-discovery scopes) | Single global store implied by current architecture. | **MISSING as a scoped system** | Spec §5.7, §18.7. These must not collapse into one global store. |
| Guide / progressive discovery (`?`, discovery states, contextual tips) | Absent. | **MISSING** | Spec §6. LOCKED — not deferred. Gets its own phase. |

### 3.5 Tables & spreadsheet foundation

| System | Current state | Verdict | Action |
|---|---|---|---|
| `TableBlock` (headers, rows, cell inline content, column widths/alignments) | `BlockDocument.kt:144`. Block-level table only. | **RETAIN as document-table UI seed** | Spec §10.1–§10.2. |
| Shared calculation/data engine (formulas, dependency graph, named ranges, multi-sheet, validation, conditional formatting, virtualization, offline calc) | Absent entirely. | **MISSING (major new engine)** | Spec §10.3. |
| SpreadsheetHost (full spreadsheet environment over the shared engine) | Absent. | **MISSING** | Spec §1.3. The Spreadsheet workspace exposes full spreadsheet behavior as a distinct host, not just advanced document-table controls. |
| Insert Around / directional row-column ops | Slash exists; directional Insert-Around surface per §7.5 not confirmed present. | **REFACTOR** | Spec §4.15, §7.4–§7.5. No ambiguous `addRow()`. |

### 3.6 Chart Studio

| System | Current state | Verdict | Action |
|---|---|---|---|
| Form-based chart builder → Vega-Lite JSON or rasterized PNG | `ChartBuilderSheet.kt` (403 ln), `ChartSpecCodec.kt` (419 ln). 6 chart types; multi-series layered specs; `norfold` round-trip metadata. | **RETAIN as prototype evidence; REPLACE as product** | Spec §17.1. `ChartBlock.vegaLiteSpec:String` is a reasonable interchange seed but is not the ChartDocument model. |
| Data/encoding/visual-override separation | Absent. | **MISSING** | Spec §11.5, §20.6. Locked — "never corrupt data". |

### 3.7 Images, Color, Typography, Fonts, Styles

| System | Current state | Verdict | Action |
|---|---|---|---|
| `ImageBlock` (source, caption, layout, height) | `BlockDocument.kt:157`. Display only. | **RETAIN as block; BUILD Image Focus** | Spec §9.1–§9.2. Image Focus workspace is MISSING. |
| Color Studio | Row-color swatches inside chart builder only; no unified ColorValue model. | **MISSING (build)** | Spec §9.3–§9.4, §2.10. |
| Named styles & tokens | Inline `FontFamilyInline`/`FontSizeInline`/marks exist; no named-style system. | **MISSING (build)** | Spec §9.5 "foundational, not deferred". |
| Offline font library (30 guaranteed-offline families) | **Conflict.** `NorfoldFonts.kt` fetches from Google Fonts provider at runtime; 18-family hardcoded fallback; no bundled binaries. | **REPLACE delivery model** | Spec §9.6, §9.8, §9.9, §20.7, §18.16. The 30 named families must be **bundled in the APK/AAB or available via an install-time asset pack** so they are guaranteed offline without any network request. True on-demand delivery (network fetch) is only for the extended catalogue beyond the 30. The runtime-fetch model does not satisfy "keep local/offline editing correct." BENCHMARK: APK/AAB size contribution. |
| User-imported fonts | Absent. | **MISSING** | Spec §9.7–§9.8, §20.13. |
| `ShapeCatalog` (Phosphor/Material/Lucide/Tabler; on-demand fetch + strict SVG sanitization; SPDX-gated) | `ui/shapes/ShapeCatalog.kt` (175 ln). Sanitization is a genuine asset. | **RETAIN (refactor for offline subset)** | Spec §17.1. A bundled subset for local-first correctness; extended catalogue remains on-demand. |

### 3.8 Relationships, comments, review

| System | Current state | Verdict | Action |
|---|---|---|---|
| Relationship model, Connect mode, Relationship Manager | Absent. | **MISSING** | Spec §12.1–§12.3. Temporary contextual Connect mode — not permanent ports (§18.3), not an infinite canvas (§18.1). |
| Comments / mentions / anchored discussion / snapshots-for-review | `MentionInline`/`TagInline` exist; no comment/anchor/review system. | **MISSING** | Spec §12.4. AI rewrites/proposals explicitly deferred. |

### 3.9 Undo / History / Snapshots / Recovery

| System | Current state | Verdict | Action |
|---|---|---|---|
| Canonical History Engine | Absent. Only per-session snapshot deque in `BlockEditorSession`. | **MISSING (build core in Phase 0)** | Spec §13. Owns undo semantics over all surfaces. Core transaction model and auto-snapshot infrastructure are Phase 0 — no migration may precede recovery protection. |
| Snapshots (auto before risky ops; manual named; scoped) | Encrypted document snapshots for sync; not a user-facing recovery-point system. | **REFACTOR/BUILD** | Spec §13.7–§13.8. Auto-snapshot before import/migration/Raw-Source/mass-style ops. |

### 3.10 Import / Export / Compatibility / Raw Source

| System | Current state | Verdict | Action |
|---|---|---|---|
| DOCX export | `domain/DocxExporter.kt` (64 ln). Hand-written OOXML; paragraphs/headings only; no tables, images, inline formatting, or fidelity report. | **REFACTOR (major)** | Spec §15.6 + Fidelity Inspector (§15.5). Round-trip import is MISSING. |
| Markdown/HTML/print export | `domain/MarkdownExporter.kt` (227 ln). | **RETAIN (as export path)** | Not the versioned product Raw Source. |
| Import (DOCX/PDF/XLSX with intents, region fidelity states, Compatibility/Native modes, preflight) | Absent. | **MISSING** | Spec §15.1–§15.8. |
| Raw Source | Absent. Only export. | **MISSING** | Spec §15.10, §20.14. Must not expose persistence JSON as Raw Mode (§18.17). |

### 3.11 Data / persistence layer

| System | Current state | Verdict | Action |
|---|---|---|---|
| Room schema v50; `DocumentEntity`/`DocumentBlockEntity`; `NoteEntity.overlapMode`+`freeformLayoutJson`; owner model | `data/Entities.kt` (1204 ln), `NorfoldDatabase.kt` (1423 ln). | **RETAIN (extend forward-only)** | Spec §17.1. All editor schema growth is additive, forward-only, snapshot-guarded (§13.7, §17.4). No destructive migration (§17.4, §18.18). |
| `layout_mode` values `reflow/bounded/overlap` on persisted documents | Persisted; `docOverlapModeOf` decodes legacy literals. | **MIGRATION BOUNDARY** | Retiring `Overlap` requires copy-on-write migration (§17.3) with recovery snapshot in place first. |

### 3.12 Collaboration infrastructure — PRESERVE-FROZEN

| System | Current state | Verdict | Action |
|---|---|---|---|
| Transport SPI, Supabase/Cloudflare/Ably adapters, QoS, coalescer, interpolator, offline outbox, crypto boundary, merge SPI, `SemanticOperation` experiments, benchmark harness, `CollaborationSessionFactory` | `collaboration/**` (complete, unit-tested). | **PRESERVE-FROZEN** | Spec §14, §20.16, Appendix B. Do not bind editor schema to `SemanticOperation`; do not select a CRDT or room partitioning during editor phases. Ten Appendix-B corrections are addressed in Phase 12 (Collaboration Integration & Certification). |

---

## 4. Migration risks & boundaries

Every risk below is ordered by blast radius. A risk marked **DATA-LOSS CLASS** may not be worked without a functioning recovery snapshot already in place (§13.7); this is the recovery-protection rule from §2, enforced as a hard entry condition on the relevant phase.

| # | Risk | Class | Boundary rule |
|---|---|---|---|
| R1 | Retiring `DocOverlapMode.Overlap` drops user `FreeformPlacement` data (x/y/w/h/z) that never maps into the Document scene model. | **DATA-LOSS CLASS** | Copy-on-write migration (§17.3). Read legacy `layout_mode`/`freeformLayoutJson`, project into `SceneObject` transforms, write the new representation alongside the old, verify, and only then stop reading the old. Never destructive (§17.4, §18.18). Recovery snapshot required first. |
| R2 | Splitting one `StructuredBlockEditorHost` into FlowHost + DocumentHost changes which code path renders existing notes/documents. | **BEHAVIOR-DIVERGENCE CLASS** | Feature-flagged parallel hosts. Legacy host stays the default until the new host reaches parity per phase gates. No default switch before Phase 13 (Cutover). |
| R3 | Superseding `BlockEditorSession`'s snapshot-deque undo with the canonical History Engine changes undo semantics mid-stream for in-flight edits. | **BEHAVIOR-DIVERGENCE CLASS** | History Engine core lands in Phase 0 behind the flag; sessions adopt it host-by-host. `mergeRemote` remains block-level LWW and never becomes the CRDT (§14.7). |
| R4 | Additive Room growth for scene objects, styles, calc data, chart documents, relationships, comments. | **SCHEMA CLASS** | Forward-only, additive, snapshot-guarded migrations (§13.7, §17.4). Each new table/column ships with a decode path for older rows. No column is repurposed; no destructive `ALTER`. |
| R5 | New block/inline kinds must not break `v:0`/`v:1` wire format or the `UnknownBlock` round-trip. | **WIRE-FORMAT CLASS** | New kinds must add safe decode/round-trip handling; older clients continue to preserve them verbatim as `UnknownBlock` (§17.1). Increment `SCHEMA_VERSION` only when serialized semantics require migration or changed interpretation; adding a safely round-trippable kind alone does not automatically require a global version bump. Round-trip fidelity test is a merge gate. |
| R6 | Binding any editor domain schema to the preliminary `SemanticOperation` classes, or selecting a CRDT/room partitioning during editor work. | **ARCHITECTURE-LOCK CLASS** | Forbidden until Phase 12. The integration seam stays versioned and provisional (§14.7, §20.16). History Engine owns undo; merge engines only supply inversion primitives. |
| R7 | Replacing runtime Google-Fonts fetch with bundled/offline delivery increases APK/AAB size. | **BENCHMARK CLASS** | The 30 guaranteed families are bundled or install-time asset-pack delivered; only the extended catalogue is true on-demand (§9.6, §20.7). Size contribution is `BENCHMARK` — measured, not assumed. |
| R8 | Import (DOCX/PDF/XLSX) and Raw Source can corrupt content if domain models aren't final. | **DATA-INTEGRITY CLASS** | Import/Export/Raw Source (Phase 8) runs *after* Relationships/Comments/Review (Phase 7) so anchored comments and stable serialization bind to finalized domain models. Auto-snapshot before every import/Raw-Source/mass-style op (§13.7). |

**Load-bearing invariants (never violated by any phase):**
- No destructive migration; all content migration is copy-on-write and recovery-snapshot-guarded.
- Unknown/future payloads survive round-trip.
- The collaboration infrastructure is not modified by editor phases and is not bound to editor schema until Phase 12.
- Product identity (Flow vs Document vs Spreadsheet) is a host, never a layout flag.

---

## 5. Implementation phases

Fourteen phases, `0`–`13`. Phase 0 is foundation; Phases 1–9 are domains; Phases 10–13 are hardening, integration, and cutover. Domains do not begin until Phase 0's foundation exists, because every domain is expressed as commands over the shared engines.

**Every domain phase (1–9) carries these as in-scope requirements, not deferrals (revision #9):**
- **Accessibility:** every command reachable and operable via the accessibility path (§4 universal command contract); semantics, focus order, and announcements for the surfaces the phase introduces.
- **Incremental rendering:** the phase's surfaces render progressively; no full-document reflow on local edit.
- **Virtualization:** large collections the phase introduces (pages, rows, scene objects, list items) are virtualized.
- **Performance instrumentation:** the phase ships with measurement hooks for its hot paths; `BENCHMARK` targets are recorded, not hardcoded.

Phase 11 is **certification and hardening** of these properties, not their first implementation.

**PR/merge discipline (revision #12):** each phase is one integration branch off `main`, feature-flagged. Work merges to the integration branch in reviewable slices sized to a coherent unit of behavior (an engine, a surface, a migration) — not per-file micro-PRs and not one monolithic phase PR. The integration branch merges to `main` only when the phase's validation gate passes. The default-behavior switch never rides in a domain PR; it is exclusively Phase 13.

---

### Phase 0 — Foundation & recovery protection

**Entry condition:** none (first phase). Baseline build green (Gradle 9.4.1 / Kotlin 2.3.0, JDK 21, Room schema 50).

**Deliverables:**
1. **Feature-flag substrate** — parallel-host and per-domain flags; legacy `StructuredBlockEditorHost` remains the default.
2. **Production-capable canonical History Engine core** (§13.1–§13.4) — one authoritative transaction history; transaction records; cross-target atomic transactions; deterministic inverse operations supplied by local commands/transactions. History owns user-facing undo semantics and must not depend on a CRDT or merge engine before Phase 12. A collaboration merge engine may later provide additional selective/inversion primitives only through the Phase-12 integration seam. This is production code, not a stub (revision #1).
3. **Recovery snapshot infrastructure** (§13.7) — auto-snapshot before risky ops; snapshot store; restore path. **No migration in any later phase may execute without this in place** (recovery-protection rule).
4. **Command Registry** (§4.1–§4.4) — one semantic command invoked from dock/slash/keyboard/menu/inspector/accessibility.
5. **Selection Engine** (§4.5–§4.14) — selection domains (text/Flow-block/Document-object/table/chart/relationship), primary vs secondary, remote ≠ local state.
6. **Tool Lifecycle / Surface Coordinator** (§5.1–§5.6) — surface classes (command menu, continuous tool, picker/browser, navigator, focus tool, decision modal) and their dismissal policies; back hierarchy. Foundation, not audit-only (revision #2).
7. **Adaptive Presentation runtime** (§2.6, §3, §5.1) — same tool state adapts across phone popover / tablet pane / expanded three-panel without resetting values (revision #2).
8. **Interaction Memory runtime** (§5.7, §18.7) — the six scopes (transient / tool-session / artifact-UI / context-preference / user-preference / guide-discovery) as distinct stores; must not collapse into one global store (revision #2).
9. **Font-delivery benchmark** — measure APK/AAB size contribution of bundling the 30 guaranteed families vs install-time asset pack (§9.6, revision #10); decision recorded, not implemented yet.

**Validation gate:** History core round-trips transactions and inverts them; cross-target atomic undo verified; recovery snapshot create/restore verified against real documents; Command Registry + Selection Engine exercised by unit tests across all selection domains; Surface Coordinator dismissal policies unit-tested; Adaptive Presentation state survives a simulated form-factor change; Interaction Memory scopes proven independent (a transient value does not leak into user-preference). Full build + existing test suite green. No behavior change visible to users (all flags off).

**Stop condition:** History core cannot invert a cross-target transaction atomically, OR recovery snapshot cannot restore a real document — halt; do not begin any domain phase.

**PR/merge gate:** merges to `main` only when the gate passes; flags default off.

---

### Phase 1 — FlowHost (Notes)

**Entry condition:** Phase 0 merged.

**Deliverables:**
1. A genuine `FlowHost` — writing-first, responsive, structurally edited, feature-flagged alongside the legacy host. It adopts History Engine, Command Registry, Selection Engine, Surface Coordinator, Adaptive Presentation, and Interaction Memory. It retains the `BlockDocument` foundation and `BlockEditorSession` mutation vocabulary only where those semantics remain correct. There is no `EditorSurface` enum flip; FlowHost is a distinct product surface.
2. **Touch-first `+` insertion** using the shared Command Registry, with compact context-aware insert surfaces rather than a separate command implementation.
3. **Slash Local Command Resolver** — the typed `/query` is the search; no second search field. Results are context-sensitive, compact, and share command semantics with `+`. Slash is suppressed or treated literally in contexts such as URLs, paths, code, and equations where `/` is content rather than a command trigger.
4. **Structural editing commands** — insert above/below, convert block type, duplicate, move, split/merge, nest/unnest, wrap/unwrap where supported, and structural drag/drop with stable IDs and deterministic placement semantics.
5. **Context dock / lower interaction territory** that stays keyboard/IME-aware and exposes the visible touch route for essential actions.
6. **Keyboard and accessibility parity** through the same semantic commands; gestures and shortcuts remain accelerators rather than the only route.
7. **Phone multi-select where structurally relevant** — visible entry into multi-select, with long-press as an accelerator only.
8. **Directional table insertion hooks** (`row above/below`, `column left/right`) registered through the shared command model so Phase 4 can attach full table semantics without ambiguous `addRow()` behavior.
9. **History integration** — every Flow mutation emits a canonical transaction with deterministic inverse semantics; the legacy snapshot deque is retired for the new Flow path.

**Validation gate:** legacy Flow parity is the **minimum migration floor, not Phase-1 completion**. Phase 1 passes only when the locked Flow behaviors above and in the canonical spec are implemented, or when an item is explicitly proven to belong to a later shared-domain phase rather than silently omitted. The gate must verify: touch `+`; slash Local Command Resolver and suppression rules; structural insertion/conversion/move/nesting/drag-drop; context dock; visible multi-select where applicable; keyboard/accessibility parity; canonical History undo/redo; `v:0`/`v:1` + `UnknownBlock` round-trip; offline correctness; and the per-phase accessibility/incremental-rendering/virtualization/performance requirements. Build + tests green.

**Stop condition:** Flow editing regresses a locked behavior, or the new host reaches only legacy parity while canonical Flow behaviors remain unimplemented without an explicit downstream owner.

**PR/merge gate:** reviewable slices (host shell, shared-engine adoption, command/selection integration, insertion/slash/structural editing, parity + canonical-behavior closure); merge on gate pass; flag default off.

---

### Phase 2 — DocumentHost (pages, scene model, page control)

**Entry condition:** Phase 1 merged. Recovery snapshot infrastructure verified (R1 is DATA-LOSS CLASS).

**Deliverables:**
1. `DocumentHost` (composition-first, spatial) as a distinct surface — not a layout flag (revision #4 context; §17.2, §18.2).
2. **All four independent combinations** (revision #4): Adaptive/Read, Adaptive/Edit, Original/Read, Original/Edit. Neither axis collapses into the other (§3.1–§3.4).
3. **Protected Page Control** (§8.1–§8.2) — size/margins/columns/headers-footers/bleed/section-start/per-page override; page structure protected while content stays editable; entering Page Control is a deliberate mode. Page-size safety bounds enforced (max 2000 mm / 2 m², §8.3).
4. **Scene model** (§8.4–§8.8) — typed scene objects, transforms, z-order, containment, frames vs groups, locks, capabilities.
5. **Direct manipulation** (§2.4, §8.5, §8.6) — 4-corner resize, rotate, snap/guides, exact x/y/w/h/angle, cancel-during-drag. Retires one-corner-only resize (§17.2).
6. **Overlap → scene copy-on-write migration** (R1) — `FreeformPlacement` projected into `SceneObject` transforms; recovery snapshot required first; never destructive.

**Validation gate:** all four combinations independently reachable and correct; Page Control protects structure while content edits; scene objects transform/resize/rotate/snap with exact numeric entry and cancel-during-drag; Overlap migration projects real user placements with a verified copy-on-write path and a restorable snapshot; page-size bounds enforced; per-phase a11y/incremental/virtualization (page + scene-object virtualization)/perf met. Build + tests green.

**Stop condition:** any legacy `Overlap`/`Bounded` document loses placement data, OR migration runs without a restorable snapshot, OR any of the four combinations is not independently reachable.

**PR/merge gate:** slices = scene engine, page control, four-combination surface, migration; migration slice blocked until recovery verified; merge on gate pass; flag default off.

---

### Phase 3 — Color Studio, Named Styles, offline Typography

**Entry condition:** Phase 2 merged.

**Deliverables:**
1. **Color Studio** with a canonical `ColorValue` model (§9.3–§9.4, §2.10) — one color representation across every surface.
2. **Named styles & tokens** (§9.5) — a named-style system; "foundational, not deferred."
3. **Bundled offline typography** (§9.6, revision #10) — the 30 guaranteed families **bundled in the APK/AAB or delivered via an install-time asset pack**, guaranteed offline with no network request. True on-demand delivery applies only to the extended catalogue beyond the 30. Retires the runtime Google-Fonts fetch as the delivery model for the guaranteed set.
4. **User-imported fonts** (§9.7–§9.8) — import path with sanitization.
5. `ShapeCatalog` refactored to a bundled offline subset for local-first correctness; extended catalogue remains on-demand (retains existing SVG sanitization + SPDX gating).

**Validation gate:** color is one canonical value across Flow + Document; named styles apply and cascade; airplane-mode test proves all 30 families render with zero network; imported fonts sanitized and applied; extended catalogue fetch is the only network path; APK/AAB size contribution recorded against the Phase 0 benchmark. Per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** any of the 30 families requires a network request to render, OR color diverges between hosts.

**PR/merge gate:** slices = ColorValue, named styles, font delivery, imports; merge on gate pass; flag default off.

---

### Phase 4 — Tables, Calculation Engine & SpreadsheetHost

**Entry condition:** Phase 3 merged.

**Deliverables:**
1. **Shared calculation/data engine** (§10.3) — formulas, dependency graph, named ranges, multi-sheet, validation, conditional formatting, virtualization, offline calc. Shared across document tables and the spreadsheet host.
2. **Document tables** over the shared engine (evolve the existing `TableBlock` UI seed, §10.1–§10.2).
3. **SpreadsheetHost** (§1.3, revision #5) — a full spreadsheet environment as a distinct host, exposing full spreadsheet behavior, not merely advanced document-table controls.
4. **Directional Insert-Around** row/column ops (§4.15, §7.4–§7.5) — no ambiguous `addRow()`.

**Validation gate:** formulas/dependency-graph/named-ranges/multi-sheet/validation/conditional-formatting correct and offline; SpreadsheetHost is a genuine distinct host over the shared engine (proven by a spreadsheet-only workflow, not a document table); Insert-Around is directional and unambiguous; large sheets virtualized; per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** SpreadsheetHost degenerates into a document-table variant, OR calculation requires network.

**PR/merge gate:** slices = calc engine, document tables, spreadsheet host, insert-around; merge on gate pass; flag default off.

---

### Phase 5 — Chart Studio

**Entry condition:** Phase 4 merged (charts may bind to calc-engine data).

**Deliverables:**
1. A **`ChartDocument` model** (§11.1) with data sources, transforms/aggregations, scales/axes, ordered layers, marks, series/bindings, encodings, layer defaults, mark overrides, annotations, legends, themes, accessibility metadata, and stable round-trip metadata.
2. **Data / semantic encoding / visual-override separation** (§11.5, §20.6). Visual overrides never mutate source values. Potentially misleading overrides are inspectable, reversible, and warned about without forcing the entire chart into a separate destructive mode.
3. **Professional initial chart-family catalogue** covering at minimum: bar/column; grouped, stacked, and normalized variants; line/area; scatter/bubble; pie/donut; histogram; heatmap; box-and-whisker; range/interval; waterfall; radar; funnel; candlestick/OHLC; rule/reference line/reference band; and mixed layered compositions. Additional modules such as Sankey/maps/treemap/sunburst/network remain governed by the canonical deferred/benchmark decisions rather than being silently counted as complete.
4. **Layered/mixed charts as ordinary composition**, with a layer manager supporting reorder, hide/show, lock where appropriate, duplicate, type conversion, axis binding, and shared/independent scales.
5. **Axes and legends** as selectable/editable chart entities. Dual axes are never auto-created; they require explicit user action and an interpretation warning.
6. **Annotations and reference layers** — data-relative annotations, labels, rule/reference lines, reference bands, and other canonical annotation behavior.
7. **Direct manipulation appropriate to family** — e.g. bar value/spacing controls, line/area point manipulation, scatter x/y/size, pie/donut rotation/boundaries/explode, histogram bin/range controls rather than fake raw-height editing, axis/domain manipulation, and exact-value fallback controls.
8. **Inheritance and override scopes** — chart theme → layer → series → mark override, with reset at property / mark / series / layer / all-visual-overrides levels.
9. **Color Studio and chart-theme integration**, including data palettes and non-color differentiation where needed.
10. **Accessibility** — meaningful description, deterministic data-table fallback, reading order, keyboard exploration, non-color encodings, and color-vision checks.
11. **Incremental/retained rendering** — ordinary mark edits invalidate only affected marks, labels, scales, or layers; unrelated chart/document content does not fully rerender. Likely-next chart resources and visible data may be preloaded/cached according to the canonical performance rules.
12. The existing Vega-Lite codec/round-trip metadata is retained as interchange/prototype evidence, not as the authoritative product model.

**Validation gate:** Phase 5 cannot pass with a small fixed chart subset or a form-only builder. The gate verifies: the required initial chart-family catalogue; layered/mixed composition; layer manager; axes/legends; explicit dual-axis creation; annotations/reference layers; family-appropriate direct manipulation; per-layer/series/mark overrides and reset scopes; Color Studio/theme integration; deterministic data-table accessibility fallback; keyboard interaction; incremental rendering; calc-engine range binding; interchange round-trip; and data-integrity tests proving that encoding/visual edits do not corrupt underlying data. Performance thresholds remain `BENCHMARK` values and are measured rather than invented. Build + tests green.

**Stop condition:** any chart edit path corrupts source data, a required canonical chart family/interaction is silently omitted and still called complete, or ordinary direct manipulation causes intrusive whole-chart/whole-document rerendering.

**PR/merge gate:** reviewable slices = ChartDocument/data model, family renderers, layer/axis/annotation systems, direct manipulation, visual overrides/themes, accessibility + incremental-render hardening, data-source binding; merge on gate pass; flag default off.

---

### Phase 6 — Image Focus

**Entry condition:** Phase 3 merged (styles/color available); may follow Phase 5.

**Deliverables:** a complete deterministic **Image Focus workspace** (§9.1–§9.2) over the retained `ImageBlock`, with non-destructive editing integrated into History Engine and recovery snapshots. It must include:
1. **Crop** — free/original/aspect presets; direct edges/corners; pan/zoom image behind the frame; straighten; rotate 90°; flip; vertical/horizontal perspective; composition guides.
2. **Frame and fit** — Fit, Fill, focal point, position, and scale.
3. **Masks and geometry** — deterministic masks/geometry controls supported by the canonical model.
4. **Core image adjustments** — exposure, contrast, highlights, shadows, temperature, tint, saturation, sharpness, and blur.
5. **Border/effects and arrangement** — borders/effects plus arrange/stack behavior appropriate to the Document scene.
6. **Source and accessibility controls** — replace source and edit alt text without losing existing placement/transform state.
7. **Comparison and recovery controls** — compare original; reset current property; reset current tool; reset all image adjustments; reusable presets; copy/paste adjustments.
8. **Focus-tool local history** — individual steps can be undone while the focus session is open; `Done` commits the session as one higher-level artifact transaction where appropriate, and `Cancel` restores the exact pre-session state.
9. AI/generative operations such as content-aware fill, generative expand, and subject replacement remain explicitly deferred and are not required for Phase 6 completion.

**Validation gate:** Phase 6 passes only when the locked deterministic Image Focus set above is implemented and works offline. Tests must verify non-destructive source preservation; exact Cancel restoration; local step undo; one higher-level History transaction on Done where specified; compare-original; reset property/tool/all; presets; copy/paste adjustments; alt-text path; and per-phase accessibility/incremental-rendering/virtualization/performance requirements. A crop/rotate/brightness-only implementation cannot satisfy this gate. Build + tests green.

**Stop condition:** image edits are destructive, bypass History, Cancel cannot restore exact pre-session state, or the deterministic toolset is reduced to a small subset while the phase is declared complete.

**PR/merge gate:** reviewable slices = focus shell/history contract, crop/geometry, fit/mask/source controls, deterministic adjustments, effects/arrangement/accessibility, presets/reset/compare; merge on gate pass; flag default off.

---

### Phase 7 — Relationships, Comments & Review

**Entry condition:** Phase 2 merged (needs scene/document domain models); scheduled **before** Import/Export (revision #6).

**Deliverables:**
1. **Relationship engine + Connect mode + Relationship Manager** (§12.1–§12.3) — temporary contextual Connect mode; **not** permanent ports (§18.3); **not** an infinite canvas (§18.1).
2. **Comments / mentions / anchored discussion / snapshots-for-review** (§12.4) — over finalized domain models so anchors are stable. AI rewrites/proposals explicitly deferred (§12.4).

**Rationale (revision #6):** DOCX comment export and stable Raw Source serialization (Phase 8) must bind to finalized relationship/comment/anchor models, so this phase precedes Import/Export.

**Validation gate:** Connect mode is temporary and contextual (no permanent ports, no infinite canvas); comments anchor stably across edits; review snapshots restore; per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** Connect mode acquires permanent ports or an infinite canvas, OR comment anchors drift on edit.

**PR/merge gate:** slices = relationship engine + Connect mode, comments/anchors/review; merge on gate pass; flag default off.

---

### Phase 8 — Import / Export / Fidelity / Raw Source

**Entry condition:** Phase 7 merged (comment/anchor + domain models final). Auto-snapshot before import/Raw-Source verified (R8).

**Deliverables:**
1. **Import** (DOCX/PDF/XLSX) with intents, region fidelity states (NATIVE/APPROXIMATED/HYBRID/FIXED/UNSUPPORTED), Compatibility/Native modes, preflight (§15.1–§15.8).
2. **DOCX export** rebuilt (§15.6) — tables, images, inline formatting, comments — plus **Fidelity Inspector** (§15.5). Retains Markdown/HTML/print as export paths.
3. **Raw Source** (§15.10, §20.14) — versioned product source. **Must not expose persistence JSON as Raw Mode** (§18.17).

**Validation gate:** round-trip import→edit→export preserves fidelity per region states with a Fidelity report; DOCX export carries tables/images/inline formatting/comments; Raw Source is the versioned product source, not persistence JSON; auto-snapshot precedes every import/Raw-Source/mass-style op; per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** Raw Source leaks persistence JSON, OR import corrupts content without a fidelity state and snapshot.

**PR/merge gate:** slices = import pipeline, export/fidelity, raw source; merge on gate pass; flag default off.

---

### Phase 9 — Guide & Progressive Discovery

**Entry condition:** the domains it teaches (Phases 1–8) merged.

**Deliverables:** the **Guide / progressive discovery** system (§6, revision #3) — `?` entry, discovery states, contextual tips. LOCKED — not deferred; its own phase.

**Validation gate:** `?` and discovery states operate across every host and surface introduced in Phases 1–8; discovery state persists in the correct Interaction Memory scope (guide-discovery); per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** Guide is bound to a single host, or discovery state leaks across scopes.

**PR/merge gate:** single coherent slice; merge on gate pass; flag default off.

---

### Phase 10 — History UI, filtering, named snapshots, recovery hardening

**Entry condition:** Phases 1–9 merged (all surfaces exist to be surfaced in history).

**Deliverables:** the *non-core* History work deferred out of Phase 0 (revision #1) — filtered history (§13.6), focus-tool local history, global vs contextual undo UI, manual named snapshots (§13.7–§13.8), recovery-point UX. The core engine already exists from Phase 0; this is UI, filtering, and recovery hardening only.

**Validation gate:** filtered/contextual/global undo behave per §13.6; focus-tool local history isolates correctly; named snapshots create/restore; recovery UX exercised against real documents; per-phase a11y/incremental/virtualization/perf met. Build + tests green.

**Stop condition:** filtered undo violates the single-authoritative-history invariant.

**PR/merge gate:** slices = filtering/contextual UI, named snapshots, recovery UX; merge on gate pass; flag default off.

---

### Phase 11 — Accessibility & Performance certification (hardening)

**Entry condition:** Phases 1–10 merged.

**Deliverables (revision #9):** certification and hardening of the accessibility, incremental-rendering, virtualization, and performance-instrumentation properties that every domain phase already implemented. Cross-surface accessibility audit; end-to-end performance benchmarking against `BENCHMARK` targets; virtualization stress at scale; incremental-render verification under load. This phase does **not** implement these properties for the first time — it certifies them.

**Validation gate:** full accessibility pass across all hosts/surfaces; performance targets met and recorded (`BENCHMARK` values filled from measurement); virtualization holds at documented scale; no full-document reflow on local edit anywhere. Build + tests green.

**Stop condition:** any host fails accessibility or a recorded performance target that a domain phase claimed to meet.

**PR/merge gate:** certification report + fixes as slices; merge on gate pass.

---

### Phase 12 — Collaboration Integration & Certification

**Entry condition:** all domain schemas final (Phases 1–10) and certified (Phase 11). This is the first phase permitted to bind editor schema to collaboration (revision #7; R6 lifts here).

**Deliverables:**
1. Bind finalized domain schemas to the collaboration integration seam (previously versioned/provisional, §14.7, §20.16).
2. Select CRDT/merge engine and room partitioning against final schemas.
3. Complete the **ten Appendix-B corrections** — durability, authorization, security, merge, and device gates (§14, Appendix B).
4. History Engine remains the owner of undo; merge engines supply inversion primitives only.

**Validation gate:** all ten Appendix-B gates pass (durability, authorization, security, merge, device); collaborative editing preserves History semantics and unknown-payload round-trip; no editor regression under collaboration; the crypto boundary and outbox are unmodified in contract. Build + tests green.

**Stop condition:** any Appendix-B gate fails, OR binding forces an editor schema change (schema was not actually final — return to the owning domain phase).

**PR/merge gate:** slices = seam binding, engine/partitioning selection, Appendix-B gates; merge on gate pass; collaboration remains behind its own flag.

---

### Phase 13 — Cutover & Legacy Retirement

**Entry condition:** Phases 0–12 merged and certified (revision #8). All parity gates green.

**Deliverables:**
1. **Feature-flag default switch** — new hosts become the default; legacy `StructuredBlockEditorHost` path becomes opt-in fallback.
2. **Migration cohorts** — staged rollout of copy-on-write migrations (R1, R4) across user cohorts, each recovery-snapshot-guarded.
3. **Fallback/recovery window** — a defined period during which users can fall back to the legacy host and all migrations remain restorable.
4. **Eventual removal** — after the window closes clean, remove `StructuredBlockEditorHost`, the `EditorSurface` enum, and legacy `DocOverlapMode.Overlap` product behavior (geometry data already migrated, never dropped).

**Validation gate:** default switch reversible during the window; every cohort migration restorable; no data-loss across the full legacy corpus; legacy removal leaves no orphaned data and no reachable Overlap product surface. Build + tests green.

**Stop condition:** any cohort loses data, OR fallback is not reversible within the window — halt rollout, do not remove legacy.

**PR/merge gate:** default switch is its own PR (never rides a domain PR); cohort rollout and legacy removal are separate gated slices; removal PR blocked until the fallback window closes clean.

---

## 6. Agent handoff & stop conditions

**Universal stop conditions (any phase halts if):**
- A migration is about to run without a functioning, restorable recovery snapshot (recovery-protection rule).
- A change would break `v:0`/`v:1` wire format or the `UnknownBlock` round-trip.
- Editor work would modify the collaboration infrastructure, bind editor schema to `SemanticOperation`, or select a CRDT/room partitioning before Phase 12.
- A migration would be destructive rather than copy-on-write.
- Product identity would be expressed as a layout flag rather than a host.
- Completion is being claimed from compilation, a screenshot, or a single happy-path test rather than the phase's validation gate.

**Handoff contract between phases:**
- A phase is "done" only when its validation gate passes and its integration branch has merged to `main` with flags default-off.
- The next phase's entry condition names the exact predecessor merges it requires; an agent must verify those merges before starting.
- Each phase leaves its `BENCHMARK` measurements recorded (not converted to constants) for downstream phases and Phase 11 certification.
- Where this roadmap and the canonical spec disagree, the spec wins; the agent stops and flags the conflict rather than reinterpreting product design.

**Authority order (highest first):** spec v1.0 locked decisions → acceptance criteria → contradiction resolutions → migration boundaries → current repository behavior → superseded planning drafts.

---

## 7. Validation gates summary

| Phase | Gate headline | Hard stop |
|---|---|---|
| 0 | History core inverts cross-target atomically; recovery snapshot restores real docs; engines unit-tested; no user-visible change | Cannot invert cross-target txn OR cannot restore a real document |
| 1 | FlowHost parity behind flag; `v:0/v:1` + `UnknownBlock` round-trip; History undo | Flow editing regresses a locked behavior |
| 2 | Four Adaptive/Original×Read/Edit combos independent; protected Page Control; scene direct-manipulation; Overlap copy-on-write with restorable snapshot | Placement data lost, migration without snapshot, or a combo not reachable |
| 3 | One canonical ColorValue; named styles; 30 fonts render offline (airplane-mode); extended catalogue is the only network path | A guaranteed font needs network OR color diverges between hosts |
| 4 | Shared calc engine offline; SpreadsheetHost a genuine distinct host; directional Insert-Around | SpreadsheetHost degenerates to a table variant OR calc needs network |
| 5 | Encoding/override edits never mutate data; charts bind to calc ranges | Any chart edit corrupts source data |
| 6 | Image Focus non-destructive + undoable via History | Image edits destructive or bypass History |
| 7 | Connect mode temporary/contextual (no ports, no infinite canvas); stable comment anchors | Permanent ports/infinite canvas OR anchor drift |
| 8 | Fidelity-stated round-trip; DOCX carries tables/images/inline/comments; Raw Source ≠ persistence JSON; auto-snapshot before import | Raw Source leaks persistence JSON OR import corrupts without state+snapshot |
| 9 | Guide + discovery across all hosts; discovery state in guide-discovery scope | Guide bound to one host OR discovery scope leak |
| 10 | Filtered/contextual/global undo per §13.6; named snapshots; recovery UX | Filtered undo violates single-authoritative-history |
| 11 | Cross-surface accessibility pass; performance targets recorded and met; virtualization at scale; no full-doc reflow | Any host fails a11y or a claimed performance target |
| 12 | All ten Appendix-B gates pass; collaboration preserves History + unknown-payload round-trip | Any Appendix-B gate fails OR binding forces a schema change |
| 13 | Reversible default switch; every cohort migration restorable; no corpus-wide data loss; clean legacy removal | A cohort loses data OR fallback not reversible in the window |

Every domain phase (1–9) additionally gates on its in-scope accessibility, incremental-rendering, virtualization, and performance-instrumentation requirements (§5).

---

## 8. What this roadmap does not decide

This roadmap maps the repository and sequences the work. It deliberately does **not** decide, and no agent should invent:

- **The final editor domain schemas** — each is finalized inside its owning phase, then frozen for Phase 12.
- **CRDT/merge engine selection or room partitioning** — deferred to Phase 12 against final schemas (§14.7, §20.16).
- **Collaborative undo semantics** — the History Engine owns undo; the collaboration contract is not reopened here.
- **`BENCHMARK` values** — never converted to constants in this document. Open benchmark-dependent values include:
  - APK/AAB size contribution of bundling the 30 guaranteed font families vs install-time asset pack (§9.6, R7).
  - Virtualization thresholds for pages, scene objects, sheet rows, and list items.
  - Performance targets for incremental render, calc recompute, and large-document interaction (Phase 11 certification).
  - Page-geometry rendering limits within the §8.3 safety bound (max 2000 mm / 2 m²).
- **Product interaction design** — owned by the canonical spec. Where this roadmap and the spec conflict, the spec wins.

Implementation is phase-gated. Only the phase explicitly authorized in §9 may begin; all later phases require their stated entry conditions and predecessor validation/merge gates. All implementation remains behind feature flags, with the legacy host as default until Phase 13.

---

## 9. Implementation authorization

This final roadmap incorporates the completed repository audit, canonical-spec corrections, migration boundaries, phase sequencing, validation gates, and final surgical clarifications. It now authorizes **Phase 0 only**.

**Authorized now:** Phase 0 — Foundation & recovery protection.

**Not yet authorized:** Phases 1–13. Each later phase becomes authorized only after its stated entry condition and predecessor validation/merge gate have passed.

Phase 0 implementation must remain behind default-off feature flags and must not create user-visible behavior changes. The collaboration infrastructure remains preserve-frozen; no editor schema may bind to preliminary `SemanticOperation`, no CRDT/merge engine may be selected, and no collaboration room partitioning may be fixed before Phase 12.

**Phase 0 completion is not established by compilation alone.** The full Phase-0 validation gate must pass, including canonical History inversion, atomic cross-target undo, recovery snapshot restoration on real documents, Command/Selection coverage, Surface Coordinator dismissal/back behavior, Adaptive Presentation state preservation, Interaction Memory scope isolation, full build, and existing tests.

When Phase 0 passes and merges to `main`, return its implementation report and validation evidence for review before Phase 1 begins.
