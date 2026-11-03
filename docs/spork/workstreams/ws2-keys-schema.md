# WS2 (Java side): `spork-keys` and schema v1

Train 1, Recon track (01-architecture.md section 8). Branch `claude/ws2-keys-schema-ee9519`. Status: the Java key library,
the golden vectors, schema v1 for `identity`, `recdir`, `rec_<p>`, `txt_<p>` and `media_<p>`, and its installer are done
and tested. The TS port, ETL, the offline GraphBatch build and formation are follow-ups (below).

## What shipped

**New Maven module `spork-keys`** (`arcadedb-spork-keys`), added to the root `pom.xml` right after `engine`. It depends
on `arcadedb-engine` only (for `com.arcadedb.similarity.Stylometry` and the schema API), never on the server, so ingest,
the offline build and the WS3 plugin can all embed it. No new third-party dependency, so ATTRIBUTIONS.md is unchanged.
WS3 may add a module to the root `pom.xml` too: both are one-line additions to the `<modules>` list, so a merge conflict
there, if any, is resolved by keeping both lines.

`com.arcadedb.spork.keys`:

| Class | What |
|---|---|
| `SporkKeys` | The canonical keys of 2.3, one function per kind: `phone` (`ph:`), `email` (`em:`), `handle` (`sh:<platform>:<handle>`), `business` (`bz:`), `imageHash` (`ih:`), `name` (`nm:`), `place` (`pl:...`), plus `placeId`, `cityNorm`, `geohash7`, `phoneKeys` (both 7-digit keys for `Target.identifierKeys[]`), `packImageHash`/`unpackImageHash`. Null when the input normalizes to nothing. |
| `ReconNormalizers` | Exact ports of every Recon use site, for parity and the WS4 shadow diff: `engine.ts` canonPhone, areaCodeOf, clusterKey, link keys, classifySource; `compare.ts` normPhone; `ops/board.ts` normalizeAnchor; `ops/dossier.ts` identKey; `ops/feed.ts` phoneKey; `ops/audit.ts` intakeSubject. |
| `ReconGeography` | Recon's CITIES, AREA_CODE_TO_PLACE, PLACE_TO_AREA and the `api-source.ts` gazetteer. |
| `UpstreamRecords` | `api-source.ts` mapRecord's field extraction from an enriched RedLight record (phones, emails, socials, business names, image hashes, city/state/country, the lat/lon fallback chain), on `JSONObject`. |
| `StyleKeys` | `Record.style` and `Record.styleVec`: delegates to the engine's `Stylometry` (Recon `identity.ts` port), not ported again. |
| `TextHash` | `textHash`, 32 bytes. |
| `UpstreamRid` | `clusterId << 40 \| clusterPos` and back (`"168:8"`, `"#168:8"`). |
| `RecordId` | `period << 48 \| sequence`. |

`com.arcadedb.spork.schema.SporkSchema`: `install(database)` picks the kind from the database name (`identity`, `recdir`,
`rec_*`, `txt_*`, `media_*`) and runs `common.sql` then the kind's script from
`spork-keys/src/main/resources/spork/schema/v1/`, then the three 64 KB Java-builder indexes (Place GEOSPATIAL, Evidence and
TextBody FULL_TEXT), then upserts a `SporkSchemaInfo {name, version}` row. Every statement is idempotent; the test installs
every kind twice on a fresh database and compares the full schema snapshot.

**`docs/spork/config/schema-ddl.sql` is now generated** from those scripts (`SporkSchema.documentation()`), and
`SporkSchemaTest.documentedDdlMatchesTheInstalledScripts` fails while they differ. Edit the scripts, then:

```bash
mvn -o -pl spork-keys -am test -Dtest=SporkSchemaTest -Dsurefire.failIfNoSpecifiedTests=false -Dspork.schema.regenerate=true
```

## Golden vectors

`spork-keys/golden/generate-golden.ts` writes `spork-keys/src/test/resources/golden/*.json` from Recon's own code (Recon
commit `7815426`): exported functions are imported, the non-exported ones (`canonPhone`, `areaCodeOf`, `clusterKey`,
`compare.ts` normPhone, `mapRecord` and its helpers) are cut verbatim out of the source and evaluated, and the inline link
keys that cannot be cut out are transcribed with a check that fails the generator when the source line changes. Each case
carries every Recon use site's output and the SPORK key side by side. `GoldenVectorsTest` replays them; the output is
deterministic. After a Recon change:

```bash
bun spork-keys/golden/generate-golden.ts ~/Documents/Projects/recon/recon-next
```

Coverage: 301 phone cases (43 inputs x 7 posting places), 14 emails, 10 socials, 13 business names, 20 names, 7 image
hashes, 16 style texts (bit-exact doubles and the fingerprint), 7 text hashes, 11 RIDs, 10 upstream locations, a full
upstream record, 15 intake tokens, and the area-code tables. Inputs include JS-vs-Java traps: NBSP and U+2003 (JS `trim`
and `\s` cover them, Java's do not), C0 controls (Java `trim` strips them, JS does not), Arabic-Indic and fullwidth digits
(JS `\D` is ASCII), dotted capital I, final sigma, titlecase digraphs.

## Decisions (where the rule in 2.3 needed one)

- **Phone key = Recon's engine link key.** `ph:` + `engine.ts` canonPhone with the posting place: ASCII digits, last 10, a
  7-digit number inherits the place's area code. Other Recon sites disagree on this corpus: board 87 of 301 cases, feed 94,
  dossier 59, compare 24 (mostly 7-digit numbers in a known city, 11-digit numbers not starting with 1, and under-7-digit
  tokens that board, feed and dossier key as lowercased text). Each is a WS4 shadow-diff decision, not a bug here.
- **Recon quirk kept: Houston 7-digit numbers inherit 281, not 713.** PLACE_TO_AREA takes the first area code per place in
  `Object.entries` order, and JS enumerates integer-like keys numerically. The port reproduces it (and so does the key);
  changing it is a Recon-visible change for WS4.
- **Place match is case sensitive** (`"Houston, TX"` matches, `"houston, tx"` does not), as in Recon.
- **Email and handle keys are JS-trimmed**, then lowercased, one leading `@` stripped. The engine link key does not trim
  (4 of 14 email cases differ: whitespace padding or a leading `@`); the board and dossier do. A blank platform is Recon's `social`.
- **Business key = the cluster/dossier rule** (lowercase ASCII alnum). The engine's link-cluster key and the board only
  lowercase (12 of 13 cases differ): the 2.3 rule as written.
- **Name key** (no Recon use site exists): NFKD, lowercased, letters only, **with whitespace runs between letters kept as
  one space** (`nm:jose garcia`), so "John Smith" and "Johns Mith" stay apart. Punctuation inside a token is dropped
  (`O'Brien` to `obrien`), digits are dropped (`Smith 3rd` to `smith rd`).
- **Image hash** verbatim, never trimmed. Packed to 32 bytes only when it is 64 lowercase hex (only that round-trips to the
  exact string); the vertex key stays the `ih:` STRING in v1.
- **textHash** = SHA-256 over the 4-byte big-endian UTF-8 length of the title, the title, then the body. The length prefix
  keeps ("Hi", "there") and ("Hit", "here") apart, which plain title + body would merge into one TextBody with one title.
  Stored as the full 32 bytes (the "sha256/128" wording in 2.1 is open, see follow-ups).
- **Place key** `pl:<cc>:<state>:<city>[:<geohash7>]`: components trimmed, lowercased, whitespace-collapsed, `:` read as a
  space; the geohash only when the point is known (Recon's 0 means missing). `placeId` = first 8 bytes of SHA-256 of the
  key, big-endian. Lat/lon come from Recon's chain: search row, record geocode, gazetteer.
- **`record_id` is a LONG** with the period in the top 16 bits (2.1 says UUID; a LONG keeps the HASH index on 4 KB pages,
  0.4, and needs no directory). `upstreamRid` keeps 23 bits of cluster id over 40 of position, so every packed value is
  non-negative and -1 means "not a RID" without boxing.
- **Markings:** a `LONG` bitmask on `Record`, `Image`, `Face`, `Tattoo` (2.1 "markings (bitmask)"); `LIST OF STRING` on
  registry objects and edges (2.1 `markings[]`). Owner question 16 may unify them.
- **`_prov`** is `EMBEDDED OF Prov {sourceId, collectedAt, assertedBy, confidence}` on `Record`, `Identity` and
  `Assertion`. Its on-disk size against the 13 B of 0.4 is for P0a to measure.
- **VERSIONED** on `Identity`, `MEMBER_OF` and `Assertion` (2.1 makes Assertion versioned too). 2.6 says Q1 runs on the
  stock engine and turns versioning on at the Q2 engine merge; since native versioning is already on main, schema v1
  sets it now. A Q1 deployment on a stock engine would need those three statements dropped.
- `Identifier`, `SoftLink` and `CaseScoped` are interface types (`BUCKETS 0`); every concrete type has one bucket and its
  own UNIQUE HASH. `Record.upstreamRid` and `(tenantId, importedId)` are UNIQUE HASH with `NULL_STRATEGY SKIP`, because an
  analyst import has no upstreamRid and an upstream record has no importedId. Bare `Handle.handle`, `Target.anchorKey`,
  `Target.assignee`, `Alert.watchKey` and friends are NOTUNIQUE HASH (exact lookups, C8).
- `Case` is an SQL keyword: the scripts back-quote it.
- **Record display fields follow Recon's `ReconRecord` names** for the hydrate projection (WS3): `city`, `state`, `country` (display, Recon defaults `Unknown`, `??`, `US`) next to the normalized `cityNorm`, `stateCode`, `countryCode`; `paymentTypes` (2.1 says `payment`); `socials` as `platform:handle` strings.

## Follow-ups (out of scope here)

1. **TS port** of `SporkKeys` inside Recon (`generate-golden.ts` holds the reference implementation to start from), with
   the same golden files replayed on the TS side.
2. **ETL** (6.1 step 2): transform upstream NDJSON with `UpstreamRecords` + `SporkKeys` + `StyleKeys` + `TextHash`, the
   pass-1 identifier counts, period assignment and `recdir` entries.
3. **Offline GraphBatch build on n4** with `withWAL(true)` and the 10M-record P0 gate (6.1 step 3), creating every
   database through `SporkSchema.install`.
4. **Initial formation** by leader-shipped snapshots (6.1 steps 5 and 6).
5. **WS4 shadow-diff decisions** for each Recon disagreement listed above (the golden files are the test corpus).
6. **Schema still to add:** the `ops` and `audit` databases (1.2); the term df table, `ChainHead`, `OutboxEvent`,
   `OutboxCounter` and the AuditEntry chain columns (WS3 owns the chain); `Review`; the `StyleVec` / `LSM_VECTOR` index
   (WS6) and the S-TEXT `LSM_IVF_VECTOR` (WS13/E18); `Image.pdq`, `pdqBands` and the band index (WS12).
7. **Open questions:** the `Url` key rule (2.1 lists the type, 2.3 has no rule; v1 creates the type and index only);
   `textHash` "sha256/128" (16 B truncated would save 16 B per record and TextBody, 4.6 GB per 290M records); storing
   `ImageHash` keys as packed bytes; `stateCode` normalization (full state names to codes; Recon only maps the other way).
