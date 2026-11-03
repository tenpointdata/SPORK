-- =====================================================================================================================
-- recdir (1.2, 2.5): the record directory, upstreamRid (UpstreamRid, clusterId << 40 | clusterPos) to the period that
-- holds the record. One document type {upstreamRid LONG, period SHORT}, 70 B per record, 16 KB bucket pages (C9) and a
-- UNIQUE HASH on upstreamRid (C8; LONG key: 4 KB HASH pages, the engine default kept in round 6). A hydrate by upstream
-- RID is this probe plus one in the period's rec_<p>; a record_id (RecordId) carries its period and skips recdir.
-- Written by step 1 of the ingest saga (6.2) and by the initial load (6.1). No LSM index, so the compaction keys of
-- common.sql are moot here.
-- =====================================================================================================================
CREATE DOCUMENT TYPE RecordDir IF NOT EXISTS BUCKETS 1 PAGESIZE 16384;
CREATE PROPERTY RecordDir.upstreamRid IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY RecordDir.period IF NOT EXISTS SHORT (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON RecordDir (upstreamRid) UNIQUE_HASH;
