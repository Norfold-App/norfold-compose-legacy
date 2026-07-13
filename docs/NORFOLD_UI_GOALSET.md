# Norfold UI Completion Goalset

This checklist is the controlling acceptance document for the next visible build. The exact screenshots in `Downloads/Zielorya` define the visual result; `Downloads/Problems` defines defects that must disappear. Unfinished modules remain in source but are hidden from production navigation.

## P0: Layout And Navigation

- [x] Fix Tasks Table, Board, Calendar, and every other view so the fixed header remains visible and content fills and scrolls through the complete remaining viewport.
- [x] Remove all cropped fixed-height content hosts and prevent bottom navigation from covering content.
- [x] Make Android Back close sheets, dialogs, task details, editors, settings subpages, and the sidebar before navigating to the actual previous destination.
- [x] Keep compact bottom navigation exactly `Home / Notes / Create / Tasks / Chat`.
- [x] Hide Goals and Canvas from Home, bottom navigation, sidebar, search shortcuts, and prominent production actions without deleting their implementation.
- [x] Keep Chat collaboration-focused; remove AI-assistant language and affordances.

## P0: Tasks Exact Workspace

- [x] Match `Table view of tasks (2).png` on expanded widths and the supplied compact grouped Table designs on phones.
- [x] Keep status groups, inline expansion, populated-properties-only rendering, cell selection, adaptive row height, focused editors, property ordering, and `+ property` functional.
- [x] Match `Board_view_Plan_Adaptive.png`; cards size to populated title, notes, status, priority, assignee, labels, dates, checklist, files, comments, and timestamps.
- [x] Preserve BoardPointer within-column and cross-column drag behavior.
- [x] Rebuild task detail to `Opening a task from board-view.png`: Color, one compact Main properties card, Checklist, Notes, Files, Details, New property, Comments, and Delete.
- [x] Use the dedicated editors from `pop-ups when editing a value in Table view.png`; never show every editor expanded inside task detail.
- [x] Merge Filter, Sort, New, and Settings into the single right-side controls panel shown in `file_00000000300871fbb350dc3d3500d106.png`.
- [x] Add swipe/page navigation to Task Calendar week and month modes, including month rollover and selected-day task list.
- [x] Hide the global hamburger while task detail is open.

## P0: Notes And Preview

- [x] Match Page, Raw, and Preview screenshots in `Downloads/Zielorya`.
- [x] Remove duplicated Page title and reduce the excessive left offset.
- [x] Style inline Markdown syntax while preserving source offsets and lossless Markdown round trips.
- [x] Keep Preview under one Compose vertical scroll owner, add bottom navigation clearance, and ensure the final line is reachable.
- [x] Keep editor toolbars compact, functional, IME-safe, and aligned beside the expanded sidebar.

## P1: Product Structure

- [x] Replace sidebar `Command / New` with compact `+ Note` and `+ Task` actions.
- [x] Reduce sidebar to workspace identity plus Home, Notes, Tasks, Calendar, Chat, functional Files, and Settings/Profile.
- [x] Remove Goals, Canvas, command clutter, sync monitor, conflict tools, and disconnected secondary sections from visible sidebar navigation.
- [x] Remove Goals and Sync Chain from Home; show only actual recent notes, current tasks, quick actions, continue writing, and real activity.
- [x] Ensure empty workspaces show empty states rather than seeded/demo content unless the user selected a demo template.

## P1: Onboarding And Settings Wiring

- [x] Keep the ten-screen onboarding visual structure and persist profile image/name, workspace/template, collaboration, system appearance, notifications, and restore choices.
- [x] Add Start empty and selectable demo workspace templates; seed sample content only for a chosen template.
- [x] Persist name/display name into Profile and workspace name into Workspace settings.
- [x] Describe Chat as member collaboration throughout onboarding and production UI.
- [x] Keep Workspace settings grouped as Visuals, Details, Members, Permissions, and Advanced.
- [x] Keep Account & Restore focused on account, restore/import/backup, `Sync now`, and `Last synced`; hide technical sync-chain configuration from normal production UI.

## P1: Premium Motion And QA

- [x] Use restrained shared transitions for bottom navigation, sidebar, destination changes, settings navigation, task expansion, popups, sheets, onboarding, and drag feedback.
- [x] Verify compact portrait, compact landscape, tablet, and expanded layouts in light and dark themes.
- [x] Verify large text, long labels, IME behavior, process recreation, and partially populated tasks.
- [x] Pass unit tests, lint, web build, debug APK, release bundle, and emulator smoke tests.

## Completion Rule

This goalset is complete only after every visible acceptance item above is implemented or a concrete external blocker is documented. Screenshot references are specifications, not inspiration.

## Active Side Tasks (July 12 Emulator Notes)

These remain subordinate to the ordered hard gates in `CODEX_PROMPT.md`, but must be completed alongside the related destination work.

- [x] Rename the current compact Tasks "Table" presentation to `List`; reserve `Table` for the AppFlowy spreadsheet implementation from `Table view of tasks (2).png`.
- [ ] Add positional, animated drag feedback before drop for note blocks, table rows/columns, task rows, Kanban cards/columns, checklist rows, sidebar nodes, and every other reorderable surface.
- [x] Promote Calendar to a workspace-level sidebar destination rather than a Tasks-only view.
- [x] Add smooth, continuously animated calendar navigation instead of instant seven-day/month jumps.
- [x] Provide Day, Week, and Month modes: Day is a vertically scrollable timestamp schedule; Week scrolls timed items across days; Month shows compact colored event blocks.
- [x] Tapping any month cell opens Day mode for that date, whether or not the date already contains an item.
- [ ] Unify tasks, notes, countdowns, reminders, and later external events in the workspace calendar while retaining source-specific editing.
- [ ] Match the AppFlowy note-table screenshots in `Downloads/Update/Screenshot (144).png` through `(148).png`, including adaptive cells, persisted resizing, row/column selection, and row/column action menus.
- [ ] Keep code, embed, chart, math, Mermaid, image, file, and table blocks compact by default, editable in Edit mode, horizontally scrollable when wide, and user-resizable where content needs a larger viewport.
- [ ] Add a visible drag-corner resize affordance to code, embed, chart, math, Mermaid, image, file, and other viewport blocks; persist the chosen size and keep initial height content-adaptive rather than oversized.
- [ ] Verify Code, Embed, Chart, Math, and Mermaid blocks each open their type-specific editor from both the block action menu and direct Edit-mode affordance.
- [ ] Verify Image and File insertion launches the Android picker, persists the selected URI permission and metadata, and supports replace/open/share/remove after reload.
- [x] Make paste structure-aware in Page/Edit mode. A single paste containing multiple paragraphs, headings, lists, checklists, fenced code, Mermaid, mind-map/diagram syntax, math, tables, links, images, and embeds must be parsed into the corresponding ordered block types rather than inserted as one plain paragraph.
- [x] Preserve the pasted source losslessly when a construct is not recognized, keep one undo step for the complete paste transaction, and restore a valid caret after insertion.
- [x] Cover structure-aware paste with unit and Compose tests for mixed multi-paragraph content, engine blocks, replacement of a cross-block selection, undo/redo, and save/reload persistence.
- [x] Support rich inline Markdown and LaTeX inside ordinary text and every compatible block, not only dedicated engine blocks. View mode must render inline emphasis, links, code, tags, mentions, emoji, and math while Edit mode preserves editable source offsets and round-trips without dropping syntax.
- [x] Give every block type a long-press/context menu with actions specific to that block instead of one generic menu for everything; match the latest popup screenshot in `C:\Users\sheik\Downloads\Update`.
- [ ] Restore a compact phone/tablet formatting bar for text-compatible blocks alongside slash insertion, with keyboard-safe positioning and no duplicate commands.
- [ ] Add Editor & Markdown settings that let users enable or disable individual block types, slash suggestions, the compact formatting bar, automatic rich inline rendering, and other per-block editing affordances.
- [ ] Treat the rich-inline menus, formatting bar, and per-block settings as follow-up work after the ordered `CODEX_PROMPT.md` gates; they remain mandatory and must not interrupt Area 0 stabilization.
- [x] Replace plain Material three-dot menus with a polished, adaptive pill treatment by default. Menus must use system light/dark surfaces and restrained borders/shadows, without adding decorative page backgrounds.
- [x] Add organized Editor & Markdown appearance controls for contextual menus and toolbars: `Pill` (default), `Block`, and `Minimal`, plus color behavior `App accent` or `Follow theme`.
- [ ] Apply the selected contextual-menu treatment consistently to editor blocks, task cells/cards, checklist rows, sidebar nodes, files, comments, and other three-dot/long-press surfaces so the premium interaction language is shared across the app.
- [ ] Verify every menu treatment in light and dark system themes, compact and expanded widths, large text, keyboard-open layouts, and edge-of-screen anchoring.
- [ ] Add Nova-launcher-style popup customization under Appearance/Editor settings so users can tune app-wide popup density, placement, animation, corner treatment, dismissal behavior, and item layout while preserving accessible defaults and consistent action semantics.
- [x] Add a direct-manipulation chart-builder canvas above the precise data grid. Pie charts start as one complete circle, expose an in-chart add-section control, and visibly subdivide as sections are added; equivalent visual creation gestures must be provided for bar, line, scatter, histogram, and area charts.
- [ ] Give every chart section/series editable numeric values, rich captions with Markdown/LaTeX inline support, legend labels, colors, ordering, and accessible non-visual controls. Keep the data grid as the deterministic fallback and serialize both editors to the same Vega-Lite model.
- [ ] Make chart creation understandable without knowing Vega-Lite: live preview every edit, validate bad values inline, provide usable defaults/templates, and preserve the existing editable-chart versus flattened-image placement choice.
