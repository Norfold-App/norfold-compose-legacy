# Norfold Android Alpha 0.1.0-alpha.3

This is a heavy-testing alpha build. Do not use it as the only copy of important documents, attachments, workspace data, chat history, or sync-chain content.

## Included

- Android package `com.norfold.app`, `minSdk` 26, with Norfold branding and a native splash handing over to a branded Compose loader.
- Room-backed documents, tasks, workspaces, file metadata, chat, settings, vault, backups and sync snapshots.
- One editor across three hosts — Docs, Flow and Spreadsheet — over a shared structured document model, with Chart Studio, Image Focus, Shape Builder, the equation builder, the diagram builder, named styles, bundled offline fonts, and local history and recovery.
- Task workspace with Table, Board, Feed, Calendar, Chart, List, Timeline and Matrix views, persisted manual order, column-backed statuses, and a task property management surface.
- End-to-end encrypted chat: personal groups, workspace channels, message requests, disappearing messages, per-conversation mute, and privacy-safe abuse reporting.
- Task reminders that fire, quiet hours that hold a reminder rather than dropping it, and a Notifications section in Settings that governs both.
- Google Drive API sync path using Android OAuth client metadata and `appDataFolder`.
- Privacy Policy and Terms hosted from `docs/` on GitHub Pages, which the in-app links point at.

## Known alpha risks

- **Not release-signed.** The release build type carries no signing configuration on purpose; the production identity does not exist yet and will be generated outside this environment.
- **Not certified on a physical device.** Real-device OAuth, keyboard behaviour, gestures, dense-board drag, exact-alarm delivery and notification behaviour under Doze all still need device validation. Unit tests and lint run in CI; they cannot stand in for that.
- Live collaboration is disabled in this build because no backend is deployed for it to reach. Local durable history and merging do not depend on it.
- Back up manually before testing sync, import, export, vault or attachment workflows. Norfold's own export and restore path is the recovery route — Android's automatic backup is deliberately off.

## Version policy

`versionCode` and `versionName` in `apps/android/build.gradle.kts` are bumped by hand, one step per release that leaves the build machine. Nothing derives them from a tag or a commit count, because `versionCode` is permanent once Play has seen it and a computed value is what silently reissues one after a squash, a re-tag or a shallow clone.
