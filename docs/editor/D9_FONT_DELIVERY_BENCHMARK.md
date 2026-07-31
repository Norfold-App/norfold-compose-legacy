# D9 — Font-Delivery Benchmark (Phase 3 measured result)

**Class:** `BENCHMARK` (Roadmap R7) — measured, not assumed.
**Status:** **Implemented and measured from the Phase-3 debug APK.**
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

## 3. Measurement method

The Phase-3 measurement is taken from the produced artifact, not from a projection:

- artifact: `apps/android/build/outputs/apk/debug/android-debug.apk`;
- APK SHA-256: `7f57f4599e87c47eca32a224ff2809e27be76478d5fb03bc345aa3d9706cb7a4`;
- family and face counts come from `assets/fonts/manifest.json` and are cross-checked against the
  ZIP entries actually present in the APK;
- raw and compressed contributions come from `zipinfo -t` over
  `assets/fonts/faces/*`;
- the 30 license files and the packaged manifest are measured separately from the face payload.

The debug APK includes debug/runtime overhead and is therefore a conservative package-size proof.
A store-delivered AAB split may be smaller, but this benchmark does not claim a value that was not
produced and inspected.

## 4. Produced package measurement and historical estimate (30 guaranteed families, §9.6)

| Produced artifact metric | Result |
|---|---:|
| Guaranteed families in manifest | **30** |
| Font faces in manifest | **65** |
| Font face files present in APK | **65** |
| License files present in APK | **30** |
| Face payload, raw | **14,055,364 bytes (13.40 MiB)** |
| Face payload, APK-compressed | **7,811,558 bytes (7.45 MiB)** |
| Full font asset tree, raw (faces + licenses + manifest) | **14,224,564 bytes (13.57 MiB)** |
| Full font asset tree, APK-compressed | **7,881,690 bytes (7.52 MiB)** |
| Total debug APK | **53,668,253 bytes (51.18 MiB)** |

The table below is retained as the Phase-0 planning estimate for traceability. The produced totals
above are authoritative for Phase 3.

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

- The real 65-face payload is **13.40 MiB raw / 7.45 MiB compressed**. Compression reduces the
  face contribution by 44.4%.
- The complete debug APK is **51.18 MiB**, safely below the preferred ~120 MB boundary and far
  below the mandatory-review and hard-stop boundaries.
- Font faces account for about **14.6% of the compressed debug APK**. Licenses plus manifest add
  only about 70 KiB compressed.
- All 30 guaranteed identities enumerate and resolve from packaged assets. The former Android
  Google Fonts provider dependency, certificate resource, and runtime-fetch catalogue have been
  removed from the guaranteed path.
- The two Bangla sans/serif families plus Hind Siliguri and Tiro Bangla are locally packaged and
  advertise Bengali script coverage in the registry.

## 6. Decision (implemented)

> **Bundle the 30 guaranteed families directly in the Android package. Do NOT introduce an
> install-time asset pack or provider request for the guaranteed set.**

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

**Contingency:** if the *extended catalogue* (§9.7) or
additional bundled scripts later push the total base download toward the 150 MB review band, the
install-time asset pack is the correct tool **at that point** — applied to the extended/optional
tiers, never to the guaranteed 30. Re-run this produced-artifact measurement after any material
font-set or packaging change.

## 7. Sensitivity

The produced debug APK has roughly 68.8 MiB of headroom before the preferred ~120 MB boundary.
The packaged face payload would have to grow by more than nine times its current compressed size
before fonts alone consumed that headroom. The decision remains safe, while the explicit 150 MB
review gate protects future script/catalogue expansion.

## 8. Scope boundaries honoured

- The guaranteed registry uses only app-private packaged assets and performs no network/provider
  request during enumeration or resolution.
- Each bundled family has explicit license metadata and a packaged license file: 29 OFL families
  and one Apache-2.0 family (Roboto Slab).
- The optional extended catalogue is not shipped in Phase 3; no incomplete download path is hidden
  behind the Font Browser.
- Imported fonts are validated before being atomically copied into controlled private storage and
  remain `PRIVATE_LOCAL`; their source URI is not the durable reference.
- Physical airplane-mode rendering remains a separate device certification and is not inferred
  from these unit/package checks.
