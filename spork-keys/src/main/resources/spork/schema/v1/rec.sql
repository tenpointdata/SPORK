-- =====================================================================================================================
-- rec_<p> (1.2, 2.1, 2.4, 2.5): Record (32 KB bucket), partition-local identifier vertices for every identifier on the
-- period's records (16 KB pages), lightweight HAS_* edges, HASH point indexes and the LSM range composites (64 KB).
-- C5: fullSeriesThreshold=0 (common.sql). At seal and after every admission the plugin runs a full compaction of every
-- LSM index of the partition, one at a time under the transient-slot lease (0.4); HASH indexes never compact.
-- =====================================================================================================================

-- ── Record (2.1) ────────────────────────────────────────────────────────────────────────────────────────────────────
CREATE VERTEX TYPE Record IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;      -- C9: 40 rows per page, 75% density
CREATE PROPERTY Record.record_id IF NOT EXISTS LONG (mandatory true, notnull true);   -- RecordId: period << 48 | seq
CREATE PROPERTY Record.upstreamRid IF NOT EXISTS LONG;                 -- UpstreamRid; absent on analyst imports (2.5)
CREATE PROPERTY Record.upstreamClass IF NOT EXISTS STRING;
CREATE PROPERTY Record.ridEpoch IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.source IF NOT EXISTS STRING;                    -- 'analyst-import' for /ingest (2.5)
CREATE PROPERTY Record.aggregator IF NOT EXISTS STRING;
CREATE PROPERTY Record.website IF NOT EXISTS STRING;
CREATE PROPERTY Record.urlHash IF NOT EXISTS BINARY;
CREATE PROPERTY Record.tsPosted IF NOT EXISTS LONG;
CREATE PROPERTY Record.tsCollected IF NOT EXISTS LONG;
CREATE PROPERTY Record.tsRemoved IF NOT EXISTS LONG;
CREATE PROPERTY Record.articleType IF NOT EXISTS STRING;
CREATE PROPERTY Record.views IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.age IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.category IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.services IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.rates IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.paymentTypes IF NOT EXISTS LIST OF STRING;     -- Recon's ReconRecord field name
CREATE PROPERTY Record.clientele IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.shanenet IF NOT EXISTS LONG;                    -- flags bitmask
CREATE PROPERTY Record.state IF NOT EXISTS STRING;                     -- display, UpstreamRecords.state ("??" when unknown)
CREATE PROPERTY Record.country IF NOT EXISTS STRING;                   -- display, UpstreamRecords.country ("US" default)
CREATE PROPERTY Record.stateCode IF NOT EXISTS STRING;                 -- normalized at ingest
CREATE PROPERTY Record.placeId IF NOT EXISTS LONG;                     -- SporkKeys.placeId
CREATE PROPERTY Record.city IF NOT EXISTS STRING;                      -- display, UpstreamRecords.city ("Unknown")
CREATE PROPERTY Record.cityNorm IF NOT EXISTS STRING;                  -- SporkKeys.cityNorm
CREATE PROPERTY Record.countryCode IF NOT EXISTS STRING;
CREATE PROPERTY Record.areaCodes IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.lat IF NOT EXISTS DOUBLE;
CREATE PROPERTY Record.lon IF NOT EXISTS DOUBLE;
CREATE PROPERTY Record.geoCell IF NOT EXISTS STRING;                   -- SporkKeys.geohash7
CREATE PROPERTY Record.geoCell4 IF NOT EXISTS STRING;
CREATE PROPERTY Record.geoPacked IF NOT EXISTS LONG;
CREATE PROPERTY Record.located IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Record.hasImages IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Record.textHash IF NOT EXISTS BINARY;                  -- TextHash, 32 B
CREATE PROPERTY Record.imageHashes IF NOT EXISTS LIST OF STRING;       -- upstream hashes, full length
-- Recon's display values, denormalized so a hydrate or search row reads no edge or local vertex (2.1)
CREATE PROPERTY Record.phones IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.emails IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Record.socials IF NOT EXISTS LIST OF STRING;           -- "platform:handle"
CREATE PROPERTY Record.businessNames IF NOT EXISTS LIST OF STRING;
-- Style per record from the exact article_body, before dedup (StyleKeys): the label and the float32 copy (96 B)
CREATE PROPERTY Record.style IF NOT EXISTS STRING;
CREATE PROPERTY Record.styleVec IF NOT EXISTS ARRAY_OF_FLOATS;
-- Ontology fields (2.7, 28 B with _prov): provenance, the markings bitmask, import id, governance
CREATE PROPERTY Record._prov IF NOT EXISTS EMBEDDED OF Prov;
CREATE PROPERTY Record.markings IF NOT EXISTS LONG;
CREATE PROPERTY Record.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY Record.importedId IF NOT EXISTS STRING;
CREATE PROPERTY Record.priorityScore IF NOT EXISTS FLOAT;              -- 4d, ingest time
CREATE PROPERTY Record.retentionClass IF NOT EXISTS STRING;
CREATE PROPERTY Record.holdCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Record.batchId IF NOT EXISTS STRING;
-- Point lookups (C8). record_id and upstreamRid are LONG keys: 4 KB HASH pages (round 6: 56 KB per upstream-RID hydrate).
-- upstreamRid and importedId are absent on most records, so their indexes skip nulls.
CREATE INDEX IF NOT EXISTS ON Record (record_id) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Record (upstreamRid) UNIQUE_HASH NULL_STRATEGY SKIP;
CREATE INDEX IF NOT EXISTS ON Record (tenantId, importedId) UNIQUE_HASH NULL_STRATEGY SKIP;   -- 2.5 analyst imports
CREATE INDEX IF NOT EXISTS ON Record (textHash) NOTUNIQUE_HASH;                -- text to records
CREATE INDEX IF NOT EXISTS ON Record (imageHashes BY ITEM) NOTUNIQUE_HASH;     -- P0a verifies BY ITEM on HASH
-- Range composites (02-retrieval-speed.md 2.4 to 2.6), plain LSM NOTUNIQUE at 64 KB from indexDefaultPageSize.
-- Posted-sort arm
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
CREATE INDEX IF NOT EXISTS ON Record (located, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (located, tsCollected) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (tsCollected) NOTUNIQUE;
-- Views-sort arm
CREATE INDEX IF NOT EXISTS ON Record (stateCode, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (placeId, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (cityNorm, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (areaCodes BY ITEM, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (services BY ITEM, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (hasImages, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (age, views, tsPosted) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (views, tsPosted) NOTUNIQUE;
-- Identifier arm
CREATE INDEX IF NOT EXISTS ON Record (phones BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (emails BY ITEM, tsPosted, views) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Record (businessNames BY ITEM, tsPosted, views) NOTUNIQUE;
-- Geo: self-answering (geoCell4, tsPosted, geoPacked)
CREATE INDEX IF NOT EXISTS ON Record (geoCell4, tsPosted, geoPacked) NOTUNIQUE;

-- ── Local identifier vertices (2.1, 2.4) ────────────────────────────────────────────────────────────────────────────
-- Every identifier on the period's records, shared or not, keyed by the same spork-keys canonical key as the identity
-- copy, with the kind-specific fields plus the gram and phonetic lists of partial search (LSM range, 64 KB).
CREATE VERTEX TYPE Identifier IF NOT EXISTS BUCKETS 0;
CREATE PROPERTY Identifier.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE VERTEX TYPE Phone     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Email     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Handle    IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Name      IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Business  IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Url       IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
-- (no local ImageHash: image hashes use the Record.imageHashes BY ITEM index, 2.2)
CREATE PROPERTY Phone.digits IF NOT EXISTS STRING;
CREATE PROPERTY Phone.rev IF NOT EXISTS STRING;
CREATE PROPERTY Phone.areaCode IF NOT EXISTS STRING;
CREATE PROPERTY Phone.exchange IF NOT EXISTS STRING;
CREATE PROPERTY Phone.inferredAreaCode IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Phone.grams4 IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Phone.digitSuffixes IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Email.domain IF NOT EXISTS STRING;
CREATE PROPERTY Email.local IF NOT EXISTS STRING;
CREATE PROPERTY Email.grams3 IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Handle.platform IF NOT EXISTS STRING;
CREATE PROPERTY Handle.handle IF NOT EXISTS STRING;
CREATE PROPERTY Handle.grams3 IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Name.tokens IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Name.phonetic IF NOT EXISTS LIST OF STRING;            -- Double Metaphone, Beider-Morse
CREATE PROPERTY Name.grams3 IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Business.businessName IF NOT EXISTS STRING;
CREATE PROPERTY Business.grams3 IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Url.url IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Phone (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Email (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (handle) NOTUNIQUE_HASH;          -- 2.1: bare-handle intake
CREATE INDEX IF NOT EXISTS ON Name (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Business (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Url (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Phone (rev) NOTUNIQUE;                   -- suffix search, LSM range 64 KB
CREATE INDEX IF NOT EXISTS ON Phone (digits) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Phone (digitSuffixes BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Phone (grams4 BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Email (grams3 BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Handle (grams3 BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Business (grams3 BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Name (grams3 BY ITEM) NOTUNIQUE;
CREATE INDEX IF NOT EXISTS ON Name (phonetic BY ITEM) NOTUNIQUE;
-- Lightweight HAS_* edges (2.2): Record to local identifier, no edge record (LightEdgeRID)
CREATE EDGE TYPE HAS_PHONE IF NOT EXISTS LIGHTWEIGHT;
CREATE EDGE TYPE HAS_EMAIL IF NOT EXISTS LIGHTWEIGHT;
CREATE EDGE TYPE HAS_HANDLE IF NOT EXISTS LIGHTWEIGHT;
CREATE EDGE TYPE HAS_NAME IF NOT EXISTS LIGHTWEIGHT;
CREATE EDGE TYPE HAS_BUSINESS IF NOT EXISTS LIGHTWEIGHT;
-- Sealed partitions only, after CHECK DATABASE ... FIX (C7): ALTER DATABASE `arcadedb.graph.ghostEdgeProbe` false;
