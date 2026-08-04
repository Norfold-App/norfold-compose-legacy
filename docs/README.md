# Norfold Documentation

Entry point for everything under `docs/`. Start with the category you need; each
entry says whether the document is authoritative, active, or historical.

> `docs/` is also the **GitHub Pages site root**. The `*.html` files, `legal.css`,
> and `assets/` are published web pages, not documentation. Do not move or rename
> them — published URLs and existing deployments depend on those paths.

## Repository and contributor guidance

| Document | Status | Purpose |
| --- | --- | --- |
| [../AGENTS.md](../AGENTS.md) | **Authoritative** | Short non-negotiable rules: release stage, forbidden Git operations, what must never be committed, commit convention, scope limits. Read first. |
| [REPOSITORY_GUIDE.md](REPOSITORY_GUIDE.md) | **Authoritative** | Long form: repository layout, feature flags, safe Git workflow, build and verification gates, release-stage rules, Supabase and reset safety, how to resume partial work. |
| [EXTERNAL_REFERENCE_POLICY.md](EXTERNAL_REFERENCE_POLICY.md) | **Authoritative** | What external links and references are acceptable, how research is summarized locally, licensing and attribution requirements. |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | **Authoritative** | Contribution status. External contributions are not accepted. |

## Product and architecture

| Document | Status | Purpose |
| --- | --- | --- |
| [product/NORFOLD_UI_GOALSET.md](product/NORFOLD_UI_GOALSET.md) | **Active** | UI completion acceptance checklist. Items whose only definition was a screenshot are marked `[reference capture required]`. |
| [product/PRODUCT_STATUS_TRACKER.md](product/PRODUCT_STATUS_TRACKER.md) | Active log | Running product-status log: UI acceptance notes, implementation constraints, fixed items, and what still needs manual or on-device verification. A log, not a specification. |
| [editor/decisions/EDITOR-REARCHITECTURE.md](editor/decisions/EDITOR-REARCHITECTURE.md) | **Authoritative** | The structured-document decision: content is a versioned typed block tree; markdown is an import/export boundary only. |

## Editor implementation

| Document | Status | Purpose |
| --- | --- | --- |
| [editor/EDITOR_IMPLEMENTATION_ROADMAP.md](editor/EDITOR_IMPLEMENTATION_ROADMAP.md) | **Authoritative — roadmap of record** | Maps the repository against the canonical editor spec; classifies every editor system retain/refactor/replace/missing; defines phases, validation gates, and stop conditions. |
| [editor/PHASE_4_FINAL_REPORT.md](editor/PHASE_4_FINAL_REPORT.md) | **Active — most recent completed phase** | Phase 4: shared pure-JVM calculation domain, table routing through the shared engine, gated SpreadsheetHost. Kept out of the archive so the current phase stays discoverable. |

Phase 4 is complete and frozen. Do not modify its code, tests, report, or commit
boundaries. Phase 5 (ChartDocument, Chart Studio, chart renderers) has not started.

## Benchmarks and validation

| Document | Status | Purpose |
| --- | --- | --- |
| [editor/benchmarks/D9_FONT_DELIVERY_BENCHMARK.md](editor/benchmarks/D9_FONT_DELIVERY_BENCHMARK.md) | Measured result | Font-delivery packaging benchmark measured from the Phase-3 debug APK — variable builds, script subsetting, asset delivery, package-size targets. |
| [collaboration/BENCHMARK_PROTOCOL.md](collaboration/BENCHMARK_PROTOCOL.md) | Active protocol | Measurement methodology for transport provider selection and adaptive QoS tuning. Its default parameters are hypotheses until device measurements replace them. |

## Decisions and specifications

| Document | Status | Purpose |
| --- | --- | --- |
| [collaboration/PROVIDER_DECISION.md](collaboration/PROVIDER_DECISION.md) | Pending device validation | Collaboration transport provider criteria, synthetic results, and decision framework. Server-side benchmarks complete; final call needs Android device measurements. |
| [collaboration/AUTHORIZATION_CONTRACT.md](collaboration/AUTHORIZATION_CONTRACT.md) | Scaffolded, not wired | Authorization model for the collaboration transport. Authentication is enforced; artifact authorization is not yet wired to workspace permissions. |

Collaboration and CRDT integration remain frozen until Phase 12. These documents
record intent; they do not describe shipped behavior.

## Operations

| Document | Status | Purpose |
| --- | --- | --- |
| [operations/EXTERNAL_SERVICES_ALPHA.md](operations/EXTERNAL_SERVICES_ALPHA.md) | **Operationally authoritative** | Provisioning runbook for the tester-only Alpha environment. No provider secret, private key, database password, or service-account JSON belongs in the Android project. |
| [operations/TELEGRAM_PHONE_VERIFICATION.md](operations/TELEGRAM_PHONE_VERIFICATION.md) | **Operationally authoritative** | Phone verification through the Telegram bot `request_contact` flow. The app talks to Supabase; Supabase talks to Telegram. |
| [../infra/collaboration/ably/README.md](../infra/collaboration/ably/README.md) | Reference | Ably transport infrastructure notes. |

## Historical and archive material

| Document | Status | Purpose |
| --- | --- | --- |
| [archive/editor/PHASE_1_FINAL_REPORT.md](archive/editor/PHASE_1_FINAL_REPORT.md) | Historical evidence | FlowHost Phase 1 closure. Implementation complete; device certification deferred. |
| [archive/editor/PHASE_2_VALIDATION_REPORT.md](archive/editor/PHASE_2_VALIDATION_REPORT.md) | Historical evidence | DocumentHost Phase 2 validation. Gate passed with one scope note. |
| [archive/editor/PHASE_3_FINAL_REPORT.md](archive/editor/PHASE_3_FINAL_REPORT.md) | Historical evidence | Phase 3 rendering, tokens, mixed state, semantics, gestures, style preservation, document scale. |

Archived reports are evidence of what was verified at the time. They are not
current-state claims and are not a substitute for reading the source.

## Brand assets

| Path | Purpose |
| --- | --- |
| `assets/` | Published site assets: favicons, app-touch icon, logo SVG/PNG. Referenced by the Pages HTML. |
| [../brand/](../brand/) | Source brand assets. `norfold-logo-animated.html` is the logo animation source of record. |
| [../website-v4/brand/motion-spec.md](../website-v4/brand/motion-spec.md) | Marketing-site motion specification. |

Brand assets are **not** covered by any open-source grant. See [../NOTICE.md](../NOTICE.md).

## Published web pages (do not reorganize)

`index.html`, `download.html`, `changelog.html`, `support.html`, `privacy.html`,
`terms.html`, `legal.css`, `assets/`.
