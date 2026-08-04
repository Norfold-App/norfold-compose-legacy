# Norfold Repository Guide

The long-form companion to [../AGENTS.md](../AGENTS.md). `AGENTS.md` holds the
non-negotiable rules; this guide explains the layout, the workflow, and the reasoning
behind them. The documentation map is [README.md](README.md).

---

## 1. Repository layout

| Path | Contents | Notes |
| --- | --- | --- |
| `apps/android/` | **The product.** Kotlin / Jetpack Compose Android app. | Only Gradle module: `:apps:android`. |
| `apps/web/` | Web prototype. | Visual prototype only. Not the product, not a build target for feature work. |
| `docs/` | Documentation **and the GitHub Pages site root**. | See §3. |
| `supabase/` | Database migrations and edge functions. | See §9 before touching. |
| `infra/` | Collaboration transport infrastructure (Ably, Cloudflare). | Frozen until Phase 12. |
| `brand/`, `assets/`, `website-v4/` | Brand assets and the marketing site. | Not covered by any open-source grant — see [../NOTICE.md](../NOTICE.md). |
| `scripts/` | Repository maintenance scripts. | `scripts/local/` is ignored and holds secret-bearing local setup. |
| `legacy/` | Retired prototype material. | Historical only. |
| `gradle/`, `gradlew`, `settings.gradle.kts`, `build.gradle.kts` | Build system. | Gradle 9.4.1, Kotlin 2.3.0. |

### Android source, test, and build locations

| Kind | Path |
| --- | --- |
| Application source | `apps/android/src/main/java/com/norfold/app/` |
| JVM unit tests | `apps/android/src/test/java/com/norfold/app/` |
| Instrumentation tests | `apps/android/src/androidTest/java/com/norfold/app/` |
| Resources / assets | `apps/android/src/main/res/`, `apps/android/src/main/assets/` |
| Debug APK output | `apps/android/build/outputs/apk/debug/` |
| Release bundle output | `apps/android/build/outputs/bundle/release/` |

Everything under `apps/android/build/` is generated. It is ignored and must never be
committed.

Editor subsystems of interest:

| Package | Role |
| --- | --- |
| `editor/flags/` | Feature-flag substrate. Pure JVM, no Android dependency. |
| `editor/calculation/` | Shared calculation domain (Phase 4). Pure JVM — no Compose, Android, network, reflection, or filesystem access. |
| `editor/flow/`, `editor/document/` | Flow and Document host runtimes. |
| `ui/editor/` | Compose surfaces for the editor hosts. |

---

## 2. Documentation organization

```
docs/
  README.md                     documentation index — start here
  REPOSITORY_GUIDE.md           this file
  EXTERNAL_REFERENCE_POLICY.md  external reference and licensing rules

  product/                      product goals and status
  editor/                       active editor roadmap
    decisions/                  architecture decision records
    benchmarks/                 measured results
  collaboration/                transport decisions and protocols (frozen until Phase 12)
  operations/                   provisioning and service runbooks
  archive/                      historical records
    editor/                     completed phase reports

  index.html download.html changelog.html support.html
  privacy.html terms.html legal.css assets/     <- GitHub Pages site, do not move
```

Rules:

- **The `docs/*.html` files and `docs/assets/` are published web pages.** Existing
  Pages deployments and external links depend on those exact paths. Never move or
  rename them as part of a documentation reorganization.
- One canonical roadmap. Do not create a second copy of
  `editor/EDITOR_IMPLEMENTATION_ROADMAP.md`.
- Completed phase reports go to `archive/editor/`. The **current** phase report stays
  in `editor/` so it remains discoverable.
- Move a document only when the move improves discoverability, and use `git mv` so the
  move stays recognizable in history.
- Update every repository-relative link after a move, including links inside the moved
  file and any source comment that points at it.
- Archived reports are historical evidence, not current-state claims.

---

## 3. Feature flags

`apps/android/src/main/java/com/norfold/app/editor/flags/EditorFeatureFlags.kt`

**Every flag defaults to `false`.** The default map is the source of truth for what
ships to users. Overrides are process-local and exist so tests and gated internal
builds can flip a flag without changing a default.

| Flag | Key | Default | Gates |
| --- | --- | --- | --- |
| `FLOW_HOST` | `editor.host.flow` | `false` | FlowHost for Notes (Phase 1) |
| `DOCUMENT_HOST` | `editor.host.document` | `false` | DocumentHost for bounded documents (Phase 2) |
| `SPREADSHEET_HOST` | `editor.host.spreadsheet` | `false` | SpreadsheetHost workspace (Phase 4) |
| `HISTORY_ENGINE`, `RECOVERY_SNAPSHOTS`, `COMMAND_REGISTRY`, `SELECTION_ENGINE`, `SURFACE_COORDINATOR`, `ADAPTIVE_PRESENTATION`, `INTERACTION_MEMORY` | `editor.engine.*` | `false` | Shared engines (Phase 0 substrate) |
| `COLOR_STUDIO`, `NAMED_STYLES`, `OFFLINE_FONTS` | `editor.domain.*` | `false` | Phase 3 domains |
| `CALC_ENGINE` | `editor.domain.calc` | `false` | Phase 4 calculation domain |
| `CHART_STUDIO`, `IMAGE_FOCUS`, `RELATIONSHIPS`, `IMPORT_EXPORT_RAW`, `GUIDE` | `editor.domain.*` | `false` | Phases 5–9, not started |

Changing any default is a product decision, not an implementation detail. It requires
explicit owner authorization.

---

## 4. Editor phase status

| Phase | Subject | Status | Report |
| --- | --- | --- | --- |
| 0 | Shared engine substrate | Complete | Roadmap |
| 1 | FlowHost (Notes) | Complete; device certification deferred | [archive/editor/PHASE_1_FINAL_REPORT.md](archive/editor/PHASE_1_FINAL_REPORT.md) |
| 2 | DocumentHost | Complete | [archive/editor/PHASE_2_VALIDATION_REPORT.md](archive/editor/PHASE_2_VALIDATION_REPORT.md) |
| 3 | Rendering, tokens, styles, fonts | Complete | [archive/editor/PHASE_3_FINAL_REPORT.md](archive/editor/PHASE_3_FINAL_REPORT.md) |
| 4 | Calculation domain + SpreadsheetHost | **Complete and frozen** | [editor/PHASE_4_FINAL_REPORT.md](editor/PHASE_4_FINAL_REPORT.md) |
| 5+ | Chart Studio, Image Focus, relationships, import/export, guide | **Not started** | — |

Do not begin a later phase before the current one is reviewed. Phase 5 means no
`ChartDocument`, no chart renderer, no Chart Studio, and no chart command or UI. The
read-only calculated-range seam that Phase 4 added is the only permitted forward hook.

---

## 5. Safe Git workflow

### Before changing anything

```
git status --short
git status
git log --oneline --decorate -40
git diff --stat
git diff
git diff --cached
git ls-files --others --exclude-standard
git remote -v
git branch -vv
git tag --list
```

If the observed state contradicts how the task described it, **stop and report**.

### Safety capture before risky work

Capture outside the repository — `/tmp/` is appropriate:

- current HEAD, branch, and remote-tracking SHA
- `git status --porcelain=v2`
- `git diff --binary` and `git diff --cached --binary`
- untracked file list, plus an archive of those files if any exist
- `git log --all --decorate --graph --oneline`
- `git bundle create <path> --all`
- tree hashes of any commits that must be preserved byte-for-byte

Never add the capture to Git.

### Forbidden without explicit authorization

`git reset --hard`, `git clean`, `git restore .`, `git checkout -- .`, `git stash`,
`git rebase`, `git filter-branch`, `git filter-repo`, `git push --force`, creating a
branch or tag, opening a pull request.

### History rewriting

Rewriting a commit changes its SHA and every descendant SHA. It requires **explicit
owner authorization for that specific rewrite** — it is never a routine cleanup step.

When authorized, the requirements are absolute:

1. a safety capture exists first, including a `git bundle` of all refs;
2. no commit is squashed, reordered, combined, or split unless that is the stated goal;
3. every rewritten commit's tree hash is compared to its original and matches exactly,
   unless the rewrite is deliberately changing content;
4. author identity, author date, and committer date are preserved;
5. the result is verified before the push, not after.

Push a rewrite only with `--force-with-lease`, never plain `--force`. Stop immediately
if the remote moved unexpectedly, if another contributor depends on the current SHAs,
or if commit signatures or release references would break.

### Commit convention

Conventional Commits, owner identity only, no third-party attribution trailers:

```
feat(editor): add shared pure-JVM calculation domain
fix(document): preserve page-point rendering scale
docs(repo): organize repository documentation
chore(repo): reconcile repository links and history records
```

Scope the commit to one coherent change. Stage by explicit path — **never
`git add -A`**. Documentation reorganization and application source changes belong in
separate commits.

### Pushing

The ordinary push is a plain fast-forward to the tracking remote:

```
git push origin main
```

Confirm first that local `main` is a descendant of `origin/main` with zero behind:

```
git merge-base --is-ancestor origin/main HEAD && echo fast-forward-safe
git rev-list --left-right --count origin/main...HEAD    # behind<TAB>ahead
```

Never plain `--force`. `--force-with-lease` only when separately authorized.

---

## 6. Local-only versus tracked artifacts

Local-only (ignored, and correctly so):

- `apps/android/build/`, `.gradle/`, `.kotlin/`, `node_modules/`, `dist/`
- `local.properties`, `norfold.properties`, `*.keystore`, `*.jks`, `*.p8`,
  `service-account*.json`, `supabase/.env*`
- `scripts/local/` — secret-bearing local setup
- agent and planning tooling state directories
- `*:Zone.Identifier` — Windows download metadata that appears beside any file
  fetched through a browser on the Windows side of WSL

Tracked but sensitive-adjacent: `norfold.properties.example` is a template and must
never contain a real value. Supabase edge functions read secrets through
`requiredEnv("...")` — the environment-variable *name* is tracked, the value never is.

If a file only makes sense on one machine, it does not belong in the repository. Put
what a future contributor needs into a tracked document instead of a local path.

---

## 7. Build and verification

JDK 21 is required. **JDK 25 breaks the build and the unit tests.**

```
export JAVA_HOME=<path to a JDK 21 installation>
```

The gate, run in this order:

```
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:compileDebugKotlin
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:compileDebugAndroidTestKotlin
```

Continuous integration (`.github/workflows/build.yml`) additionally runs
`lintDebug` and `bundleRelease`, and builds the web prototype.

Rules:

- Run the gate before claiming an implementation is complete.
- Report failures with the output. Never describe a skipped step as passed.
- For documentation-only work, do **not** rerun the Android suite unless a tracked
  application or build file was touched. If one changes unexpectedly, investigate and
  restore it.
- Device, emulator, screenshot, and assistive-technology certification require explicit
  authorization. Static and unit tests do not substitute for them — mark such gates
  **deferred**, not passed.

---

## 8. Release-stage rules

Current stage: **`PRE_BETA`.**

Development data is disposable. A clean install or destructive reset is permitted only
on explicitly scoped test devices, and only when the current task authorizes it.
Current-schema persistence and backup/sync round trips still require tests.

The moment distribution to outside testers is discussed, stop and warn that earlier
builds allowed destructive resets, and ask permission to activate the Beta migration
gate. After that gate is active:

- migrations from every distributed beta version are mandatory;
- destructive production fallback is forbidden.

---

## 9. Supabase, backup, and reset safety

- **Never wipe or reset Supabase independently of installed clients.** A server-only
  wipe leaves stale sessions and queued local writes that repopulate the deleted data,
  producing a state that looks like corruption. Server and client resets are one
  coordinated operation.
- Provisioning belongs in
  [operations/EXTERNAL_SERVICES_ALPHA.md](operations/EXTERNAL_SERVICES_ALPHA.md).
- No provider secret, private key, database password, Stripe secret, or service-account
  JSON goes into the Android project or any tracked file.
- Any data added to Room must also be covered by backup and by encrypted sync
  snapshots; otherwise a restore silently loses it.
- Do not change Supabase, chat, `apps/web`, or infrastructure unless the current task
  explicitly owns them.

---

## 10. External references and licensing

Full policy: [EXTERNAL_REFERENCE_POLICY.md](EXTERNAL_REFERENCE_POLICY.md).

Summary: external projects are reference-only. They may inform ideas, workflows,
compatibility expectations, and test categories. They may not enter Norfold as copied
source, translated source, copied tests or assets, vendored code, submodules,
binaries, or runtime dependencies. Never copy, translate, or link GPL/AGPL or
source-available code. Any dependency addition requires owner approval plus license,
replaceability, and security review, and a recorded decision.

---

## 11. Resuming work safely

A contributor or agent picking up partial work should:

1. **Read the state before changing it.** `git status`, the branch, recent commits, and
   untracked files. Read the dirty worktree; do not assume it is disposable.
2. **Check the state against the task description.** A mismatch is a stop condition.
3. **Capture before acting** (§5) when anything risky is planned.
4. **Read [../AGENTS.md](../AGENTS.md), then this guide, then the roadmap**
   ([editor/EDITOR_IMPLEMENTATION_ROADMAP.md](editor/EDITOR_IMPLEMENTATION_ROADMAP.md)),
   then the current phase report.
5. **Read the source, not only the reports.** A report is evidence of what was verified
   at a point in time; the source is the current truth.
6. **Keep scope tight.** Finish what was asked. If part of it is blocked, complete
   everything else and say plainly what was left and why.
7. **Never mark something complete that was not verified.** Deferred is an honest
   status; falsely passed is not.
