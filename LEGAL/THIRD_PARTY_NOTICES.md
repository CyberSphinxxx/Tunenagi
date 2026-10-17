# Third-party notices and dependency licence plan

**Task:** TUN-0004. **Status:** inventory current as of `gradle/libs.versions.toml` @
Compose BOM 2024.10.01; the release-time refresh is described at the end.

`SPEC/SECURITY_LEGAL.md` requires that "license compatibility of every dependency" is
checked, and the proposed GPL-3.0-or-later app licence (`LICENSE_PROPOSAL.md`) depends on
every bundled dependency being compatible with it. This file records what is actually
linked into the debug APK, what each artefact is licensed under, and how the list is kept
honest instead of guessed.

## Inventory — runtime (bundled into the APK)

Extracted from the resolved `debugRuntimeClasspath`, not from the version catalog alone,
because transitive resolution is what actually ships.

| Artefact | Version | Licence | Used for |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | Android/Kotlin extensions |
| `androidx.activity:activity-compose` | 1.9.3 | Apache-2.0 | Compose host activity |
| `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | 2.8.6 | Apache-2.0 | Lifecycle-aware state holders |
| `androidx.navigation:navigation-compose` | 2.8.5 | Apache-2.0 | Tab + player routing |
| `androidx.compose:compose-bom` (ui 1.7.5, material3 1.3.1, material-icons-core 1.7.5, tooling/preview) | 2024.10.01 | Apache-2.0 | UI toolkit and design system |
| `androidx.media3:media3-exoplayer` | 1.4.1 | Apache-2.0 | Audio playback (phase 02) |
| `androidx.room:room-runtime`, `room-ktx` | 2.6.1 | Apache-2.0 | Local persistence (phase 01) |
| `androidx.hilt:hilt-navigation-compose` | 1.2.0 | Apache-2.0 | ViewModel injection in routes |
| `com.google.dagger:hilt-android` | 2.51.1 | Apache-2.0 | Dependency injection |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 | HTTP for providers |
| `com.squareup.retrofit2:retrofit` | 2.11.0 | Apache-2.0 | Provider API clients |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.20 | Apache-2.0 | Kotlin runtime |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | Apache-2.0 | Concurrency |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.6.3 | Apache-2.0 | Manifest/JSON models |

Transitives pulled in by the above (okio, androidx.annotation/collection, kotlinx
serialization runtime, media3-common, etc.) are all Apache-2.0 except `okio`, which is also
Apache-2.0. No LGPL, CDDL, EPL or proprietary artefact appears on the runtime classpath;
if one ever does, it must be reviewed before merge and recorded here.

## Test-only and build-time (not bundled)

| Artefact | Version | Licence | Scope |
|---|---|---|---|
| `junit:junit` | 4.13.2 | **EPL-1.0** | JVM unit tests only — never shipped, no obligation on the APK |
| `androidx.test.ext:junit`, `androidx.espresso:espresso-core`, `androidx.compose.ui:ui-test-junit4` | 1.2.1 / 3.6.1 / 1.7.5 | Apache-2.0 | On-device tests only |
| Android Gradle Plugin, Kotlin Gradle plugin, KSP | 8.7.3 / 2.0.20 / 2.0.20-1.0.25 | Apache-2.0 | Build only |
| Gradle wrapper | 8.11.1 | Apache-2.0 | Build only |
| Eclipse Temurin JDK | 21.0.12.1 | GPLv2 with the Classpath Exception | Build only |
| Android SDK platform 35 / build-tools 35.0.0 | — | Android SDK Terms of Service | Build only |

## Content assets — the real gap

No audio, artwork, fonts or icon artwork are bundled yet. The launcher icon is an original
placeholder drawn in `app/src/main/res/drawable/ic_launcher_foreground.xml`. Before any
release:

- brand artwork and the wordmark must be original work (or licensed in writing);
- any bundled font must be redistributable (OFL/Apache) with its own notice;
- no third-party album art is bundled — artwork is fetched at runtime and cached, which is
  a provider-terms question, not a code-licence one (`SPEC/SECURITY_LEGAL.md`);
- no provider content is embedded in the application.

## How this list is kept honest

1. Regenerate the inventory from the resolved classpath, never by hand:

       JAVA_HOME=<jdk-21> bash gradlew :app:dependencies --configuration debugRuntimeClasspath

   The captured run for this revision is a `TUN-0004` evidence manifest under
   `STATE/evidence/TUN-0004/`.
2. Diff the artefact list against this table whenever `gradle/libs.versions.toml` changes;
   a new artefact with no row here is an unfinished review, not a formality.
3. At release time (phase 09) generate the aggregate notice text from the final resolved
   list and ship it in the APK and beside the artefact, together with the `LICENSE` file
   once the licence proposal is approved.
4. Provider *services* (Audius, MusicBrainz, LRCLIB) are governed by their own terms, which
   are recorded in `STATE/preflight/TUN-0001-environment.md` and
   `SPEC/SECURITY_LEGAL.md`; they are not code dependencies and their rows do not belong
   in this table.
