#!/usr/bin/env python3
"""Simulate NorfoldDatabase Room migrations and diff final schema vs 35.json.

Extracts literal execSQL statements from NorfoldDatabase.kt so it stays in sync
with edits. Kotlin-logic-only steps (data loops with '?' binds) are skipped —
they don't change schema.
"""
import json, re, sqlite3, sys, os

KT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../src/main/java/com/norfold/app/data/NorfoldDatabase.kt")
SCHEMAS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../schemas/com.norfold.app.data.NorfoldDatabase")
TARGET = 35

src = open(KT).read()

STR = r'(?:"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*")'
EXEC_RE = re.compile(r'execSQL\(\s*(' + STR + r'(?:\s*\+\s*' + STR + r')*)\s*(?:\.trimIndent\(\)\s*)?[,)]')
ADDCOL_RE = re.compile(r'addColumnIfMissing\("(\w+)",\s*"(\w+)",\s*"([^"]+)"\)')

def parse_str(lit):
    parts = re.findall(STR, lit)
    out = []
    for p in parts:
        if p.startswith('"""'):
            out.append(p[3:-3])
        else:
            out.append(p[1:-1].replace('\\"', '"').replace("\\n", "\n"))
    return "".join(out)

def extract_stmts(block):
    stmts = []
    for m in EXEC_RE.finditer(block):
        s = parse_str(m.group(1)).strip()
        if "?" in s:
            continue  # data statement with binds — schema-neutral
        s = s.replace("$now", "0")
        if "$" in s:
            continue  # interpolated loop statement — data-only
        stmts.append(s)
    addcols = ADDCOL_RE.findall(block)
    return stmts, addcols

def block_between(start_pat, end_pat):
    a = src.index(start_pat)
    b = src.index(end_pat, a + 1)
    return src[a:b]

# ensureCurrentSchema body
ECS_BLOCK = block_between("private fun SupportSQLiteDatabase.ensureCurrentSchema",
                          "private fun SupportSQLiteDatabase.addColumnIfMissing")
ECS_STMTS, ECS_ADDCOLS = extract_stmts(ECS_BLOCK)

# explicit migrations
mig_blocks = {}
MIG_RE = re.compile(r'(?:private|internal) val MIGRATION_(\d+)_(\d+) = (schemaMigration\(\d+, \d+\)|object : Migration\(\d+, \d+\))')
matches = list(MIG_RE.finditer(src))
for i, m in enumerate(matches):
    frm, to = int(m.group(1)), int(m.group(2))
    is_schema = m.group(3).startswith("schemaMigration")
    end = matches[i + 1].start() if i + 1 < len(matches) else src.index("private fun schemaMigration")
    body = src[m.start():end]
    mig_blocks[(frm, to)] = ("ECS" if is_schema else extract_stmts(body),
                             "uses_ecs" if "ensureCurrentSchema()" in body else "")

def add_column_if_missing(db, table, col, definition):
    cols = [r[1] for r in db.execute(f"PRAGMA table_info({table})")]
    if col not in cols:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {col} {definition}")

def run_ecs(db):
    for s in ECS_STMTS:
        db.execute(s)
    for t, c, d in ECS_ADDCOLS:
        add_column_if_missing(db, t, c, d)

def apply_migration(db, key):
    payload, flag = mig_blocks[key]
    if payload == "ECS":
        run_ecs(db)
        return
    stmts, addcols = payload
    for s in stmts:
        db.execute(s)
    for t, c, d in addcols:
        add_column_if_missing(db, t, c, d)
    # object-migrations that also call ensureCurrentSchema at the end
    if flag == "uses_ecs":
        run_ecs(db)

def create_from_schema(db, ver):
    j = json.load(open(f"{SCHEMAS}/{ver}.json"))
    for e in j["database"]["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for ix in e.get("indices", []):
            db.execute(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))

def affinity(t):
    t = t.upper()
    if "INT" in t: return "INTEGER"
    if "CHAR" in t or "CLOB" in t or "TEXT" in t: return "TEXT"
    if t == "": return "BLOB"
    if "BLOB" in t: return "BLOB"
    if "REAL" in t or "FLOA" in t or "DOUB" in t: return "REAL"
    return "NUMERIC"

def read_schema(db):
    out = {}
    tables = [r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')")]
    for t in tables:
        cols = {}
        for cid, name, typ, notnull, dflt, pk in db.execute(f"PRAGMA table_info({t})"):
            cols[name] = (affinity(typ), bool(notnull), pk)
        idx = {}
        for _, iname, unique, origin, _p in db.execute(f"PRAGMA index_list({t})"):
            if origin != "c":  # skip pk/unique autoindexes
                continue
            icols = [r[2] for r in db.execute(f"PRAGMA index_info({iname})")]
            idx[iname] = (bool(unique), tuple(icols))
        fks = set()
        for row in db.execute(f"PRAGMA foreign_key_list({t})"):
            _id, _seq, ref, frm, to, onupd, ondel, _m = row
            fks.add((ref, frm, to, onupd, ondel))
        out[t] = (cols, idx, fks)
    return out

def expected_schema():
    j = json.load(open(f"{SCHEMAS}/{TARGET}.json"))
    out = {}
    for e in j["database"]["entities"]:
        t = e["tableName"]
        pk_cols = e["primaryKey"]["columnNames"]
        cols = {}
        for f in e["fields"]:
            pk = (pk_cols.index(f["columnName"]) + 1) if f["columnName"] in pk_cols else 0
            cols[f["columnName"]] = (f["affinity"], f.get("notNull", False), pk)
        idx = {}
        for ix in e.get("indices", []):
            idx[ix["name"]] = (ix["unique"], tuple(ix["columnNames"]))
        fks = set()
        for fk in e.get("foreignKeys", []):
            for c, rc in zip(fk["columns"], fk["referencedColumns"]):
                fks.add((fk["table"], c, rc, fk["onUpdate"].upper(), fk["onDelete"].upper()))
        out[t] = (cols, idx, fks)
    return out

def diff(actual, exp, ver):
    probs = []
    for t in exp:
        if t not in actual:
            probs.append(f"MISSING TABLE {t}")
            continue
        acols, aidx, afks = actual[t]
        ecols, eidx, efks = exp[t]
        for c, spec in ecols.items():
            if c not in acols:
                probs.append(f"{t}: missing column {c}")
            elif acols[c] != spec:
                probs.append(f"{t}.{c}: got {acols[c]} want {spec}")
        for c in acols:
            if c not in ecols:
                probs.append(f"{t}: EXTRA column {c}")
        for i, spec in eidx.items():
            if i not in aidx:
                probs.append(f"{t}: missing index {i}")
            elif aidx[i] != spec:
                probs.append(f"{t}: index {i} got {aidx[i]} want {spec}")
        for i in aidx:
            if i not in eidx:
                probs.append(f"{t}: EXTRA index {i}")
        if afks != efks:
            for fk in efks - afks: probs.append(f"{t}: missing FK {fk}")
            for fk in afks - efks: probs.append(f"{t}: EXTRA FK {fk}")
    for t in actual:
        if t not in exp:
            probs.append(f"EXTRA TABLE {t}")
    return probs

exp = expected_schema()
versions = sorted(int(f.split(".")[0]) for f in os.listdir(SCHEMAS) if f.endswith(".json"))
versions = [v for v in versions if v < TARGET]
overall_fail = False
for start in versions:
    db = sqlite3.connect(":memory:")
    try:
        create_from_schema(db, start)
        cur = start
        path = []
        while cur < TARGET:
            cands = [k for k in mig_blocks if k[0] == cur and k[1] <= TARGET]
            if not cands:
                raise RuntimeError(f"no migration from {cur}")
            nxt = max(cands, key=lambda k: k[1])
            path.append(nxt)
            apply_migration(db, nxt)
            cur = nxt[1]
        probs = diff(read_schema(db), exp, start)
    except Exception as ex:
        probs = [f"MIGRATION CRASH: {ex}"]
    if probs:
        overall_fail = True
        print(f"=== v{start} -> {TARGET}: {len(probs)} problem(s)")
        for p in probs[:60]:
            print("   ", p)
        if len(probs) > 60:
            print(f"    ... and {len(probs)-60} more")
    else:
        print(f"=== v{start} -> {TARGET}: OK")
sys.exit(1 if overall_fail else 0)
