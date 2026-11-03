# Identity model MLOps: learning the match parameters

Status: design note, 2026-11-02. Companion to `04-identity-resolution-design.md` (slice 5, "Calibration") and roadmap
item N10. Step 1 of section 8 is implemented (see 04 section 2.8); steps 2 to 5 are not.

## 1. Why

`identity.compare`, `identity.cohesion` and `identity.candidates` run a Fellegi-Sunter model whose parameters are
hand-set today: per kind m, concurrent, handoff and halfLifeDays, plus the prior, conflictLR, redundancy and mStrength.
Those values set every probability and interval the analyst sees, so they have to be **learned from Recon's data, versioned,
evaluated, deployed and monitored**. A hand-set value that is too low lets the same agency phone merge three women into one
identity, and the analyst gets no warning that the number is wrong.

The engine side stays as it is: scoring is deterministic Java on the read path. Everything below sits around it.

## 2. What is learned, and from what

| Parameter | Meaning | Learned from |
|---|---|---|
| u_i (per identifier) | Coincidence rate | Already data-derived: `recordCount` / degree over the population. MLOps only monitors it |
| m_k | P(share kind k \| same person) | EM over blocked pairs (unsupervised, as Splink does), then corrected by labels |
| concurrent_k, handoff_k, halfLife_k | Non-identity sharing hazard over time | Labelled different-person pairs that share an identifier, binned by gap; fit h(gap) = concurrent * rho + handoff * (1 - rho) by maximum likelihood |
| conflictLR, maxSpeedKmh, minConflictKm | Impossible travel | Labelled pairs with conflicts: the conflict rate among same-person pairs over the rate among different-person pairs |
| redundancy | Correlation within a kind | Fit on pairs sharing 2 or more identifiers of one kind |
| prior | Base rate | Per entry point: candidates output, analyst-chosen compares and cohesion have different base rates |
| mStrength | Confidence in m | The effective sample size behind each fitted m, so intervals narrow as labels accumulate |

**Labels, best first:**

1. **Analyst decisions.** `ResolutionDecision` merges, splits and unlinks (2.6), accepted and rejected soft links, `BoardLabel`
   and `Feedback` verdicts. Splits and rejections are the scarce, valuable negatives.
2. **Strong weak labels.** The same ROC face cluster at a high-confidence FMR threshold; the same upstream account id across
   aggregators. Positives for training, with label noise taken into account.
3. **Unlabelled blocked pairs.** EM needs no labels. It is the bootstrap until analysts have labelled a few hundred pairs.

Labels are dated, so the fit can be done on decisions made before a cutoff and evaluated on those made after. That is the
only honest test of a model that will score future pairs.

## 3. No training/serving skew: the engine makes the features

The biggest MLOps risk here is training on features that the engine does not compute the same way: a Python
reimplementation of the gap, the window or the frequency drifts from the Java one. So training reads the engine's own
comparison vectors:

- **`identity.features(clusterA, clusterB, options)`**: a new procedure that returns, for one pair, the raw terms the model
  scores (per shared identifier: kind, countA, countB, frequency, population, sizes, gapDays; per disjoint kind: kind and
  window gap; travel: comparable pairs and conflicts). These are the same `ClusterProfile` and the same code path as
  `compare`, stopping before weights are applied.
- **Batch export**: a server-side job (on the OLAP node, n4) runs `identity.candidates` to block, then `identity.features`
  on each pair, joins labels by pair key, and writes a dated Parquet or JSONL snapshot with the engine version and options
  hash. The snapshot is the training set and is kept for reproducibility.

## 4. The pipeline

```
 snapshot (engine features + labels, dated)
   -> train   EM for m, ML fit for hazard curves and conflictLR, per kind
   -> evaluate on a later time window (section 5)
   -> register IdentityModel document (name, version, parameters, metrics, data snapshot hash)
   -> shadow: score live traffic with candidate and champion, log both
   -> promote: alias 'recon-default' moves to the new version, by an approver
   -> monitor (section 6), and retrain on trigger or schedule
```

- **Training** runs offline in Python (EM and a handful of maximum-likelihood fits; Splink, MIT, can serve as the reference
  implementation). No GPU and no deep learning: the model has tens of parameters.
- **Registry in the database.** A fitted model is a JSON document of type `IdentityModel` in the `identity` database: the
  kinds map and settings in exactly the shape of the procedures' options, plus metrics, the training snapshot hash, the
  code version, the author and the approver. It is replicated by Raft like any record, so every node scores with the same
  version, with no external model server on the read path. It is immutable once registered; an alias document points
  at the champion.
- **Engine change.** The identity procedures take `model: 'recon-default'` or `'recon@7'`, load the document once per
  call (cached by version), and let explicit options override it. Every result and every `ResolutionDecision` records the
  model version, so a decision can always be re-scored with the model that made it, and with the current one.
- **Shadow and promotion.** For a period, the candidate scores alongside the champion and the plugin logs both. Promotion
  needs the evaluation gates below plus a named approver, and it is audited like any other write.

## 5. Evaluation gates

All measured on a time-split holdout, overall and per kind, region and source:

| Gate | Metric | Why |
|---|---|---|
| Calibration | Reliability curve and expected calibration error of `probability` | The number is shown as a probability, so it must behave like one |
| Interval coverage | Share of labelled pairs whose label falls inside the interval at each confidence level: 95% intervals should cover about 95% | Validates the variance model and mStrength. Too narrow is the dangerous failure |
| Verdict quality | Precision of SAME and LIKELY_SAME; recall of DIFFERENT on known splits | False merges cost more than missed merges in casework, so the gate is weighted that way |
| Ranking | Recall@k of `identity.candidates` against known merges | Blocking quality |
| Stability | Verdict churn against the champion on the same pairs | A new version must not silently flip a large share of existing decisions |
| Slices | The same metrics by region, source and kind | A global average can hide a region where agency phones dominate |

## 6. Monitoring in production

- **Input drift.** The distribution of frequencies, gaps, kinds per cluster and conflicts per pair, against the training
  snapshot. A new aggregator or a change in ingest moves them before it moves accuracy.
- **Output drift.** The distribution of scores and the rates of each verdict, per day.
- **Feedback.** The analyst override rate: decisions that contradict the verdict. That is the most direct accuracy signal.
  A sustained rise triggers retraining.
- **Hazard watch.** Identifiers that turn up in many low-cohesion clusters (`identity.cohesion` on an identifier's own
  footprint) are candidate agency phones. They are a per-identifier signal to fold into `concurrent`, and a case to
  review.
- Metrics go out through the engine's Micrometer wiring; the retraining job reads labels and drift from the `identity`
  database.

## 7. Governance

- Training snapshots carry the Operating Context of their export (analyst, purpose) and follow retention and legal hold
  (5.5). A deleted or held record leaves the next snapshot or keeps its row frozen, as policy requires.
- Snapshots hold features and identifier keys, never biometric templates or ad text. Faces enter only as a calibrated
  same-cluster flag.
- Records flagged as minors are excluded from training and evaluation exports. Scoring them stays allowed and audited.
- Model promotion, alias moves and threshold changes are audited writes, like resolution decisions.
- Every model document can explain itself: training data window, label counts per kind, metrics, and how it differs from
  its predecessor.

## 8. Build order

| Step | Content | Owner |
|---|---|---|
| 1 | `identity.features` procedure and the `model:` option with `IdentityModel` and `IdentityModelAlias` document types (engine). **Done**: `IdentityFeaturesAndModelsTest.compareWeightsAreRecomputableFromTheFeatures` is the reference spec for the Python trainer. The registration path must enforce immutability, since the engine caches settings by name@version | identity session |
| 2 | Export job and dated snapshots (plugin, n4) | plugin |
| 3 | EM bootstrap on unlabelled blocked pairs; the first registered model replaces the hand-set defaults | offline |
| 4 | Label join from ResolutionDecision, BoardLabel and Feedback; supervised hazard fit; evaluation report with the section 5 gates | offline |
| 5 | Shadow scoring, promotion workflow, drift and override monitoring | plugin + ops |

Until step 3 ships, the procedures' outputs are rankings, not calibrated probabilities, and the Recon UI should say so.
