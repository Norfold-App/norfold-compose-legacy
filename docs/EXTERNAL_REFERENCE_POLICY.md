# External Reference Policy

Norfold is **source-available and view-only**, not open source. It is not a
derivative work of any other project and must not become one by accident. This policy
draws the line between *learning from* something external and *taking* it.

It applies to every contributor and every agent, and to code, tests, assets, strings,
schemas, and documentation alike.

---

## 1. What external projects may inform

External projects, products, and documentation may be used as reference for:

- feature ideas and scope;
- user workflows and interaction patterns;
- compatibility expectations (file formats, function catalogues, keyboard conventions);
- architectural questions and trade-off analysis;
- test categories and edge cases worth covering;
- usability critique.

That is genuinely valuable and explicitly allowed. Understanding *what* a good
spreadsheet does with a circular reference is not the same as copying *how* someone
else implemented it.

## 2. What may never enter Norfold

Regardless of the source project's license, none of the following is permitted:

- copying source code;
- language-to-language translation of source code (a Rust→Kotlin retype is still a copy);
- copying tests, fixtures, or test data;
- copying assets, icons, illustrations, or user-facing strings;
- vendoring a copy of an external project into the tree;
- Git submodules;
- committing prebuilt binaries;
- JNI or WASM embedding of external code;
- adding a runtime dependency;
- copying database or wire schemas;
- copying cryptographic implementations.

**Never copy, translate, link, or vendor GPL, AGPL, or source-available code.** Those
licenses are incompatible with Norfold's licensing and with owner control of the
codebase. Copyleft contamination is not reversible by deleting the file later; the
history keeps it.

### Restricted reference

`vayun-mathur/Modern-Apps` is **reference-only and restricted**. It must not
contaminate Norfold's licensing or owner control. Do not copy any source, asset,
string, or content from it. Reading it to understand a problem is acceptable;
anything more is not.

## 3. Adding a dependency

Every dependency addition requires all five, in order:

1. explicit owner approval;
2. license review — including transitive dependencies;
3. replaceability review — what happens when it is abandoned;
4. security review — supply-chain surface, permissions, network access;
5. a recorded decision under `docs/editor/decisions/` or `docs/collaboration/`.

An agent may propose a dependency. An agent may not add one.

## 4. Summarizing external research locally

When external research informs a decision, write the *conclusion* into the repository,
not the source material:

- Record the finding in your own words in the relevant decision document.
- State the observed behavior and why Norfold does or does not follow it.
- Cite the external source as a stable public URL for provenance.
- Do not paste external code, tests, or long verbatim quotations. Short quotation for
  citation is acceptable; reproduction is not.

A future contributor should be able to act on the summary without visiting the source.

## 5. Acceptable links in tracked files

**Acceptable:**

- stable public documentation URLs (vendor docs, standards, platform references);
- public repository URLs used purely as provenance for a cited claim;
- public issue or specification URLs relevant to a recorded decision;
- repository-relative paths to files that actually exist in this repository.

**Never acceptable:**

- machine-local paths — `/home/...`, `C:\Users\...`, `\\wsl.localhost\...`, `/Users/...`,
  downloads folders, local APK or build paths;
- private chat, session, or transcript URLs, including agent and assistant session links;
- temporary or expiring download links, signed URLs, and file-share links that die;
- links requiring a personal login to resolve;
- credentials, API keys, bearer tokens, or any secret embedded in a URL;
- internal ticket links that a reader outside the machine cannot open.

The test is simple: **if the link only resolves on one machine or for one person, it is
not a reference — it is a note to yourself.** Put the substance in the repository
instead.

## 6. Licensing and attribution requirements

- Any third-party material that is legitimately permitted must be recorded in
  `third_party_licenses.json` / `third_party_licenses.txt` and surfaced in
  `apps/android/src/main/assets/THIRD_PARTY_NOTICES.md`.
- Attribution must name the project, the license, and the version.
- Norfold's own brand assets — `brand/`, `docs/assets/`, `assets/` — carry **no**
  open-source grant. See [../NOTICE.md](../NOTICE.md) and [../LICENSE](../LICENSE).
- Do not add a license header from another project to a Norfold file.
- Do not commit third-party product screenshots, personal screenshots, or unrelated
  downloads.

## 7. When a reference becomes unavailable

References go stale: a screenshot set lives only in someone's downloads folder, a URL
rots, a design file moves. When that happens:

1. **Do not keep citing it as the source of truth.** A reference nobody can open is not
   a specification, and a checklist item defined only by a missing image cannot be
   certified complete.
2. **Say plainly what is missing** and what would have to be supplied to restore it.
3. **Rewrite the requirement so it stands on its own** where possible — describe the
   intended behavior in words that are verifiable without the artifact.
4. **Mark items that genuinely cannot be verified without the artifact**, so nobody
   later mistakes an unverified item for a passed one.
5. **To restore a reference properly**, commit the approved artifact to a
   repository-relative path — for example `docs/product/reference/` — and point the
   requirement at that path. Only owner-approved, publishable material may be committed.
6. **Never invent a reference.** If the capture does not exist, saying so is the correct
   outcome.

[product/NORFOLD_UI_GOALSET.md](product/NORFOLD_UI_GOALSET.md) is the worked example:
its original screenshot sets are unrecoverable, so the affected items now carry a
`[reference capture required]` marker instead of a dead local path.
