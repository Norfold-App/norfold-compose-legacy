#!/usr/bin/env python3
"""Guarded Norfold Note/Notes -> Doc/Docs terminology migration.

Dry-run is the default. This script intentionally does not rename persisted storage contracts
(`notes`, `note_blocks`, `noteId`, `NOTE`, sync object type `note`) or generated/archive content.
Run it repeatedly: every phase is idempotent and writes files atomically only with --apply.
"""

from __future__ import annotations

import argparse
import os
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "apps" / "android" / "src"
EXCLUDED_PARTS = {
    "build", ".gradle", "schemas", "assets", "archive", "generated", "graphify-out",
}

# Exact UI literals only. Lowercase persistence/scope literals are intentionally absent.
UI_COPY = {
    '"Notes"': '"Docs"',
    '"All notes"': '"All docs"',
    '"New note"': '"New doc"',
    '"Untitled note"': '"Untitled doc"',
    '"Delete note"': '"Delete doc"',
    '"No notes yet"': '"No docs yet"',
    '"Search notes"': '"Search docs"',
    '"Recent notes"': '"Recent docs"',
}

# High-level Kotlin architecture names. Database/entity/DAO names remain compatibility seams.
SYMBOLS = {
    "NotesUiState": "DocsUiState",
    "NotesViewModel": "DocsViewModel",
    "NotesRepository": "DocsRepository",
    "NotesScreen": "DocsScreen",
}

PROTECTED_LITERALS = (
    '"notes"', '"note_blocks"', '"noteId"', '"NOTE"', '"note"',
)


def source_files() -> list[Path]:
    return sorted(
        path for path in SOURCE_ROOT.rglob("*.kt")
        if not any(part in EXCLUDED_PARTS for part in path.parts)
    )


def transform(text: str, phase: str) -> str:
    before_contracts = {literal: text.count(literal) for literal in PROTECTED_LITERALS}
    changed = text
    if phase in {"ui-copy", "all"}:
        for old, new in UI_COPY.items():
            changed = changed.replace(old, new)
    if phase in {"symbols", "all"}:
        for old, new in SYMBOLS.items():
            changed = re.sub(rf"\b{re.escape(old)}\b", new, changed)
    after_contracts = {literal: changed.count(literal) for literal in PROTECTED_LITERALS}
    if before_contracts != after_contracts:
        raise RuntimeError("A protected storage/sync literal changed; refusing to continue")
    return changed


def atomic_write(path: Path, content: str) -> None:
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, path)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("ui-copy", "symbols", "all"), default="all")
    parser.add_argument("--apply", action="store_true", help="write changes; otherwise dry-run")
    args = parser.parse_args()

    changes: list[tuple[Path, str, str]] = []
    for path in source_files():
        original = path.read_text(encoding="utf-8")
        updated = transform(original, args.phase)
        if updated != original:
            changes.append((path, original, updated))

    mode = "APPLY" if args.apply else "DRY-RUN"
    print(f"{mode}: phase={args.phase}; files={len(changes)}")
    for path, original, updated in changes:
        delta = sum(1 for a, b in zip(original.splitlines(), updated.splitlines()) if a != b)
        print(f"  {path.relative_to(ROOT)} (~{delta} changed lines)")
    if args.apply:
        for path, _, updated in changes:
            atomic_write(path, updated)
    else:
        print("No files written. Re-run with --apply after reviewing this list.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
