# Analyst requirements (Recon, 2026-11-05)

Status: source requirements and the design principles they imply, for owner and Recon product review.

Source: a Recon analyst (Alisha Willemssen) set out, in Slack on 2026-11-05, the four workflows she spends her time on
and what each one needs from the system. This note records what she asked for, the principles it imposes on SPORK, and
the parts of it the design does not yet answer. The per-item open work is [HANDOFF.md](../../HANDOFF.md) items 34 to 44;
this note is the why behind them and the rule set that outlives any one item.

It is the first requirements statement from a named end user of the system. Where it conflicts with an engineering
instinct, it wins.

## 1. What is being asked for

Read the four points together and the system she describes never outputs "this is the person". It outputs what supports
an association, what contradicts it, who looked, when, and what is still unresolved. Her words: the opportunity is
"supporting gathering, comparison, and retention of information, not necessarily automating every step".

That is an **evidence ledger with fast comparison**, not an entity resolver, and it is a different product from the one
most identity systems ship. Three consequences follow immediately, and all three invert a common engineering default:

- **Tune for recall, not precision.** She asks for more candidates to review rather than fewer, on one condition: that
  she can see what connects them. Suppressing a candidate to protect a precision number costs her a lead; showing one
  without its basis costs her the time to reconstruct it. Thresholds belong in the ranking, not in a filter that drops
  rows.
- **Never merge automatically.** Deduplication that collapses is a loss of evidence (3, P1). Identity membership stays
  an analyst decision with a reversible record, which is what 2.6 already says.
- **Never present a score as an answer.** A reply of "87% match" does the one thing she explicitly did not ask for.
  Confidence ranks the list; evidence is the output.

## 2. The four workflows, as stated

| # | What she asked for | Where it is tracked |
|---|---|---|
| 1 | Identity work is two tasks, not one: **discovery** (which identities touch this selector) and **assessment** (which, if any, supports the person I am researching). The phone question is not "who owns this number" but "what supports the association of this person to this number during the relevant time period", and the answer must carry supporting and conflicting sources with their dates. | 34, 35 |
| 2 | **Group, do not collapse.** Three relations must be told apart: the same source ingested twice, separate ads sharing attributes, and records possibly referring to one person. Collapsing destroys the individual value of a record. Her example: different ad sites, same number, different photos including tattoos, where the extra photos were what made the positive ID. | 37, 38 |
| 3 | **More connections, provided the basis is visible.** "Suggested connections save time when the underlying info is immediately available. Unexplained connections still require me to reconstruct the work." The basis, the relevant dates and the review status all have to be on the connection. VoIP and burner accounts mean shared information is not automatically a verified identity or relationship. | 38, 43, and the ladder in 4.3 |
| 4 | **Historical comparison without asserting movement.** Four distinctions must survive: when an ad was reportedly posted, when it was collected, what location it listed, and whether presence can be independently corroborated. People advertise cities they are travelling to next, or several at once. | 40 |
| 5 | **Retention of completed work.** Reviewed and rejected matches kept, with historical selectors and patterns, so work is not repeated, while reassessment stays possible when new information appears. | 36, 39, 44 |

## 3. Design principles

These are the rules the items implement. An item can be satisfied narrowly and still break the principle, so the
principle is what a reviewer checks.

**P1. Records are immutable; grouping is a layer above them.** No ingest, enrichment or dedup step may suppress,
overwrite or hide a record because a hash, key or band matched another. Exact-byte `textHash`, image SHA-256 and PDQ
clustering are storage and grouping signals only. The tattoo case is the standing test: the apparent duplicate carried
the decisive evidence, so a pipeline that drops it is wrong even when it is right about the similarity.

**P2. Every suggested connection carries its basis, materialised at write time.** An edge whose explanation has to be
recomputed when the analyst asks costs more than the candidate is worth. Basis, dates and review state travel with the
row that suggests the connection, not a hop away.

**P3. The provenance ladder has four rungs, and no layer may flatten them.**

| Rung | What it means | Who asserts it |
|---|---|---|
| L0 co-occurrence | Two records carry the same normalized selector | Machine. No claim about any person |
| L1 source claim | A source associates a selector with a person | The source, not us |
| L2 analyst review | An analyst has assessed the record or pair and recorded a state | Analyst, as a judgement about evidence |
| L3 analyst assertion | An analyst asserts the relationship and defines what it is | Analyst, as a claim |

The rung is a typed property on the connection, not a tag, a string or a UI convention. The rule that gives it value is
negative: **no route, response, export or rendering may describe an L0 or L1 connection in words that imply L2 or L3**
("linked", "same person", "associated with", "known to"). One flattening layer anywhere in the chain, including a CSV
export or a graph view, destroys the distinction for every consumer above it. Soft edges already carry `method`,
`evidence[]`, `createdBy` and `opContext` (2.2), which is the right struct; what is missing is the rung itself and the
prohibition travelling with it.

**P4. Four clocks and a corroboration flag, never collapsed into one.**

| Axis | What it is | Where it lives today |
|---|---|---|
| Claim time | What the record says about itself: posted at, located in | `Record.tsPosted`, `placeId` / `city` / `lat` / `lon` |
| Observation time | When we collected it | `Record.tsCollected`, `_prov.collectedAt` |
| Assertion time | When a person or process made a judgement | `_prov.assertedBy` plus the engine's `@txFrom` |
| System time | When the stored value changed | E25 `_VersionHistory`, `@txFrom` / `@txTo` |

Corroboration is not a time axis. It is a separate property of a claim: whether an independent source puts the person
there. **A claimed location is never promoted to an observed one**, and no route labels a sequence of listed places as
movement (40).

**P5. A selector is a time-boxed, shared, reassignable handle, not an identity anchor.** VoIP numbers, burner accounts
and aggregator numbers are routinely shared and reassigned, so a selector match is evidence whose weight depends on the
interval and on the class of the selector. Identity scoring that treats a phone hit as strong evidence regardless of
when the two sides saw it, or of what kind of number it is, is wrong in exactly the cases she says cost her the most
time (41).

**P6. Judgements are durable, reversible and distinguishable from silence.** Four states, not three: unexamined,
unresolved, supported, rejected. "Unresolved" means someone looked and could not tell, which is different from nobody
having looked, and it is the state most likely to be worth revisiting when new evidence arrives. A reassessment
supersedes rather than overwrites, so the earlier judgement and its basis stay readable (39).

**P7. Confidence ranks; evidence answers.** See 1.

## 4. What items 34 to 40 do not cover

Items 34 to 40 cover the four workflows and the retention ask. These are the parts of the message they do not reach, and
they are items 41 to 44.

- **Selector strength (41).** P5. Nothing discounts a selector match by interval overlap or by selector class, so a
  shared VoIP number scores like a shared private one.
- **Candidate volume (42).** The recall preference is a tuning directive with no home. It needs to be written down
  somewhere a future reviewer will find it, because every instinct during tuning will be to raise thresholds and cut
  the candidate list.
- **The ladder as an enforced contract (43).** P3's prohibition has to hold at the API and export boundary, not only in
  the data model.
- **When assessment state lands (44).** A scheduling decision, not a design one. See 5.

## 5. Why assessment state should be in the Q1 schema

Item 39 is correctly in the human lane: what a subject is, what states exist and who sees whose assessments are Recon
product decisions. But the **shape** of the type should land with schema v1 in Q1 even if the routes and the
reassessment machinery (WS10) ship in Q2, for two reasons:

1. Every day Recon runs on SPORK without it is a day of analyst judgements that are not captured, and those cannot be
   recovered afterwards. A rejection made in March is only an asset in November if it was recorded in March.
2. Retrofitting a versioned judgement type onto records that have already accumulated history is materially harder than
   defining it before the first partition is sealed.

The decision the owner has to make is therefore narrower than item 39 suggests: not "design the assessment model now",
but "reserve the type and its key now, so the product decision can be made later without a migration".

## 6. Images are on the critical path

Her most time-consuming workflow ends at image comparison: phone number or URL, then candidate identities, then textual
comparison, then possible social media profiles, then image comparison, then a supported, rejected or unresolved
assessment. That is the strongest user-side evidence for the round-8 directive that the System 1 index ships from the
beginning rather than in Q3.

It also raises a scheduling question worth putting to the owner: the decisive signal in her example was a **tattoo**,
and S-TATTOO is a train 2 space (WS15b). The near-duplicate and text spaces shipping in Q1 do not serve the step where
her workflow actually resolves.

## 7. Open questions back to the analyst

Worth asking before 38, 39 and 40 are designed:

1. What counts as independently corroborated presence? Which sources would she accept as corroborating, and does a
   second ad listing the same city count for anything?
2. When a pair has been rejected before, does she want it hidden from candidates or shown flagged with the earlier
   decision? Her wording ("prevent repeated work while allowing reassessment") implies shown and flagged, which is also
   the safer default, but it is her call.
3. What is an assessment scoped to: her, her team, the tenant, the case? Does she want to see another analyst's
   rejection, and under whose name?
4. Is there a candidate volume at which the recall preference inverts, and is it per selector or per screen?
5. Which selector classes does she already distrust by sight (VoIP ranges, known aggregator numbers, recycled handles)?
   Those are the discount tiers for 41, and she is the person who knows them.
6. In her dedup example, what distinguishes "same girl, different sites" from "same ad syndicated"? That distinction is
   the one item 38 has to encode, and the answer is probably operational rather than structural.

## Related

- [01-architecture.md](01-architecture.md) 2.1 schema, 2.2 soft edges, 2.6 identity decisions, 2.7 ontology and `_prov`
- [04-identity-resolution-design.md](04-identity-resolution-design.md) the time-aware compare model, DISJOINT and CONFLICT
- [08-native-versioning-design.md](08-native-versioning-design.md) E25, the system-time axis of P4
- [workstreams/ws3-recon-plugin.md](workstreams/ws3-recon-plugin.md) the routes most of these items land in
