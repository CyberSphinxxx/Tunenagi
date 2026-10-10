# Skill — Compose UX and Visual Verification

**Trigger:** Any task touching Compose screens, navigation, artwork, typography, or animation.

1. Read `SPEC/UX_DESIGN.md`, identify screen states; list success/empty/loading/error/offline/permission states as applicable.
2. Write a small composable state contract and preview variants, retaining stable data and key semantics. Do not show fake provider content in release runtime; fixture data belongs in previews/tests.
3. Use Material 3 baseline and Tunenagi token components; enforce color contrast, ≥48dp common targets and font scaling. Insets on mini-player/bottom nav/keyboard are mandatory; test landscape and 360dp.
4. Build actual screen connected to ViewModel/state and Room/providers. Every clickable icon must have a working action, no ornamental buttons unless clearly decorative.
5. Instrument navigation/back; capture actual `adb exec-out screencap -p` screenshots or test harness screenshot, record device/API; compare visual hierarchy, loading states, spacing and text overflow.
6. Adversarial flows: rotate during buffering; switch tabs quickly; open keyboard + mini-player; long track names; 200% font; TalkBack; no network/empty library; art fails; rapid taps.
7. Run Compose UI tests, accessibility checks and an emulator walkthrough. Do not mark polished from compilation alone. Update screenshot matrix and cite artifacts.

**Exit:** actual screenshots/videos for affected states, tests with evidence, no broken gestures/dead controls or obvious clipping. Perform independent UI evaluator prompt on phase completion.
