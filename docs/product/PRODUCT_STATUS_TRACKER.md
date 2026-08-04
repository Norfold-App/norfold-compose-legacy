# Norfold Product Status Tracker

This is the preserved product-status material that previously lived in the root
`AGENTS.md`. It was moved here so the root file could become a short, durable
repository-safety and contributor guide, and so this material would be tracked in
Git instead of surviving only as an untracked local file.

**This is a running status log, not a specification.** It records UI acceptance
notes, implementation constraints, what has been fixed, and what still needs manual
or on-device verification. Where it disagrees with
[../editor/EDITOR_IMPLEMENTATION_ROADMAP.md](../editor/EDITOR_IMPLEMENTATION_ROADMAP.md)
or [../editor/decisions/EDITOR-REARCHITECTURE.md](../editor/decisions/EDITOR-REARCHITECTURE.md),
those documents win.

Entries below predate the editor re-hosting phases. Treat "Recently Fixed" as a
historical record of the legacy editor and shell, not as a claim about the current
Flow/Document/Spreadsheet hosts, all of which remain behind default-off feature flags.

## Current UI Acceptance Notes
- Product direction is now a private workspace operating system: workspace hub first, Docs/Tasks/Chat/Files/Databases/Graph/Activity connected by shared objects.
- Use `brand/norfold-logo-animated.html` as the logo animation source. It is a vector reconstruction of an earlier raster glyph; that raster original is held outside this repository and is not required to build or modify the vector. Matching tracked assets: `docs/assets/norfold-logo-animated.svg`, `docs/assets/norfold-icon.svg`, `brand/norfold-glyph.svg`.
- The splash/loading logo must be centered by the icon stage, not shifted upward by surrounding preview controls or title text.
- Launcher/in-app icon geometry must derive from the HTML logo path and final line positions.
- Overall Android UI should lean toward the new dark premium dashboard references: dark navy base, compact glass cards, purple/pink/blue identity gradient, workspace banners, and stronger micro-interactions.
- The app should open to a Workspace Hub, not directly to Notes.
- Notesnook is a reference for side navigation behavior, but the target sidebar is workspace-first: workspace switcher, pinned, recent, smart collections, inbox, Docs, Tasks (including Calendar), Files, Chat, Graph, Activity, Templates, and Settings.
- Avoid fixed-size assumptions on phones; controls must wrap or stack before text clips.
- Task rows must stay compact, stack metadata/actions on narrow phones, and keep status changes visible without cramping the card.
- Editor needs a Markleaf-like writing surface: cleaner top actions, readable markdown editing, bottom formatting actions, and better preview rendering.
- Settings needs the new nested structure: Profile, Workspace, Appearance, Editor & Markdown, Security & Vault, Account & Restore, Backup & Import, App Info, Workspace Icon, Workspace Background, Permissions, Sync Settings, and Conflict Resolution. Exclude plan, billing, and sites.
- Add Universal Command Palette and Spotlight Search as first-class surfaces, not just a notes search box.
- Rich empty states should include illustrations, quick actions, import, capture, and restore paths.
- Adaptive FAB must change actions by section: Doc, Task, Chat, File/Import, and Capture.

## Implementation Constraints
- Google account restore is the preferred beginner-friendly sync direction.
- Use Google Drive `appDataFolder` with encrypted Norfold snapshots for automatic restore.
- The current Android AppAuth bridge uses only the public Android client ID. Do not embed the OAuth client secret in the APK.
- Production Google auth should move toward Credential Manager for sign-in plus Google AuthorizationClient for Drive access.
- Folder-provider Drive/OneDrive/local sync remains an advanced/manual fallback.
- OneDrive API/account restore comes after Google restore is stable.
- App data added to Room must be included in backup and encrypted sync snapshots.
- UI changes must keep compact phone, tablet, and desktop-class layouts usable.
- New backend tables should move toward a shared workspace object/reference/activity/history layer instead of isolated feature silos.
- Sync, backup, and conflict handling must include all new object-layer data.

## Recently Fixed
- HTML loading asset is used directly.
- Folder-provider encrypted sync chain exists.
- Tasks, Chat, profile, workspace, import, and attachment metadata are persisted and included in encrypted backups/sync.
- Google Drive AppAuth wiring uses the current Android client ID and requests identity plus `drive.appdata` scopes.
- Sync conflict reports now use structured v2 JSON with local/remote snapshot summaries and changed object lists for folder-provider and Google Drive API sync.
- Conflict Review can load generated folder-provider or Google Drive conflict reports, then show local/remote snapshot summaries, recent objects, red/green changed lists, and a "Use local next sync" resolution path.
- Supplied landscape images are embedded as built-in cover/background resources and exposed for workspace/profile visual selection.
- Local diagnostics logging, crash detection, post-crash share prompt, and a Diagnostics settings page exist without third-party analytics.
- Workspace Hub is the default destination and has native dashboard cards.
- Shared workspace object, object link, activity, and file tables exist in Room schema 14.
- Object/activity/file records are included in encrypted backup/sync snapshots.
- Room schema 14 adds workspace object history and comments.
- Object history/comment records are included in encrypted backup/sync snapshots.
- Note edits, task status moves, attachments, Chat messages, uploads, and object comments write shared history/activity records.
- Note attachments and editor embeds create workspace object links for graph/backlink views.
- Files, Database, Graph, Activity, Templates, Inbox, and Command Palette screens exist as first-pass native surfaces.
- Object Detail is a first-class destination with source preview, backlinks, comments, history, comment creation, and open-source-object action.
- Database rows, Graph chips, Search results, and Command Palette object results open the Object Detail destination.
- Database now has searchable Table, List, Board, Gallery, and Timeline views over shared workspace objects.
- Search is now a Spotlight-style cross-object search across workspace objects, tags, and command/settings surfaces.
- Note long-press and swipe actions are configurable in settings.
- Task Kanban cards expose compact visible status-move controls.
- Editor toolbar exposes checklist, table, divider, attachment, embed, and cover actions.
- Editor now has local undo/redo, explicit save, a read-only source/code viewing toggle, and a collapsible draggable floating formatting toolbar constrained to the screen.
- Editor now defaults to an AppFlowy/AFFiNE-inspired Page mode: markdown stays as the stored source, but headings, paragraphs, lists, checklists, quotes, code, rules, and image-markdown render as editable visual blocks.
- Editor mode navigation is now Page, Source, and Preview; Back returns Source/Preview to Page before leaving the note.
- Chat input now switches between IME and navigation-bar padding so it sits just above the keyboard instead of being pushed too far upward.
- Main app shell no longer renders the global `Home`/`Notes`/`Editor` title top bar; compact and expanded layouts now leave immersive screen headers to each destination.
- Compact bottom navigation now includes a `Menu` entry that opens the section sidebar without restoring the old global top bar.
- Editor mode controls and note actions are split into separate scrollable rows so Save/media/archive/delete controls do not clip off-screen on narrow phones.
- Workspace visual dialogs now use bounded scrollable content and compact picker rows so Save/Cancel and image/background controls remain reachable on narrow phones.
- Sidebar notebook rows now filter the Notes screen, highlight the active notebook, and show a removable active notebook filter chip in Notes.
- Profile picture and profile cover rows now open Android image picker directly instead of requiring manual URI entry.
- Note media insertion is now inline: Page mode exposes image/file/embed/note-link controls inside the editor surface, and Source mode image insertion opens Android's image picker and writes markdown image syntax.
- Note attachment/embed cards now live inline in Page/Preview surfaces with image thumbnails plus Open/Share actions for in-app/outside-app viewing.
- Note cover changes are constrained to Preview mode; cover renders as part of the scrollable preview document instead of as a separate editor block.
- In-app static logo surfaces now use the supplied `1000011037_cutout.png` asset as `norfold_logo`.
- Task Kanban is now a horizontal Trello-style board with status columns and task cards, plus a dedicated task Timeline view.
- Editor embeds now expose recent-note chips that insert `[[note title]]` links into the current note.
- Preview mode now renders attached images and image embeds above the markdown body.
- Manifest now declares `windowSoftInputMode="adjustResize"` for the main activity so editor/chat bottom bars can respond to IME insets reliably.
- Workspace Hub includes a first-run create/restore/import setup card.
- Activity screen is now a timeline-style `My Activity` feed over activity and object history.
- Profile and Workspace settings now show banner cards with stored image/background URIs and gradient fallbacks.
- Notes support long-press quick actions.
- Biometric unlock now guards prompt crashes and avoids repeated automatic prompt loops.
- Compact chat no longer receives the global floating create button.
- Notes Home no longer shows the older quick-action block.
- Destination changes now crossfade at the app-shell boundary.
- Note cards and Kanban task cards animate content-size changes for smoother interaction feedback.
- Chat and editor bottom bars now use screen-level IME padding plus navigation-bar padding so controls stay above the keyboard more reliably.
- Task Kanban columns now support inline card creation in each status column.
- Task Kanban cards can be long-pressed and dragged left/right to move to adjacent status columns.
- Task Kanban now has persisted task boards and task columns in Room schema 16, with backup/sync snapshot coverage and conflict-report visibility.
- Tasks can be created directly inside a persisted column, and long-press drag moves cards between adjacent persisted columns.

## Still To Verify Manually

- Background notifications are not complete: runtime notification permission, explicit post-login FCM token registration, event enqueueing, dispatcher scheduling, data-message routing/deep links, invalid-token cleanup, and missed-message recovery remain.
- Background sync/backup is not implemented: settings fields exist, but there is no WorkManager worker/schedule for sync, Daily/Weekly/Monthly backup, task reminders, or retry reconciliation.
- The Google Drive API sync store still assumes one `norfold-sync.json`; clean-install backup discovery and versioned per-device manifests remain to be implemented.
- Do not wipe Supabase independently of installed clients: stale sessions and local queues will repopulate deleted data. Server and client resets must be coordinated. See the backup/reset safety section of [../REPOSITORY_GUIDE.md](../REPOSITORY_GUIDE.md).
- Workspace Hub, command palette, object graph, files library, database views, Object Detail, and activity feed need real-device visual/interaction QA and deeper feature completion.
- Sync-chain visualization is not implemented yet.
- Actual launcher icon appearance on Android launchers after adaptive mask application.
- Splash centering on narrow/tall devices.
- Built-in covers need APK-size review and final curation/compression.
- Diagnostics sharing needs real-device crash/ANR validation.
- Full editor comfort against Markleaf reference screenshots.
- Editor floating toolbar drag/click behavior and keyboard retention need real-device validation with Gboard and other keyboards.
- Page editor block editing needs real-device QA for cursor behavior, block deletion, large documents, and markdown round-trip edge cases.
- Local APK preview is blocked in the current development environment: ADB sees no connected device and no emulator image is installed. Device verification is deferred, not passed.
- New no-global-header shell needs screenshot QA across all destinations to catch any status-bar crowding.
- Trello-style Kanban long-press drag needs real-device QA; current drag moves cards to adjacent columns by horizontal direction.
- Task board column rename/delete/reorder is not implemented yet.
- Real-device Google consent/redirect behavior against debug and release signing fingerprints.
- Conflict review still needs per-object/per-field merge controls, keep-both flow, and remote-wins restore action polish.
- Actual swipe/long-press ergonomics on narrow Android devices.
