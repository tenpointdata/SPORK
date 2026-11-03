-- SPORK day-1 schema: buckets, page sizes and point-lookup indexes (02-retrieval-speed.md C8, C9, 2.0, 2.1, 2.4-2.7;
-- 01-architecture.md 0.4, 1.2, 2.1, 3.6 E0).
--
-- GRAMMAR CHECKED against engine/src/main/java/com/arcadedb/query/sql/parser:
--   CREATE [VERTEX|DOCUMENT] TYPE t [IF NOT EXISTS] [EXTENDS s] [BUCKET b,...] [BUCKETS n] [PAGESIZE n]
--       PAGESIZE reaches TypeBuilder.withPageSize (CreateVertexTypeStatement.java:42-53, CreateDocumentTypeStatement.java:42-54),
--       so BUCKET page sizes CAN be set in SQL. BUCKETS defaults to arcadedb.typeDefaultBuckets (1).
--   CREATE PROPERTY t.p [IF NOT EXISTS] TYPE [OF t2] [(attr value, ...)]
--       attributes (CreatePropertyAttributeStatement.ATTRIBUTES): readonly mandatory notnull hidden external compression max
--       min default regexp; compression in {none, fast, max, auto}, "lz4" is accepted as an alias of fast (LocalProperty:256).
--   CREATE INDEX [name] [IF NOT EXISTS] ON t (p [BY ITEM|BY KEY|BY VALUE] [COLLATE c], ...)
--       UNIQUE | NOTUNIQUE | FULL_TEXT | LSM_VECTOR | LSM_SPARSE_VECTOR | GEOSPATIAL | UNIQUE_HASH | NOTUNIQUE_HASH
--       [NULL_STRATEGY s] [METADATA {...}] [ENGINE e]
--       There is NO PAGESIZE clause (CreateIndexStatement.java:404). The page size an index gets from SQL is:
--         plain LSM_TREE (UNIQUE/NOTUNIQUE): arcadedb.indexDefaultPageSize   -> 64 KB with read-node.properties (IndexBuilder.getLsmTreePageSize)
--         HASH:   HashIndexBucket.defaultPageSize(keyTypes): 16 KB for STRING/BINARY/DECIMAL keys, 4 KB for LONG and
--                 other fixed-width keys (HashIndexBucket.java:83-87, 1200-1205); maximum 64 KB (MAX_PAGE_SIZE)
--         FULL_TEXT, GEOSPATIAL, vector: LSMTreeIndexAbstract.DEF_PAGE_SIZE = 256 KB, the setting is ignored
--                 (IndexBuilder.getPageSize() at :352; LSMTreeFullTextIndex.java:148, LSMTreeGeoIndex.java:141)
--       So: LSM composites at 64 KB are plain SQL; every HASH index is plain SQL at its engine default (round 6: the
--       4 KB default on LONG/UUID keys is kept, the engine's own #5712 benchmark picked it and a cold probe moves 4 KB);
--       only FULL_TEXT / GEOSPATIAL need the Java builder for 64 KB. Those are written as Java below, marked "-- JAVA:".
--       The Java spelling of BY ITEM is the property name suffixed " by item" (TypeIndexBuilder.java:368).
--   ALTER DATABASE `arcadedb.key` value   (SCOPE.DATABASE keys only; persisted in the database configuration)
--
-- C9 page sizes (round 6): Record, TextBody, Target, AuditEntry buckets 32 KB; recdir and identifier vertices 16 KB; HASH
-- indexes at the engine defaults (4 KB LONG/UUID, 16 KB STRING/BINARY); LSM range composites, FULL_TEXT, GEOSPATIAL 64 KB;
-- any LSM point index 256 KB (fallback only, none below).
-- C8 HASH on every exact key; 2.0: one bucket per type (StyleVec excepted).
-- Property types are the minimum the indexes need; the full property lists are in 01-architecture.md 2.1.

-- =====================================================================================================================
-- Every database: settings that must be in place BEFORE any type is created (page sizes are fixed at creation, C9)
-- =====================================================================================================================
ALTER DATABASE `arcadedb.bucketDefaultPageSize` 32768;          -- C9 default for Record, TextBody, Target, AuditEntry
ALTER DATABASE `arcadedb.indexDefaultPageSize` 65536;           -- C9: plain LSM composites at 64 KB
ALTER DATABASE `arcadedb.indexCompactionMinPagesSchedule` 64;   -- E0 / C5 (round 6): one series per round, moot on sealed partitions
ALTER DATABASE `arcadedb.indexCompactionFullDeadEntriesRatio` 0; -- E0 / 0.4: no automatic full compaction
ALTER DATABASE `arcadedb.indexCompactionFullSeriesThreshold` 0;  -- E0 / C5 (round 6): 0 on every database
ALTER DATABASE `arcadedb.graph.supernodeThreshold` 1000000;     -- C7: before load, one-way promotion
-- Every database: AuditEntry chain (2.1 "every database"; C9 32 KB; C5 names its composites as 1-series indexes)
CREATE DOCUMENT TYPE AuditEntry IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;
CREATE PROPERTY AuditEntry.seq IF NOT EXISTS LONG;
CREATE PROPERTY AuditEntry.detailCiphertext IF NOT EXISTS BINARY (external true);   -- 2.0 EXTERNAL properties
CREATE INDEX IF NOT EXISTS ON AuditEntry (seq) UNIQUE;                               -- LSM 64 KB via indexDefaultPageSize

-- =====================================================================================================================
-- identity (1.2): shared identifiers, Place, Target, Watch, case layer. fullSeriesThreshold stays 0 here too (round 6: C5's 3
-- was rejected; weekly leased full compaction of the identity LSM indexes instead, 01 0.4).
-- =====================================================================================================================
-- Identifier vertices: one bucket, 16 KB pages (C9 "identifier vertices 16 KB"), HASH UNIQUE key (C8). Keys are STRING
-- (2.3: "ph:<10 digits>" etc.), so SQL UNIQUE_HASH already yields a 16 KB HASH page (variable-width key default).
CREATE VERTEX TYPE Identifier IF NOT EXISTS;
CREATE PROPERTY Identifier.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Identifier.recordCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Identifier.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY Identifier.lastSeen IF NOT EXISTS LONG;
CREATE PROPERTY Identifier.periodMask IF NOT EXISTS LONG;
CREATE PROPERTY Identifier.hub IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Identifier.stop IF NOT EXISTS BOOLEAN;
CREATE VERTEX TYPE Phone     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Email     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Handle    IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Name      IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Business  IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE ImageHash IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Url       IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
-- One HASH per concrete type (a type index on the abstract supertype would span 7 buckets; 2.0 wants 1 probe per lookup)
CREATE INDEX IF NOT EXISTS ON Phone (key) UNIQUE_HASH;        -- C8; 16 KB (STRING key default)
CREATE INDEX IF NOT EXISTS ON Email (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (handle) NOTUNIQUE;      -- 2.1: bare-handle intake, LSM 64 KB
CREATE INDEX IF NOT EXISTS ON Name (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Business (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON ImageHash (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Url (key) UNIQUE_HASH;
-- Place (2.1): placeId LONG hash with its own UNIQUE index (C8 HASH), point GEOSPATIAL at 64 KB (C9), cityNorm LSM.
CREATE VERTEX TYPE Place IF NOT EXISTS BUCKETS 1;             -- bucket page size not specified by the docs: database default 32 KB
CREATE PROPERTY Place.key IF NOT EXISTS STRING;
CREATE PROPERTY Place.placeId IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY Place.cityNorm IF NOT EXISTS STRING;
CREATE PROPERTY Place.point IF NOT EXISTS STRING;             -- WKT point; GEOSPATIAL index key
CREATE INDEX IF NOT EXISTS ON Place (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Place (cityNorm) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Place (placeId) UNIQUE_HASH;    -- C8; LONG key -> 4 KB HASH pages (engine default, round 6)
-- JAVA: GEOSPATIAL ignores indexDefaultPageSize (256 KB from SQL); C9 wants 64 KB:
--   db.getSchema().buildTypeIndex("Place", new String[]{"point"})
--       .withType(Schema.INDEX_TYPE.GEOSPATIAL).withPageSize(65536).withIgnoreIfExists(true).create();
--   (TypeGeoIndexBuilder.withPrecision / withTokenization keep their defaults; the docs do not set them.)
-- Target (2.1, C8 "Target id", C9 32 KB bucket): id is Recon's tgt_ STRING; identifierKeys[] BY ITEM for deconfliction.
CREATE VERTEX TYPE Target IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;
CREATE PROPERTY Target.id IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Target.identifierKeys IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.snapshot IF NOT EXISTS EMBEDDED (external true);   -- 2.0: EXTERNAL, never inlined on RETURN
CREATE PROPERTY Target.history IF NOT EXISTS LIST (external true);
CREATE INDEX IF NOT EXISTS ON Target (id) UNIQUE_HASH;                      -- C8; 16 KB (STRING)
CREATE INDEX IF NOT EXISTS ON Target (identifierKeys BY ITEM) NOTUNIQUE_HASH;  -- 2.1 BY ITEM; HASH is non-unique capable (2.0)
-- Watch (2.1, C8 "Watch key"): UNIQUE normalized source-set key, 409 on duplicate
CREATE VERTEX TYPE Watch IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Watch.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON Watch (key) UNIQUE_HASH;
-- Evidence FULL_TEXT in identity (2.7); 64 KB (C9) needs the Java builder:
-- JAVA: db.getSchema().buildTypeIndex("Evidence", new String[]{"body"})
--           .withType(Schema.INDEX_TYPE.FULL_TEXT).withPageSize(65536).withIgnoreIfExists(true).create();
-- NOT EXPRESSIBLE: arcadedb.graph.edgeListInitialChunkSize=2048 "on shared identifier vertices" (C7): the value is read
-- JVM-wide (LocalDatabase.getNewEdgeListSize) and there is no per-vertex or per-type API; engine item 02 S18 / 01 E24. See README.

-- =====================================================================================================================
-- recdir (1.2): one document type {upstreamRid LONG, period SHORT}, 16 KB bucket (C9), HASH UNIQUE upstreamRid (C8).
-- The docs name the database and the index ("recdir.upstreamRid") but not the type; "RecordDir" is a placeholder.
-- recdir has no LSM index after C8, so the compaction keys are moot here.
-- =====================================================================================================================
CREATE DOCUMENT TYPE RecordDir IF NOT EXISTS BUCKETS 1 PAGESIZE 16384;
CREATE PROPERTY RecordDir.upstreamRid IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY RecordDir.period IF NOT EXISTS SHORT (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON RecordDir (upstreamRid) UNIQUE_HASH;   -- C8; LONG key -> 4 KB HASH pages (engine default, round 6)

-- =====================================================================================================================
-- rec_<p> (1.2, 2.1, 2.4-2.6): Record 32 KB bucket; local identifier vertices 16 KB; HASH point indexes; 27 LSM composites.
-- C5: fullSeriesThreshold=0 (already set above for every database; sealed shape about 23 series at 64 KB would loop a full rewrite, risk 7.4)
-- =====================================================================================================================
CREATE VERTEX TYPE Record IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;      -- C9: 40 rows per page, 75% density
CREATE PROPERTY Record.record_id   IF NOT EXISTS LONG (mandatory true, notnull true);   -- 2.1: SPORK-assigned, period in high bits
CREATE PROPERTY Record.upstreamRid IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY Record.textHash    IF NOT EXISTS BINARY;                -- 2.1: sha256/128 (32 B)
CREATE PROPERTY Record.imageHashes IF NOT EXISTS LIST OF STRING;        -- 2.1: upstream hashes, full length
CREATE PROPERTY Record.tsPosted    IF NOT EXISTS LONG;
CREATE PROPERTY Record.tsCollected IF NOT EXISTS LONG;
CREATE PROPERTY Record.views       IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.stateCode   IF NOT EXISTS STRING;
CREATE PROPERTY Record.countryCode IF NOT EXISTS STRING;
CREATE PROPERTY Record.placeId     IF NOT EXISTS LONG;
CREATE PROPERTY Record.cityNorm    IF NOT EXISTS STRING;
CREATE PROPERTY Record.areaCodes   IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.services    IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.age         IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.hasImages   IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Record.website     IF NOT EXISTS STRING;
CREATE PROPERTY Record.aggregator  IF NOT EXISTS STRING;
CREATE PROPERTY Record.located     IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Record.phones      IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.emails      IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.businessNames IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.geoCell4    IF NOT EXISTS STRING;
CREATE PROPERTY Record.geoPacked   IF NOT EXISTS LONG;
-- Point lookups (C8). textHash is BINARY and imageHashes STRING, so SQL HASH gives 16 KB for those two; record_id and
-- upstreamRid are LONG keys and keep the 4 KB engine default (round 6: 56 KB per upstream-RID hydrate instead of 80).
CREATE INDEX IF NOT EXISTS ON Record (textHash) NOTUNIQUE_HASH;              -- C8 non-unique, 2.7 text -> records
CREATE INDEX IF NOT EXISTS ON Record (imageHashes BY ITEM) NOTUNIQUE_HASH;   -- C8 non-unique BY ITEM (P0a verifies on HASH)
CREATE INDEX IF NOT EXISTS ON Record (record_id) UNIQUE_HASH;                -- C8; 4 KB (LONG default)
CREATE INDEX IF NOT EXISTS ON Record (upstreamRid) UNIQUE_HASH;              -- C8; 4 KB (LONG default)
-- Range composites (2.4, 2.5, 2.6), plain LSM NOTUNIQUE: 64 KB from arcadedb.indexDefaultPageSize=65536 (C9), SQL is enough.
-- Posted-sort arm (2.4)
CREATE INDEX IF NOT EXISTS ON Record (stateCode, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (countryCode, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (placeId, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (cityNorm, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (areaCodes BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (services BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (age, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (hasImages, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (website, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (aggregator, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (located, tsPosted) NOTUNIQUE;          -- 2.5 P5
CREATE INDEX IF NOT EXISTS ON Record (located, tsCollected) NOTUNIQUE;       -- 2.5 P5
CREATE INDEX IF NOT EXISTS ON Record (tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (tsCollected) NOTUNIQUE;
-- Views-sort arm (2.4)
CREATE INDEX IF NOT EXISTS ON Record (stateCode, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (placeId, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (cityNorm, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (areaCodes BY ITEM, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (services BY ITEM, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (hasImages, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (age, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (views, tsPosted) NOTUNIQUE;
-- Identifier arm (2.4)
CREATE INDEX IF NOT EXISTS ON Record (phones BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (emails BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (businessNames BY ITEM, tsPosted, views) NOTUNIQUE;
-- Geo (2.6): self-answering (geoCell4, tsPosted, geoPacked)
CREATE INDEX IF NOT EXISTS ON Record (geoCell4, tsPosted, geoPacked) NOTUNIQUE;
-- Local identifier vertices (2.1 P1): one bucket, 16 KB pages, HASH UNIQUE key, LSM range indexes of the partial-search
-- rows at 64 KB. Same Identifier hierarchy as identity; shown for Phone, repeat for Email/Handle/Name/Business/Url.
CREATE VERTEX TYPE Identifier IF NOT EXISTS;
CREATE PROPERTY Identifier.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE VERTEX TYPE Phone IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE PROPERTY Phone.digits IF NOT EXISTS STRING;
CREATE PROPERTY Phone.rev IF NOT EXISTS STRING;
CREATE PROPERTY Phone.digitSuffixes IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Phone.grams4 IF NOT EXISTS LIST OF STRING;
CREATE INDEX IF NOT EXISTS ON Phone (key) UNIQUE_HASH;                          -- C8; 16 KB (STRING)
CREATE INDEX IF NOT EXISTS ON Phone (rev) NOTUNIQUE;                            -- 2.0 LSM range, 64 KB
CREATE INDEX IF NOT EXISTS ON Phone (digits) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Phone (digitSuffixes BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Phone (grams4 BY ITEM) NOTUNIQUE;
-- (Email/Handle/Business: grams3 BY ITEM; Name: phonetic BY ITEM, grams3 BY ITEM; all NOTUNIQUE at 64 KB.)
-- Lightweight HAS_PHONE/HAS_EMAIL/HAS_HANDLE/HAS_NAME/HAS_BUSINESS edges (2.2): edge types with no record; out of this
-- file's scope (not a point-lookup index or bucket page size item). CreateEdgeTypeStatement has a lightweight flag.
-- Sealed partitions only, after CHECK DATABASE ... FIX (C7):
-- ALTER DATABASE `arcadedb.graph.ghostEdgeProbe` false;
-- At seal and after every admission the plugin runs a full compaction of EVERY LSM index of the partition, one at a time
-- under the transient-slot lease (01 0.4, round 6); the HASH indexes above never compact (HashIndex.compact() is false).

-- media_<p> (06-stylometry-perceptual-hash.md 3.1, round 6): PDQ band keys ONLY while the partition is inside the hot window
-- (and on case databases); 8 bands x 18 B = 144 B per distinct image, 24 GB today and 43 GB at 3x. No corpus-wide band
-- index (194 GB at 3x) and no corpus-wide pHash (49 GB): the radius-31 near-dup is the S-PDQ IVF space (4c).
-- CREATE PROPERTY Image.pdqBands IF NOT EXISTS LIST OF LONG;
-- CREATE INDEX IF NOT EXISTS ON Image (pdqBands BY ITEM) NOTUNIQUE;          -- LSM 64 KB; dropped when the partition leaves the hot window

-- =====================================================================================================================
-- txt_<p> (1.2, 2.1, 2.7, 2.8): TextBody 32 KB bucket, 16 KB EXTERNAL pages, HASH UNIQUE textHash, FULL_TEXT 64 KB
-- =====================================================================================================================
-- E0 / 0.4: MUST precede the EXTERNAL property. LocalDocumentType creates the paired _ext bucket from this database
-- setting, not from TypeBuilder.withPageSize, and LocalSchema.checkPairedExternalBucketPageSize reads the same setting.
ALTER DATABASE `arcadedb.externalPropertyBucketDefaultPageSize` 16384;
ALTER DATABASE `arcadedb.vectorIndex.searchCacheSize` 500000;          -- E0, 1.1: database setting, not index metadata
CREATE DOCUMENT TYPE TextBody IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;   -- C9; 2.0 TextBody single-bucket (50 probes, not 800)
CREATE PROPERTY TextBody.textHash IF NOT EXISTS BINARY (mandatory true, notnull true);   -- 2.1: sha256/128
CREATE PROPERTY TextBody.title IF NOT EXISTS STRING;                                     -- inline
CREATE PROPERTY TextBody.text IF NOT EXISTS STRING (external true, compression "lz4");   -- 2.1: EXTERNAL LZ4 ("lz4" = fast tier alias)
CREATE INDEX IF NOT EXISTS ON TextBody (textHash) UNIQUE_HASH;          -- C8; BINARY key -> 16 KB from SQL
-- JAVA: FULL_TEXT ignores indexDefaultPageSize (256 KB from SQL); C9 and 2.7 want 64 KB. One stored property only
--   (0.4: a two-property index posts every token twice):
--   db.getSchema().buildTypeIndex("TextBody", new String[]{"text"})
--       .withType(Schema.INDEX_TYPE.FULL_TEXT).withPageSize(65536).withIgnoreIfExists(true).create();
--   (TypeFullTextIndexBuilder.withAnalyzer etc. are left at defaults here; the n-gram/phonetic analyzers are M17.)
-- StyleVec (2.8): the only multi-bucket type (month buckets of at most 8M), LSM_VECTOR INT8 on style[24], degree 16,
-- storeVectorsInGraph=true (per-index metadata, E0). Its metadata keys are vector-index metadata, not GlobalConfiguration
-- keys, and were not validated in this pass; see README "not expressed here".

-- =====================================================================================================================
-- n4 only (C12): keep arcadedb.queryIndexMaxSelectivity at its default 0.6 on every database (no ALTER DATABASE there).
-- Every ALTER DATABASE above is persisted in the database configuration and replicated with the schema; the JVM-wide
-- defaults in read-node.properties cover a database created without this script.
-- =====================================================================================================================
