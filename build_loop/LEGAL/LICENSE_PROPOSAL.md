# Licence proposal — Tunenagi app and plugin SDK

**Status: PROPOSAL — not in force.** No `LICENSE` file is committed yet, so the project is
currently "all rights reserved by default", which is not the intent. This document is the
decision material for the owner; committing the licence text is an owner action
(`OPERATIONS/OWNER_REQUIRED.md`: legal risk and distribution decisions are not delegated to
the agent).

Source of the requirement: `SPEC/SECURITY_LEGAL.md` — *"app GPL-3.0-or-later **proposal**,
SDK Apache-2.0 **proposal**"*.

## Proposal

| Component | Proposed licence | Rationale |
|---|---|---|
| Tunenagi app (`app/`, `core/`, UI, playback) | **GPL-3.0-or-later** | Matches the ecosystem it borrows from (mpv/VLC lineage, most open-source Android players) and guarantees that improvements to a music player people install on their own devices stay available to them. |
| Plugin SDK / sample plugins (`plugin-sdk/`, `sample-plugins/`, the declarative manifest schema) | **Apache-2.0** | A copyleft SDK would force licence choices on third-party plugin authors and make compliance advice impossible to give; Apache-2.0 also carries an explicit patent grant. |
| Documentation (`README.md`, `SPEC/`, `OPERATIONS/`) | **GPL-3.0-or-later** unless the owner prefers CC-BY-4.0 | Keeps one licence stream to reason about. |

Apache-2.0 → GPL-3.0 is the compatible direction: Apache-2.0 code may be combined into a
GPL-3.0 work, so an Apache-2.0 SDK and an Apache-2.0 dependency set are fine inside the
GPL-3.0-or-later app. The reverse (taking GPL code into an Apache-2.0 app) is not, which is
why the app licence is the one that has to be chosen first.

## Obligations this creates (and what they cost)

1. **Corresponding source for every distributed binary.** Each release artefact must be
   accompanied by the exact source: a public repository plus the tagged commit. The build
   must therefore stay reproducible enough to rebuild from a tag.
2. **Verbatim licence text and notices.** `LICENSE` (the complete, unmodified GPL-3.0 text
   from https://www.gnu.org/licenses/gpl-3.0.txt — never a paraphrase), retained copyright
   headers in source files, and the third-party notices in
   [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
3. **State your changes.** Modified files must be marked as changed and dated when
   distributed; this is why source files carry an "owned" marker today.
4. **No additional restrictions.** The licence may not be narrowed by terms of service,
   accounts, or an app store agreement; a store listing that conflicts is a blocker, not a
   detail (relevant to the "GitHub Releases first" decision in `SPEC/SECURITY_LEGAL.md`).
5. **Installation information for "User Products" (GPL-3.0 §6).** If Tunenagi is ever sold
   as a physical product with a locked bootloader, the corresponding installation
   information must be supplied. Distributing only free APKs on GitHub sidesteps this, but
   it must be re-checked before any commercial distribution.
6. **Dependency licence review.** Every dependency must be compatible; the inventory and
   the plan for maintaining it are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
   This matters most for Media3/ExoPlayer (Apache-2.0) and for any future artwork, fonts or
   audio used in releases — placeholder assets are currently used, and brand artwork is not
   yet licensed for distribution.

## What is *not* being proposed

- No licence change to the provider content: Audius terms, the Open Music License, LRCLIB
  and MusicBrainz terms are constraints on *use*, not on the code licence, and are tracked
  in `STATE/preflight/TUN-0001-environment.md` and `SPEC/SECURITY_LEGAL.md`.
- No promise that GPL-3.0-or-later is acceptable to every future store. F-Droid is
  compatible; any store whose terms conflict with point 4 above is not, and that check is
  part of the release gate.

## If the owner approves

1. Add `LICENSE` with the unmodified GPL-3.0 text (and `plugin-sdk/LICENSE` with
   Apache-2.0) — the exact bytes, not a summary.
2. Add the licence section to `README.md` and an SPDX identifier to new source files.
3. Record the decision as an ADR in `STATE/decisions.md`, replacing this proposal's status
   line.
4. Re-run the dependency licence review at release time
   (`THIRD_PARTY_NOTICES.md` documents how).

**Owner decision required:** approve GPL-3.0-or-later for the app and Apache-2.0 for the
SDK, or name different licences. Until then, no release may be published and the status
line above stays "PROPOSAL".
