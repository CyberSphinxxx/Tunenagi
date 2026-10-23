# Product UX, interaction design and polished visual system

## Design direction

Tunenagi is understated, fast and sophisticated, *not* a Spotify clone. Dark charcoal with subtle indigo/violet accents; artwork is vivid and leads visual hierarchy. Short labels, restrained gradients, sophisticated spacing, calm animation. Japanese etymology is brand context, not decoration. Ensure original icon/logo and original layout rather than copying another app's assets. Avoid flashy glassmorphism that impairs readability/performance.

### Design tokens (proposed, subject to contrast audit)

- Core surfaces: `#0C0D12` background, `#171923` raised, `#252837` overlay, `#35394A` outline. Primary text `#F4F3FA`, secondary `#B6B8C5`, interactive accent `#A99BFA`, danger `#FF7B85`, positive `#7FE0BB`.
- Accent backgrounds must be audited against text WCAG ratios; use actual contrast check and dark/light mode semantics. Do not hard-code disabled alpha that falls below necessary contrast.
- Spacing: 4pt base, common 8/12/16/24/32dp, scalable top/bottom insets, 48dp minimum control targets where practical.
- Typography: system sans through Material3 styles; Hero title 28–32sp, screen header 22–24sp, row title 15–16sp, supporting 12–14sp; respect font scaling 1.3x/2.0x.
- Shapes: cards 12–16dp, album artwork 8–12dp, pills only for functional filters; shadows restrained.
- Motion: navigation 180–240ms; mini-player expand/collapse shared state; album artwork fades on load; honor reduced-motion accessibility setting where supported; no required 120Hz performance promise.

## Screen map and content hierarchy

**Home**: app wordmark, settings icon -> “Continue listening” if history exists; quick entry Favorites + Playlists + On Device; “Discover on Audius” with curated/trending API results, real loading/empty/error states; mini-player anchored above navigation. Without content, prominent Import Music and Browse Audius actions.

**Search**: immediate search field, recent searches optional/local-only; filters All / Device / Audius; track, artist, playlist result types supported by provider; debounce 250–400ms, cancel in-flight requests, paginated results, no duplicate query storms; the keyboard must not obscure results. Error with retry and provider attribution.

**Library**: tabs/filters Songs / Artists / Albums / Playlists / Favorites; sorting by title/recent/artist; local import and rescan action; preserve empty-state clarity. A remote track saved to playlist remains visible offline with disabled state and clear reason.

**Now Playing**: expanded cover, title/artist, source inspector chip, seekbar with times, prominent play/pause and skip; secondary shuffle/repeat/favorite; bottom controls for lyrics/queue/source. Optional crossfade unsupported v1. Loading spinner attaches to playback state and has timeout. If no source, show choose available source / retry, not a dead button.

**Queue**: current item, next up, drag reorder, Play Next, remove; shuffle ordering deterministic; scroll position preserved.

**Playlist detail/editor**: cover placeholder, title, count, play/shuffle, overflow rename/delete/export, drag ordering and empty state; destructive actions confirm.

**Lyrics**: timestamp highlight, tappable valid line, manual scroll does not fight auto-follow, tap to resume; plain text fallback; attribution and no-lyrics state.

**Plugins**: installed / add from URL or file; dialog displays publisher verification unknown by default, source domains, required permissions, supported operations, version; enabled toggle; diagnostics; never autoplay from untrusted plugin before user action.

**Settings**: sections Playback / Library & Storage / Sources & Plugins / Appearance / Privacy & Data / About. No login, paywall or nag screen.

## Navigation

Bottom: Home, Search, Library. Mini-player persists across destinations and sits **above** the navigation bar/inset. Player opens as full-screen modal/route. Back from player returns previous tab; back on Home follows Android expectations. Accessibility labels on every icon.

## Microinteraction specification

- Optimistic favorite/playlist actions only when local Room transaction is immediate and persistent; rollback visibly on failure.
- Skeleton rows only during meaningful network waits; for empty states show explanatory copy + action. Never infinite skeleton.
- Tapping currently playing track opens player instead of restarting if practical. Queuing unrelated remote track should not interrupt current song until selected.
- Scrubbing shows elapsed preview; actual player seek commits at gesture release or throttle to prevent flooding.
- Long text scroll/marquee only under motion/accessibility constraints; normally ellipsize and show full details on tap.
- Artwork failures show consistent gradients/artist initials; errors not logged repeatedly.

## Device test matrix

Small phone 360x640dp, mainstream 393x852dp, tablet 800dp+, Android 8/13/latest stable where available; portrait & landscape; gesture and three-button navigation; system font 100/150/200%; TalkBack focus order; keyboard, cutouts, notches, RTL text (content), permission denial/revocation; dark and optional system light.

## Visual release gate

UI screenshot set in `STATE/evidence/ui/`: first launch, home populated, home empty, search results, search error, library, playlist editor, player, queue, lyrics synced/plain/missing, plugin details/permission, settings, font scale. Compare manually against tokens and contrast; record findings. No placeholder Lorem Ipsum, obviously synthetic provider data, clipped layouts, impossible buttons or unstable mini-player.
