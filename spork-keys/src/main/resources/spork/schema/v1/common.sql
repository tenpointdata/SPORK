-- =====================================================================================================================
-- Every database (identity, recdir, rec_<p>, txt_<p>, media_<p>): settings that must be in place BEFORE any type is
-- created (page sizes are fixed at creation, 02-retrieval-speed.md C9), the provenance struct and the audit chain table.
-- =====================================================================================================================
-- GRAMMAR (engine/src/main/antlr4/.../SQLParser.g4), what each statement below can and cannot say:
--   CREATE [VERTEX|DOCUMENT|EDGE] TYPE t [IF NOT EXISTS] [EXTENDS s] [LIGHTWEIGHT] [BUCKETS n] [PAGESIZE n]
--       PAGESIZE reaches TypeBuilder.withPageSize, so bucket page sizes ARE plain SQL. BUCKETS 0 is an interface type.
--   CREATE PROPERTY t.p [IF NOT EXISTS] TYPE [OF t2] [(attr value, ...)]: readonly mandatory notnull hidden external
--       compression max min default regexp; compression "lz4" is an alias of the fast tier.
--   CREATE INDEX [IF NOT EXISTS] ON t (p [BY ITEM], ...) UNIQUE|NOTUNIQUE|FULL_TEXT|GEOSPATIAL|UNIQUE_HASH|NOTUNIQUE_HASH
--       [NULL_STRATEGY s]. There is NO PAGESIZE clause. From SQL an index gets: LSM (UNIQUE/NOTUNIQUE)
--       arcadedb.indexDefaultPageSize (64 KB below); HASH 16 KB for STRING/BINARY keys and 4 KB for LONG and other
--       fixed-width keys (HashIndexBucket.defaultPageSize; round 6 keeps those defaults); FULL_TEXT and GEOSPATIAL 256 KB
--       whatever the setting, so those are built by SporkSchema with the Java builder at 64 KB (C9).
--   ALTER DATABASE `arcadedb.key` value: SCOPE.DATABASE keys only, persisted in the database configuration.
--   ALTER TYPE t VERSIONED true: native record versioning (08-native-versioning-design.md).
-- C9 page sizes (round 6): Record, TextBody, Target, AuditEntry buckets 32 KB; recdir and identifier vertices 16 KB.
-- C8: HASH on every exact key. 2.0: one bucket per type. SQL keywords used as type names (Case) are back-quoted.
ALTER DATABASE `arcadedb.bucketDefaultPageSize` 32768;           -- C9 default for Record, TextBody, Target, AuditEntry
ALTER DATABASE `arcadedb.indexDefaultPageSize` 65536;            -- C9: plain LSM composites at 64 KB
ALTER DATABASE `arcadedb.indexCompactionMinPagesSchedule` 64;    -- E0 / C5 (round 6): one series per round
ALTER DATABASE `arcadedb.indexCompactionFullDeadEntriesRatio` 0; -- E0 / 0.4: no automatic full compaction
ALTER DATABASE `arcadedb.indexCompactionFullSeriesThreshold` 0;  -- E0 / C5 (round 6): 0 on every database
ALTER DATABASE `arcadedb.graph.supernodeThreshold` 1000000;      -- C7: before load, one-way promotion

-- Provenance (2.7): the parallel struct on every Record and on the few properties analysts can assert. Ingested values
-- carry sourceId = upstream source and assertedBy = 'system:ingest'; when is the engine's (_txFrom on versioned types).
CREATE DOCUMENT TYPE Prov IF NOT EXISTS BUCKETS 0;
CREATE PROPERTY Prov.sourceId IF NOT EXISTS STRING;
CREATE PROPERTY Prov.collectedAt IF NOT EXISTS LONG;
CREATE PROPERTY Prov.assertedBy IF NOT EXISTS STRING;
CREATE PROPERTY Prov.confidence IF NOT EXISTS FLOAT;

-- Schema version marker, one row per database, written by SporkSchema after the script ran.
CREATE DOCUMENT TYPE SporkSchemaInfo IF NOT EXISTS BUCKETS 1 PAGESIZE 16384;
CREATE PROPERTY SporkSchemaInfo.name IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY SporkSchemaInfo.version IF NOT EXISTS INTEGER (mandatory true, notnull true);
CREATE PROPERTY SporkSchemaInfo.installedAt IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON SporkSchemaInfo (name) UNIQUE_HASH;

-- AuditEntry chain (2.1 "every database", 5.2; C9 32 KB). Only the columns the indexes need: the chain body, the
-- commitment and the MAC sidecar are the WS3 plugin's and are added by its installer.
CREATE DOCUMENT TYPE AuditEntry IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;
CREATE PROPERTY AuditEntry.seq IF NOT EXISTS LONG;
CREATE PROPERTY AuditEntry.detailCiphertext IF NOT EXISTS BINARY (external true);   -- 2.0 EXTERNAL properties
CREATE INDEX IF NOT EXISTS ON AuditEntry (seq) UNIQUE;                               -- LSM 64 KB via indexDefaultPageSize
