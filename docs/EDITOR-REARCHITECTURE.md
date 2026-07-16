# Editor re-architecture: retiring markdown as the canonical format

**Status:** direction adopted 2026-07-16 · implementation not started
**Severity:** major fix — this corrects a foundational decision, not a feature bug

## How we got here

Norfold did not start as what it is today, and the editor's storage format never
caught up with that growth:

1. **Simple note app.** The original goal. Markdown was the obvious and correct
   choice — notes, headings, lists, links, code. Nothing more was needed.
2. **Note + task app.** Tasks, boards, and views were added around the notes.
   Markdown still held, but the editor was already being asked to do more than
   markdown models.
3. **Workspace app.** Shared objects, databases, canvas, calendar, collaboration
   surfaces, and a full document editor with a designed toolbar. At this point
   markdown-as-source-of-truth became the wrong foundation.

The mistake: **markdown stayed the editor's main storage format** while the
product became a document editor. That is now being fixed. Markdown is demoted
to an **import/export format**; the canonical document becomes a
**schema-controlled structured JSON tree**.

## Why markdown cannot be the canonical format

Markdown is great for notes, articles, and anything that should stay readable
as plain text. It cannot reliably preserve what a Word/Canva-grade editor
produces:

- Fonts, sizes, colors, highlighting, letter/line spacing
- Page sizes, margins, orientation, headers and footers
- Text boxes, floating images, object positioning and z-order
- Complex tables (merged cells, per-cell fill, column ops)
- Columns and sections
- Comments, suggestions, tracked changes
- Custom embedded components

Every workaround is a nonstandard extension or raw HTML, at which point the
format stops being predictable — the worst of both worlds.

## Target architecture

```text
Responsive editor interface (phone / tablet / desktop / web)
          ↓
ProseMirror-style structured editor (Tiptap is the leading candidate)
          ↓
Structured JSON document (versioned schema)
          ↓
Database + offline cache
          ↓
HTML / Markdown / DOCX / PDF exporters
```

Key rules:

- **One editor engine across all screen sizes.** Only the chrome changes:
  phone gets the two-bar layout (top document bar + bottom formatting bar with
  anchored popup cards — see the approved toolbar contract below), tablet gets
  a wider single arrangement, desktop expands into toolbar + inspectors +
  shortcuts.
- **Structured JSON is the single source of truth.** Store `content_json` with
  a `schema_version`, plus a plain-text extraction for search, media as
  references (never base64), and version snapshots.
- **Formats are boundaries, not storage:**

  | Format | Purpose |
  | --- | --- |
  | Structured JSON | Canonical editable document |
  | HTML | Rendering, clipboard, email |
  | Markdown | Simple import/export, technical content |
  | DOCX | Word interchange |
  | PDF | Final fixed-layout output |
  | Plain text | Search, indexing, accessibility |

  Exporting a complex document to markdown warns that unsupported formatting
  will be simplified.
- **Schema before toolbar.** Define the supported block nodes (paragraph,
  heading, lists, checklist, table, image, video, divider, page break, text
  box, callout, columns, embed), inline marks (bold/italic/underline/strike,
  link, color, highlight, font family/size, super/subscript, comment anchor,
  change tracking), and document-level attributes (page size, orientation,
  margins, header/footer, theme, default font, language) — then build toolbar
  commands only for what the schema can preserve. No button whose result the
  document format cannot store.
- **Web-first delivery.** Responsive PWA first (offline via service worker +
  IndexedDB autosave); wrap with Capacitor for store distribution when native
  capabilities are needed. ProseMirror/Tiptap depend on the DOM, so a
  WebView/browser host is the realistic path — not a parallel native rewrite
  of the editor per platform.
- **Collaboration later, deliberately.** Yjs (CRDT) + websocket provider is
  the plan for real-time collaboration, but only after single-user saving,
  recovery, and schema migrations are reliable.

## Rollout shape

1. **First:** structured editor + JSON storage + responsive two-bar UI +
   offline autosave + HTML/markdown export. Existing markdown notes import
   into the new model.
2. **Second:** store packaging (Capacitor), image/file uploads, tables and
   custom blocks, PDF generation, DOCX import/export, version history.
3. **Third:** Yjs collaboration, comments, presence, suggestions/track
   changes, advanced page layout.

## Relationship to existing work

- The **approved toolbar contract** (two lavender pill bars, anchored popup
  cards, sticky/armed toggles, sidebar ToC with the document name) is the UI
  for this editor. Interactive demos: `Editor_Floating_Bar_plan/`
  (`norfold-floating-bar-demo.html`, `norfold-two-bar-editor-demo.html`).
- The current Android markdown/block editor keeps working until the structured
  editor replaces it; nothing is deleted before the import path is proven.
- `MarkdownExporter`, backup codecs, and sync snapshots survive — they move
  from "the format" to "one of the exporters/boundaries".
