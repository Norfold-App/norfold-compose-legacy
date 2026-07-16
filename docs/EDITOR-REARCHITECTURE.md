# Android structured-document architecture

**Status:** active implementation since 2026-07-16
**Authority:** Android is the primary product; the web app remains a visual prototype.

## Decision

Norfold stores editable content as a versioned tree of typed blocks. Markdown is not an editor mode or a persistence model. It is supported only at import, export, print, clipboard, and interoperability boundaries.

This corrects the earlier architecture, where Markdown remained canonical after Norfold had grown from a notes app into a workspace operating system. Markdown cannot faithfully preserve page geometry, structured tables, layout containers, media metadata, comments, or future document capabilities.

## Active Android contract

```text
Jetpack Compose structured editor
              ↓
BlockDocument + typed DocumentBlock payloads
              ↓
versioned block envelope with unknown-block preservation
              ↓
Room documents + document_blocks
              ↓
encrypted Backup V3 / sync snapshots
```

The editor and persistence layers follow these rules:

- `BlockDocument` is the canonical editable payload.
- Every block has a stable ID. Room uses `(document_id, block_id)` as the block key so IDs cannot collide across owners.
- A generic `DocumentOwner` attaches the same document contract to notes, tasks, and calendar events.
- `documents` stores owner identity, layout mode, canvas metadata, and timestamps. `document_blocks` stores ordered, independently writable block envelopes.
- Saving writes dirty block rows without rebuilding the whole document.
- Unknown or future block payloads round-trip byte-for-byte as `UnknownBlock` instead of being discarded.
- Note search text, task descriptions, calendar-event descriptions, and workspace-object previews are derived plain-text projections. They are not editable sources of truth.
- Backup V3 and encrypted sync snapshots preserve exact owner documents, stable block IDs, block payloads, layout, canvas specification, and timestamps.
- Docs retain Flow and the bounded Document Canvas with explicit page/artboard sizes and export. The retired standalone workspace Canvas is not part of this architecture.

## Format boundaries

| Format | Purpose |
| --- | --- |
| Structured block envelope | Canonical editing and persistence |
| Plain text | Search, summaries, accessibility, object previews |
| Markdown | Import/export and technical interchange |
| HTML | Print/export generation and clipboard boundaries |
| DOCX | Editable office interchange |
| PDF | Fixed-layout output |

Markdown import is a one-time conversion into blocks. Markdown export is a lossy projection and may simplify features that Markdown cannot represent.

## Owner behavior

- **Note:** opening a note selects its note-owned document.
- **Task:** the task Docs section opens the same full structured editor. Existing description text is converted once when a task document is first ensured.
- **Calendar event:** event rows in Day, Week, and Agenda views open an event-owned structured document. Event description remains a derived summary for calendar cards and cloud payloads.

Deleting an owner deletes its document transactionally. Workspace deletion clears note, task, and calendar-event documents before their owners.

## Retired implementation

The following paths were removed from the Android build and preserved under `codex-handoffs/archive/legacy-markdown-editor-2026-07-16/`:

- WebView Markdown/MathJax/Mermaid/Vega preview
- render cache and rerender controls
- task-property live Markdown field
- per-block Render/Source persistence
- bundled JavaScript rendering engines

Typed code, equation, diagram, and chart payloads remain editable through their structured blocks and builders. Their current read surface is native Compose. Reintroducing a renderer requires a new contract decision; it must not restore Markdown as canonical storage.

## Verification status

Unit tests, debug APK assembly, and Android-test APK compilation pass. Real-device interaction, Light/Dark visual review, compact/adaptive layouts, accessibility, IME behavior, reopen persistence, and installed backup/restore remain required before this area can be called complete.
