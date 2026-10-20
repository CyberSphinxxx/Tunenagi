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

## ADR-004 - Repository hygiene gates are project-local, self-tested, and check stored content (2026-10-13)

**Context**: TUN-0004 owed "formatting/static checks and no secrets". The kit had neither:
nothing enforced text hygiene, and nothing checked for credentials, although
`SPEC/SECURITY_LEGAL.md` requires secret scanning and `SPEC/QUALITY_GATES.md` requires a
lint/format layer and at least one negative test path.

**Options**: (a) adopt ktlint/detekt now; (b) author project-local gates with self-tests
alongside Android Lint; (c) rely on Android Lint alone.

**Decision**: (b). Android Lint covers Android/Compose correctness (`:app:lintDebug`,
`:core:lintDebug`). Two dependency-free scripts cover what it cannot see:
`SCRIPTS/check_format.py` (F1-F5 text rules) and `SCRIPTS/secret_scan.py` (credentials in
tracked content and in the whole commit history). ktlint/detekt is deferred to TUN-0005,
where it can be pinned in the version catalog and run for every commit; adopting a
formatter now would mean repository-wide auto-formatting inside a hygiene task.

**Consequences**:
- Both gates read the content git *stores*, not the working tree. A Windows checkout with
  `core.autocrlf=true` legitimately has CRLF on disk while the index is LF, so
  working-tree-based checks would fail every file and be ignored in practice. `git show
  :<path>` resolves against the repository *root*, not the current directory: getting that
  wrong made every read fail silently and looked exactly like a clean repository, so both
  scripts now prepend the checkout prefix and warn if they read zero files.
- Both gates ship a `--self-test` that proves they fail when they should: a gate that
  cannot fail is not a gate.
- `LENGTH_EXEMPT` in `check_format.py` waives the 140-character limit for four pre-existing
  compact harness scripts and the generated Gradle wrapper. **The list may only shrink**;
  adding to it changes a gate and therefore needs an independent audit.
- Findings never print the secret they matched, only a redacted prefix.
- `secret_scan.py` excludes itself, because its own pattern table would otherwise be
  reported as the thing it detects.
- These are new enforcement scripts, so `OPERATIONS/STATE_PROTOCOL.md` (self-change
  restrictions) requires them to be covered by the phase-00 independent audit rather than
  accepted on the author's word.
- Lint: `ObsoleteSdkInt` is suppressed for `mipmap-anydpi-v26` in `app/lint.xml` because the
  suggested merge breaks the build (AAPT2 cannot resolve the adaptive icon from a plain
  `anydpi` folder; reproduced with a clean build). The Gradle/AGP "newer version available"
  advisories are deliberately left visible, since ADR-001 pins versions on purpose.

**Affected tasks**: TUN-0004 (this slice), TUN-0005 (CI must run these gates), phase-00 audit.

**Owner review needed?** No for the mechanism; yes if the length waiver ever grows.

## ADR-005 - Recovered the misplaced TUN-0001 artifacts and removed the stray root STATE (2026-10-13)

**Context**: The 2026-10-11 session wrote `STATE/progress.md`,
`STATE/preflight/TUN-0001-environment.md` and `STATE/preflight/TUN-0001_preflight_test.py`
to a `STATE/` directory at the *repository root* while the kit - including
`STATE/tasks.json` and all of TUN-0001's evidence - lives in `Tunenagi-Agent-Loop/`. The
preflight document was therefore never committed, this log had no TUN-0001 entry, and the
repository carried an untracked duplicate tree at the root.

**Options**: (a) leave the stray directory and ignore it; (b) copy the artifacts into the
kit, record the entry here, and remove the root copy; (c) move the kit to the root.

**Decision**: (b). The root `STATE/` was a misplacement, not an alternative layout; the kit
is the system of record and its `STATE/` is already tracked. TUN-0001's evidence manifests
and `device-preflight.txt` are unaffected, so the verified status stands.

**Consequences**: the preflight document and its test are now versioned and re-runnable from
their canonical path (verified: the recovered test passes after the move). One over-long
`print(...)` line was re-wrapped with identical output so the new format gate could stay
uniform - the only content change. The repository root now contains only the kit checkout,
`.git` and local tool state.

**Affected tasks**: TUN-0001 (artifacts only, no status change), TUN-0004.

**Owner review needed?** No - nothing was reworded or deleted that was not also preserved.

## ADR-006 - CI is a thin mirror of one local verification entry point (2026-10-13)

**Context**: TUN-0005 owed CI build/lint/unit jobs, reproducible local commands and "no
remote source smoke spam". Three facts shaped the design: GitHub Actions cannot be executed
on this machine, so a workflow cannot be proven to work here; the kit is a *subdirectory* of
the repository (`Tunenagi-Agent-Loop/`), so every relative command is one directory away from
where Actions starts; and Android Lint only fails a build on errors, so the warning classes
fixed at TUN-0004 could silently return.

**Options**: (a) hand-write the workflow and validate it with a project-local checker;
(b) generate the workflow from a data structure; (c) adopt external CI linters (actionlint)
and accept the extra binary dependency.

**Decision**: (a). `SCRIPTS/verify.py` is the single local entry point (eight steps, one
verdict, JDK auto-discovery). `.github/workflows/ci.yml` runs the *same* commands, and
`SCRIPTS/check_ci.py` fails (rule C4) if any command in the workflow is not a `verify.py`
step — it imports the step list rather than duplicating it, so the two cannot drift. The
checker also enforces the pins (C1), the runner image (C2), single-line commands (C3), no
provider host (C5), full-history checkout for the secret scan (C6), read-only permissions
and no secrets (C7), and the working directory (C8). `SCRIPTS/check_lint.py` turns the
documented lint baseline into an enforced one.

**Consequences**:
- Action pins were verified against the projects' release pages instead of memory:
  `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`. The
  node20-era `@v4` pins are deprecated and their runtime was removed from runners
  (2026-09-23), so they are on an explicit rejected list. Tags are still movable, so SHA
  pinning remains available as later hardening.
- `runs-on: ubuntu-24.04` is pinned instead of `ubuntu-latest`, because the `latest` label
  migrates to Ubuntu 26.04 during October-November 2026 and the 24.04 x64 image already
  ships the Android SDK, so no SDK setup action is needed.
- `cache-provider: basic` is used for Gradle caching: the open-source (MIT) provider, rather
  than the proprietary enhanced cache, which suits this project's licence direction.
- CI never contacts Audius, LRCLIB or MusicBrainz and uses no secrets. Live provider smoke
  tests stay manual, rate-limited and owner-approved.
- **Residual risk, stated rather than hidden**: the first real Actions run happens on push,
  which is an owner action, so the claim is "the definition is consistent and every command
  passes locally", never "CI is green". `OPERATIONS/CI_AND_VERIFICATION.md` says so.
- A green CI proves nothing about device behaviour: TUN-0003's device evidence is still
  outstanding and TUN-0006 owns the first device record.
- `gradlew` was tracked without the executable bit (100644); fixed to 100755, otherwise
  `./gradlew` fails on Linux runners and contributors' machines alike.
- As with TUN-0004, the new gate scripts are enforcement code and need independent audit
  coverage at the phase-00 audit.

**Affected tasks**: TUN-0005 (this slice), TUN-0006 (runs the same commands on a device),
phase-00 audit, TUN-0009 (release CI).

**Owner review needed?** No for the mechanism. Yes before the first push, which is what
actually executes this pipeline.


## ADR-007 - Device evidence waits for a real runtime, and software emulation is not a substitute (2026-10-13T18:19Z)

**Context**: TUN-0003's contract requires `command` *and* `device` evidence. The device half
could not be produced: no device is attached and the emulator cannot execute a guest on this
host. The previous blocker note asserted that `-accel off` "is not a supported fallback"
without a measurement, which left genuinely open whether that path had been tried and what it
actually does.

**Options**: (a) keep retrying the emulator; (b) mark TUN-0003 verified on the command evidence
alone and note the missing device artifact in prose; (c) measure every runtime path, keep the
task `blocked`, and state the single owner action precisely; (d) substitute a JVM/Robolectric
test for the device artifact.

**Decision**: (c). `OPERATIONS/EMULATOR_RUNTIME.md` records the measurements: `-accel off` is
accepted - the hypervisor check passes, QEMU spawns, adb registers - but the guest never
executes (0.4375 s of CPU in nine minutes; no growth in three further configurations), and two
CPU/accelerator overrides crash the frontend. AEHD acceleration remains the supported path and
needs one elevation that the sandbox could not complete, twice.

**Consequences**:
- The blocker names an action the owner can take in seconds (run `silent_install_safe.bat` in
  an elevated console, or attach a device) instead of an unmeasured claim about a flag.
- (b) is rejected: `taskctl verify` exists precisely to refuse a claim whose evidence is
  missing, and weakening it here would devalue every later `verified` status.
- (d) is rejected: a JVM substitute exercises different code paths and cannot stand in for
  `device` evidence. For the Compose shell, whose subject *is* real-device behaviour, it would
  be weaker evidence wearing the same label.
- `-accel off` must never be offered as a device-test substitute in CI. A green CI run proves
  nothing about device behaviour, and this host cannot manufacture that proof in software.
- Phase 00 cannot be audited, and no later phase can start, until a runtime exists: `taskctl`
  gates each phase on the previous phase's audit, which gates on all six phase-00 tasks being
  verified.

**Affected tasks**: TUN-0003 (stays `blocked`), TUN-0006 (first device record; depends on it),
the phase-00 audit and every later phase.

**Owner review needed?** Yes - the runtime decision is the owner's, and installing a kernel
driver is a system-level change this project must not make unasked.

## ADR-008 - Owner defers on-device verification; JVM-hosted Android tests carry the load (2026-10-14)

**Context**: ADR-007 kept TUN-0003 `blocked` because its contract demanded `device` evidence and
no Android runtime exists on this host (no device attached; the emulator cannot execute a guest
without hypervisor acceleration, measured in `OPERATIONS/EMULATOR_RUNTIME.md`; the driver install
needs one UAC elevation that has been cancelled twice). Every later phase is gated behind the
phase-00 audit, which is gated behind all six phase-00 tasks being verified, so the whole
backlog - 63 remaining P0 tasks - was frozen on one prompt nobody could click.

**Options**: (a) keep everything frozen until a runtime appears; (b) mark device tasks verified
on command evidence alone and say nothing further; (c) owner-directed: continue the build
without device testing, record the deferral precisely, and substitute the strongest
*non-device* verification that genuinely exercises the same code.

**Decision**: (c), on the owner's explicit instruction ("continue until everything is finished,
no need to test in android"). Two mechanisms, discovered and implemented together:

1. `evidenceKinds` is reduced to `command` on the 38 tasks that required `device`/`live`
   artifacts. No task, dependency, deliverable or acceptance sentence is removed; what changes is
   which artifact `taskctl verify` demands.
2. The substitute is real, not rhetorical: the Compose shell suite now lives in
   `app/src/sharedTest/` and runs **twice** from one source - on the JVM under Robolectric
   (`:app:testDebugUnitTest`, no device needed) and as an instrumented test on a device
   (`:app:connectedDebugAndroidTest`). `AndroidJUnit4` is the delegating runner that makes the
   single source possible. Robolectric executes the real Android framework, resources, Compose
   runtime and navigation graph; only the device-specific surface is missing.

**Consequences**:
- The substitute immediately earned its place: the first JVM execution failed on a real defect
  the finished-but-never-run device test had never detected. BUG-0001 - the compact shell mounted
  the mini-player twice, reserving the bar's height twice and leaving two identical clickable
  nodes - is filed, fixed and closed with retest evidence. That is a user-visible layout defect
  found by *adding* verification, not by lowering a bar.
- `OPERATIONS/DEFERRED_VERIFICATION.md` is a standing register of the proof that is now missing:
  install/launch/insets/OEM/audio-focus/lockscreen behaviour, live Audius/LRCLIB/MusicBrainz
  calls, owner-held release keys, GitHub Actions execution and every device performance number.
  It states the rule later sessions must not drift from: no JVM test may stand in for a
  deliverable that says "on device" or "live", no `device-*`/`live-*` artifact may be
  fabricated, and the absence is the record.
- `SPEC/QUALITY_GATES.md`'s "if Android SDK/emulator unavailable, build cannot be certified
  complete" is left standing. This project is explicitly **not certifiable complete** while the
  register is non-empty; what the owner permitted is continuing to build and to audit the
  machine-verifiable half truthfully.
- Severity discipline is unchanged: defects are still filed with real reproductions, phase audits
  still re-run the evidence, and a Critical/High finding still invalidates audits from that phase
  onwards.
- Cost accepted knowingly: a JVM-hosted test cannot see system bars, cutouts, insets, GPU
  rasterisation, input latency, TalkBack, OEM task-killing or real codecs. Anything that depends
  on those is deferred, not verified.

**Affected tasks**: TUN-0003, TUN-0006, every task that required `device` or `live` evidence, the
phase-00 audit and all later phase gates.

**Owner review needed?** Already given, and needed again before release: the deferral must be
closed - the register emptied, or its residue explicitly accepted - before the project can claim
anything about a working app on a phone.

## ADR-009 - The schema ships in two steps so the migration path is proven with real data (2026-10-14)

**Context**: TUN-0102 owed the library tables and TUN-0103 owed "explicit Room migration, no
destructive fallbacks, migrate seed old-schema database without user data loss". Writing one
version-1 schema containing everything and then inventing a version 2 afterwards would have made
the migration test circular: it would prove that a migration from the schema it just wrote to the
schema it just wrote works.

**Options**: (a) one version-1 schema with every table, and a migration test that only checks the
current schema; (b) two versions where version 1 is the library and version 2 adds playback
persistence, so the first migration moves *real* user data (playlists with duplicated tracks,
favourites, lyrics, a plugin, an unavailable source) forward; (c) wait until phase 02 has a real
schema change to write any migration at all.

**Decision**: (b). Version 1 (TUN-0102) is the library: tracks, artists, albums, credits, sources,
playlists, entries, favourites, lyrics, plugins, metadata matches. Version 2 (TUN-0103) adds the
playback tables the queue and history repositories need. The split is inside phase 01, so the phase
still delivers every table its contract lists; what moves is which task writes which table, and
TUN-0103's notes and the phase audit both say so.

**Consequences**:
- The "old database" in the migration test is rebuilt from Room's own exported DDL for version 1
  (`core/schemas/.../1.json`, committed), with Room's `${TABLE_NAME}` placeholder substituted. It
  therefore has exactly the schema a real installation had, including `user_version = 1`, and it is
  seeded with data before the production open path is used.
- Room validates the result of a migration against the entities on every open, so a migration that
  produces the wrong schema throws `Migration didn't properly handle ...`. A test proves that by
  registering a deliberately incomplete migration and asserting the open fails *and* that the user's
  rows are still on disk afterwards.
- A second test proves the opposite direction: with no migration registered at all, Room refuses to
  open the database and the seeded rows survive — the difference between "the app failed to start"
  and "the app deleted someone's library to start".
- `MIGRATION_1_2` adds indices for the two foreign key columns as well, because Room warns (rightly)
  that an unindexed child column turns every parent write into a table scan. The warning is fixed,
  not suppressed.
- SQLite's NULL-in-a-unique-index behaviour (ADR-worthy because it is a trap, recorded here rather
  than in a comment): `track_sources.sourceVariant` stores `""` for "no variant", because a nullable
  column would have let the same provider row be imported twice while the identity index reported no
  conflict. The domain model keeps `String?`; only the column is normalised.

**Affected tasks**: TUN-0102 (version 1), TUN-0103 (version 2 and the migration harness), TUN-0105
and TUN-0106 (the repositories that use the new tables), every later phase that changes the schema
(the harness is already proven).

**Owner review needed?** No: no product requirement changed, and the phase-01 audit reviews the
split explicitly.

## ADR-010 - Machine-written files are excluded by path from the text gate (2026-10-14)

**Context**: Room exports its schema JSON into `core/schemas/` on every build, and it does not end
those files with a newline. `SCRIPTS/check_format.py` rule F3 therefore failed on both committed
schema files. The alternatives were to hand-edit generated files (the edit is lost on the next
build, and a schema that a person edited is a schema nobody can trust) or to weaken a rule.

**Options**: (a) edit the two JSON files by hand; (b) relax F3 for all JSON; (c) exclude a named
directory of generated output from the gate, by path, and keep every hand-written file under the
full rule set.

**Decision**: (c). `check_format.py` gains `GENERATED_DIRS = ("core/schemas/",)`. F3 still applies
to every JSON, XML, Markdown, Gradle, Kotlin, Python and shell file a person writes; only Room's
export directory is skipped, and the reason is written in the script and audited at the phase gate.

**Consequences**:
- The alternative in (a) would have produced a gate that fails again after the next build, which is
  how a gate gets ignored. (b) would have disabled a real rule for the backup format and every other
  JSON file in the project.
- This is a change to enforcement code, so `OPERATIONS/STATE_PROTOCOL.md` requires independent audit
  coverage: the phase-01 audit re-ran the gate's `--self-test` after the change and recorded that it
  still fails on tabs, trailing whitespace, a missing final newline, CRLF and over-long lines.

**Affected tasks**: TUN-0004 (the gate's owner), TUN-0103 (the schema export that exposed this),
every future schema version, the phase-01 audit.

**Owner review needed?** No: no product behaviour and no hand-written file's rule changed.

## ADR-011 - A single player is enforced by a gate, not by a comment (2026-10-14)

**Context**: `SPEC/ARCHITECTURE.md` requires one service that owns the sole Media3 player, and
`PHASES/02-local-media-and-robust-playback.md` names "player in the Activity" as one of the traps a
phase-02 audit must look for. Nothing in the project could fail a build when it happened: the rule
lived in prose, and a `ExoPlayer.Builder(context).build()` inside a composable or an Activity would
compile, pass every test, and produce two players competing for the audio output on a device.

**Options**: (a) rely on review and the audit; (b) restrict `:core`'s Media3 dependency to
`implementation` so the app cannot name a player (it cannot import `ExoPlayer` either way, and this
already happens for `media3-exoplayer`); (c) add rule **B10** to the existing dependency-direction
gate: only `com.tunenagi.core.playback` may import `androidx.media3.exoplayer` or construct an
`ExoPlayer`/`MediaSession`/`MediaLibrarySession` builder.

**Decision**: (c), together with (b), which was already true. B10 scans **production sources only**
(`src/main`): a *test* may drive a real player, which TUN-0201's `NowPlayingTest` deliberately does,
because the claim it checks is that the app renders the player's own state and a fake player could
not check it. `MediaController` is not restricted — attaching to the session is exactly how the UI
is supposed to reach playback.

**Consequences**:
- The rule catches the fully-qualified case (`MediaSession.Builder(...)` with no import) as well as
  the import, so "just don't import it" is not a way around the gate.
- `check_boundaries.py --self-test` gained two positive cases (an `ExoPlayer` import in the UI, a
  session built in `core.library`) and one negative case (a `MediaController` in the UI must be
  accepted), so B10 cannot pass by rejecting everything.
- `OPERATIONS/DEPENDENCY_GRAPH.md` gained the B10 row in the same change: B9 fails the gate if the
  document and the script disagree, which is what keeps this decision attached to the rule.
- The app module keeps `media3-exoplayer` as a *test*-only dependency. That is what makes B10 a
  meaningful rule rather than a coincidence of dependency resolution.

**Affected tasks**: TUN-0201 (the player and the rule), TUN-0107 (the gate's owner), the phase-02
audit, and every later task that touches playback.

**Owner review needed?** No: no product behaviour changed, and the rule only removes a way to
violate an existing architectural requirement.

## ADR-012 - A provider layer of its own, and the sub-package hole the gate had (2026-10-15)

**Context**: TUN-0202 needs to read MediaStore and persistable SAF grants, which is Android API
access - something `:core` deliberately does almost nowhere. Two things had to be decided: where
that code lives, and what stops it from reaching the player, the plugin sandbox or an HTTP client.
While adding the rule, the gate itself turned out to have a hole: `check_boundaries.py` looked a
file's layer up by *exact* package name, so `com.tunenagi.core.model.queue` (a sub-package of a
layer that has a rule) was covered by nothing, and the new `...core.provider.local` would have been
covered by nothing either.

**Options**: (a) put discovery in `core.library`, which already has a rule - rejected because that
layer is repositories, matching and playlists, and `SPEC/ARCHITECTURE.md` names `provider/local` as
its own adapter; (b) leave the gate alone and rely on review; (c) add the layer with a rule, and
make rule lookup prefix-based so a sub-package cannot escape a layer's rule.

**Decision**: (c). `com.tunenagi.core.provider` may import `core.model`, `core.database` and
`core.library`, and may not import Compose, `core.playback`, `core.plugins` or the networking
libraries - Android itself is *allowed* there, because reading a provider is exactly what an
adapter is for, and forbidding it would only push the same code into a layer the gate was not
written for. Rule lookup is now the longest prefix match, so `core.model.queue` is checked by B1
and `core.provider.local` by B11.

**Consequences**:
- B11 is in the graph document (B9 keeps the two in step) and in the self-test in both directions: a
  provider adapter importing playback must fail, and one importing MediaStore plus a model type
  must pass.
- A second self-test case proves the prefix fix (a `core.model.queue` file importing Android must
  raise B1), so the hole cannot come back silently.
- The allowed set is deliberately *not* widened for streaming providers yet: a future
  `provider.audius` will need `core.network` as well, and that is a decision to make in its own ADR
  rather than to guess now.
- The gate change strengthens rather than weakens the check, which `OPERATIONS/STATE_PROTOCOL.md`
  requires to be audited: it is part of the phase-02 audit's scope, together with ADR-011's B10.

**Affected tasks**: TUN-0202 (the layer), TUN-0107 (the gate), the phase-02 audit, and every later
provider task.

**Owner review needed?** No: an architectural rule was made enforceable, and no product behaviour
or acceptance criterion changed.

## ADR-013 - The platform-created service is handed its resolution, because the app owns the providers (2026-10-16)

**Context**: TUN-0203 makes playback resolve a real library item: the session turns a media id into a
`TrackSource`, asks a `PlaybackSourceResolver` for a fresh reference, and hands the player an item it
can open. Two dependency rules decide who can build that object. B7 forbids `:app` from importing
`core.database`, so the app cannot assemble a database-backed resolution; B11 forbids a provider
adapter from importing `core.playback`, so `core.playback` cannot construct the local resolver
either. The only place with both is a Hilt module inside `core.playback` - and the service that
needs the result is created by the *operating system*, so there is no constructor at which Hilt can
hand it anything.

**Options**: (a) annotate the service `@AndroidEntryPoint` and inject the resolution into it;
(b) declare an app-side subclass of the service in the app manifest, replacing the library's
declaration, and inject there; (c) provide the resolution from `core.playback`'s Hilt module exactly
as the app's graph would, and have `TunenagiApp.onCreate` install it into one named holder
(`PlaybackResolution`) that the service reads when it builds its session.

**Decision**: (c).

**Consequences**:
- Hilt still owns every binding: the database comes from `core.database`'s own module, the local
  resolver from the provider adapter's `@IntoSet` module (`LocalProviderModule`), and the resolution
  from `core.playback`'s `PlaybackModule`. The holder carries one value and has one default.
- (a) is rejected because Hilt's service injection makes the *generated*
  `Hilt_TunenagiPlaybackService` the component the platform starts: the merged manifest then names a
  class that exists only in build output, which takes TUN-0201's manifest assertions (declared
  service, foreground-service type, media-library action) with it, and it would require a
  Robolectric+Hilt test application this project does not have.
- (b) is rejected as a larger change for the same goal: it moves the service declaration, the
  session token and the manifest test into the app, rewriting verified TUN-0201 artifacts to gain
  nothing (c) does not already provide.
- The default is a real behaviour rather than a stub: with nothing installed, a session still plays
  an item that carries its own uri and refuses everything else with a typed error, so a host that
  forgets to install gets visible failures instead of a wrong track.
- The install is read once, when a service is built, so one session cannot resolve the same media id
  two different ways in the middle of a queue.
- The cost is stated, not hidden: a process-wide holder is a global, and its one invariant
  ("installed before the first service") is guaranteed by where it is called (an `Application`) and
  checked by a test that installs a resolution and asserts the service's session uses it.

**Affected tasks**: TUN-0203 (the resolution and the install point), TUN-0204 and TUN-0205 (the queue
and state restoration need the same service-side graph), TUN-0207 (typed failures), the phase-02
audit.

**Owner review needed?** No: no product behaviour is decided beyond "a library item resolves through
the app's own graph", and ADR-008's missing runtime is why the two injection paths were rejected.

## ADR-014 - A local file's bytes decide its format, and the playable matrix is explicit (2026-10-16)

**Context**: TUN-0203 must "resolve actual local MP3/FLAC and unsupported media via typed errors".
Three sources of evidence exist for what a file is: the content type a scan recorded (MediaStore's
`MIME_TYPE`, or a SAF provider's guess from the file name), the file extension, and the file's own
first bytes. They disagree exactly when it matters - a tagged FLAC, a media folder full of WMA, a
container written by a tool that mislabelled it.

**Options**: (a) trust the declared content type and hand the uri to Media3; (b) read the header,
map it to a container, and decide playability from an explicit set; (c) read the header only to
explain a decoder failure after the fact.

**Decision**: (b). `LocalAudioSniffer` recognises MP3 (an ID3 tag or a MPEG frame sync), FLAC, the
`ftyp`-based MP4/M4A container, Ogg, WAV and AAC in ADTS, and - deliberately - AIFF, WMA/ASF and
MIDI, which this app will not play. `LocalAudioContainer.PLAYABLE` names the six containers the app
ships with; anything outside it is `ProviderError.UnsupportedFormat`. The declared content type is
used for exactly one thing: a video brand in an `ftyp` box is not treated as the user's music.

**Consequences**:
- A file whose bytes say WMA fails at resolve time with a typed error instead of failing inside a
  decoder, so `SPEC/AUDIO_ENGINE.md`'s ban on silently transcoding unsupported media holds by
  construction rather than by review.
- The format handed to the player - and written back to the source's `contentType` - is the sniffed
  one, so a wrong scan label is corrected by playback instead of propagated.
- The matrix is an app-level declaration, not a device measurement: `SPEC/AUDIO_ENGINE.md` says local
  support follows the platform decoders, and whether a given device's decoders accept a container is
  unverifiable here (ADR-008). The deferral is recorded in `OPERATIONS/DEFERRED_VERIFICATION.md`, and
  a header check is never described as a decode test.
- Sixteen bytes are read per resolve, and that bound is part of the contract (`HEADER_BYTES`) and is
  asserted, so a later change cannot quietly turn resolution into a full file read.
- Bytes that only *look* like a container (an `ftyp` video brand, `RIFF` without `WAVE`, a header too
  short to hold the signature) are reported as unknown rather than guessed at.

**Affected tasks**: TUN-0203 (the sniffer and the resolver), TUN-0207 (buffering/error states map
from these typed errors), TUN-0307 (the source inspector shows the format this learned), TUN-0505
(embedded artwork and tag reading, which this decision deliberately leaves out), the phase-02 audit.

**Owner review needed?** No: the six playable containers are the formats the platform has covered
since the project's minimum API, and the three excluded ones are excluded because none of them is
guaranteed across that range.

## ADR-016 - The app starts a queue it has already stored (2026-10-20)

**Context**: the phase-02 audit found BUG-0004: `SafTreeAudioDiscovery`, `LocalLibraryIndexer` and
`PlaybackQueueCommands.play` were implemented and tested, but nothing in the app requested a grant,
ran a scan or started a queue. Local playback was unreachable, so the phase's outcome could not be
demonstrated by a user even in principle. ADR-003 had said the Import action would land "with the
tasks that give them behaviour (local playback...)", which is this phase.

**Options**: (a) add a custom Media3 session command (`PLAY_TRACKS`) that the session applies to its
own player and queue, advertising it through `onConnect`; (b) have the app persist the queue through
the Hilt-provided `QueueRepository` and then command its `MediaController` to `setMediaItems` the same
track ids, `prepare()` and `play()`, letting the session's existing `onAddMediaItems` resolve them;
(c) wait for the phase-04 library UI and leave playback unreachable until then.

**Decision**: (b).

**Consequences**:
- There is one order, written in one place: **persist the queue, then tell the session to play**. If
  the second step fails, the stored queue is the authority the next command re-applies and a relaunch
  still shows the import; the reverse order would play music that disappears at the next restart.
- The app must inject `QueueRepository`. That is a second writer of the queue besides
  `PlaybackQueueCommands`. It is bounded: the import is the only app-side write, it happens before
  any controller command, and a mid-command interleaving can only leave the store ahead of the
  player, which `reapply`/`restore` already repair by design.
- (a) was rejected because Media3 only invokes `onCustomCommand` for commands the session advertises
  in `onConnect`, and the handler would have to marshal player work onto the session's application
  thread — more machinery, and a second command protocol to keep in sync, for one action.
- (c) was rejected because it leaves a P0 phase outcome unmet and the missing piece is the import
  entry point, not the library screen.
- The grant is taken in the Activity (`OpenDocumentTree` + `takePersistableUriPermission`), because
  only an Activity can; the scan and the indexing stay in `:core` behind the `LocalFolderImporter`
  domain interface, so the app never imports `core.provider` or `core.database`.
- MediaStore discovery remains unwired: it needs a runtime permission and a library screen to explain
  it, which is phase 04. The user-visible path here is the folder they pick, which is the narrower
  and more explicit consent.

**Affected tasks**: TUN-0202 (the missing half), BUG-0004 (closed by it), TUN-0703 (the rest of the
queue UI), phase 04 (MediaStore and the library surface).

**Owner review needed?** No: it implements an action ADR-003 already promised for this phase, and no
product requirement or acceptance criterion changed.

## ADR-017 - Audius is read with the documented identity, and a missing key is not a workaround (2026-10-20)

**Context**: TUN-0301 needs the documented search/trending contract, and `SPEC/PROVIDERS.md`
requires re-reading the current docs rather than assuming an older blog post. The check on
2026-10-20 found: the canonical root is `https://api.audius.co` (the old
`discoveryprovider.audius.co` host is deprecated); search is `GET /v1/tracks/search` with `query`,
`limit`, `offset`; trending is `GET /v1/tracks/trending`; the current official search example sends
an `x-api-key` header and describes the read-only key as safe for client use; `app_name` is the
documented client identification used by examples; the official SDK plans page lists 10 req/s and
500k requests/month on the free plan. No Audius credential exists in this repository, and AGENTS.md
forbids inventing one.

**Options**: (a) send only `app_name` and assume anonymous reads will always work; (b) require an
API key before shipping discovery, blocking TUN-0301 until the owner supplies one; (c) build the
client to send the documented identity it has (User-Agent + `app_name`), accept an optionally
injected read-only key, and surface a refusal as `Unauthorized` instead of silently finding another
route.

**Decision**: (c). The key is a constructor parameter with a null default, never a `BuildConfig`
value and never committed. The client pins the canonical HTTPS host, refuses a non-HTTPS base at
construction, sends no undocumented parameter (`time`, `sort`, host-discovery rotation), and follows
only HTTPS redirects to an explicitly allowed host. Pagination is offset-based with a page size
bounded at 100; a full page offers `nextOffset`, a short or empty page ends paging.

**Consequences**:
- An anonymous refusal is an honest state the UI can show (TUN-0305), not a reason to scrape, rotate
  hosts or guess a different endpoint.
- The live smoke (TUN-0306) and any real user discovery may need an owner-supplied key. That is an
  external dependency recorded before it bites, not discovered during the smoke.
- The contract tests in `AudiusApiTest` use fixtures written from the documented shape, explicitly
  not captured live, because CI must not call the provider. `OkHttpTransportTest` uses a real
  `MockWebServer` for the HTTP behaviour. Neither is live proof.
- `Retry-After` is parsed in the transport (seconds or HTTP-date) and mapped by the taxonomy;
  bounded retry policy remains TUN-0304's subject.
- The DTO keeps provider fields (nullable title, seconds) and does not normalize; TUN-0302 owns the
  mapping and the entitlement decision.

**Affected tasks**: TUN-0301 (this slice), TUN-0302, TUN-0303, TUN-0304, TUN-0305, TUN-0306.

**Owner review needed?** Yes before any live discovery/streaming claim: an Audius read key may have
to be provided, and the key must be supplied through the build environment or a local untracked
file, never committed.

## ADR-018 - A stream URL is asked for as JSON, checked, and never stored (2026-10-21)

**Context**: TUN-0303 must play a public Audius track with late URL resolution, expiry handling and
no bypass. Re-reading the provider's OpenAPI document on 2026-10-21 (`https://api.audius.co/v1/swagger.yaml`)
found `GET /tracks/{track_id}/stream` (server root `/v1`) with a `no_redirect` parameter documented
as "If true will not return a 302 and instead will return the stream url in JSON", a `200` schema
`stream_url_response = { data: string }`, and a security list of `[{}, OAuth2 read]` where the empty
requirement means a public track needs no credential. The protocol's gate documentation says a track
with `access_authorities` answers `401` to an unsigned request. The document promises no stream-URL
lifetime.

**Options**: (a) call the endpoint, follow its `302` to the content node and hand whatever final URL
arrives to the player; (b) ask for JSON with `no_redirect=true`, validate the returned URL against
the app's transport policy, and hand the player that URL; (c) request a `preview` or a download URL
so the audio is fetched by the app instead of streamed.

**Decision**: (b). The resolver sends exactly `app_name` and `no_redirect=true`, accepts only
`{data: <url>}`, and refuses anything that is not HTTPS (plain HTTP only to the loopback hosts in
the production transport's closed list, and only under the test flag) or that has no trustworthy
host. It never sends a signature, an access token, `preview` or `skip_play_count`, and never tries a
second endpoint if the first refuses.

**Consequences**:
- A redirect is a typed failure, not a detour: the app, not an HTTP hop, decides which host the
  player opens, and the transport's audio-body budget is never hit by a `200` that turns out to be
  an MP3.
- `ProviderError` keeps its distinctions (`404` NotFound, `401` Unauthorized, `403` Restricted, `429`
  Throttled with `Retry-After`, unreadable body MalformedPayload). Bounded retry of the transient
  ones remains TUN-0304's subject; the resolver makes one attempt.
- The returned reference carries `expiresAt = now + 10 minutes` as **this app's** re-resolution
  budget, not as a provider fact, because the API documents no expiry. Nothing stores the URL: a
  media id names a source, and the stream is resolved again on the next playback.
- `mimeType` stays null. The bytes are never fetched, so claiming `audio/mpeg` from the endpoint's
  summary would be a format claim the app cannot back; Media3 sniffs progressive audio itself.
- A track that is `is_streamable` but gated by `access_authorities` is not visible as gated in the
  discovery DTOs (TUN-0302's mapping never read `is_stream_gated`), so it fails at resolve with the
  provider's `401` rather than being pre-marked unplayable. The failure is honest but late; surfacing
  it earlier is a follow-up for TUN-0305/TUN-0307, not a silent bypass.

**Affected tasks**: TUN-0303 (this slice), TUN-0304, TUN-0305, TUN-0306, TUN-0307.

**Owner review needed?** No new decision for the owner: no credential is required or invented. The
live smoke (TUN-0306) still needs a real device and a currently permitted public track.
