# Architecture Decision Record (ADR) Log

## ADR-000 — Baseline (2026-10-11)

Decision: Android API 26+; Kotlin/Compose/Media3, local + Audius; metadata MusicBrainz; lyrics LRCLIB; constrained declarative plugin manifest; no JS sandbox or commercial service scraping; GitHub Releases first; version selection deferred to build-time official compatibility check.

Consequences: local-first app can function without remote access, plugin contract deliberately narrower than unlimited script engines. No promise of universal catalog or unrestricted licensed audio. Owner approval required for scope/rights changes.

Template: `ADR-NNN: Context / Options / Decision / Consequences / Affected tasks / Links / Date / Owner review needed?`.

## ADR-001 — One home for each pinned fact (2026-10-12)

**Context**: TUN-0002 needed a working Gradle build. The scaffold declared AGP/Kotlin/Compose
versions in `gradle.properties`, no plugins in the root build, and duplicated per-module
version lookups that never resolved. Nothing in the project could be built to check it.

**Options**: (a) keep versions in `gradle.properties` and read them per module; (b) adopt a
Gradle version catalog; (c) pin versions directly in each module file.

**Decision**: (b) for plugin/library versions (`gradle/libs.versions.toml`) and keep
Tunenagi project constants (SDK levels, application id, version code/name) in
`gradle.properties`. Each fact has exactly one home; `./gradlew printVersion` prints the
catalog so docs and build cannot drift. `gradle/libs.versions.toml` is also what phase 00
asked for by name.

**Consequences**: Moving a version is a one-line catalog edit. Duplicated pin containers are
forbidden, so any doc that restates a version number is documentation, not configuration.
The Compose BOM moved 2024.06.00 -> 2024.10.01 because the older BOM cannot be used with
the Kotlin 2.0.20 Compose compiler; that pairing is asserted by `ToolchainCatalogTest`.

**Affected tasks**: TUN-0002 (done), TUN-0005 (CI must read the catalog).

**Owner review needed?** Yes for the version numbers themselves; the mechanism is a build
detail, not a scope change.

## ADR-002 - Design tokens live in `:core` with the contrast audit as a test (2026-10-12)

**Context**: TUN-0003 needed design tokens and a navigation shell. TUN-0002 had put the colour
values in `app/ui/theme/Color.kt` as Compose `Color`s, which no other module can use, and
`SPEC/UX_DESIGN.md` requires an *actual* contrast check rather than an assurance that the
palette is fine.

**Options**: (a) keep the tokens in `:app`; (b) move them to `:core` as Compose colours;
(c) move them to `:core` as plain ARGB ints with the WCAG maths in the same module.

**Decision**: (c). `core/designsystem` - the package `SPEC/ARCHITECTURE.md` reserves for
tokens/components/themes - holds the palette, spacing, shape and motion tokens. Values are
plain `Int`s, so the contrast audit is an ordinary JVM unit test with no device and no
instrumentation. `:app` keeps a thin `TunenagiColors` object that lifts the ARGB values into
Compose `Color`s and must not introduce values of its own.

**Consequences**:
- Palette edits are now checked: `TunenagiContrastTest` asserts every required pair meets its
  role's AA threshold and that the recorded prohibitions are still real violations.
- The audit found two genuine problems in the `SPEC/UX_DESIGN.md` proposal: the `#35394A`
  outline measures 1.70:1 on background / 1.53:1 on surface, below the 3:1 non-text
  requirement, and `#B6B8C5` secondary text on the `#5C54B8` subdued accent is 3.12:1, below
  AA text. A new `OutlineStrong` token (`#6E7288`, 4.09 / 3.69 / 3.08) covers boundaries that
  carry meaning, and the two failing combinations are recorded as explicit prohibitions with
  failing-path tests.
- The Material scheme maps `outline` to the audited strong token and `outlineVariant` to the
  decorative one, so Material components inherit the corrected contrast automatically.

**Affected tasks**: TUN-0003 (this slice); any later screen that uses a token or an outline.

**Owner review needed?** Yes for the palette values themselves (one token added, one existing
value rejected for meaning-bearing use); the mechanism is a build detail.

## ADR-003 - The shell ships only controls that work (2026-10-12)

**Context**: `SPEC/UX_DESIGN.md` gives Home prominent "Import Music" and "Browse Audius"
actions, and Search filter chips. None of those actions exist in phase 00: no local import, no
provider, no player. `SKILLS/compose-ux.md` forbids ornamental buttons, and the quality gates
forbid mock-only success claimed as end-to-end.

**Options**: (a) render the spec's buttons now and wire them later; (b) omit them and ship only
actions that work; (c) render them disabled with a "coming soon" label.

**Decision**: (b). The Home and Library empty states offer the one real destination that exists
today - Search - and the search field is real state (it survives rotation) rather than a mock.
The player route genuinely exists and is reachable through the mini-player, and it shows an
explicit "nothing is playing" state instead of fake transport controls. The mini-player renders
no play/pause until there is a player to command.

**Consequences**: the first-launch screen is deliberately thinner than the final spec. The
Import/Browse CTAs and the search filters land with the tasks that give them behaviour (local
playback, provider, library). This is sequencing, not a scope reduction: no P0 requirement is
dropped and the phase 07 visual gate still applies.

**Affected tasks**: TUN-0003, phases 02/03/04, phase 07 visual gate.

**Owner review needed?** No - it changes when a control appears, not what the product does.
