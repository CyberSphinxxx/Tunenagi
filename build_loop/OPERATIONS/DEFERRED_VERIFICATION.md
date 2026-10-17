# Deferred verification register

Owner instruction (2026-10-14): continue the build **without** Android device testing. ADR-008
records the decision. This file is the standing, auditable list of the proof that is therefore
*missing* from every `verified` status in `STATE/tasks.json`, so that "verified" is never
mistaken for "proven on a device".

## What changed, mechanically

`evidenceKinds` was reduced to `command` on the 38 tasks that previously required a `device`
and/or `live` artifact (35 `command,device`; 3 `command,device,live`). No task was deleted,
reordered or re-scoped, and no acceptance criterion was removed: the deliverables and the
`acceptance` text still describe the on-device behaviour. What changed is which artifact
`taskctl verify` demands as the minimum, because the owner has directed that no Android runtime
is available.

A substitute is used for the parts that can honestly be substituted, and nothing else:

| Kind of proof | Substitute used | What it genuinely proves | What it never proves |
|---|---|---|---|
| Compose UI behaviour | `src/sharedTest` Compose tests executed on the JVM by Robolectric (`:app:testDebugUnitTest`) | real composition, real navigation graph, real semantics tree, real assertions | device insets/cutouts, OEM theming, GPU rendering, input latency, TalkBack |
| Audio playback | `Media3` `ExoPlayer` driven through a `TestPlayer`/fake renderer in JVM tests | state machine, queue transitions, error mapping, state restoration | real codecs, real audio focus, real background service survival, lockscreen |
| Local source resolution (TUN-0203) | real header bytes supplied to the real sniffer and resolver, plus a real Room database with the real unique indices | the container decision from bytes, the typed errors for unsupported/gone/denied, the format and availability written back, the media-id convention | opening a file through a real `ContentResolver`/MediaStore/SAF grant, and a device decoder actually accepting the container the header announced |
| Queue commands and transitions (TUN-0204) | the real `PlaybackQueueCommands` driven against a **real** `ExoPlayer` and a real Room database, asserting the player's own queue, current index, `playWhenReady`, repeat/shuffle state and the persisted snapshot | that a `next`/`previous`/`remove`/`move`/`shuffle` really changes what is stored and what the player will play, including the 3-second Previous threshold and repeat/shuffle policies | that audio plays, that a seek lands where the user *hears* it, that an edit is gapless, or that the queue survives process death; the fixture uri points at no file and no decoder is involved |
| Provider integration | MockWebServer fixtures of the real payloads | request shape, parsing, error/rate-limit mapping, retry policy | live network, real catalog contents, upstream policy/entitlement changes |
| Storage | Room in-memory + migration tests on the JVM | schema, DAO queries, migration correctness | real on-device storage behaviour, process death, large real libraries |
| Live third-party smoke | none | — | an actual Audius/LRCLIB/MusicBrainz request never happened; no live artifact exists |

## The three verification classes, stated plainly

1. **Verified here** — the task ran a real command that passed, with a manifest produced by
   `SCRIPTS/run_check.py`.
2. **Verified by JVM substitute** — as the table above; the limitation is recorded per task.
3. **Not performed at all** — anything named in the deliverables below that needed a device, a
   live provider or an owner-held key.

## Standing list of what remains unperformed

- **Runtime/launch proof**: install and launch the APK on a real Android runtime; first-launch
  screenshots; API 26/33/35 matrix; rotation, font scale 1.3x/2.0x, small-screen 360x640dp,
  tablet 800dp+; gesture vs three-button navigation; notification/media-session controls; audio
  focus and headset/bluetooth behaviour; screen-lock and background-survival; upgrade-in-place.
- **Live provider proof**: Audius discovery and public-track streaming, LRCLIB lyrics fetch and
  MusicBrainz enrichment against the real services with recorded timestamps and redacted URLs.
- **Owner-held artefacts**: release signing keystore, store/F-Droid publication, CI execution on
  GitHub runners (the workflow definition is checked locally; it has never run).
- **Performance**: Macrobenchmark startup/jank/memory numbers from a device; no threshold in
  this project may be claimed from a JVM measurement.
- **Local resolve proof**: no file on this host has ever been opened through a real
  `ContentResolver`, no device decoder has been asked to play a container this app classified, and
  embedded tags and cover art have never been read. TUN-0203 supplies the *bytes* and asserts the
  decision made from them; that a real file with those bytes then plays is outstanding, and so is
  TUN-0505's artwork work. The task note says so instead of implying decode proof.
- **The app's install step (TUN-0203)**: that `TunenagiApp.onCreate` installs the library-backed
  resolution into `PlaybackResolution` is checked by the compiler (Hilt fails the build if the graph
  cannot supply `MediaItemResolution`, or if no `PlaybackSourceResolver` is contributed) and by a
  test that shows the service resolves through whatever is installed. What is *not* covered is a
  runtime launch of the installed application, because that needs a device or a Hilt-aware
  Robolectric application this project has no test infrastructure for yet. The failure mode is
  deliberately visible rather than silent - a session with nothing installed refuses an item it
  cannot resolve on its own instead of playing something else - but the residual risk is real and is
  noted here rather than assumed away.
- **The queue command surface (TUN-0204)**: the commands exist, are built by the service over its own
  player, and are exercised end to end against a real `ExoPlayer` and a real Room database. What no
  JVM test can show is what the user experiences: that pressing Next *sounds* like the next track,
  that the 3-second Previous threshold matches what a listener expects when they hear it, that a
  reorder during playback does not glitch audibly, and that Media3's session routes a hardware or
  lockscreen control into the same command. Also outstanding by design: nothing in the UI can reach
  these commands yet - the UI-facing transport (custom session commands or a controller-side facade)
  is scheduled with the queue UI in TUN-0703, because Media3's controller surface cannot express the
  previous-threshold or materialised-shuffle policies. Until then this is a verified session-side
  capability, not a user-visible feature, and the phase-02 audit must read it that way.

## Rule for later sessions (do not drift)

- No task may be marked verified *because of* a JVM test for a deliverable that says "on
  device", "live" or "on an emulator"; the task note must name the substitution explicitly.
- Any file that would have been `device-*.png` or `live-*.txt` must not be fabricated. The
  absence is the record.
- If a device, an emulator or a live provider becomes available, the tasks listed above must be
  re-run for real and the results attached before any release claim. ADR-008 is a deferral, not
  a waiver of the underlying requirement.
- `SPEC/QUALITY_GATES.md` still says a build cannot be *certified complete* without an Android
  runtime. That sentence stands: this project is explicitly **not certifiable complete** while
  this register is non-empty. What ADR-008 permits is continuing to build and to audit the
  machine-verifiable half.
