-- =====================================================================================================================
-- txt_<p> (1.2, 2.1): TextBody deduplicated by the exact-byte textHash (TextHash), 32 KB bucket, 16 KB EXTERNAL pages,
-- UNIQUE HASH textHash (BINARY key: 16 KB HASH pages), FULL_TEXT on the single stored property at 64 KB (SporkSchema,
-- Java builder; 0.4: a two-property index posts every token twice). Only the console role has a grant on txt_* (5.4).
-- =====================================================================================================================
-- E0 / 0.4: MUST precede the EXTERNAL property. LocalDocumentType creates the paired _ext bucket from this database
-- setting, not from TypeBuilder.withPageSize, and LocalSchema.checkPairedExternalBucketPageSize reads the same setting.
ALTER DATABASE `arcadedb.externalPropertyBucketDefaultPageSize` 16384;
ALTER DATABASE `arcadedb.vectorIndex.searchCacheSize` 500000;          -- E0, 1.1: database setting, not index metadata
CREATE DOCUMENT TYPE TextBody IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;   -- C9; 2.0 single bucket (50 probes, not 800)
CREATE PROPERTY TextBody.textHash IF NOT EXISTS BINARY (mandatory true, notnull true);   -- 32 B, TextHash.of(title, body)
CREATE PROPERTY TextBody.title IF NOT EXISTS STRING;                                     -- inline
CREATE PROPERTY TextBody.text IF NOT EXISTS STRING (external true, compression "lz4");   -- title + body; lz4 = fast tier
CREATE PROPERTY TextBody.style IF NOT EXISTS ARRAY_OF_FLOATS;           -- StyleKeys.vector, hot partitions only
CREATE PROPERTY TextBody.textCode IF NOT EXISTS BINARY;                 -- S-TEXT 1-bit and 4-bit codes (4c)
CREATE PROPERTY TextBody.lang IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON TextBody (textHash) UNIQUE_HASH;
-- JAVA (SporkSchema): TextBody (text) FULL_TEXT at 64 KB pages. The style LSM_VECTOR index and the S-TEXT
-- LSM_IVF_VECTOR index are created when a partition enters the hot window (WS6, WS13/E18), not by schema v1.
