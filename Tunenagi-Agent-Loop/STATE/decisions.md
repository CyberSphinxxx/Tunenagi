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
