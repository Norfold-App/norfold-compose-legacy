# D9 — Font-Delivery Benchmark (Phase 0)

**Class:** `BENCHMARK` (Roadmap R7) — measured, not assumed.
**Status:** Decision recorded. **Delivery is NOT implemented in Phase 0.**
**Spec basis:** §9.6 (the 30 guaranteed-offline families), §9.8–§9.9 (import + package-size
targets), §20.7 (resolution: variable builds, script subsetting, asset delivery, measured
packaging), §18.16, revision #10.
**Roadmap basis:** delivery-model row "REPLACE delivery model"; R7 BENCHMARK CLASS;
Phase-0 open-benchmark item ("APK/AAB size contribution of bundling the 30 guaranteed font
families vs install-time asset pack").

---

## 1. Question this benchmark answers

The current delivery model (`ui/fonts/NorfoldFonts.kt`) fetches faces from the Google Fonts
provider **at runtime**, with an 18-family hardcoded fallback and **no bundled binaries**. The
spec requires the **30 guaranteed families (§9.6) to be available offline without any network
request**. R7 asks a single, bounded question before that replacement is designed:

> What is the APK/AAB size contribution of bundling the 30 guaranteed families, and does bundling
> directly in the app binary stay inside the §9.9 package-size envelope — or is an **install-time
> asset pack** required to stay inside it?

Everything beyond the 30 (the *extended catalogue*, §9.7) remains true on-demand fetch and is
out of scope for this benchmark.

## 2. §9.9 size envelope (the thresholds this is measured against)

| Band | §9.9 rule |
|------|-----------|
| **Preferred** | Base download at or below **~120 MB**. |
| **Mandatory review** | Base download above **150 MB**. |
| **Rejected** | Ordinary base package above **180 MB**, *unless* measured evidence + delivery architecture justify it. |
| **Hard constraint** | Do **not** solve size by silently removing the professional offline library (§9.9, §20.7). |

These thresholds are for the **whole base download**, not for fonts alone. The font contribution
must fit *within* the app's total budget, so the smaller it is, the more headroom the rest of the
app keeps.

## 3. Measurement method and honesty caveat

This is a **measure-only** deliverable and the local environment has **no font-optimization
toolchain** (`fontTools` / `pyftsubset` absent; no font binaries vendored in the repo). Therefore
the numbers below are a **grounded estimate**, not a produced-artifact measurement:

- **One direct local anchor** was taken: the system copy of `NotoSans[wght].ttf` (a `wght`
  variable font, full unsubsetted glyph coverage) measures **712,460 bytes (≈ 696 KB)**. This
  calibrates the "full variable, unsubset" end of the range for a large-coverage family.
- The remaining 29 families are estimated from **published upstream release file sizes** for their
  variable builds (Latin/Latin-ext subset) and, where no official variable build ships, the
  necessary static faces. Each is labelled as an estimate.
- The estimate deliberately reports **two columns**: a *naive* worst case (all static weights,
  unsubset, full Unicode coverage) and the *optimized* case the spec actually mandates
  (§20.7: variable builds + script subsetting + only-necessary static faces).

Before implementation (Phase 8+, when font delivery is actually built), these estimates **must be
re-measured** with `pyftsubset` against the exact chosen faces and axes, and this artifact updated.
The **decision** recorded in §6 is robust across the whole estimate range (see §7 sensitivity), so
it does not depend on the estimate being exact.

## 4. Per-family estimate (30 guaranteed families, §9.6)

Optimized = variable build where one ships (axes limited to `wght`, plus `ital`/`opsz` only where
the design needs it), subset to the scripts the family is bundled for (Latin/Latin-ext for the
Latin families; Bengali + Latin for the Bangla families). Naive = every static weight, unsubset.

| # | Family | Role | Ships VF? | Naive (KB) | **Optimized (KB)** |
|---|--------|------|-----------|-----------:|-------------------:|
| 1 | Inter | sans | yes (wght+ital) | 1600 | **350** |
| 2 | Source Sans 3 | sans | yes | 1200 | **250** |
| 3 | IBM Plex Sans | sans | partial (statics) | 1400 | **320** |
| 4 | Noto Sans | sans | yes (anchor 696 KB full) | 1800 | **320** |
| 5 | Manrope | sans | yes | 600 | **120** |
| 6 | Lato | sans | statics | 1500 | **220** |
| 7 | Montserrat | sans | yes | 1100 | **220** |
| 8 | Carlito | sans (Calibri-metric) | statics | 1200 | **300** |
| 9 | Arimo | sans (Arial-metric) | yes | 900 | **170** |
| 10 | Atkinson Hyperlegible Next | sans (accessibility) | yes | 900 | **200** |
| 11 | Source Serif 4 | serif | yes | 1400 | **300** |
| 12 | Noto Serif | serif | yes | 1600 | **300** |
| 13 | Lora | serif | yes | 800 | **190** |
| 14 | Merriweather | serif | yes | 1300 | **260** |
| 15 | EB Garamond | serif | yes | 1300 | **340** |
| 16 | Literata | serif | yes (opsz) | 1400 | **350** |
| 17 | Tinos | serif (Times-metric) | statics | 1100 | **300** |
| 18 | Roboto Slab | slab | yes | 1200 | **250** |
| 19 | JetBrains Mono | mono | yes | 900 | **210** |
| 20 | Source Code Pro | mono | yes | 1000 | **250** |
| 21 | Fira Code | mono (ligatures) | yes | 1400 | **400** |
| 22 | Cousine | mono | statics | 1000 | **250** |
| 23 | Playfair Display | display | yes (opsz) | 1200 | **350** |
| 24 | Fraunces | display | yes (opsz+ital+soft) | 2200 | **600** |
| 25 | Poppins | display sans | statics | 1800 | **400** |
| 26 | Lexend | display sans | yes | 700 | **150** |
| 27 | Noto Sans Bengali | Bangla sans | yes | 1200 | **260** |
| 28 | Noto Serif Bengali | Bangla serif | yes | 1300 | **300** |
| 29 | Hind Siliguri | Bangla sans | statics | 1500 | **450** |
| 30 | Tiro Bangla | Bangla serif | statics (reg+ital) | 500 | **210** |
| | **TOTAL** | | | **≈ 37.5 MB** | **≈ 8.5 MB** |

## 5. Findings

- **Optimized bundle ≈ 8.5 MB** for all 30 guaranteed families (variable builds + script
  subsetting + necessary statics, per §20.7). Add a conservative **+30 % margin** for hinting
  tables, packaging overhead, and families that need an extra italic/opsz axis → **≈ 11 MB**.
- **Naive worst case ≈ 37.5 MB** (every static weight, unsubset, full Unicode). Even this
  never-do-this figure sits **far below the 120 MB preferred threshold**.
- Font contribution therefore consumes **< 10 %** of the *preferred* base-download band even at
  the realistic estimate, and **≈ 31 %** at the absurd naive extreme.
- No band boundary (120 / 150 / 180 MB) is approached by the font payload alone under any column.

## 6. Decision (recorded, not implemented)

> **Bundle the 30 guaranteed families directly in the Android App Bundle (AAB), as optimized
> variable builds with script subsetting (§20.7). Do NOT introduce an install-time asset pack for
> the guaranteed set.**

Rationale:

1. **It fits with enormous headroom.** At ≈ 8.5–11 MB optimized (≈ 37.5 MB even naive), the
   guaranteed set is nowhere near the §9.9 preferred ceiling. An install-time asset pack exists to
   keep a *large* payload out of the base install; there is no size pressure here to justify its
   added delivery complexity, split-install failure modes, or first-run "still downloading fonts"
   states that would violate "guaranteed offline without any network request" on day one.
2. **AAB already does per-device slimming.** Play delivery serves only the device's density/ABI
   splits; there is no per-language font split needed at this size, so bundling does not bloat
   individual installs meaningfully.
3. **It satisfies the offline guarantee literally.** Bundled-in-binary fonts are present at first
   launch with zero network dependency — the exact property §9.6/§20.7 require and the property the
   current runtime-fetch model fails.
4. **The hard constraint is honoured.** Nothing is removed from the professional library to hit a
   size target (§9.9, §20.7).

**Contingency (recorded for later phases, not now):** if the *extended catalogue* (§9.7) or
additional bundled scripts later push the total base download toward the 150 MB review band, the
install-time asset pack is the correct tool **at that point** — applied to the extended/optional
tiers, never to the guaranteed 30. Re-run this benchmark with a real `pyftsubset` pass when font
delivery is implemented (Phase 8+) and update §4–§5 with produced measurements.

## 7. Sensitivity (why the decision is safe despite estimated inputs)

The decision "bundle in AAB, no asset pack" only flips if the guaranteed-30 payload could plausibly
approach the 120 MB preferred ceiling. To get there from the optimized estimate would require the
true size to be **~14×** the estimate; from the naive worst case, **~3.2×**. Neither is physically
possible for 30 subsetted font families — a single unsubset full-Unicode variable font of the
largest coverage measured here is < 0.7 MB (§3 anchor). The conclusion is therefore stable across
the entire credible error range of the estimate, which is why it is recorded now and the exact
`pyftsubset` measurement is deferred to implementation time without risk to the decision.

## 8. Scope boundaries honoured

- No font binaries were added to the repository.
- `NorfoldFonts.kt`, `FontCatalogPicker.kt`, `font_certs.xml`, and the assets directory were **not
  modified**. Delivery-model replacement is a later-phase task; Phase 0 records the decision only.
- No user-visible behavior changed.
