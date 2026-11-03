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
