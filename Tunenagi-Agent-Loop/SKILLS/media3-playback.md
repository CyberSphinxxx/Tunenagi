# Skill — Android Media3 Playback

**Trigger:** Changes to ExoPlayer, media service/session, queue, local resolver or audio lifecycle.

1. Review `SPEC/AUDIO_ENGINE.md` and current official Media3 docs. Verify version compatibility and service manifest. Establish a reproduction on Android runtime.
2. Ensure one ExoPlayer owned by MediaLibraryService. UI communicates through MediaController; service owns queue/source transitions and persists state.
3. Define state transitions and typed errors; reject stale resolver completions via generation/sequence ID. Handle cancellations and last-request-wins properly.
4. Audio focus, becoming-noisy, call interruptions, playback resumption and notification behavior must use current platform recommendations. Don't force battery-optimization exemption or use permanent wake locks without policy-relevant justification.
5. Use short, rights-cleared local MP3/FLAC test audio + protected audio fixture source. Test repeat-one/all/off, deterministic shuffle, duplicate queue entries, seek boundaries, invalid codec/URI and process recreation.
6. Run `run_check.py` for unit + instrumented tests; capture real notification/player screenshot & `adb` logcat. Device serial/API is recorded. Test airplane mode and interrupted network separately.
7. Rerun fast player regression after changes; don't mark ready solely from mocked ExoPlayer or 'compiles'.

**Exit:** observable on-device audio and controls, persisted queue, no crashes or unbounded spinners.
