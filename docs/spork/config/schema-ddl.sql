-- SPORK schema v1 (01-architecture.md 1.2, 2.1, 2.2, 2.5; 02-retrieval-speed.md C8, C9).
--
-- GENERATED, do not edit: this file is the concatenation of the scripts SporkSchema installs, from
-- spork-keys/src/main/resources/spork/schema/v1/. Edit those and regenerate with
--   mvn -o -pl spork-keys -am test -Dtest=SporkSchemaTest -Dsurefire.failIfNoSpecifiedTests=false -Dspork.schema.regenerate=true
-- SporkSchemaTest fails while the two differ. Install with com.arcadedb.spork.schema.SporkSchema.install(database):
-- common.sql runs on every database, then the script of the database's kind (identity, recdir, rec_<p>, txt_<p>,
-- media_<p>), then the indexes marked JAVA (64 KB FULL_TEXT and GEOSPATIAL, which SQL cannot size).
-- Every statement is idempotent: the installer runs on a fresh database and again on an installed one.

-- >>> common.sql
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

-- >>> identity.sql
-- =====================================================================================================================
-- identity (1.2, 2.1, 2.2, 2.6, 2.7, 4b, 4d): shared identifiers (2 or more records, 2.4), Place, Identity and its
-- memberships, the board and case layer, the ontology registry, the decision layer. One bucket per type (2.0), HASH on
-- every exact key (C8), identifier vertices on 16 KB pages (C9). fullSeriesThreshold stays 0 (common.sql; round 6).
-- =====================================================================================================================

-- ── Identifiers (2.1) ───────────────────────────────────────────────────────────────────────────────────────────────
-- Identifier is the interface (2.7): no bucket of its own, every concrete type one bucket and one UNIQUE HASH on key
-- (a type index on the supertype would span 7 buckets; 2.0 wants 1 probe per lookup). Keys are spork-keys canonical
-- STRINGs (2.3), so UNIQUE_HASH gets 16 KB pages from the engine default for variable-width keys.
CREATE VERTEX TYPE Identifier IF NOT EXISTS BUCKETS 0;
CREATE PROPERTY Identifier.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Identifier.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY Identifier.lastSeen IF NOT EXISTS LONG;
CREATE PROPERTY Identifier.recordCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Identifier.periodMask IF NOT EXISTS LONG;                  -- a bit per period
CREATE PROPERTY Identifier.topCells IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Identifier.componentId IF NOT EXISTS LONG;                 -- the labeled "network" view (2.6)
CREATE PROPERTY Identifier.hub IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Identifier.stop IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Identifier.holdCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Identifier.retentionClass IF NOT EXISTS STRING;
CREATE PROPERTY Identifier.markings IF NOT EXISTS LIST OF STRING;
CREATE VERTEX TYPE Phone     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Email     IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Handle    IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Name      IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Business  IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE ImageHash IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
CREATE VERTEX TYPE Url       IF NOT EXISTS EXTENDS Identifier BUCKETS 1 PAGESIZE 16384;
-- Kind-specific fields (2.1 "extras"; the gram and phonetic lists are local-copy only, rec.sql)
CREATE PROPERTY Phone.digits IF NOT EXISTS STRING;
CREATE PROPERTY Phone.rev IF NOT EXISTS STRING;
CREATE PROPERTY Phone.areaCode IF NOT EXISTS STRING;
CREATE PROPERTY Phone.exchange IF NOT EXISTS STRING;
CREATE PROPERTY Phone.carrier IF NOT EXISTS STRING;
CREATE PROPERTY Phone.cc IF NOT EXISTS STRING;
CREATE PROPERTY Phone.inferredAreaCode IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Email.domain IF NOT EXISTS STRING;
CREATE PROPERTY Email.local IF NOT EXISTS STRING;
CREATE PROPERTY Handle.platform IF NOT EXISTS STRING;
CREATE PROPERTY Handle.handle IF NOT EXISTS STRING;
CREATE PROPERTY Name.first IF NOT EXISTS STRING;
CREATE PROPERTY Name.middle IF NOT EXISTS STRING;
CREATE PROPERTY Name.last IF NOT EXISTS STRING;
CREATE PROPERTY Name.aliases IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Name.tokens IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Business.businessName IF NOT EXISTS STRING;
CREATE PROPERTY Business.erotic IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Business.opened IF NOT EXISTS LONG;
CREATE PROPERTY ImageHash.hash IF NOT EXISTS STRING;                       -- the upstream string verbatim (2.1)
CREATE PROPERTY Url.url IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Phone (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Email (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Handle (handle) NOTUNIQUE_HASH;               -- 2.1: bare-handle intake (7.2), exact
CREATE INDEX IF NOT EXISTS ON Name (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Business (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON ImageHash (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Url (key) UNIQUE_HASH;

-- FaceCluster (2.1): fk:<algo@ver>:<ulid>
CREATE VERTEX TYPE FaceCluster IF NOT EXISTS BUCKETS 1 PAGESIZE 16384;
CREATE PROPERTY FaceCluster.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY FaceCluster.representatives IF NOT EXISTS LIST OF STRING;   -- up to 3 face keys
CREATE PROPERTY FaceCluster.memberFaceCount IF NOT EXISTS INTEGER;
CREATE PROPERTY FaceCluster.minorFlag IF NOT EXISTS BOOLEAN;
CREATE PROPERTY FaceCluster.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY FaceCluster.lastSeen IF NOT EXISTS LONG;
CREATE PROPERTY FaceCluster.markings IF NOT EXISTS LIST OF STRING;
CREATE INDEX IF NOT EXISTS ON FaceCluster (key) UNIQUE_HASH;

-- Place (2.1): pl:<cc>:<state>:<city>[:<geohash7>] and its placeId LONG (SporkKeys.placeId) with its own UNIQUE HASH
-- (4 KB pages, the LONG default). point is WKT for the GEOSPATIAL index, built at 64 KB by SporkSchema (Java builder).
CREATE VERTEX TYPE Place IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Place.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Place.placeId IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY Place.point IF NOT EXISTS STRING;
CREATE PROPERTY Place.city IF NOT EXISTS STRING;
CREATE PROPERTY Place.cityNorm IF NOT EXISTS STRING;
CREATE PROPERTY Place.state IF NOT EXISTS STRING;
CREATE PROPERTY Place.county IF NOT EXISTS STRING;
CREATE PROPERTY Place.zip IF NOT EXISTS STRING;
CREATE PROPERTY Place.country IF NOT EXISTS STRING;
CREATE PROPERTY Place.generalLocation IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Place (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Place (placeId) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Place (cityNorm) NOTUNIQUE_HASH;              -- city-only filters resolve to placeIds (4)

-- Identity (2.1, 2.6): idn_<ulid>. VERSIONED (round 7, 08-native-versioning-design.md): the engine stamps _txFrom and
-- keeps every superseded image in _VersionHistory, so history.of / history.asOf replay its states. Analyst-asserted
-- properties carry _prov (2.7). Records written before the flag are valid since the beginning (2.6).
CREATE VERTEX TYPE Identity IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Identity.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Identity.kind IF NOT EXISTS STRING;                         -- person, persona, org, unknown
CREATE PROPERTY Identity.status IF NOT EXISTS STRING;                       -- active, superseded
CREATE PROPERTY Identity.confidence IF NOT EXISTS DOUBLE;
CREATE PROPERTY Identity.createdByDecision IF NOT EXISTS STRING;
CREATE PROPERTY Identity.displayName IF NOT EXISTS STRING;
CREATE PROPERTY Identity.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY Identity.markings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Identity._prov IF NOT EXISTS EMBEDDED OF Prov;
CREATE INDEX IF NOT EXISTS ON Identity (key) UNIQUE_HASH;
ALTER TYPE Identity VERSIONED true;

-- ── Edges (2.2) ─────────────────────────────────────────────────────────────────────────────────────────────────────
-- Registry edges an analyst can see carry their own markings, independent of their endpoints.
-- CO_OCCURS: Identifier to Identifier, aggregated; reposts only raise the weight.
CREATE EDGE TYPE CO_OCCURS IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY CO_OCCURS.weight IF NOT EXISTS INTEGER;
CREATE PROPERTY CO_OCCURS.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY CO_OCCURS.lastSeen IF NOT EXISTS LONG;
CREATE PROPERTY CO_OCCURS.markings IF NOT EXISTS LIST OF STRING;
-- MEMBER_OF: shared Identifier or FaceCluster to Identity. VERSIONED (a regular edge type: lightweight edges have no
-- record to version). A membership that ends is deleted; history.edges(identity, t) is the as-of view (2.6).
CREATE EDGE TYPE MEMBER_OF IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY MEMBER_OF.decisionId IF NOT EXISTS STRING;
CREATE PROPERTY MEMBER_OF.confidence IF NOT EXISTS DOUBLE;
CREATE PROPERTY MEMBER_OF.markings IF NOT EXISTS LIST OF STRING;
ALTER TYPE MEMBER_OF VERSIONED true;
-- TARGETS: Target to a shared Identifier or Identity; SUPERSEDED_BY: Identity to Identity
CREATE EDGE TYPE TARGETS IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY TARGETS.markings IF NOT EXISTS LIST OF STRING;
CREATE EDGE TYPE SUPERSEDED_BY IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY SUPERSEDED_BY.decisionId IF NOT EXISTS STRING;
-- Soft edges: proposed links with evidence, never automatic (2.2, 2.6). SoftLink is their shared shape, no bucket of its
-- own; an accepted link is also written as ACCEPTED_LINK because GraphAnalyticalViewBuilder filters by type only.
CREATE EDGE TYPE SoftLink IF NOT EXISTS BUCKETS 0;
CREATE PROPERTY SoftLink.confidence IF NOT EXISTS DOUBLE;                    -- calibrated
CREATE PROPERTY SoftLink.rawScore IF NOT EXISTS DOUBLE;
CREATE PROPERTY SoftLink.method IF NOT EXISTS STRING;                        -- style24@v2, roc-021@3.17, jw+dm@v1
CREATE PROPERTY SoftLink.threshold IF NOT EXISTS DOUBLE;
CREATE PROPERTY SoftLink.evidence IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY SoftLink.createdAt IF NOT EXISTS LONG;
CREATE PROPERTY SoftLink.createdBy IF NOT EXISTS STRING;
CREATE PROPERTY SoftLink.opContext IF NOT EXISTS MAP;
CREATE PROPERTY SoftLink.state IF NOT EXISTS STRING;                         -- proposed, accepted, rejected
CREATE PROPERTY SoftLink.decisionId IF NOT EXISTS STRING;
CREATE PROPERTY SoftLink.markings IF NOT EXISTS LIST OF STRING;
CREATE EDGE TYPE SIMILAR_STYLE IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;
CREATE EDGE TYPE SIMILAR_FACE IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;
CREATE EDGE TYPE NAME_LIKE IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;
CREATE EDGE TYPE CO_LOCATED IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;
CREATE EDGE TYPE ANALYST_LINK IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;
CREATE EDGE TYPE ACCEPTED_LINK IF NOT EXISTS EXTENDS SoftLink BUCKETS 1;

-- ── Decisions (2.6) and assertions (2.7) ────────────────────────────────────────────────────────────────────────────
CREATE DOCUMENT TYPE ResolutionDecision IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY ResolutionDecision.key IF NOT EXISTS STRING (mandatory true, notnull true);   -- rd_<ulid>
CREATE PROPERTY ResolutionDecision.kind IF NOT EXISTS STRING;                -- merge, split, link, unlink
CREATE PROPERTY ResolutionDecision.inputs IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ResolutionDecision.outputs IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ResolutionDecision.canonicalKey IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.winnerKey IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.otherKeys IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ResolutionDecision.rule IF NOT EXISTS STRING;                -- e.g. hard-key-v1
CREATE PROPERTY ResolutionDecision.ruleVersion IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.actor IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.opContext IF NOT EXISTS MAP;
CREATE PROPERTY ResolutionDecision.rationale IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.supersedes IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.revertedBy IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.deciderLogId IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY ResolutionDecision.createdAt IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON ResolutionDecision (key) UNIQUE_HASH;
-- Assertion (2.1, round 7): one per asserted property of a subject whose type is NOT versioned; itself versioned, so a
-- correction updates value and _prov in place and history.of replays it. value is untyped on purpose.
CREATE DOCUMENT TYPE Assertion IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Assertion.subjectKey IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Assertion.propertyType IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Assertion._prov IF NOT EXISTS EMBEDDED OF Prov;
CREATE PROPERTY Assertion.markings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Assertion.state IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Assertion (subjectKey, propertyType) UNIQUE_HASH;
ALTER TYPE Assertion VERSIONED true;

-- ── Board and case layer (2.1) ──────────────────────────────────────────────────────────────────────────────────────
-- Target: Recon's tgt_ id verbatim, never recomputed with spork-keys. identifierKeys[] (spork-keys canonical, both
-- 7-digit phone keys) BY ITEM for deconfliction. snapshot and history are EXTERNAL so a list never inlines them (2.0).
CREATE VERTEX TYPE Target IF NOT EXISTS BUCKETS 1 PAGESIZE 32768;
CREATE PROPERTY Target.id IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Target.anchor IF NOT EXISTS MAP;                             -- {type, value} verbatim
CREATE PROPERTY Target.anchorKey IF NOT EXISTS STRING;
CREATE PROPERTY Target.identifierKeys IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.label IF NOT EXISTS STRING;
CREATE PROPERTY Target.priority IF NOT EXISTS STRING;
CREATE PROPERTY Target.assessment IF NOT EXISTS STRING;
CREATE PROPERTY Target.disposition IF NOT EXISTS STRING;
CREATE PROPERTY Target.assignee IF NOT EXISTS STRING;
CREATE PROPERTY Target.caseRef IF NOT EXISTS STRING;
CREATE PROPERTY Target.caseIds IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.sources IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.snapshot IF NOT EXISTS EMBEDDED (external true);
CREATE PROPERTY Target.history IF NOT EXISTS LIST (external true);
CREATE PROPERTY Target.notes IF NOT EXISTS LIST;
CREATE PROPERTY Target.tags IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.origin IF NOT EXISTS STRING;                          -- intake, watch, manual
CREATE PROPERTY Target.grams IF NOT EXISTS LIST OF STRING;                   -- board search grams (4)
CREATE PROPERTY Target.createdAt IF NOT EXISTS LONG;
CREATE PROPERTY Target.updatedAt IF NOT EXISTS LONG;
CREATE PROPERTY Target.revision IF NOT EXISTS INTEGER;                       -- OCC
CREATE PROPERTY Target.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY Target.markings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Target.subjectDeleted IF NOT EXISTS BOOLEAN;
CREATE INDEX IF NOT EXISTS ON Target (id) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Target (anchorKey) NOTUNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Target (identifierKeys BY ITEM) NOTUNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Target (assignee) NOTUNIQUE_HASH;
-- Case-scoped documents: case-scoped key, tenantId, caseId, revision (2.1). Evidence carries the FULL_TEXT body, built at
-- 64 KB by SporkSchema (Java builder).
CREATE DOCUMENT TYPE CaseScoped IF NOT EXISTS BUCKETS 0;
CREATE PROPERTY CaseScoped.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY CaseScoped.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY CaseScoped.caseId IF NOT EXISTS STRING;
CREATE PROPERTY CaseScoped.revision IF NOT EXISTS INTEGER;
CREATE PROPERTY CaseScoped.markings IF NOT EXISTS LIST OF STRING;
CREATE DOCUMENT TYPE `Case` IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE CaseMember IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE Evidence IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE WikiRevision IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE Binding IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE Detector IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE LegalHold IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE RetentionPolicy IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE DOCUMENT TYPE MindMapNote IF NOT EXISTS EXTENDS CaseScoped BUCKETS 1;
CREATE PROPERTY Evidence.body IF NOT EXISTS STRING;                          -- title + excerpt + origin (4)
CREATE INDEX IF NOT EXISTS ON `Case` (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON CaseMember (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON CaseMember (caseId) NOTUNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Evidence (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Evidence (caseId) NOTUNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON WikiRevision (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Binding (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Detector (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON LegalHold (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON RetentionPolicy (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON MindMapNote (key) UNIQUE_HASH;
-- Watch: UNIQUE normalized source-set key (409 on a duplicate); Alert: Recon's al_ id, watchKey for the remove cascade
CREATE VERTEX TYPE Watch IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Watch.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Watch.sources IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Watch.threshold IF NOT EXISTS DOUBLE;
CREATE PROPERTY Watch.baseline IF NOT EXISTS MAP;
CREATE PROPERTY Watch.lastScan IF NOT EXISTS LONG;
CREATE PROPERTY Watch.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY Watch.createdOc IF NOT EXISTS MAP;
CREATE PROPERTY Watch.markings IF NOT EXISTS LIST OF STRING;
CREATE INDEX IF NOT EXISTS ON Watch (key) UNIQUE_HASH;
CREATE DOCUMENT TYPE Alert IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Alert.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Alert.watchKey IF NOT EXISTS STRING;
CREATE PROPERTY Alert.watchName IF NOT EXISTS STRING;
CREATE PROPERTY Alert.kind IF NOT EXISTS STRING;                             -- threshold, new, escalated
CREATE PROPERTY Alert.anchor IF NOT EXISTS MAP;
CREATE PROPERTY Alert.score IF NOT EXISTS DOUBLE;
CREATE PROPERTY Alert.delta IF NOT EXISTS DOUBLE;
CREATE PROPERTY Alert.families IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY Alert.command IF NOT EXISTS STRING;
CREATE PROPERTY Alert.at IF NOT EXISTS LONG;
CREATE PROPERTY Alert.tenantId IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Alert (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Alert (watchKey) NOTUNIQUE_HASH;
-- Missions (2.1): Mission id UNIQUE plus an updatedAt range index; CommandKey request key UNIQUE
CREATE DOCUMENT TYPE Mission IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Mission.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Mission.revision IF NOT EXISTS INTEGER;
CREATE PROPERTY Mission.updatedAt IF NOT EXISTS LONG;
CREATE PROPERTY Mission.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY Mission.createdOc IF NOT EXISTS MAP;
CREATE PROPERTY Mission.markings IF NOT EXISTS LIST OF STRING;
CREATE INDEX IF NOT EXISTS ON Mission (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Mission (updatedAt) NOTUNIQUE;
CREATE DOCUMENT TYPE CommandKey IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY CommandKey.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY CommandKey.missionId IF NOT EXISTS STRING;
CREATE PROPERTY CommandKey.fingerprint IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON CommandKey (key) UNIQUE_HASH;
-- Ranker (2.1): model version; analyst feedback by anchorKey; the label ledger by targetId
CREATE DOCUMENT TYPE RankerModel IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY RankerModel.version IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON RankerModel (version) UNIQUE_HASH;
CREATE DOCUMENT TYPE Feedback IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Feedback.anchorKey IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON Feedback (anchorKey) UNIQUE_HASH;
CREATE DOCUMENT TYPE BoardLabel IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY BoardLabel.targetId IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON BoardLabel (targetId) UNIQUE_HASH;

-- ── Ontology registry (2.7) and saved sets (4b) ─────────────────────────────────────────────────────────────────────
CREATE DOCUMENT TYPE ObjectType IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY ObjectType.apiName IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY ObjectType.title IF NOT EXISTS STRING;
CREATE PROPERTY ObjectType.primaryKeyProperty IF NOT EXISTS STRING;
CREATE PROPERTY ObjectType.titleProperty IF NOT EXISTS STRING;
CREATE PROPERTY ObjectType.interfaces IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ObjectType.prominent IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ObjectType.defaultMarkings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ObjectType.retentionClass IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON ObjectType (apiName) UNIQUE_HASH;
CREATE DOCUMENT TYPE PropertyType IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY PropertyType.objectType IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY PropertyType.apiName IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY PropertyType.baseType IF NOT EXISTS STRING;
CREATE PROPERTY PropertyType.searchable IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY PropertyType.markings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY PropertyType.pii IF NOT EXISTS BOOLEAN;
CREATE PROPERTY PropertyType.displayMask IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON PropertyType (objectType, apiName) UNIQUE_HASH;
CREATE DOCUMENT TYPE LinkType IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY LinkType.apiName IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY LinkType.sideA IF NOT EXISTS MAP;
CREATE PROPERTY LinkType.sideB IF NOT EXISTS MAP;
CREATE PROPERTY LinkType.cardinality IF NOT EXISTS STRING;
CREATE PROPERTY LinkType.directed IF NOT EXISTS BOOLEAN;
CREATE PROPERTY LinkType.propertyTypes IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY LinkType.transformId IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON LinkType (apiName) UNIQUE_HASH;
CREATE DOCUMENT TYPE Marking IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Marking.name IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Marking.kind IF NOT EXISTS STRING;
CREATE PROPERTY Marking.parent IF NOT EXISTS STRING;
CREATE INDEX IF NOT EXISTS ON Marking (name) UNIQUE_HASH;
CREATE DOCUMENT TYPE ObjectSetDef IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY ObjectSetDef.defHash IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY ObjectSetDef.tree IF NOT EXISTS MAP;
CREATE PROPERTY ObjectSetDef.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY ObjectSetDef.caseId IF NOT EXISTS STRING;
CREATE PROPERTY ObjectSetDef.markings IF NOT EXISTS LIST OF STRING;
CREATE PROPERTY ObjectSetDef.createdOc IF NOT EXISTS MAP;
CREATE INDEX IF NOT EXISTS ON ObjectSetDef (defHash) UNIQUE_HASH;
CREATE DOCUMENT TYPE ObjectSetSnapshot IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY ObjectSetSnapshot.defHash IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY ObjectSetSnapshot.at IF NOT EXISTS LONG (mandatory true, notnull true);
CREATE PROPERTY ObjectSetSnapshot.members IF NOT EXISTS BINARY (external true);   -- roaring bitmap, envelope-encrypted
CREATE PROPERTY ObjectSetSnapshot.tenantId IF NOT EXISTS STRING;
CREATE PROPERTY ObjectSetSnapshot.markings IF NOT EXISTS LIST OF STRING;
CREATE INDEX IF NOT EXISTS ON ObjectSetSnapshot (defHash, at) UNIQUE;        -- range on at: LSM 64 KB

-- ── Decision layer (4d) ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE DOCUMENT TYPE DecisionLog IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY DecisionLog.key IF NOT EXISTS STRING (mandatory true, notnull true);   -- dl_<ulid>
CREATE INDEX IF NOT EXISTS ON DecisionLog (key) UNIQUE_HASH;
CREATE DOCUMENT TYPE OutcomeFeedback IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY OutcomeFeedback.decisionLogId IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON OutcomeFeedback (decisionLogId) NOTUNIQUE_HASH;
CREATE DOCUMENT TYPE ThresholdPolicy IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY ThresholdPolicy.question IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY ThresholdPolicy.riskLevel IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE INDEX IF NOT EXISTS ON ThresholdPolicy (question, riskLevel) UNIQUE_HASH;
CREATE DOCUMENT TYPE AnalystQueueItem IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY AnalystQueueItem.key IF NOT EXISTS STRING (mandatory true, notnull true);   -- aq_<ulid>
CREATE INDEX IF NOT EXISTS ON AnalystQueueItem (key) UNIQUE_HASH;

-- NOT EXPRESSIBLE: arcadedb.graph.edgeListInitialChunkSize=2048 "on shared identifier vertices" (C7): the value is read
-- JVM-wide (LocalDatabase.getNewEdgeListSize) and there is no per-type API; engine item 02 S18 / 01 E24.
-- JAVA (SporkSchema): Place (point) GEOSPATIAL and Evidence (body) FULL_TEXT at 64 KB pages.

-- >>> recdir.sql
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

-- >>> rec.sql
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
-- The records a search count may have to hide (WS3 P4, 01 5.3): marked records and analyst imports. Sparse because unmarked
-- records leave markings null and upstream records have no tenantId, so the plugin walks them per request instead of reading
-- a Record per counted entry.
CREATE INDEX IF NOT EXISTS ON Record (markings) NOTUNIQUE NULL_STRATEGY SKIP;
CREATE INDEX IF NOT EXISTS ON Record (tenantId) NOTUNIQUE NULL_STRATEGY SKIP;
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

-- >>> txt.sql
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

-- >>> media.sql
-- =====================================================================================================================
-- media_<p> (1.2, 2.1): Image by content sha, Face and Tattoo by their keys, on the rec_<p> period boundaries. The PDQ
-- columns (pdq, pdqBands and its hot-window BY ITEM index, pdqClusterRep) are WS12's and the S-IMG / S-PDQ / S-FACE /
-- S-TATTOO LSM_IVF_VECTOR indexes are E18's; neither is created here.
-- =====================================================================================================================
CREATE VERTEX TYPE Image IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Image.sha IF NOT EXISTS BINARY (mandatory true, notnull true);            -- content SHA-256, 32 B
CREATE PROPERTY Image.upstreamHash IF NOT EXISTS STRING;                                  -- Recon's hash, verbatim
CREATE PROPERTY Image.phash64 IF NOT EXISTS LONG;
CREATE PROPERTY Image.novelty IF NOT EXISTS FLOAT;
CREATE PROPERTY Image.ocrText IF NOT EXISTS LIST;
CREATE PROPERTY Image.csamHashHit IF NOT EXISTS BOOLEAN;                                  -- set before any vision model (5.7)
CREATE PROPERTY Image.mime IF NOT EXISTS STRING;
CREATE PROPERTY Image.w IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.h IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.firstSeen IF NOT EXISTS LONG;
CREATE PROPERTY Image.faceCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.tattooCount IF NOT EXISTS INTEGER;
CREATE PROPERTY Image.markings IF NOT EXISTS LONG;                                        -- bitmask, as on Record
CREATE INDEX IF NOT EXISTS ON Image (sha) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Image (upstreamHash) NOTUNIQUE_HASH;                        -- Recon hash to content sha
-- Face: fc:<imageSha>:<idx>:<algo>. Gallery slot and face codes are envelope-encrypted (5.6) and written by WS-ROC.
CREATE VERTEX TYPE Face IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Face.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Face.imageSha IF NOT EXISTS BINARY;
CREATE PROPERTY Face.quality IF NOT EXISTS FLOAT;
CREATE PROPERTY Face.ageBand IF NOT EXISTS STRING;
CREATE PROPERTY Face.minorFlag IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Face.clusterKey IF NOT EXISTS STRING;
CREATE PROPERTY Face.representative IF NOT EXISTS BOOLEAN;
CREATE PROPERTY Face.state IF NOT EXISTS STRING;                                          -- staged, searchable, tombstoned
CREATE PROPERTY Face.markings IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON Face (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Face (imageSha) NOTUNIQUE_HASH;
CREATE VERTEX TYPE Tattoo IF NOT EXISTS BUCKETS 1;
CREATE PROPERTY Tattoo.key IF NOT EXISTS STRING (mandatory true, notnull true);
CREATE PROPERTY Tattoo.imageSha IF NOT EXISTS BINARY;
CREATE PROPERTY Tattoo.markings IF NOT EXISTS LONG;
CREATE INDEX IF NOT EXISTS ON Tattoo (key) UNIQUE_HASH;
CREATE INDEX IF NOT EXISTS ON Tattoo (imageSha) NOTUNIQUE_HASH;
