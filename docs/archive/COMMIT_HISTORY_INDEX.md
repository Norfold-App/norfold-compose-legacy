# Commit History Index

**This document changes no Git objects.** It records what unclear historical commit
messages actually did, so the information is available without rewriting published
history. Every SHA below is the real, current SHA. Nothing here has been rewritten,
squashed, reordered, or re-parented.

---

## Why published history was not rewritten

This section is about **published** commits — everything at or below `ab4df0a`.
(The five unpushed Phase 4 commits were a separate case and *were* reworded; see
[Phase 4 commits](#phase-4-commits).)

Rewriting a published commit message changes that commit's SHA and the SHA of every
descendant. For this repository that is not a cosmetic operation:

| Constraint | Effect of a rewrite |
| --- | --- |
| **GitHub Pages deployment** | `docs/` is the published site root. Deployments reference published commit SHAs; rewriting them invalidates that reference. |
| **Published alpha tags** | `v0.1.0-alpha.1` → `63f29dc` and `v0.1.0-alpha.2` → `c13a188` point at published commits. A rewrite either orphans the tags or requires re-tagging published releases. |
| **Merged pull request** | PR #3 (`e32da5b`, dependabot postcss bump) is merged into published history. Rewriting re-parents a merge commit whose PR record cannot follow it. |
| **PR references #1 and #2** | Being handled separately by GitHub Support. Rewriting the underlying history while that is open would invalidate the SHAs under discussion. |
| **Secondary remotes** | `norfold/main` and `notesnync/*` track published commits (`361878e`, `c13a188`). A rewrite of `origin/main` desynchronizes them. |

A clearer commit message is worth very little. A broken deployment reference, an
orphaned release tag, and an invalidated support case are worth a great deal more.
**The index is the correct trade.**

Consequently, for published history:

- No `git rebase`, `git filter-branch`, or `git filter-repo` was run at any point.
- No force push of any kind was performed, with or without lease.
- No commit at or below `ab4df0a` was altered in any way.
- No backup tag was created or pushed — no published commit was touched, and a public
  tag would itself become another published reference to maintain.

---

## Current canonical starting point

| Item | Value |
| --- | --- |
| Repository | `Norfold-App/Norfold` (`origin`) |
| Default branch | `main` |
| Last published commit at cleanup time | `ab4df0a` — *Reconcile Phase 3 final report* |
| Local `main` before cleanup | `8a8f609` — five unpushed Phase 4 commits ahead, zero behind |
| Local `main` after the Phase 4 reword | `398f406` — same five commits, same trees, Conventional subjects |
| Total commits in history | 130 |
| First commit | `12b2da6` — *Initial Norfold release* (2026-07-11) |

`ab4df0a` is the boundary between published and unpublished work. Everything at or
below it is immutable in practice.

---

## Naming convention, before and after

Early history used conversational, sentence-style subjects — for example
*"Make persistence a state machine that actually retries"* or *"Ask the block being
edited, not the row that drew it"*. They read well but do not sort, filter, or scan,
and they do not say which subsystem changed.

From the Phase 3 work onward the repository uses **Conventional Commits**:

```
feat(scope): description        fix(scope): description
docs(scope): description        refactor(scope): description
test(scope): description        perf(scope): description
build(scope): description       chore(scope): description
```

All future commits use this form. Historical subjects are left as they are and
translated here instead.

---

## Historical milestones

| Date | SHA | Milestone |
| --- | --- | --- |
| 2026-07-08 | `63f29dc` | Alpha 1 build — tagged `v0.1.0-alpha.1` (pre-rebrand). |
| 2026-07-08 | `c13a188` | Alpha 2 — tagged `v0.1.0-alpha.2`; compact sidebar and keyboard bars. |
| 2026-07-11 | `12b2da6` | *Initial Norfold release* — the rebranded repository baseline. |
| 2026-07-16 | — | Architecture pivot recorded: markdown retired as the canonical storage format in favour of a versioned typed block tree. See [../editor/decisions/EDITOR-REARCHITECTURE.md](../editor/decisions/EDITOR-REARCHITECTURE.md). |
| 2026-07-27 | `6bded71` | Chat phases complete through Phase 15; migrations repaired for remote deploy. |
| 2026-07-28 | `17c1f93` … `67ccc07` | **Editor Phase 0** — shared engine substrate: feature flags, History Engine, recovery snapshots (`17c1f93`), Command Registry (`6eac8ab`), Selection Engine (`c3475c2`), Surface Coordinator (`da43836`), Adaptive Presentation (`c3d253b`), Interaction Memory (`ac9808e`), font-delivery benchmark (`67ccc07`). |
| 2026-07-29 | `0af67ff` … `b9e628c` | **Editor Phase 1** — FlowHost: FlowEditController over canonical History (`0af67ff`), structural command catalogue (`e1b2029`), Slash Local Command Resolver (`b26f344`), Compose shell (`a2ce0b9`), certification slice (`8367226`), closure (`b9e628c`). |
| 2026-07-30 → 07-31 | `e5a90b9` … `361878e` | **Editor Phase 2** — DocumentHost, scene/page model, persistence state machine, bounded-page rendering. This is the conversationally-named run; see the translation table below. |
| 2026-07-31 | `e32da5b` | Merge of PR #3 — dependabot postcss 8.5.16 → 8.5.25 in `apps/web`. |
| 2026-08-01 | `5e51035` … `ab4df0a` | **Editor Phase 3** — shared styling, offline typography, Color Studio boundaries, rendering semantics, document scale closure. Last published commit. |
| 2026-08-04 | `f767831` … `398f406` | **Editor Phase 4** — shared calculation domain and gated SpreadsheetHost. Unpushed; subjects reworded from `63c9af4` … `8a8f609` with trees unchanged (see below). |

---

## Translation of conversationally-named commits

These subjects are accurate but unscannable. The right-hand column is what the subject
**would** be under the current convention. The stored message is unchanged.

### Phase 2 — DocumentHost (2026-07-30 → 2026-07-31)

| SHA | Stored subject | Conventional equivalent |
| --- | --- | --- |
| `e5a90b9` | Make nested and to-do content addressable by the block vocabulary | `feat(editor): address nested and to-do content by block vocabulary` |
| `9a6e444` | Give the runtime the interaction facts the surface cannot own | `refactor(editor): move interaction facts from surface to runtime` |
| `6138c1a` | Make FlowHost behave the way the runtime already decided | `fix(flow): align host behavior with runtime decisions` |
| `c60a72b` | Clear the dirty baseline only once persistence has accepted the write | `fix(editor): clear dirty baseline only after persistence accepts` |
| `2f3a334` | Cover the interaction gate with tests instead of deferral notes | `test(editor): cover the interaction gate` |
| `4c27d14` | Persist what the document actually changed, and only when it lands | `fix(editor): persist only landed document changes` |
| `07254cf` | Resolve drops against the document, not the visible window | `fix(editor): resolve drag drops against document coordinates` |
| `1845bb6` | Pin the seven fixes with tests that fail without them | `test(editor): pin seven fixes with failing-without tests` |
| `885dfbf` | Make persistence a state machine that actually retries | `feat(editor): make persistence a retrying state machine` |
| `1166c5f` | Ask the block being edited, not the row that drew it | `fix(editor): resolve edit context from block, not row` |
| `540a6df` | Prove the five fixes by breaking them again | `test(editor): mutation-verify five fixes` |
| `1498e80` | Let the document own its writer, not the screen | `refactor(document): move writer ownership to the document` |
| `bfe23d2` | Ask the item which vocabulary it speaks | `fix(editor): resolve item vocabulary from the item` |
| `8210209` | Break the three fixes again to prove the tests hold | `test(editor): mutation-verify three fixes` |
| `a3c84cb` | Make a page a block so undo already knows how to invert it | `refactor(document): model a page as a block for undo inversion` |
| `3abf810` | Give the page surface its own scope to answer against | `refactor(document): give the page surface its own scope` |
| `f6b23dd` | Build the migration a candidate, never a rewrite | `fix(document): make legacy migration produce a candidate` |
| `8cfd792` | Let the document editor reach the writer Flow already proved | `feat(document): reuse the proven Flow writer` |
| `aa4dbfa` | Keep editor scaffolding and download cruft out of the repo | `chore(repo): exclude editor scaffolding and download cruft` |
| `76cd768` | Give the bounded page the human half it was missing | `feat(document): complete bounded-page interaction` |
| `361878e` | Make the rotated page behave like the page you can see | `fix(document): correct rotated-page behavior` |

### Earlier boundary commits

| SHA | Stored subject | Conventional equivalent |
| --- | --- | --- |
| `61b16ef` | checkpoint: uncommitted working state before status audit | `chore(repo): checkpoint working state before status audit` |
| `e8b3555` | A1 setup: initialize parallel mission maintenance | `chore(repo): initialize parallel mission maintenance` |
| `575701f` | Fix document point rendering and Todo scaling | `fix(document): correct point rendering and Todo scaling` |
| `ab4df0a` | Reconcile Phase 3 final report | `docs(editor): reconcile phase 3 final report` |

---

## Phase-to-report mapping

| Phase | Subject | Boundary commits | Report |
| --- | --- | --- | --- |
| 0 | Shared engine substrate | `17c1f93` → `67ccc07` | Recorded in the roadmap; D9 benchmark at [../editor/benchmarks/D9_FONT_DELIVERY_BENCHMARK.md](../editor/benchmarks/D9_FONT_DELIVERY_BENCHMARK.md) |
| 1 | FlowHost (Notes) | `0af67ff` → `b9e628c` | [editor/PHASE_1_FINAL_REPORT.md](editor/PHASE_1_FINAL_REPORT.md) |
| 2 | DocumentHost | `e5a90b9` → `361878e` | [editor/PHASE_2_VALIDATION_REPORT.md](editor/PHASE_2_VALIDATION_REPORT.md) |
| 3 | Rendering, tokens, styles, fonts | `5e51035` → `ab4df0a` | [editor/PHASE_3_FINAL_REPORT.md](editor/PHASE_3_FINAL_REPORT.md) |
| 4 | Calculation domain + SpreadsheetHost | `f767831` → `398f406` | [../editor/PHASE_4_FINAL_REPORT.md](../editor/PHASE_4_FINAL_REPORT.md) |

---

## Phase 4 commits

Complete and frozen. Their code, tests, report, and commit boundaries must not change.

These five commits were **unpushed**, so rewording their subjects carried none of the
constraints listed at the top of this document. Their subjects were rewritten to
Conventional Commit form. **Only the subject lines changed.**

| # | Old SHA | New SHA | Tree (unchanged) | New subject |
| --- | --- | --- | --- | --- |
| 1 | `63c9af4` | `f767831` | `56fad2a4109543e6ce05f21188e925dbcbefad3c` | `feat(editor): add shared pure-JVM calculation domain` |
| 2 | `0a665b3` | `cd2532c` | `f0154bf8fc34fb4afb7cdf6fd966ca1eb42df09b` | `feat(editor): persist calculation workbooks on table blocks` |
| 3 | `254fd8a` | `e004c74` | `ed7ce233d568cb2c7c31c8bb8da0388e53f87ce7` | `feat(editor): route Flow and Document tables through shared engine` |
| 4 | `a9e65ff` | `c5ca2c4` | `b57d23a73fc4181a6a481eb7c03caaba296a5105` | `feat(editor): add gated SpreadsheetHost workspace` |
| 5 | `8a8f609` | `398f406` | `8de5098367284c057bbedd5881d579aa85e6c251` | `test(editor): complete Phase 4 validation and report` |

Original subjects, in the same order: *Add shared pure-JVM calculation domain*;
*Persist calculation workbooks additively on TableBlock*; *Route Flow and Document
tables through the shared engine*; *Add SpreadsheetHost as a distinct workspace behind
its flag*; *Add Phase 4 calculation tests and final report*.

Why this was safe, checked before the rewrite:

- Only `refs/heads/main` contained them — no tag, no remote-tracking ref, no other
  local branch (`git for-each-ref --contains`).
- None were pushed to any remote.
- None were signed, so no signature was invalidated.
- The history is linear across the five; no merge topology to preserve.
- No tracked file cited any of the five SHAs, so no document needed updating.

What was preserved, verified after the rewrite:

- **Every tree hash is byte-identical** to its original — the column above is the same
  value before and after, and `git diff 8a8f609 398f406` is empty.
- Author name, author email, author date, committer name, committer email, and
  committer date are unchanged on all five.
- Every message **body** below the subject line is byte-identical.
- Commit count, order, and parentage are unchanged; `ab4df0a` is still the parent of
  the first of the five.
- Nothing was squashed, reordered, combined, or split.

The rewrite used `git commit-tree` against the original trees, then a single
`git update-ref` on `refs/heads/main`. No `rebase`, `filter-branch`, `filter-repo`,
`reset`, `stash`, or `clean` was run, and the working tree was never modified by the
operation.

Verify at any time with `git rev-parse <sha>^{tree}` against the table above.

---

## Pull requests #1 and #2

References to PR #1 and #2 are being handled separately by GitHub Support. Nothing in
this cleanup touched them, and no history rewrite was performed that would change the
SHAs under discussion. PR #3 (`e32da5b`) is merged and remains in published history
unaltered.

---

## What this document is not

- It is **not** a record of a rewrite of *published* history. No published commit was
  altered. The only messages changed in Git were the five **unpushed** Phase 4 subjects
  documented above; everything else in the tables below is a translation for reading,
  not an edit that was applied.
- It is **not** authoritative over the source. It explains history; the source is the
  current truth.
- It does **not** license a future rewrite. The constraints at the top still apply.

For the rules that govern future commits, see [../../AGENTS.md](../../AGENTS.md) and
[../REPOSITORY_GUIDE.md](../REPOSITORY_GUIDE.md).
