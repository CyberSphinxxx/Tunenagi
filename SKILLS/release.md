# Skill — Android Release Candidate and Supply Chain

**Trigger:** CI, dependency updates, release APKs, signing, final phase.

1. Verify all phases/tasks and independent audits on current SHA. Freeze dependency catalog and versionCode/versionName. Run full clean build and Android tests.
2. Generate debug-signed QA build and install on supported Android; capture real launch/local/Audius playback and plugin proof. Record artifact SHA256 and `apksigner` output.
3. With explicit owner-managed keystore, configure secure release signing via environment/CI secrets. Never create pretend official signature, reuse exposed test passwords or commit signing secrets.
4. Use current official target-SDK requirements and GitHub/F-Droid policies; do not assume store acceptance. Review app permissions/exported components and supplier licenses.
5. Produce LICENSE/NOTICE/privacy/attribution, install + update guides, supported devices, known limitations (Audius ≠ mainstream catalog), changelog and owner handoff. Reproducibility instructions must work on a clean machine.
6. Re-run final independent release audit; deliver `READY FOR OWNER APPROVAL` only with installable evidence. Publishing requires owner authorization.

**Exit:** verifiable APK + checksum + test proof + docs; honest limitations and signing status.
