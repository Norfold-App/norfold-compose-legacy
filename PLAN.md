# Norfold Plan Update

## North Star

Norfold should be treated as a private workspace operating system, not only a notes app.

The product identity is:

> Your entire digital workspace. Yours first.

Everything revolves around a workspace. A workspace contains notes, tasks, canvas boards, files, chat, calendar, databases, graph relationships, activity, templates, settings, members, and encrypted sync state. Cloud providers are transport layers, not the source of truth.

## Revised Product Shape

### Workspace Hub

The app should open to a Workspace Hub instead of a notes list. The hub should show:

- greeting and current workspace
- global search entry
- quick stats for notes, tasks, canvas, chat, files, and members
- today's tasks
- continue writing
- recent notes and files
- recent chats
- workspace activity
- sync status
- calendar/upcoming deadlines
- pinned projects
- quick capture actions

### Universal Object System

Move toward a shared object model where every meaningful item can have:

- stable object ID
- title and body/description
- type: note, task, file, canvas, chat message, database row, meeting, journal, wiki page, checklist, flashcard, code note
- tags
- backlinks
- comments
- permissions
- attachments and embeds
- history/version records
- sync metadata

Current Room tables can remain, but new backend work should add a shared object/reference layer instead of making each feature isolated.

### Navigation Model

Primary navigation target:

- Home
- Inbox
- Search
- New
- Workspace

Workspace sections:

- Notes
- Tasks
- Canvas
- Files
- Chat
- Calendar
- Database
- Graph
- Activity
- Templates
- Settings

The left drawer/sidebar should become a workspace sidebar with workspace switcher, pinned items, recent items, smart collections, and collapsible section groups. Bottom navigation on compact phones should stay short and context aware.

### Design Direction

Use the new dark premium references as the design target:

- dark navy/black base
- purple, pink, and blue identity gradient
- soft glass surfaces without heavy blur everywhere
- compact cards and rows
- dashboard-first workspace feel
- animated but not noisy transitions
- richer empty states
- workspace banners and visual personalization
- no plan, billing, or sites surfaces in v1

Settings should follow the new reference structure:

- Profile
- Workspace
- Appearance
- Editor & Markdown
- Security & Vault
- Account & Restore
- Backup & Import
- App Info
- Workspace Icon
- Workspace Background
- Permissions
- Sync Settings
- Conflict Resolution

### Signature Features

- Universal command palette: create, open, move, rename, insert, sync, export, and settings commands from one surface.
- Spotlight search: search everything, including notes, tasks, chats, canvas, files, tags, settings, and commands.
- Workspace dashboard: the app's default home.
- Workspace activity feed: GitHub-like activity across notes, tasks, files, chat, sync, and conflicts.
- Sync chain visualization: device graph, provider, encrypted state, queue, history, conflicts, and device manager.
- Conflict review: GitHub-style mine/theirs diffs, merge, keep both, reject, history, and admin decisions.
- Knowledge graph and backlinks: relationships between all workspace objects.
- Adaptive FAB: context-specific actions per section.
- Rich media notes: YouTube, Spotify, Maps, PDFs, Figma, GitHub, Google Docs link previews, Loom, Mermaid, diagrams, and local files as embeds.

## Verified Current State

- Android app builds as `com.norfold.app` with display name `Norfold`.
- Debug APK builds successfully with the ToolNeuron Gradle wrapper.
- Native app layers exist under `branding`, `data`, `domain`, and `ui`.
- Room persistence exists for workspaces, notes, notebooks, tags, attachments, note embeds, tasks, chat messages, canvas nodes, canvas edges, shared workspace objects, object links, object history, comments, workspace activity, workspace files, and app settings.
- Room schema version 14 adds workspace object history and workspace comments on top of the schema 13 object/link/activity/file layer.
- Compact, medium, and expanded Compose shells exist.
- Compact top bar now shows destination context only, not the old large branded navbar.
- The global compact/desktop destination title top bar has now been removed for the immersive reference layout; screens own their own headers/actions.
- Bottom navigation and FAB hide while the keyboard is visible.
- Chat input uses IME-aware padding so it stays near the keyboard instead of stacking keyboard and navigation-bar offsets.
- Chat no longer receives the compact global create FAB.
- Editor and chat bottom controls now use screen-level IME padding plus navigation-bar padding to stay above the keyboard.
- Destination changes crossfade at the app-shell boundary, and high-traffic note/task cards animate content-size changes.
- Notes Home no longer includes the old quick-actions block.
- Main activity declares `windowSoftInputMode="adjustResize"` so edge-to-edge Compose IME insets can move editor/chat bottom bars above the keyboard.
- Workspace Hub is now the default destination instead of the notes list.
- Workspace Hub has a first-run create/restore/import prompt when no sync identity is configured.
- Section sidebar now includes Home, Inbox, Notes, Tasks, Canvas, Files, Chat, Database, Graph, Activity, Templates, Sync, Conflicts, Vault, and Settings.
- Section sidebar changes secondary items for hub, tasks, canvas, chat, files, database, graph, activity, templates, command palette, sync, settings, and related destinations.
- Workspace visual popup exists for edit and create:
  - workspace name
  - text/emoji/image/GIF icon mode
  - icon URI
  - background URI
  - visual/team permission toggles
- Nested settings index exists:
  - Profile
  - Workspace
  - Appearance
  - Editor & Markdown
  - Security & Vault
  - Account & Restore
  - Backup & Import
  - App Info
- Settings include theme mode, custom palettes, UI scale, editor line width, editor font, markdown syntax toggle, configurable note gestures, privacy/security, vault, Google restore, advanced manual sync, backup, import, and app details.
- Notes list is more compact than the earlier card-heavy build, supports cover thumbnails, and has configurable long-press/swipe quick actions.
- Markdown editor has Page/Source/Preview modes, cover media, attachments, first-class link embeds, and a bottom formatting toolbar with checklist/table/divider/attachment/embed/cover actions.
- Page mode is the default editor direction: markdown remains the stored/sync/export source, but parsed blocks render as editable visual blocks inspired by AppFlowy/AFFiNE document editing.
- Markdown editor has local undo/redo, explicit save, read-only source/code viewing for scrolling without accidental edits, and a collapsible draggable floating formatting toolbar constrained to the visible editor area.
- Compact editor controls split mode switches from note actions so Save/media/archive/delete controls remain reachable on narrow phones.
- Markdown editor can insert recent notes as `[[note title]]` links, and preview mode renders attached images/image embeds above the markdown body.
- Task Kanban columns now allow inline card creation in a specific status column.
- Task Kanban cards can be long-pressed and dragged horizontally to move to adjacent status columns.
- Task Kanban now has persisted boards and columns in Room schema 16, with board/column data included in encrypted backup/sync snapshots and conflict reports.
- Compact bottom navigation includes a `Menu` entry for the section sidebar while keeping the immersive no-global-header shell.
- Workspace visual dialogs are scroll-bounded with compact picker rows to avoid clipped controls on narrow phones.
- Sidebar notebook rows filter the Notes screen, highlight the selected notebook, and Notes shows a clearable active notebook filter chip.
- Note cover metadata is persisted and included in backup/sync snapshots.
- Note embeds are persisted and included in backup/sync snapshots.
- Editor embeds now support link, file, image, video, and audio picker workflows with type-specific inline cards, image previews, and Android Open/Share actions.
- Chat supports direct file attachment metadata and rendering.
- Shared workspace object indexing exists for notes, tasks, chat messages, canvas blocks, and files.
- Workspace activity records are created for note/task/chat/canvas/file operations and shown in the Activity screen and Workspace Hub.
- Workspace object history records are created for note edits, task status moves, attachments, canvas links, chat messages, file uploads, and object comments.
- Workspace comments are persisted, included in backup/sync snapshots, and can be added from Object Detail.
- Note attachments, note embeds, canvas edges, linked canvas notes, and canvas file/media targets create workspace object links for graph/backlink views.
- Workspace file library records exist and are included in backup/sync.
- Tasks support Trello-style horizontal Kanban, list/table/calendar/timeline/chart views, visible Kanban status-move controls, status, assignee, priority, due metadata with date picker, description, labels, file attachment metadata, task filtering, and a task detail popup with full edit/save flow.
- Canvas nodes and canvas edges are persisted and included in backup/sync snapshots.
- Canvas nodes now render as draggable absolute board blocks using normalized positions.
- Canvas nodes can be tapped to inspect/edit title and content, preview linked note content, open linked notes, or delete the node.
- Canvas node details show incoming/outgoing connections, can add a connection to another block, and can remove existing connections.
- Canvas node details can open the indexed workspace object, and canvas title/content edits update that object with activity/history.
- Canvas link/file/media nodes persist target URI, MIME type, display name, and size metadata in Room and backup/sync snapshots.
- Canvas node moves persist to Room.
- Canvas connectors use orthogonal candidate routing that tries to avoid node bounding boxes before falling back to a direct routed line.
- Canvas node edges are removed when a node is dropped into a hard overlap zone with another node.
- New Workspace Hub, Inbox, Files, Database, Graph, Activity, Templates, and Command Palette screens exist as first native surfaces.
- Search is now a Spotlight-style cross-object surface across workspace objects, tags, and command/settings destinations.
- Object Detail is a first-class destination with source preview, backlinks, comments, object history, comment creation, and open-source-object action.
- Database rows, Graph chips, Search results, and Command Palette object results open Object Detail.
- Database now has searchable Table, List, Board, Gallery, and Timeline views across shared workspace objects, with type filters and object-detail navigation.
- Folder-provider encrypted sync chain exists for Google Drive, OneDrive, or local folders through Android's system document tree picker.
- Sync provider labels now identify Google Drive and OneDrive as Android folder-picker targets, not direct OAuth API providers.
- Google Drive API sync code exists:
  - INTERNET permission
  - AppAuth dependency
  - redirect receiver for `com.googleusercontent.apps.1092049010490-dghvvj5tmu5urr41pjqpufafa1ilga25`
  - public Android Google OAuth client ID in app code
  - identity scopes plus `drive.appdata`
  - Continue/Reconnect Google button
  - authorization-code token exchange
  - Android Keystore-backed encrypted AppAuth state storage
  - Drive API upload/download of `norfold-sync.json` in `appDataFolder`
  - Drive API list uses `spaces=appDataFolder`
  - structured conflict report upload as `norfold-conflict.json` with local/remote counts and changed object lists
- Sync creates encrypted snapshot, manifest, status, and conflict report files under a `Notesnync` folder.
- Conflict Review loads generated folder-provider or Google Drive conflict reports and shows local/remote snapshot counts, recent object labels, red/green changed lists, sync actions, and a "Use local next sync" path that clears the base hash so the next manual sync overwrites remote with local.
- Canvas now stores positions in a larger world coordinate range, supports pan/zoom gestures, visible zoom controls, fit-to-content recovery, and constrained off-content drift.
- Canvas has a Wide focus mode that requests landscape orientation, expands the board surface, and provides Back/Exit controls with orientation restoration.
- Supplied landscape wallpapers are bundled as built-in cover/background resources for workspace/profile visuals.
- Profile and Workspace settings now render banner cards with stored image/background URIs and gradient fallbacks.
- Activity is now a timeline-style My Activity feed over activity and object history.
- In-app static logo surfaces use the supplied cutout asset as `norfold_logo`.
- Local diagnostics now has an Application-level crash hook, previous process-exit detection on Android 11+, local log file, post-crash share prompt, Settings controls, and Android share-sheet export.
- Auto-sync on app background exists when sync is configured.
- Backup folder selection uses Android's document tree picker and persists folder permission.
- Encrypted backup export now writes a timestamped `.enc` file into the selected backup folder while still exposing the payload field.
- Encrypted backup import supports selecting a `.enc` file from Android's file picker or pasting the payload.
- Room schema export is enabled and schema version 14 is generated under `apps/android/schemas`.
- Vault lock supports Android biometric unlock when enabled, with PIN/password fallback and guarded prompt failure handling.
- Unit tests currently cover vault crypto, markdown export/HTML export, and backup encode/encrypted round trip including task, canvas target, workspace object, object link, activity, history, comment, and file metadata.
- README now reflects the current Android feature state and moves unfinished work into a remaining roadmap.

## Verified Gaps

- Room destructive migration fallback has been removed and replaced with explicit idempotent migrations to schema version 12.
- Google Drive API sync is implemented with the current Android client ID but still needs real-device validation against debug/release/Play signing fingerprints and the final production auth stack.
- Production Google account restore should move from the interim AppAuth bridge to Credential Manager sign-in plus Google AuthorizationClient Drive authorization. The OAuth client secret must not be embedded in Android code.
- Direct OneDrive OAuth/API sync is not implemented. Current OneDrive support depends on Android's document provider folder picker.
- Canvas connectors now try multiple orthogonal routes around node bounding boxes and the board can pan/zoom beyond fixed phone bounds, but the router still needs richer edge labels, selected-edge handles, minimap/overview, and manual validation under dense/dragged layouts.
- Canvas Wide focus mode is implemented, but real-device orientation restore, tablet/foldable behavior, and keyboard/system-gesture edge cases still need validation.
- Canvas disconnect behavior handles hard node overlap, but full "try every route, then disconnect if impossible" routing still needs real-device tuning after the larger world-space viewport.
- Task board is improved but not yet AppFlowy-grade. It has Kanban/list/table/calendar/chart views, task detail editing, visible status move controls, labels, filters, and file attachment metadata, but still needs drag/drop status changes, grouped board polish, object comments/history, linked notes, and richer cards.
- Kanban is now horizontally columned like Trello, but drag/drop between columns is still not implemented.
- Workspace object layer now has shared object, link, activity, history, comment, and file records, but it is not the full universal object editor yet.
- Command Palette exists as a destination with core actions and object opening, but gesture shortcuts, fuzzy ranking, and full command coverage are incomplete.
- Files, Graph, Activity, Templates, Inbox, Object Detail, and Spotlight search exist, but they are first-pass native surfaces rather than final full-featured modules. Database has multi-view object browsing now, but still needs custom fields, saved views, sorting controls, and row editing.
- Editor is now page-first with editable visual blocks, but still below the Markleaf/AppFlowy/AFFiNE comfort target. It needs better selection/cursor ergonomics, block drag/reorder, slash commands, live syntax highlighting, backlinks, local note links, callouts, footnotes, and richer inline media playback.
- Page mode currently rebuilds markdown from parsed blocks, so complex markdown round-trip behavior needs more tests before treating it as production-complete.
- Editor undo/redo, read-only source viewing, and a movable floating toolbar exist, but toolbar focus retention with third-party keyboards still needs real-device QA.
- The no-global-header shell still needs screenshot QA across compact/expanded destinations to verify status-bar spacing and screen-specific actions.
- Rich inline media playback is still missing; current embed support is metadata cards, inline image preview, and Android Open/Share actions for external handling.
- Workspace/profile image selection uses document picker URIs but does not yet have image crop controls.
- Built-in cover assets are currently bundled as full supplied JPEGs and should be curated/compressed before release.
- Diagnostics is local-first and share-sheet based; it still needs an in-app crash-report preview and optional redaction before release.
- Launcher icon uses native adaptive/vector assets derived from the logo concept, but the in-app animation is still Compose-drawn. If exact HTML fidelity is required, use a WebView/Lottie/GIF pipeline from the supplied HTML.
- The bundled HTML logo asset exists, but the app does not yet render the HTML animation directly as the splash/loading logo.
- Conflict review is no longer hash-only for newly generated reports and can ingest generated report JSON, but it still needs GitHub-style per-object/per-field merge, keep-both, remote-wins, and reject controls.
- No Compose UI/screenshot tests exist for narrow phone, large phone, tablet, landscape, keyboard, or dark/light visual regressions.
- Local repo Gradle wrapper may fail under Java 25; release workflow should pin JDK 17 or update wrapper/toolchain handling.
- Release signing, CI validation, and distribution workflow are not production-ready.

## Recommended Priority Order

1. **Lock the new information architecture**
   - Make `Home` the Workspace Hub instead of the notes list.
   - Add `Inbox`, `Files`, `Database`, `Graph`, `Activity`, `Templates`, and command/search destinations to the domain navigation model.
   - Convert drawer/sidebar content to workspace-first navigation with pinned/recent/smart collection groups.

2. **Add the shared object/reference backend**
   - Add workspace object metadata table for cross-feature IDs, type, title, timestamps, permissions, sync metadata, and relationship indexes.
   - Add object links/backlinks table.
   - Add object activity table.
   - Add object history/version table.
   - Keep existing notes/tasks/canvas/chat tables as feature-specific detail tables until they can be migrated cleanly.

3. **Build Workspace Hub and Inbox**
   - Add dashboard cards for stats, today's tasks, continue writing, activity, sync, recent files, calendar, and pinned projects.
   - Add Inbox as the capture/process-later surface for quick notes, screenshots, voice, files, imports, tasks, and links.
   - Add rich empty states and quick actions.

4. **Build universal search and command palette**
   - Add a unified indexed search over notes, tasks, chat, canvas nodes, files, settings, and commands.
   - Add command model with actions such as create note, create task, run sync, import markdown, export, switch workspace, and open settings page.
   - Add keyboard and gesture entry points later; compact Android can expose it through the search bar first.

5. **Implement the new visual shell**
   - Replace remaining generic screen surfaces with the new compact dark premium style.
   - Add workspace banner and background rendering.
   - Add adaptive FAB action sheet for section-specific creation.
   - Add micro animations for drawer, cards, FAB, screen changes, selection, and search expansion.
   - Keep reduce-motion setting respected.

6. **Harden database migration**
   - Add migration tests before changing more schema.
   - Verify upgrade from older installed builds on an emulator/device.

7. **Finish canvas routing and object embedding**
   - Add selected-edge handles, edge label editing, and connection creation from gestures.
   - Tune orthogonal routes under dense node layouts and disconnect only when no route can be kept readable.
   - Add richer file/media/note/task/database node picker flows.
   - Make canvas nodes first-class workspace objects with backlinks and history.

8. **Bring tasks closer to AppFlowy**
   - Add drag/drop between Todo, Doing, Done on top of the current visible move controls.
   - Add board/list/table/calendar/timeline/gallery views through the shared database/object model.
   - Add assignee chips, linked note/object references, subtasks, comments, and richer multi-attachment handling.

9. **Upgrade the editor to the Markleaf/Obsidian/Typora target**
   - Add live markdown syntax spans in edit mode.
   - Render markdown preview locally with headings, lists, checklists, quotes, code, tables, callouts, links, footnotes, images, and embeds.
   - Add note backlinks and `[[note link]]`.
   - Add media/file embed insertion from picker.
   - Add slash commands, block insert menu, focus mode, and split/live preview.

10. **Make media/profile/workspace visuals production-grade**
   - Add crop flow for profile image, profile background, workspace icon, workspace background, and note cover.
   - Store persistable URI permissions consistently.
   - Add fallback rendering if a URI becomes unavailable.

11. **Improve sync and conflict handling**
   - Rename current provider UX to folder-provider sync.
   - Add backup folder picker.
   - Add per-object conflict review with red/green diffs.
   - Add merge/keep local/keep remote/keep both/reject controls.
   - Add admin-only conflict decisions for team workspaces.
   - Add sync chain visualization, device manager, transfer history, queue status, and provider details.

12. **Stabilize Google account restore**
   - Validate the current Android OAuth client ID with debug, release, and Play signing SHA-1/SHA-256 fingerprints.
   - Move production auth toward Credential Manager sign-in plus Google AuthorizationClient for Drive `appDataFolder`.
   - Use only `openid`, `email`, `profile`, and `drive.appdata`; avoid full-drive scopes.
   - Keep folder-provider sync as an advanced/manual fallback for Drive folders, OneDrive folders, Syncthing, NAS, and local folders.
   - Add OneDrive account/API restore after Google restore is stable.

13. **Finish production shell**
   - Add onboarding/create-or-restore flow that uses the same workspace visuals.
   - Add workspace templates: Personal, Student, Research, Software Team, Business, Second Brain, Journal, Knowledge Base.
   - Add adaptive screenshot tests.
   - Update README and AGENTS tracking.
   - Pin JDK 17 and fix the repo wrapper path.
   - Add release signing and CI APK artifact publishing.

## Suggestions

- Keep the current folder-provider sync path. It is simpler, cheaper, and still works with Google Drive, OneDrive, Syncthing, NAS folders, and local backup folders.
- Treat Google account restore as the beginner path and folder-provider sync as the advanced/manual path.
- Do not copy Notesnook code/assets. Use Notesnook, Markleaf, AppFlowy, Affine, and Anytype as behavioral/UX references while implementing original code and branding.
- Prioritize data durability over UI expansion now. A notes app losing user data is worse than missing a visual feature.
- Use the HTML logo as a generated asset pipeline source: export centered PNG/WebP frames or Lottie/GIF for loading, then keep native adaptive icon XML for launcher compatibility.
- Move broad "everything app" features into modules: `notes`, `editor`, `tasks`, `canvas`, `chat`, `sync`, `settings`, `workspace`, and `vault`. The codebase is already layered, but screen files are becoming large.

## Current Verification

- Command used:
  `/home/sheikh/GitHub/ToolNeuron/gradlew -p /home/sheikh/GitHub/Libre-Notes :apps:android:testDebugUnitTest :apps:android:assembleDebug --console=plain`
- Result: build successful.
- Debug APK:
  `apps/android/build/outputs/apk/debug/android-debug.apk`
- SHA-256:
  `e8c84cd1aaf1793cc59b0726bca5b4ffc7849d6d8edc946d0e3884e882f6fcb0`
