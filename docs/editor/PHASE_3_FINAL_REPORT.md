# Norfold editor Phase 3 final report

Phase 3 document-scale and style-preservation closure is complete at Android implementation commit `594a6456e40b22a90663a282e0bd0209c075a8f1`.

| Item | Status | Implementation | Automated evidence | Remaining limitation |
|---|---|---|---|---|
| Document font scaling | Pass | `DocumentContentScale` applies the page scale to resolved document font sizes exactly once, including explicit inline span sizes. | `DocumentScaleStylePreservationTest.Document font line height and letter spacing scale proportionally once` | Physical visual certification deferred. |
| Document line-height scaling | Pass | Resolved line heights use the same page content scale as their font size without changing the stored style value. | `DocumentScaleStylePreservationTest.Document font line height and letter spacing scale proportionally once` | Physical visual certification deferred. |
| Document spacing/internal-layout scaling | Pass | Block before/after spacing, nested gaps, padding, rules, strokes, corners, shadows, table cells, list markers, and document placeholders scale proportionally inside the already-scaled object frame. | `DocumentScaleStylePreservationTest.Document spacing and nested wrapper dimensions scale once` | Physical visual certification deferred. |
| Read/edit scale consistency | Pass | Document read and edit rendering consume the identical scaled `TextStyle`; page headers, footers, and page numbers use the same content-scale rule. | `DocumentScaleStylePreservationTest.read and edit consume the identical scaled text metrics` | Physical cursor and glyph certification deferred. |
| Shape style detach preservation | Pass | Detaching a shape style materializes its currently resolved fill and stroke while preserving stroke width, corner radius, padding, shadow, opacity, and exact undo/redo representation. | `DocumentScaleStylePreservationTest.shape style detach preserves fill stroke and exact undo redo representation` | Physical rendering deferred. |
| Shape style delete-preserve behavior | Pass | Delete with preserve-appearance uses the same materialization path as explicit detach, so the rendered shape survives style deletion and undo/redo exactly. | `DocumentScaleStylePreservationTest.style delete preserve keeps visible shape fill and stroke` | Physical rendering deferred. |
| Gradient preservation | Pass | Direct gradients survive unrelated style detachment, while token-backed paints are frozen to their resolved literal at detach time and no longer track later token changes. | `DocumentScaleStylePreservationTest.direct gradient survives unrelated style detachment`; `token backed object style materializes current fill on detach` | Physical rendering deferred. |
| Title style reachability | Pass | Title is reachable for paragraph structure, is atomic over multiple selected paragraphs, and remains unavailable for semantic heading, quote, and code blocks. | `DocumentScaleStylePreservationTest.Title Subtitle and Caption apply atomically to paragraphs`; `presentational paragraph styles reject Heading Quote and Code` | Structural conversion is intentionally not introduced. |
| Subtitle style reachability | Pass | Subtitle follows the same paragraph-only eligibility, mixed-selection all-or-nothing rule, and one-transaction history behavior. | `DocumentScaleStylePreservationTest.Title Subtitle and Caption apply atomically to paragraphs`; `Named Styles UI status matches controller eligibility` | Structural conversion is intentionally not introduced. |
| Caption style reachability | Pass | Caption follows the same paragraph-only eligibility and UI/controller truth as Title and Subtitle. | `DocumentScaleStylePreservationTest.Title Subtitle and Caption apply atomically to paragraphs`; `Named Styles UI status matches controller eligibility` | Structural conversion is intentionally not introduced. |
| Semantic heading/quote/code protection | Pass | Heading roles still require the matching heading level; quote and code styles still require their exact structures; incompatible mixed selections remain disabled and unchanged. | `DocumentScaleStylePreservationTest.presentational paragraph styles reject Heading Quote and Code`; existing semantic-style tests | Structural conversion is intentionally not introduced. |
| History atomicity | Pass | Multi-paragraph style application, shape detach, and delete-preserve each remain one canonical transaction with exact undo/redo. | `DocumentScaleStylePreservationTest`; existing history tests in the full suite | None known. |

## Validation evidence

- `./gradlew :apps:android:testDebugUnitTest`: **PASS — 1,045 tests, 0 failures, 0 errors, 0 skipped**.
- `./gradlew :apps:android:compileDebugKotlin`: **PASS**.
- `./gradlew :apps:android:assembleDebug`: **PASS**.
- `./gradlew :apps:android:compileDebugAndroidTestKotlin`: **PASS**.
- APK: `apps/android/build/outputs/apk/debug/android-debug.apk`.
- APK size: **56,014,631 bytes**.
- APK SHA-256: `2b97d05ea5754e50b05c6ed67df0a22956007a3b23c429c5b5810ec85e622f45`.
- `FLOW_HOST` and `DOCUMENT_HOST` production defaults: **unchanged**.
- Document object model geometry and Flow metrics: **unchanged**, covered by `Document content scale never mutates object model geometry or Flow metrics`.
- Device, emulator, screenshot, TalkBack, and airplane-mode testing: **not run by explicit instruction**.
- Phase 4: **not started**.
- Supabase, collaboration/CRDT, `apps/web`, infrastructure, chat, font binaries, packaged font manifest, and ShapeCatalog: **untouched**.

`PHASE 3 IMPLEMENTATION: COMPLETE`

`PHYSICAL DEVICE CERTIFICATION: DEFERRED`

`PHASE 4: READY FOR AUTHORIZATION`
