# Norfold — Agent and Contributor Rules

Read this before touching the repository. It is short so that it actually gets read.
The long version is [docs/REPOSITORY_GUIDE.md](docs/REPOSITORY_GUIDE.md); the
documentation map is [docs/README.md](docs/README.md).

This file is the single source of truth for every assistant and contributor.
[CLAUDE.md](CLAUDE.md) exists only because some assistants load that filename by
convention; it points here and holds no rules of its own.

## Release stage: `PRE_BETA`

Development data is disposable. Incompatible development schemas may use a clean
install or destructive reset **only** on explicitly scoped test devices, and only
when the current task authorizes it. Current-schema persistence and backup/sync
round trips still require tests.

If anyone says *beta*, *beta testing*, *release candidate*, *outside testers*, or
*production distribution*: **stop and warn** that earlier builds allowed destructive
resets. Ask permission to activate the Beta migration gate before distribution.
Once that gate is active, migrations from every distributed beta version are
mandatory and destructive production fallback is forbidden.

## Never do these without explicit owner authorization

- `git reset --hard`, `git clean`, `git restore .`, `git checkout -- .`, `git stash`,
  `git rebase`, `git filter-branch`, `git filter-repo`.
- Rewriting any commit that has been pushed. Published SHAs are referenced by
  GitHub Pages deployments and by the `v0.1.0-alpha.*` tags.
- `git push --force`. Never plain `--force`; only `--force-with-lease`, and only when
  separately authorized for that specific push.
- Creating a branch, a pull request, or a tag.
- Wiping or resetting Supabase independently of installed clients. A server-only wipe
  leaves stale sessions and local queues that repopulate deleted data. Server and
  client resets must be coordinated.

## Preserve owner work

Inspect `git status`, the current branch, recent commits, and untracked files **first**.
A dirty worktree is someone's unfinished work until proven otherwise — read it before
you touch it. If what you find contradicts how the task described it, stop and report
instead of proceeding.

Before any risky operation, capture the state outside Git (patches, an untracked-file
archive, a `git bundle`). Do not commit the capture.

## Device testing

Do not run emulator tests, physical-device tests, screenshots, or accessibility-service
certification unless the current task explicitly authorizes it. Static and unit tests
are **not** a substitute for device certification — if a gate needs a device, say it is
deferred rather than claiming it passed.

## Never commit

- Credentials, API keys, bearer tokens, service-account JSON, keystores, private keys.
- Machine-local paths: `/home/...`, `C:\Users\...`, `\\wsl.localhost\...`, `/Users/...`,
  downloads folders, local APK paths.
- Private planning or agent material: handoff directories, agent transcripts,
  session logs, scratch files, local tooling state directories.
- Third-party attribution markers of any kind. Commits carry the owner's identity and
  nothing else — no additional author or co-author trailers, no tooling provenance
  markers, no mention of what was used to write the change.
- Generated build output (`apps/android/build/`, `dist/`, `node_modules/`).
- Personal screenshots, unrelated downloads, `*:Zone.Identifier` files.

Stage by explicit path. **Never `git add -A`.**

The mechanical subset of these rules is enforced by `tools/repo-guard.sh`. Run it against
your staged changes before every commit, and install it once as a pre-commit hook so the
check cannot be forgotten:

```
tools/repo-guard.sh              # check what is staged
tools/repo-guard.sh --install    # install as .git/hooks/pre-commit
```

CI runs the same script over the pushed commit range. A clean guard is the floor, not
proof of a good commit — it cannot judge scope, correctness, or whether the change was
authorized.

## Commits

Use the owner's Git identity only. Use Conventional Commits:

```
feat(scope): description        fix(scope): description
docs(scope): description        refactor(scope): description
test(scope): description        perf(scope): description
build(scope): description       chore(scope): description
```

One coherent change per commit. Do not mix documentation reorganization with
application source changes, and do not push unrelated changes together.

## Scope discipline

- Do not begin a later editor phase before the current one is reviewed.
- Do not change Supabase, chat, infrastructure, or `apps/web` unless the current task
  explicitly owns them.
- Collaboration and CRDT integration are frozen until Phase 12.
- **Phase 4 is closed.** Its code, tests, and report are fixed and must not be modified
  without an owner directive that names Phase 4 explicitly. See
  [docs/editor/PHASE_4_FINAL_REPORT.md](docs/editor/PHASE_4_FINAL_REPORT.md).
- **Every editor feature flag defaults to `false`** in
  `apps/android/src/main/java/com/norfold/app/editor/flags/EditorFeatureFlags.kt`.
  `FLOW_HOST`, `DOCUMENT_HOST`, and `SPREADSHEET_HOST` defaults do not change without
  explicit authorization.

## External code

External projects are **reference-only**: they may inform feature ideas, workflows,
compatibility expectations, and test categories. They may not enter Norfold through
source copying, translation, copied tests or assets, vendoring, submodules, or
runtime dependencies. Never copy, translate, or link GPL/AGPL/source-available code.
Any dependency addition needs owner approval plus license, replaceability, and
security review. See
[docs/EXTERNAL_REFERENCE_POLICY.md](docs/EXTERNAL_REFERENCE_POLICY.md).

## Where things are

| Path | Contents |
| --- | --- |
| `apps/android/` | The product. Source, unit tests, instrumentation tests. |
| `apps/web/` | Visual prototype only. Not the product. |
| `docs/` | Documentation **and the GitHub Pages site root** (`*.html`, `assets/`). |
| `docs/editor/` | Active editor roadmap, decisions, benchmarks. |
| `docs/archive/` | Completed phase reports and history records. |
| `supabase/`, `infra/` | Backend and transport infrastructure. |
| `brand/`, `website-v4/` | Brand assets and the marketing site. |

Authoritative documents:

- [docs/editor/EDITOR_IMPLEMENTATION_ROADMAP.md](docs/editor/EDITOR_IMPLEMENTATION_ROADMAP.md) — editor roadmap of record.
- [docs/editor/decisions/EDITOR-REARCHITECTURE.md](docs/editor/decisions/EDITOR-REARCHITECTURE.md) — structured-document architecture.
- [docs/editor/PHASE_4_FINAL_REPORT.md](docs/editor/PHASE_4_FINAL_REPORT.md) — most recent completed phase.
- [docs/product/NORFOLD_UI_GOALSET.md](docs/product/NORFOLD_UI_GOALSET.md) — UI acceptance checklist.
- [docs/REPOSITORY_GUIDE.md](docs/REPOSITORY_GUIDE.md) — layout, workflow, gates.

## Build and verification gate

JDK 21 is required. JDK 25 breaks the build and the unit tests.

```
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:compileDebugKotlin
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:compileDebugAndroidTestKotlin
```

Run the gate before claiming an implementation is complete. Report failures with the
output; never describe a skipped step as passed.

Documentation reports are evidence, not a substitute for reading the source.
