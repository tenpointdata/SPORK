# Identity resolution: cluster compare and group operations

Status: design note, slices 1, 2a and 3 and MLOps step 1 (`05-identity-model-mlops.md`) implemented, 2026-11-03. Roadmap items N10 (probabilistic match scoring) and F15 (versioned,
explainable resolution) in `02-feature-roadmap.md`; data model in `01-architecture.md` 2.6.
Goals served: **I** (identity layer, replacing Senzing), **S** (set-oriented: the inputs are clusters, not records).

## 1. Problem

Senzing answers "are these two entities the same?" with a resolve / possible match / possibly related ladder and a
`why` explanation. SPORK needs the same answer inside the database, for the question analysts actually ask: **given two
clusters of records (two footprints, two candidate personas, two resolved identities), how likely is it that they are one
individual, and how sure is that number?** Three properties matter more than in a generic entity-resolution product:

- **Time.** Trafficking identifiers move: burner phones are dropped, numbers are recycled, accounts are sold, agency phones
  are shared by several workers at once. A phone shared a day apart and a phone shared four years apart are different
  evidence.
- **Space-time contradictions.** Two clusters posting in New York and Los Angeles an hour apart are most likely two people.
- **An honest interval.** Evidence often rests on one or two rare identifiers whose frequency is itself estimated from a
  handful of records. A point probability hides that; the analyst needs the range.

## 2. Slice 1: `identity.compare`, `identity.cohesion` and `identity.candidates`

Code: `engine/src/main/java/com/arcadedb/query/opencypher/procedures/identity/`.

| Class | Role |
|---|---|
| `IdentityMatchModel` | The scoring model, pure Java, no database: settings, `features(profileA, profileB, frequencies)` (raw terms), `score(features)`, and `compare` = `score(features(...))` |
| `IdentityModelRegistry` | Loads a registered `IdentityModel` for the `model` option (2.8) |
| `ClusterProfile` | What the model knows about one cluster, in sorted primitive arrays; `without(j)` and `only(j)` views for cohesion |
| `IdentityStatistics` | trigamma, normal quantile, logistic: no statistics dependency |
| `AbstractIdentityProcedure` | Options, member loading, profile building from the graph |
| `IdentityCompare`, `IdentityCohesion`, `IdentityCandidates`, `IdentityFeatures` | The Cypher procedures, registered in `CypherProcedureRegistry` |

### 2.1 Inputs

A cluster is a list of nodes, RIDs or RID strings (in Recon, Record vertices). Stale RIDs are skipped, repeats count once,
and a node in both clusters of a compare is set aside and reported as `sharedMembers` (it would make each of its
identifiers look shared). A member's **identifiers** are the nodes it reaches over `via` in `direction`; an identifier's
**kind** is its type name (`Phone`, `Email`, `Handle`, `ImageHash`, `Place`, `FaceCluster`...). So the procedure works on
the 2.1 schema as is, and on any other graph whose identifiers are nodes.

### 2.2 The model

A Fellegi-Sunter model (the one Splink implements) with term-frequency
adjustment and time. Every piece of evidence is a likelihood ratio LR = P(evidence | same) / P(evidence | different);
the match weight is the sum of their logs (reported in bits), and the posterior log-odds is `logit(prior) + weight`.

**SHARED** (both clusters reach identifier i of kind k):

- P(same) = m_k: the chance one person's two clusters share an identifier of kind k when both use the kind.
- P(different) = u_i + h_k(gap):
  - u_i, coincidence: the chance the larger cluster (|L| members) reaches i at random, `1 - (1 - p_i)^|L|`, with p_i the
    Jeffreys posterior mean of i's rate in the population (`frequency - countInSmaller` successes over
    `population - |smaller|`). Conditioning on the smaller cluster and charging the larger one is the conservative side,
    and makes the score symmetric.
  - h_k(gap), a different person carrying i: rises from `concurrent` (same moment: agency or shared work phones) to
    `handoff` (long gaps: recycled numbers, sold handles) as rho_k(gap) = 2^(-gap / halfLife_k) decays. gap is the shortest
    time between a sighting of i in one cluster and in the other, a merge of two sorted arrays.
- A weight at or below 0 (a hub so common that sharing it is no surprise) is clamped to 0: never evidence against, but it
  still marks the kind as agreeing.
- Further shared identifiers of one kind are multiplied by `redundancy`^rank (0.5 by default), strongest first: two phones
  on the same ads are correlated evidence, not independent.

**DISJOINT** (both clusters use kind k, share none of it): LR = 1 - m_k * rho_k(gap), gap between the two clusters'
windows of use of k. Concurrent different phones say "different"; phones years apart say little, since people change
phones.

**CONFLICT** (impossible travel): over member pairs, one per cluster, within `conflictWindowHours`, the fraction f that
sits more than `minConflictKm` apart at more than `maxSpeedKmh` (or at the same instant); weight `f * ln(conflictLR)`. The
sweep runs over both clusters' points sorted by time, so it costs the pairs inside the window, bounded by `maxPairs`.

### 2.3 The interval

Each weight is a function of estimated quantities, and each estimate carries a variance:

| Quantity | Distribution | Variance of the log |
|---|---|---|
| m_k | Beta(m * mStrength, (1 - m) * mStrength) | psi1(a) - psi1(a + b), exact |
| p_i | Jeffreys Beta(successes + 0.5, failures + 0.5) | psi1(a) - psi1(a + b), then the delta method through u_i and u_i + h |
| conflict fraction f | Jeffreys Beta(c + 0.5, n - c + 0.5) | Beta variance times ln(conflictLR)^2 |

The variances add on the log-odds scale (terms treated as independent after the redundancy damping), and the interval is
`sigmoid(logOdds +- z * sd)` at `confidence` (0.95). It is therefore wide when the decision rests on one rare identifier
seen once, narrow when it rests on many, and asymmetric in probability. `mStrength` (20) says how many labelled pairs the m
values are worth; once N10's EM or analyst labels calibrate m, it grows and the intervals narrow.

### 2.4 Verdict

| Verdict | Rule |
|---|---|
| `SAME` | the whole interval is above `matchThreshold` (0.9) |
| `LIKELY_SAME` | the whole interval is above 0.5 |
| `DIFFERENT` | net evidence is negative and the whole interval is below `nonMatchThreshold` (0.1) |
| `LIKELY_DIFFERENT` | net evidence is negative and the whole interval is below 0.5 |
| `UNDETERMINED` | the interval straddles 0.5, or it sits low only because of the prior |
| `INSUFFICIENT_EVIDENCE` | no term moved the score |

The "net evidence negative" condition keeps a low prior from reading as evidence against: two clusters with nothing in
common are not "different", they are unexplained. Senzing's ladder maps roughly as resolve = SAME, possible match =
LIKELY_SAME, possibly related = UNDETERMINED with shared evidence.

### 2.5 Procedures

```cypher
CALL identity.compare(clusterA, clusterB, options?)
YIELD probability, lower, upper, logOdds, matchWeight, matchWeightStdDev, verdict,
      sizeA, sizeB, sharedMembers, evidence, temporal

CALL identity.cohesion(cluster, options?)
YIELD member, probability, lower, upper, logOdds, matchWeight, matchWeightStdDev, verdict, evidence

CALL identity.candidates(cluster, options?)
YIELD candidate, members, size, complete, overlap, blockedOn,
      probability, lower, upper, logOdds, matchWeight, matchWeightStdDev, verdict, evidence
```

`evidence` is the `why`: a list of `{type, kind, identifier, countA, countB, frequency, gapDays, weight, stdDev,
recordsA, recordsB, firstA, lastA, firstB, lastB}` (weights in bits), strongest first, cut at `evidenceLimit` (25 for
compare, 5 for cohesion and candidates). `temporal` is `{firstA, lastA, firstB, lastB, gapDays, overlapDays,
comparablePairs, conflicts, pairsTruncated}`.

**A row names the records behind it (HANDOFF open work 34).** `recordsA` and `recordsB` are the members of each side that
put the identifier there, `{record, at}` each (`at` null when the member has no time), cut at `evidenceRecords` (10) and
left out when that is 0; `firstA` to `lastB` are the ends of each side's sightings of that identifier. So "what supports
the association of this person to this number" is answered by the row itself rather than by a second query: the analyst
reads which ads tie the number to each side and when, next to the weight that says how much it is worth. The cut is on
what is shown, never on what is counted, so a cut list is visible against `countA` and `countB` and the window still
spans every sighting. A DISJOINT row has no `recordsA` or `recordsB`, since it rests on a kind rather than on one
identifier, and `firstA` to `lastB` are the ends of that side's window of use of the kind; CONFLICT and SOFT rows, whose
terms rest on member pairs, carry neither. The members are read off the profile the score already built
(`ClusterProfile.members`), so a row costs no record read it was not already paying.

| Option | Meaning | Default |
|---|---|---|
| `via`, `direction` | Edge types from member to identifier; refused if not edge types | every type, `OUT` |
| `timeProperty` | Member time (epoch ms, date or datetime). Absent makes the model time-blind (every gap concurrent); present makes an unknown gap count as a long one | absent |
| `latProperty`, `lonProperty` | Member place in degrees, for impossible travel; both or neither | absent |
| `frequencyProperty` | Identifier property holding its record count (`recordCount` on registry identifiers, 2.1) | the identifier's degree over `via`, read only for shared identifiers |
| `population` | Records the frequencies are counted over | count of the members' types |
| `prior` | Base rate of the pairs being compared | 0.05 |
| `kinds` | Per kind (a vertex type): `{m, concurrent, handoff, halfLifeDays}`; `halfLifeDays: 0` never decays | defaults below |
| `defaultM`, `defaultConcurrent`, `defaultHandoff`, `defaultHalfLifeDays` | Settings of kinds not named | 0.6, 0.001, 0.05, 365 |
| `mStrength`, `redundancy` | See 2.2 and 2.3 | 20, 0.5 |
| `maxSpeedKmh`, `conflictWindowHours`, `minConflictKm`, `conflictLR`, `maxPairs` | Impossible travel | 900, 24, 100, 0.2, 100000 |
| `confidence`, `matchThreshold`, `nonMatchThreshold` | Interval and verdict | 0.95, 0.9, 0.1 |
| `evidenceLimit` | Evidence rows returned | 25 / 5 / 5 |
| `evidenceRecords` | Records one side of an evidence row names; 0 names none | 10 |
| `limit` (cohesion, candidates) | Rows returned: weakest member first / strongest candidate first | all / 25 |

Every key is checked (an unknown one is refused, as `extractConfig` does for `algo.*`), and so is every kind key and
range. A Recon starting profile, to be calibrated (section 5):

```
kinds: {
  Phone:     {m: 0.7, concurrent: 0.002, handoff: 0.10, halfLifeDays: 180},
  Email:     {m: 0.6, concurrent: 0.001, handoff: 0.03, halfLifeDays: 730},
  Handle:    {m: 0.6, concurrent: 0.001, handoff: 0.05, halfLifeDays: 365},
  ImageHash: {m: 0.4, concurrent: 0.0005, handoff: 0.02, halfLifeDays: 0},
  Place:     {m: 0.8, concurrent: 0.05, handoff: 0.2, halfLifeDays: 90}
}
```

### 2.6 Cost

One read of each member and its adjacency (`getConnectedVertexRIDs`, no identifier record loaded), then one record read
per **shared** identifier for its frequency. Everything else is sorted primitive arrays: gaps are merges, windows are the
ends of sorted arrays, the travel sweep is a sliding window. Cohesion reads the cluster once and scores each member against
a leave-one-out view that shares the arrays (`ClusterProfile.without`), so a 2,000-member cluster costs about the sum of
its members' identifiers per member, not a rebuild. Loops run under the command's `WorkGuard`.

### 2.7 `identity.candidates` (G3)

The groups outside a cluster most likely to be the same individual. Three steps, each bounded:

1. **Blocking.** Every identifier of the cluster carried by at most `maxFrequency` records (1000) is followed back over
   `via` to those records, cluster members excluded. A hub is recognised after reading `maxFrequency + 1` of its entries
   and skipped, so it never costs its full degree; with `frequencyProperty` it is skipped before any walk. The walk counts
   the identifier's degree, which is cached as its frequency so scoring does not read it again. At most `maxCandidates`
   records (10000) are collected, identifiers taken in RID order so the cut is deterministic.
2. **Grouping.** Without `groupBy`, every record is its own candidate: **what is missing from the footprint**, the
   counterpart of cohesion's "what does not belong". With `groupBy` (a persona, `componentId`, an imported cluster id),
   records are grouped by that property and each group is loaded whole through an index on it, up to `maxGroupSize`
   members (2000); without an index only the records reached are scored and the row says `complete: false`. A record
   without the property is a group of its own, keyed by its RID. Cluster members found in a group are set aside and
   counted as `overlap`, so the footprint's own group never comes back as a candidate.
3. **Scoring.** Groups are pre-ranked by `blockedOn` (distinct blocking identifiers reaching them), the first `maxScored`
   (500) are compared with the cluster by the compare model, and the `limit` (25) strongest are returned by log-odds;
   `members` lists up to `memberLimit` (100) RIDs per row.

Groups by Identity (`MEMBER_OF`) need the identity registry and its as-of view, so they come with slice 2b.

### 2.8 Training features and registered models (MLOps step 1)

**`identity.features(clusterA, clusterB, options?)`** returns the raw terms of a pair before any parameter is applied,
for the training export of `05-identity-model-mlops.md`:

```cypher
CALL identity.features($a, $b, $opts)
YIELD sizeA, sizeB, population, temporal, sharedMembers, shared, disjoint, travel, modelVersion
```

- `shared`: `{kind, identifier, countA, countB, frequency, population, sizeA, sizeB, gapDays, recordsA, recordsB, firstA,
  lastA, firstB, lastB}` per identifier both reach, the last six as in 2.5.
  `frequency` is the one the score uses (reported, raised to the observed count) and `population` the effective one (at
  least the two sizes), so each row is self-contained for a flat export.
- `disjoint`: `{kind, countA, countB, gapDays, firstA, lastA, firstB, lastB}` per kind both use with nothing shared,
  including those whose gap is unknown (the score drops them, a trainer may not).
- `travel`: `{comparablePairs, conflicts, pairsTruncated}`. The travel bounds define what a conflict is, so they are the
  only parameters `features` reads.

No skew by construction: compare and features share `preparePair` and `IdentityMatchModel.features`, and compare is
`score(features)`. On top of that, `IdentityFeaturesAndModelsTest` recomputes every weight compare reports from the
features alone, with the formulas of 2.2 written out independently as an offline trainer would, over five pairs and two
parameter sets, and requires them to agree to 1e-9.

**The `model` option** (all four procedures) loads a registered model:

| Document type | Properties the engine reads | Other properties |
|---|---|---|
| `IdentityModel` | `name` (STRING), `version` (INTEGER), `settings` (MAP: the options shape, any key of the model options but `model` and `evidenceLimit`) | metrics, snapshot hash, code version, author, approver: kept for audit, not read |
| `IdentityModelAlias` | `name` (the alias), `model`, `version` | |

- `model: 'recon@7'` pins a version; `model: 'recon-default'` reads the alias on every call, so a promotion takes effect
  at once. Anything else, an alias pointing nowhere, a missing type or a name registered twice is refused.
- Settings keys are checked: an unknown key, or a procedure option such as `limit`, is refused naming the model.
- Explicit options override the model's settings; `kinds` merges kind by kind and key by key.
- Every row reports `modelVersion` (`name@version`, null without a model), so a decision records the model that scored it.
- Model settings are cached per database instance and `name@version`, which immutability makes safe. Immutability is the
  registration path's contract: the engine does not stop an update of an `IdentityModel` document, and a changed version
  would be served stale from the cache until the database reopens. Register a new version instead.

### 2.9 Soft evidence: style and images (slice 3)

The style vectors and perceptual image hashes of `06-stylometry-perceptual-hash.md` enter the model as two SOFT signals,
with no cluster vertices needed:

| Option | Meaning | Default |
|---|---|---|
| `styleProperty` | Member property holding a style vector, or a text styled on the fly (`SoftSimilarity.styleOf`) | absent (no style signal) |
| `styleRecon` | Style a text as Recon's 24-d vector instead of the 256-d n-gram profile | false |
| `imageHashProperty` | Member property holding image hashes: 64-bit or PDQ, hex, long or bytes, a list for several (`SoftSimilarity.hashesOf`) | absent (no image signal) |
| `soft.style` | `{threshold, m, falseMatchRate, concurrent}`: agreement is a member pair with cosine at or above `threshold` | 0.9, 0.3, 0.01, 0.05 |
| `soft.image` | `{maxDistance, m, falseMatchRate, concurrent}`: agreement is a hash pair within `maxDistance` bits. Unset, `maxDistance` follows the hash width: 8 for 64-bit, 31 for PDQ, about 12% of the bits otherwise, because a 64-bit cut on PDQ would quietly drop most near duplicates | by width, 0.4, 0.00001, 0.002 |

The term, per signal both clusters carry: P(agree | same) = m; P(agree | different) = q = c + (1 - c) * (1 - (1 -
falseMatchRate)^pairs), where `pairs` are the member pairs (style) or hash pairs (images) compared and c the `concurrent`
hazard. Agreement weighs ln(m / q), disagreement ln((1 - m) / (1 - q)), each clamped to its own sign, with m's Beta
variance as for kinds. The `falseMatchRate` term is the soft analogue of term frequency: two big clusters have many
chances to agree by accident. The `concurrent` hazard carries the domain caution of open question 5: stolen and stock
photos for images, and for style one operator writing for several people, which is why style's defaults keep it weak
(about 2 bits at most) and images strong (about 7.6 bits for one near-duplicate photo).

The pair statistics come from `SoftSimilarity` (stylometry session, pure Java) over arrays the `ClusterProfile` carries
per member, with the left-out member as skip index, so cohesion's views stay copy-free. `identity.features` exports them as
`soft: {style: {pairs, pairsAbove, best, mean, threshold}, image: {pairs, pairsWithin, memberPairsWithin, minDistance,
meanDistance, maxDistance}}` (`maxDistance` is the one used, set or by width), the drift test covers them, and a registered model can carry the properties and the `soft`
settings (merged signal by signal like `kinds`). All four numbers per signal are for the MLOps pipeline to fit; until then
they are guesses. Cost: every member pair across the two clusters is compared (a 256-d cosine, or 1 or 4 popcounts per
hash pair), so a compare of two 1,000-member clusters is a million pairs and cohesion over n members n^2.

## 3. The group operation design space

The question was whether a "group operation" (an operation whose operand is a group of nodes rather than a node) is a
viable primitive. It is, and it is the natural shape for SPORK: every Recon question about identity is about footprints,
and a footprint is a set. The candidates, with their verdict:

| # | Operation | Question | Viable now? | Notes |
|---|---|---|---|---|
| G1 | **compare(A, B)** | Are these two groups one individual? | **Yes, slice 1** | The primitive every other operation reuses |
| G2 | **cohesion(A)** | Which member of this group does not belong? | **Yes, slice 1** | Leave-one-out compare; the split candidate of a resolved identity, or the ad wrongly pulled into a target's footprint. The cheapest high-value group op: no candidate generation |
| G3 | **candidates(A)** | Which other groups are most likely A? | **Yes, slice 2a** (2.7) | Rare-identifier blocking, records or `groupBy` groups, G1 on each. Grouping by Identity (`MEMBER_OF`) and by F1 selection handles is slice 2b |
| G4 | partition(A) | How many individuals are in this group, and which members go together? | Next | Score member pairs that share at least one identifier (blocking keeps it sparse), then correlation clustering or thresholded union-find on the scored graph; reuse `algo.leiden` on a weighted unnamed view. G2 finds the outliers, G4 finds the seams |
| G5 | merge what-if(A, B) | If I merge these, does the result hold together? | Cheap after G2 | cohesion(A union B) and compare(A, B) side by side; the input to a `ResolutionDecision` (F15) |
| G6 | profile(A) | Summarize a group: identifiers with counts and windows, places over time, activity | Partly (the `ClusterProfile`) | Expose as a projection and persist it on Identity, so G1 and G3 can compare profiles without re-reading members: the step from per-call to incremental resolution (N9) |
| G7 | set algebra over groups | Overlap, difference, Venn of footprints | Yes, F1 | `selection.venn` / `selection.compare` (`03-f1-selections-design.md`) answer "what do they share"; G1 answers "what does it mean" |

What makes the group the right operand, rather than pairwise record matching as Senzing does it:

- **Evidence accumulates across members** (a phone on one ad and an email on another of the same cluster), which a
  record-to-record comparison cannot see.
- **Time is a property of the group**: windows of use, gaps between sightings, and travel conflicts only exist over many
  members.
- **It composes with selections.** F1 makes groups cheap to hold and combine (`RidBitmap`, persisted handles in slice 2);
  identity procedures then take a selection handle wherever they take a list.
- **It keeps resolution reversible.** G1, G2 and G5 only score; merges and splits stay `ResolutionDecision`s (2.6), and the
  evidence list is what the decision records as its `why`.

What it does not do, and why that is fine for now: it does not decide on its own (no automatic merge, per 2.2's "soft edges
never feed union-find"), and it does not replace blocking for corpus-wide resolution (G3 and N9 do that).

## 4. Next slices

| Slice | Content |
|---|---|
| 2a | G3 `identity.candidates` with rare-identifier blocking, record and `groupBy` modes: **done** |
| 2b | Accept F1 selection handles as clusters; `Identity` as a cluster and as a candidate grouping; hub and stop flags (2.4) honoured in blocking. With native record versioning (01 2.6 as revised; the native-versioning branch, not merged yet) `Identity` and `MEMBER_OF` are versioned types: the current members are the plain `MEMBER_OF` edge list and an as-of read (`asOf` option) goes through `history.edges(identity, t)`, so no `validTo` filter. Starts once that branch is merged |
| 3 | Soft evidence. Style and image near-duplicates: **done** (2.9). Still open: faces (`SIMILAR_FACE` from ROC, LR from its calibrated FMR table, never a raw cosine) and `NAME_LIKE` |
| 4 | G4 `identity.partition`; G5 as a `ResolutionDecision` draft carrying the evidence |
| 5 | Calibration (N10), planned in `05-identity-model-mlops.md`. Step 1 (features and registered models, 2.8) is **done**; next are the export job, the EM bootstrap and the label join |

Governance: once F13 labels exist, members and identifiers the session cannot see must be dropped before profiles are
built, so neither the score nor the evidence leaks hidden records (same rule as F1, 4).

## 5. Open questions

1. **Defaults are priors, not measurements.** m, concurrent, handoff and half-lives per kind need fitting on Recon data
   (slice 5). Until then, treat probabilities as rankings and the verdict as advisory.
2. **Correlation across kinds.** A phone and an email posted together are not independent; redundancy damps only within a
   kind. Splink accepts this; a co-occurrence discount (from `CO_OCCURS` weight) is the candidate fix.
3. **Concurrent sharing inside one operation.** An agency phone used by three workers has a low frequency and a low gap, so
   only `concurrent` protects against a false merge. The better signal is that the phone's own records do not cohere:
   G2 run on an identifier's footprint flags it, and the result could feed back as a per-identifier hazard.
4. **Posting place is not presence.** Ads are posted remotely and for touring; `conflictLR` is deliberately mild (0.2), and
   should be fitted with the rest.
5. **A shared writing style is a shared writer, not a shared person.** The operator often writes the ads of several
   victims, so style evidence (stylometry, `06-stylometry-perceptual-hash.md`) belongs mostly to the operator-network
   view. For identity, a `StyleCluster` kind gets a low m and a high `concurrent`; perceptual-hash image clusters are the
   stronger soft signal. Slice 3 takes both through `ClusterSimilarity` (`style`, `images`) as SOFT terms whose LR comes
   from a fitted curve per signal; until then they enter as SHARED terms through cluster vertices (`similarity.cluster`
   then `via: 'HAS_IMAGE_CLUSTER'`).

## 6. Verification

- `IdentityMatchModelTest` (19): trigamma and normal-quantile against closed forms and tables; prior-only result; rare
  shared identifier; symmetry; monotone decay with the gap; `halfLifeDays: 0` and time-blind mode; frequency (rare beats
  common, hubs weigh nothing, frequency raised to the observed count); disjoint evidence and its fading over time; a
  shared identifier suppressing the disjoint penalty; redundancy damping; impossible travel; interval widening with the
  confidence level; DIFFERENT on strong negative evidence; refused settings; and the leave-one-out view against a rebuilt
  profile on 30 random clusters, member by member.
- `IdentityProceduresTest` (12): one person across two clusters (verdict, interval, evidence, temporal block); two people
  posting far apart; symmetry; hub frequency; degree as the default frequency; kind settings; RID strings and stale
  members; members in both clusters; refused input (unknown option and kind keys, unknown edge or kind type, non-numeric
  option, no existing member); cohesion ordering and limit; registration.
- `IdentityCandidatesTest` (9): record mode ranks the records outside the footprint (order, members, blockedOn, verdict,
  evidence); identifiers above `maxFrequency` are not blocked on, and are once the cap is raised; `limit`, `maxScored` and
  `maxCandidates`; group mode loads whole groups through the index; the footprint's members inside a group are counted
  as overlap and removed; group mode without an index scores only the records reached (`complete: false`) and keys
  records without the property by RID; refused input; registration.
- Slice 3 adds 7 model tests (near-duplicate image strong and unmatched images mildly against; style weak by design;
  data needed on both sides; settings change the weights; image distance by hash width; symmetry; refused settings) and extends the leave-one-out test
  with random style vectors and image hashes; and 5 procedure tests (soft statistics exported; compare and cohesion use
  them; a registered model carries them; refused soft settings), with the soft terms added to the drift test.
- `IdentityFeaturesAndModelsTest` (10 before slice 3): the raw terms of a same-person pair and of a two-people pair; compare's weights
  recomputed from the features (2.8); a registered model scores like its settings as options; an alias resolves and
  follows a promotion; explicit options override the model kind by kind; every procedure reports `modelVersion`; unknown
  models, unknown settings keys and a missing alias type are refused; registration.
- Neighbouring suites run green with them: `CypherProcedureRegistryTest`, `Issue8124ProcedureOptionalArgumentsTest`,
  `SelectionProceduresTest`.
- No new dependency.
