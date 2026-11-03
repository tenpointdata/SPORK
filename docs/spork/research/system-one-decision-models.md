# "System 1" decision models (TypeSafe AI's Jev) and what they mean for SPORK

Research date 2026-11-02. Centers on TypeSafe AI's **Jev** and the "System One model" category per the owner's
correction (not JEPA). "Verified" = read on a primary source; "Inference" = reasoning.

## 0. Name disambiguation

**Verified**
- No entity is publicly branded "Typesafe Labs". Two unrelated companies match "Typesafe":
  1. Typesafe Inc. (Scala/Akka), renamed Lightbend in 2016. Unrelated.
  2. **TypeSafe AI** (typesafe.ai, San Francisco, founded 2024 by Diogo Almeida, Erik Gafni, Sasha Sheng), "an AI
     lab building machine-native intelligence infrastructure for automation." This is what the owner means.
- **Jev** is TypeSafe AI's first public model, announced 15 Sep 2026 as "the first System One model" (name from
  the Jevons paradox). https://typesafe.ai/blog/introducing-system-one-models-and-jev
- No ML model literally named "JEV" exists; JEPA is a different (perception) family.

## 1. Jev in detail

### 1.1 What it is (verified)
- "System One models are a class of AI models built to make fast, structured decisions that software can use
  directly ... evaluates a state and returns typed answers and probabilities." It understands natural-language
  input but returns typed decisions and probabilities, never generated text.
  https://docs.typesafe.ai/concepts/system-one.md
- Marketing: "a frontier-intelligence function call", "type errors are mathematically impossible", "can't
  hallucinate". The Register notes a typed output can still be wrong. https://www.theregister.com/a/5296711

### 1.2 Inputs and outputs (verified from OpenAPI/docs)
`POST https://api.typesafe.ai/v1/systemone`, bearer auth. Request = `model`, `state` (string | JSON | array),
`questions` (map of names to typed questions), optional `assets` (images).

| Primitive | Input | Output |
|---|---|---|
| `noul` | `instructions`, optional `criteria{true,false}` | P(statement true) in [0,1] |
| `choice` | `instructions`, `criteria` map of 1-255 named options | `choice` (argmax), `probabilities`, `confidence` |
| `score` | `instructions`, `criteria` ordered list of 1-10 levels | expected level (fractional), `probabilities`, `confidence` |
| `box` (1.14, experimental) | `instructions` + image + danger-zone header | bounding box as 0-1 fractions |

- All questions in a request are evaluated in parallel against the same state; adding questions barely changes
  response time. This is the "speculative fan-out" pattern. https://docs.typesafe.ai/patterns/fan-out.md
- `confidence` is derived from the distribution, not correctness: Choice = (p_max - 1/n)/(1 - 1/n).
- Limits: 64k tokens per request, 32k per question; opt-in header raises to 262k but slower and less accurate.
- Modalities: text/JSON production; still images experimental since 1.14 (14 Oct 2026). No audio/video.
  TypeSafe: "all models (Jev included) perform worse on multimodality over pure text."
- Versions `jev-1.13.0`, `jev-1.14.0`; aliases `jev-latest`, `jev-preview`; 30-60 day deprecation windows.

### 1.3 Architecture and training (thin)
Verified claims: "a new model architecture, parallel sampler", post-training "Reinforcement Learning for
Calibrated Decisions (RLCD)" optimizing "epistemically honest probabilities"; outputs parallel, not
autoregressive. Not published: parameter count, backbone, training data, RLCD math, any paper. Inference: a
transformer encoding state+question once with softmax readouts over the option set, RL-tuned on a proper scoring
rule. Treat the "new architecture" claim as unsubstantiated.

### 1.4 Latency and cost (vendor-reported)
- "70ms-500ms end to end", "40x-200x faster" than frontier LLMs; demo 0.114 s / $0.000081 vs GPT-5.6 8.566 s /
  $0.01388.
- Price $0.042 per million input tokens, output free.
- Case study (Jack & Jill screening): replaced Gemini 3.1 Flash Lite for first-pass scoring, cost -88%, shortlist
  recall 93.9% -> 94.6%, AUC 0.924 -> 0.933. https://typesafe.ai/blog/jack-jill-jev-case-study

### 1.5 Deployment, license, terms (verified)
- **Hosted API only.** No open weights, no self-hosting, no on-prem. Also via OpenRouter Decisions API, Vercel AI
  Gateway, Cloudflare Workers AI, Netlify AI Gateway.
- SDKs `typesafe-sdk` (Python) and `@typesafe-ai/sdk` (Node).
- Legal: Master Customer Agreement + DPA; commitment not to train on user data; zero data retention for enterprise
  on request. No SLA, acceptable-use list, or SOC2/HIPAA statements found; full MCA not reviewable (404).
  https://docs.typesafe.ai/legal.md
- Funding: $40M seed (DCVC, Sep 2026); reported $870M Series A at $7.5B (a16z, Oct 2026).

### 1.6 Published results and independent evidence
- Vendor "workflow evals": labels are the average of two frontier LLMs, not human. Jev trails on raw agreement
  (67.8% vs GPT-5.6 Sol 74.1%) and wins on cost/latency.
- Independent (small): 300-item sets, Jev won 3 of 4; items with confidence >= 0.9 were 89.9-99.6% accurate at
  66-90% coverage (BenchLM). Phishing: direct Noul 62.6% accuracy, ECE 0.154; logistic regression over five Jev
  signals reached 95.0%. Perplexity panel (7,210 samples): pplx-decider-v1 27B 85.71% vs Jev 84.51%.
- Documented weaknesses (TypeSafe's own list): literal reading; not a calculator; reads dates as text; weak on
  double negatives/multi-hop; accuracy falls as state grows with unrelated content; adversarial content not treated
  as hostile by default; Choice leans toward the first option.
  https://docs.typesafe.ai/model-jaggedness/jev-1.13.md
- Calibration is population-level; it does not guarantee an individual answer is correct.

## 2. The broader landscape

### 2.1 Dual-process framing
Kahneman System 1/2; Bengio NeurIPS 2019 "From System 1 to System 2 Deep Learning"; LeCun's Mode-1 (reactive
policy) vs Mode-2 (world-model planning). TypeSafe inverts the usual usage: the LLM is the slow System 2 and Jev
is "the reflex, not the essay."

Defining properties of a System-1 *decision* model (inference): single forward pass, non-autoregressive, output
space fixed in advance, emits a probability distribution plus a confidence scalar, calibrated, ~100 ms, cost
dominated by input tokens, designed to be gated by thresholds with escalation to a slower verifier.

### 2.2 Families
1. Amortized policies/classifiers (Jev with a natural-language interface and calibration training).
2. Decision Transformers (control, autoregressive over actions).
3. System 2 -> System 1 distillation (Dualformer, Meta FAIR).
4. Fast-then-verify loops (Thinker 2025; Solve-Detect-Verify ACL 2026).
5. Learned systems components (database-native System 1s): learned indexes (Kraska 2018), learned cardinality /
   plan steering, learned ANN partition routing (LIRA, learning-to-rank routing), learned cache replacement
   (Glider, LRB, PARROT: +61% hit rate over LRU).

### 2.3 The emerging "decision model" product category (Oct 2026)
OpenRouter Decisions API lists ~21-30 decision models from 13 makers; Jev most used. Competitors: **Perplexity
pplx-decider-v1/v1.1 27B** (Qwen3.8-27B fine-tune, **Apache 2.0 open weights**, 262k context, ~49 GiB GPU,
$0.04/Mtok hosted), Cloudflare Clef, AWS Strands Decider 2B, Microsoft Decision-1, Liquid d1, Inception Mercury
Decide, Upstage Solar Decide, Together Tev1 4B. Inference: the interface (state + typed questions -> probabilities)
is becoming a commodity; design to the interface, not to Jev.

### 2.4 Decision model vs embedding model
| | Embedding model (CLIP, DINO, JEPA) | System-1 decision model (Jev, Decider) |
|---|---|---|
| Output | a vector | typed answer + probabilities over your schema |
| Query-time use | ANN similarity; precomputable | one call per (state, questions); question known in advance |
| Where it sits | recall stage (index) | precision/decision stage (gate, rank, route) |
| Calibration | none | trained target (claimed) |
| Self-hostable | yes | Jev no; Decider yes (Apache 2.0, 27B) |
| Modalities | image, video, text, audio | text/JSON; images experimental |

Both are System 1 (single pass) but answer different questions: *what is near this?* vs *is this X, how sure?*

### 2.5 JEPA, briefly
Self-supervised perception family (I-JEPA, V-JEPA, V-JEPA 2/2.1); produces embeddings, not decisions. Licensing:
I-JEPA and V-JEPA 2 checkpoints CC-BY-NC; DINOv3 custom Meta license; DINOv2, Perception Encoder, SigLIP 2 are
Apache 2.0.

## 3. Implications for SPORK (inference)

### 3.1 Principle: System 1 decides *where to look and what to trust*; indexes do the looking

| DB concern | Decision-model use | Primitive |
|---|---|---|
| Query intent / planner routing | classify a query into {face, tattoo, phone lookup, geo-temporal sweep, graph expansion, full-text}; choose indexes/partitions and order | Choice + Nouls |
| Learned pruning | given query + partition summaries, Noul "could the answer be in this partition?"; probe those above threshold; exhaustive fallback | Noul fan-out |
| Ingest-time priority / pre-ranking | Score each record on rubrics (trafficking indicators, urgency, PII richness, quality); store as a column so hot items are pre-ranked and get heavier System 2 processing first | Score |
| Entity resolution fast pass | after blocking/ANN candidates, Score {different, possibly related -> analyst, same} + Nouls per attribute; only high-confidence "same" auto-merges; face matcher/OCR as slow verifier | Score + Nouls |
| Re-ranking ANN shortlists | per candidate "same person/ad/vehicle?" | Noul |
| Cache / prefetch | predict whether an entity/case/partition will be touched again soon (too slow for page cache, fine for entity-level prefetch) | Score/Noul |
| Guardrails on System 2 | verify LLM extractions, filter relevance before indexing | Noul |

### 3.2 Keep the "System 1 index" and the "System 1 decision" separate
- The index layer stays embedding-based (precomputable, self-hostable, multimodal, sub-ms ANN).
- The decision layer wraps indexes: routing, pruning, priority, merge/no-merge, escalation. One network call per
  decision, so use at ingest (once per record), at query planning (once per query), and on shortlists (tens to
  hundreds), never per index entry.
- Cascade: ANN recall -> decision-model triage with thresholds -> System 2 verification (face matcher, OCR, LLM,
  analyst).

### 3.3 What the database must expose
1. **State construction API**: compact decision-relevant JSON views (records, candidate pairs, partition
   summaries, query context); pre-compute dates/numbers/buckets server-side (model reads dates as text).
2. **Decision log**: model version, question hash, state hash, probabilities, confidence, threshold, action,
   latency, cost. Needed for audit, calibration monitoring, and version deprecation.
3. **Outcome feedback** joined to the decision log (analyst confirmed merge, verifier agreed, lead was real) to fit
   thresholds per question and detect drift.
4. **Threshold/policy configuration as data**, per action and risk level, with a middle band routed to an analyst
   queue modeled as a first-class table.
5. **Pluggable decider interface** (state + typed questions, Noul/Choice/Score) so Jev, Perplexity Decider
   (self-hostable), or an in-house classifier can be swapped; deterministic-rules fallback.
6. **Partition and index metadata** (time range, region, source, modality, size, hit rate) as small summaries for
   router questions; planner hook accepting an ordered probe list with exhaustive fallback.
7. **Batching and concurrency controls**: many questions per request is cheap; many requests are rate-limited.
8. **Privacy posture**: for CHT data, hosted-only inference is a real constraint. Design so decision calls can run
   against a self-hosted Apache-2.0 decider or a local classifier, with Jev as an optional provider.

### 3.4 Domain risks
- Adversarial ad text is a documented weak point; never gate safety-critical actions on the decision model alone.
- Option-order bias and literalism: version and test question templates like code; shuffle options.
- Calibration is population-level; merges must stay reversible with provenance.
- Image input is experimental; keep face/tattoo decisions on dedicated verifiers; feed the decision model their
  textual outputs (scores, OCR text) rather than pixels.

## Sources
typesafe.ai (blog, docs, OpenAPI, legal, jaggedness, patterns, cookbooks); openrouter.ai/blog/insights/what-is-jev;
theregister.com/a/5296711; benchlm.ai/blog/posts/what-is-jev; pulse2.com (Series A); neuraltrust.ai;
arxiv.org/abs/2609.28940; huggingface.co/perplexity-ai/pplx-decider-v1-27b; Bengio NeurIPS 2019; LeCun position
paper; arxiv 2410.09918, 2505.21097, 2106.01345, 1712.01208, 2404.11731, 2412.05921, 2503.23409; Glider (MICRO
2019); PARROT (ICML 2020).
