# Norfold

Norfold is an Android-first private workspace app with a Room-backed local core, encrypted backups, folder-provider sync, markdown editing/preview, workspace tools, shared objects, object history/comments, and adaptive Compose layouts.

## Repository Layout

- `apps/android` - Native Android app using Kotlin, Jetpack Compose, Room, Material 3, domain/data/ui/branding layers, vault crypto, backup/sync codecs, and adaptive layouts.
- `apps/web` - Secondary Vite prototype kept for visual iteration.
- `legacy/flutter-prototype` - Preserved Flutter prototype for reference only.
- `assets/brand/icon.jpg` - Legacy temporary image; Android now uses vector/adaptive Norfold launcher resources.

## Android Build

```bash
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:assembleDebug
```

If the repo wrapper is unavailable locally, use any compatible Gradle install/wrapper with the repo root as project directory.

## Current Android Features

- Package/application id: `com.norfold.app`.
- Display name: `Norfold`.
- Adaptive launcher icon generated from the Norfold HTML logo concept.
- Animated in-app logo based on the same N path, gradient, and line animation.
- Compact phone layout with top bar, Workspace Hub, note list, bottom navigation, and context-aware floating create button.
- Compact phone layout now removes the global destination-title top bar so screens can use immersive in-page headers like the supplied references.
- Medium/large layouts with two-pane and three-pane note workflows.
- Workspace Hub is the default home, with stats, quick capture, today's tasks, continue writing, recent activity, and sync summary.
- Workspace Hub includes a first-run create/restore/import card when no account/sync identity is configured.
- Workspace sections now include Inbox, Files, Database, Graph, Activity, Templates, and Command Palette destinations.
- Shared workspace object layer for notes, tasks, files, canvas blocks, chat messages, links, activity, history, comments, and file records.
- Notes CRUD, pin, star, lock, archive, delete.
- Notebooks and tags.
- Page-first markdown editor with visual editable blocks, source mode, and rendered preview.
- Spotlight-style search across workspace objects, tags, and command/settings destinations.
- Room database for notes, notebooks, tags, attachments, settings, and note-tag relations.
- Vault password hashing and lock/unlock.
- Encrypted backup export/import using `NORFOLD-VAULT-V1` and `NORFOLD-BACKUP-V1`.
- Dark, light, and system theme modes.
- Custom theme palettes and UI scale.
- Workspace visual editing with text/emoji/image/GIF icon modes, background URI, and team permission toggles.
- Nested settings for profile, workspace, appearance, editor, security/vault, account restore, sync/backup/import, and app info.
- Note cover media metadata with image/GIF rendering through Coil.
- Link/file/image/video/audio embeds with metadata cards, image previews, and Android Open/Share actions.
- Chat messages with direct file attachment metadata.
- Notes support configurable long-press and swipe quick actions for open/actions, pin, star, lock, archive, and delete.
- Editor toolbar includes Page/Source/Preview modes, undo/redo, explicit save, read-only source/code viewing, checklist, table, divider, attachment, embed, cover actions, recent-note `[[note]]` insertion, and a draggable floating compact toolbar mode.
- Page mode renders markdown as editable visual blocks while keeping markdown as the sync/export source.
- Markdown preview renders cover media, attached images, image embeds, then the markdown body.
- Tasks with Trello-style horizontal Kanban, list, table, calendar, timeline, and chart views, visible status-move controls, detail editing, description, assignee, status, priority, due metadata with date picker, labels, filters, and file attachment metadata.
- Canvas nodes and edges persisted in Room, with draggable normalized node positions, tap-to-inspect/edit node contents, Object Detail jump, incoming/outgoing connection management, routed connectors, link/file/media targets, and target metadata in backup/sync.
- Object Detail is a first-class destination with source preview, backlinks, comments, object history, comment creation, and open-source-object action.
- Database has searchable Table, List, Board, Gallery, and Timeline views over shared workspace objects, with type filters and Object Detail navigation.
- Database rows, Graph chips, Search results, and Command Palette object results open Object Detail.
- Note attachments, note embeds, canvas edges, linked canvas notes, and canvas file/media targets create workspace object links for graph/backlink views.
- Folder-provider encrypted sync chain through Android's document tree picker for Google Drive folders, OneDrive folders, or local folders.
- Google Drive API sync path using the current Android OAuth client ID, identity plus `drive.appdata` scopes, Android Keystore-backed encrypted auth-state storage, and Drive API `appDataFolder`.
- Google Drive API sync writes the encrypted app snapshot to `norfold-sync.json` and uses `spaces=appDataFolder` when listing app-data files.
- Biometric vault unlock guards prompt failures and avoids repeated automatic prompt loops.
- Auto-sync when the app leaves the foreground if a sync chain and secret are available for the session.
- Structured v2 conflict report generation for sync snapshot divergence with local/remote counts and changed object lists.
- Conflict Review loads folder-provider or Google Drive conflict reports and shows local/remote snapshot counts, recent object labels, red/green changed lists, sync actions, and a "Use local next sync" path.
- Canvas supports world-space pan/zoom, fit-to-content controls, constrained off-content drift, routed connectors, and node movement beyond the old fixed phone board.
- Canvas includes a Wide focus mode that requests landscape orientation and provides Back/Exit controls for returning to the normal app shell.
- Built-in landscape cover/background images are bundled for workspace and profile visuals, alongside custom URI/image picking.
- Profile and Workspace settings render banner cards using stored image/background URIs with gradient fallbacks.
- Activity is a timeline-style My Activity feed over workspace activity and object history.
- Local diagnostics records app starts, uncaught crashes, previous crash/ANR process exits on supported Android versions, and can share logs through Android's share sheet from Settings.
- Encrypted backup/sync snapshots include workspace objects, object links, activity, history, comments, and file library records.

## Major Fix In Progress: Editor Re-architecture

Norfold grew from a simple note app into a note+task app and then a full
workspace app — but the editor kept markdown as its main storage format the
whole way. That was a mistake for a document editor: markdown cannot preserve
fonts, colors, spacing, page layout, text boxes, complex tables, comments, or
tracked changes. The fix, adopted 2026-07-16: the canonical document becomes a
schema-controlled structured JSON tree, and markdown is demoted to one of
several import/export boundaries. Full decision record and target
architecture: [docs/EDITOR-REARCHITECTURE.md](docs/EDITOR-REARCHITECTURE.md).

## Remaining Roadmap

- Real-device Google account restore validation with debug, release, and Play signing fingerprints. The checked-in client ID is public OAuth metadata; never embed the client secret in Android code.
- Production Google auth should move from the interim AppAuth bridge toward Credential Manager sign-in plus Google AuthorizationClient Drive authorization.
- OneDrive account/API restore after Google restore is stable.
- GitHub-style per-object/per-field conflict review with merge, keep both, remote wins, local wins, and reject controls.
- Deeper universal object editor so every task/file/canvas/database row can be edited through one object surface with permissions and rich history controls.
- Database saved views, custom fields, sorting controls, and row editing.
- Spotlight search needs fuzzy ranking, command shortcuts, file content indexing, and activity-result grouping.
- Selected-edge handles, edge label editing, dense-board canvas routing polish, and richer node editing.
- Canvas real-device polish for pinch zoom, drag precision, minimap/overview, and very dense boards.
- AppFlowy/Trello-grade drag/drop on tasks, richer cards, linked notes, and richer multi-attachment handling.
- Markleaf/AppFlowy/AFFiNE-grade editor comfort: richer block editing, slash commands, live markdown syntax spans, backlinks, local note links, callouts, tables, footnotes, and richer inline media playback.
- Real-device polish for keyboard-safe editor/chat bars, floating editor toolbar behavior, and canvas Wide focus orientation restoration.
- Crop flows for profile images, workspace visuals, and note covers.
- Compose UI/screenshot regression tests for compact phones, tablets, landscape, keyboard, and theme variants.
- Release signing and CI APK artifact publishing.

## License

**Source-available, view-only. Not open source.**

This code is published so it can be read, not reused. You may browse and
fork on GitHub for viewing; you may **not** use, copy, modify, redistribute,
or build on this code or the Norfold brand assets in any project. See
[LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for the full terms.
External contributions are not accepted ([CONTRIBUTING.md](CONTRIBUTING.md)).
