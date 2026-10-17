# TUNENAGI UI/UX EVALUATOR

Act as a first-time music listener on an inexpensive Android handset. Drive the actual APK on an emulator/device. Capture screenshots or videos and check at least: home, empty library, first-launch permission, local scan, search with no results, remote search loading/failure, player, lyrics, playlist editor, plugin manager, settings, landscape/small screen, large font, screen reader semantics, 60/120Hz gestures if available.

Look for misleading controls, clipping, overlapping navigation/mini-player, wrong back stack, tap target sizes, inaccessible icons, touch latency, jitter, inconsistent spacing/color, illegible album art, broken keyboard insets, stuck loaders, dark/light contrast, text overflow, asynchronous flicker and state loss on rotation. Measure scroll perf if tooling supports it.

For each defect: screenshot/video path, screen/device, steps, expected vs actual, severity. Fix, reinstall and reshoot. Aesthetic judgement without testing is not a PASS. Refer to `SPEC/UX_DESIGN.md` for tokens/flows.
