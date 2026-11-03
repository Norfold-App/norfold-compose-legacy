# Norfold

> **Archived as of November 3, 2026.** Development has ended. This repository is kept for
> historical reference only and is no longer maintained.

Norfold is an Android-first private workspace. Notes, documents, tasks, calendar events,
files, and chat are stored as one local-first object model, with encrypted exports and
backups plus optional folder-provider or cloud sync. The live Room database itself is not
encrypted at rest in this pre-beta build.

**Release stage: pre-beta.** The current version is `0.1.0-alpha.3`. This is development
software: it is not distributed through a store, its cloud and device behaviour is not yet
certified, and no part of it should be treated as production-ready.

## Repository layout

| Path | Contents |
| --- | --- |
| `apps/android/` | The product. Kotlin, Jetpack Compose, Room, unit tests, instrumentation tests. |
| `apps/web/` | Vite visual prototype. Not the product and not shipped. |
| `supabase/` | Postgres migrations and Deno edge functions for the optional cloud layer. |
| `infra/` | Transport and backend infrastructure configuration. |
| `docs/` | Source of the project site and the legal pages. |
| `brand/`, `assets/`, `website-v4/` | Brand assets and unpublished marketing site source. |
| `legacy/flutter-prototype/` | Preserved Flutter prototype, reference only. |

## Architecture

- One Gradle module, `:apps:android`. Kotlin 2.3.21, AGP 8.13.2, Compose BOM 2025.11.00,
  Material 3, `minSdk` 26, `targetSdk`/`compileSdk` 36, JVM target 17.
- Room 2.8.4 is the local source of truth. Notes, task documents, and calendar events share
  one generic owner-based document model, alongside workspace objects, object links,
  activity, history, comments, and file records.
- The canonical document is a versioned typed block tree with stable block IDs and unknown
  block preservation. Markdown is an import, export, print, and clipboard boundary only —
  it is neither an editor mode nor a persistence format.
- The vault derives keys locally and encrypts exports; backup snapshots use the
  `NORFOLD-VAULT-V1` and `NORFOLD-BACKUP-V3` container formats.
- Sync is offline-first. A device can sync through Android's document-tree picker to any
  folder provider, or through the Google Drive API `appDataFolder`.
- Cloud features are optional and additive: Supabase Postgres with row-level security,
  security-definer RPCs, and Deno edge functions for invitations, notifications, calendar
  callbacks, collaboration tokens, and Play billing notifications.

## Build

Temurin/OpenJDK 17 is the canonical build JDK, with Android SDK platform 36 and the
bundled Gradle 9.4.1 wrapper. A newer launcher JVM is not a supported configuration.

```bash
export JAVA_HOME=/path/to/temurin-17
./gradlew :apps:android:assembleDebug
```

Cloud features read public client configuration from `norfold.properties`, which is
gitignored. Copy `norfold.properties.example` and supply your own project values. No
private key or secret belongs in that file or anywhere else in this repository.

## Verification

The non-device gate is these five commands, run on JDK 17:

```bash
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:compileDebugKotlin
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:lintDebug
./gradlew :apps:android:compileDebugAndroidTestKotlin
```

`lintDebug` is part of the gate rather than an extra: unit tests run on the desktop JVM, so
only lint catches a call to an API newer than `minSdk` 26.

What that gate does **not** cover, and what therefore remains uncertified:

- Physical-device behaviour, TalkBack and accessibility-service certification, and
  release-configuration performance.
- Live backend behaviour. The cloud and edge-function code is implemented and unit- or
  type-checked, but adversarial row-level-security, cross-identity, storage, and recovery
  testing against isolated live identities has not been completed.
- Backup and restore round trips are tested against the current schema only. Earlier
  development schemas were allowed to reset destructively.

## Site and legal

The public project and legal site is [norfold.pages.dev](https://norfold.pages.dev/).
Its source lives in `docs/`: [privacy policy](docs/privacy.html), [terms of
service](docs/terms.html), and [support](docs/support.html).

## License

**Source-available, view-only. Not open source.**

This code is published so that it can be read, not reused. You may browse and fork it on
GitHub for viewing; you may **not** use, copy, modify, redistribute, or build on this code
or the Norfold brand assets in any project. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md)
for the full terms. External contributions are not accepted — see
[CONTRIBUTING.md](CONTRIBUTING.md).
