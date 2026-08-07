# Norfold

**The rules for working in this repository are in [AGENTS.md](AGENTS.md). Read it now, before
your first tool call that touches the repository.**

This file exists because assistants load different filenames by convention — some read
`AGENTS.md`, some read `CLAUDE.md`. They must not drift into two rule sets that disagree, so
this one is a pointer and `AGENTS.md` is the single source of truth. Do not copy rules here.

## The five that get broken most

These are restated because history shows them being broken by sessions that had already read
the rules. The rest of `AGENTS.md` still applies in full.

1. **Stage by explicit path. Never `git add -A`, never `git add -f`.** The worktree normally
   holds unfinished owner work and local cruft. A blanket add is how agent scratch files,
   `*:Zone.Identifier`, and half-finished work reach a commit. `.gitignore` covers all of it,
   but `-f` overrides `.gitignore` outright — if a file needs forcing, that is the signal to
   stop, not to force it.
2. **No branch, no pull request, no tag, unless the task says so.** Commit on `main` and push
   directly to the `norfold` remote.
3. **Owner identity only, and no attribution markers** — no co-author trailer, no
   generated-with line, no mention of tooling anywhere in a commit message or source comment.
4. **Never rewrite a pushed commit.** No `--amend` on anything pushed, no rebase, no
   `push --force`. Published SHAs are referenced by GitHub Pages and by release tags.
5. **JDK 21.** The system `java` is 25 and breaks both the build and the unit tests.

## Before you start

```
git status --porcelain      # someone's unfinished work until proven otherwise
git log --oneline -5        # what actually landed, vs what the task claims landed
```

Verify the repository state rather than trusting a SHA quoted in the task. If what you find
contradicts the task description, stop and report it instead of proceeding.

## Before you claim you are done

```
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:compileDebugKotlin
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:lintDebug
./gradlew :apps:android:compileDebugAndroidTestKotlin
```

`lintDebug` earns its place in the gate: unit tests run on the desktop JDK, so they cannot
catch an Android API-level violation. A call to an API newer than `minSdk` passes every unit
test and throws on most real devices. Lint is the only step in this list that sees it.

Never describe a step you skipped as passed. If a gate needs a device, say it is deferred.
