# Skill — Room Schema and Data Migration

**Trigger:** Schema additions, rename, key strategy, playlist/queue persistence or import/export change.

1. Inventory existing Room entities, schema exports, versions and DAO users. Describe invariant before editing.
2. Write a migration test starting from **actual older schema** with representative user playlists, favorites, unavailable sources and duplicate queue entries.
3. Add new schema and explicit migration (no destructive fallback). Preserve stable TrackId and PlaylistEntryId; ensure FKs, sort indexes and constraints.
4. Run migration, backup/restore and DAO concurrency tests. Test rollback on invalid backup import. Stress 10k rows for paging/transaction latency.
5. If state format changes, introduce version mapping and document upgrade path; never silently wipe state. Update diagram/ADR and evidence.

**Exit:** old-to-new migration passes with user data intact, no uncaught schema/foreign-key errors.
