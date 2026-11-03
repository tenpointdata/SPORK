# Workstream: partial-text search in the full-text engine

Investigators search with fragments: the last 4 or 7 digits of a phone number, a piece of a handle, a misspelled name. The
full-text engine is ArcadeDB's own LSM-Tree engine that borrows only Lucene's `Analyzer` and `QueryParser`, so before this
work every fragment search (`*1234`, `*kitty*`, `smiht~2`) walked every posting of the index. This workstream makes those
searches term lookups or short range scans.

## What changed

1. **Per-field analyzers work.** `<field>_analyzer` in the index `METADATA` was parsed and persisted but never used: every
   property was analyzed with the index-wide analyzer. Each property is now indexed with its own analyzer, and a query clause
   on a field (`SEARCH_INDEX` `field:term`, or `field CONTAINSTEXT ...`) is analyzed with it. An unqualified clause uses the
   index analyzer (or, on a single-property index, that property's analyzer).
2. **Built-in partial-match analyzers**, usable by alias or class name, no extra jar:

   | Spec | Class | Stores | Query side |
   |------|-------|--------|------------|
   | `ngram`, `ngram(min,max)` (default `3,8`) | `analysis.NGramAnalyzer` | each whitespace token, folded (lower case, ASCII), whole plus every gram of `min..max` code points | the fragment whole; one longer than `max` is split into `max`-sized windows that must all match; one shorter than `min` matches only a whole token (see caveats) |
   | `edge_ngram`, `edge_ngram(min,max)` (default `2,15`) | `analysis.EdgeNGramAnalyzer` | each word (standard tokenizer), folded, whole plus its prefixes of `min..max` | the word; one longer than `max` is the word itself or, cut to `max`, a longer word it starts, the word itself ranking first |
   | `reverse` | `analysis.ReverseAnalyzer` | each whitespace token, folded, plus its reversed copy behind a `\u0001` marker | a leading wildcard (`*1234`) becomes a range scan of reversed tokens; leading wildcards are accepted without `allowLeadingWildcard` |
   | `phone`, `phone(minSuffix,countryCode)`, `phone(minSuffix,countryCode,trunkPrefix)` (default `4,1`; trunk prefix `0` for a country code other than 1, none for 1) | `analysis.PhoneAnalyzer` | every number in the value reduced to digits: canonical form (international prefix `+`/`00`/`011` dropped; a national number starting with the trunk prefix loses it and gets the country code; otherwise a 10-digit national number gets the country code) plus every suffix of at least `minSuffix` digits | the digits of the query, international prefix dropped, also tried without the trunk prefix and without the country code when the digits are a whole number (9 or more national digits) |
   | `phonetic`, `phonetic(double_metaphone)`, `phonetic(beider_morse)` | `analysis.PhoneticAnalyzer` | each word plus its Double Metaphone codes (or its Beider-Morse forms, computed before the word is folded to ASCII) | the same analysis: a word matches by spelling or by sound |

   Classes live in `com.arcadedb.index.fulltext.analysis`. A partial-match analyzer runs its *query-side counterpart* on
   queries automatically (grams OR-ed on the query side would match almost everything), unless `query_analyzer` names one
   explicitly. The phonetic analyzer uses `lucene-analysis-phonetic` + `commons-codec` (both Apache 2.0, added to
   `engine/pom.xml` and `ATTRIBUTIONS.md`; their NOTICE content was already in `NOTICE`).
3. **Fuzzy (`term~N`), wildcard, prefix and regexp walk distinct terms and seek.** The executor used to evaluate the matcher
   (Levenshtein, regex) once per *posting* and could not skip anything. It now walks *distinct* keys, and a rejected key yields
   the next key that could match; the walk reads on to it when it is close and otherwise reopens the cursor there. Fuzzy
   queries are driven by a Lucene Levenshtein automaton (`AutomatonTermSeeker`), which computes the smallest string greater
   than a rejected term that can still be accepted, so a fuzzy term reads only the terms near it. Fuzzy matching now honours
   transpositions (`ab`/`ba` is one edit), as Lucene's `term~N` syntax defines it; it used to count them as two.
4. **Query parser additions:** `SynonymQuery` (several tokens at one position, e.g. a word and its phonetic code) and
   `MultiPhraseQuery` are now evaluated; they were silently ignored.

## Usage

### SQL

```sql
CREATE DOCUMENT TYPE Profile;
CREATE PROPERTY Profile.name   STRING;
CREATE PROPERTY Profile.handle STRING;
CREATE PROPERTY Profile.phone  STRING;

-- one index, one analyzer per field
CREATE INDEX Profile_search ON Profile (name, handle, phone) FULL_TEXT METADATA {
  "name_analyzer":   "phonetic",
  "handle_analyzer": "ngram(3,8)",
  "phone_analyzer":  "phone"
};

-- substring of a handle
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'handle:kitty') = true;
-- last 4 / last 7 digits, any format; quote every number of more than one token (parentheses group, spaces separate clauses)
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'phone:1234') = true;
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'phone:555-1234') = true;
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'phone:"(702) 555-1234"') = true;
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'phone:"+1 702 555 1234"') = true;
-- a UK number, national or international on either side: phone(4,44) drops the trunk 0 and adds 44
CREATE INDEX ON Contact (phone) FULL_TEXT METADATA {"analyzer": "phone(4,44)"};
SELECT FROM Contact WHERE SEARCH_INDEX('Contact[phone]', '"020 7946 0958"') = true;
-- sounds like
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'name:smyth') = true;
-- misspelled, up to 2 edits
SELECT FROM Profile WHERE SEARCH_INDEX('Profile_search', 'name:jonhson~2') = true;
-- per-field analyzers also apply to CONTAINSTEXT
SELECT FROM Profile WHERE handle CONTAINSTEXT 'itty';

-- suffix search: a reverse index
CREATE INDEX ON Account (handle) FULL_TEXT METADATA {"analyzer": "reverse"};
SELECT FROM Account WHERE SEARCH_INDEX('Account[handle]', '*_2024') = true;

-- prefix as you type
CREATE INDEX ON Person (name) FULL_TEXT METADATA {"analyzer": "edge_ngram(2,15)"};
SELECT FROM Person WHERE SEARCH_INDEX('Person[name]', 'joh') = true;
```

### Cypher

```cypher
CREATE FULLTEXT INDEX handles FOR (n:Seller) ON EACH [n.handle]
  OPTIONS {indexConfig: {`fulltext.analyzer`: 'ngram(3,8)'}};

CALL db.index.fulltext.queryNodes('handles', 'kitty') YIELD node, score
RETURN node.handle, score ORDER BY score DESC LIMIT 20;

CREATE FULLTEXT INDEX phones FOR (n:Phone) ON EACH [n.number]
  OPTIONS {indexConfig: {`fulltext.analyzer`: 'phone'}};
CALL db.index.fulltext.queryNodes('phones', '5551234') YIELD node RETURN node.number;
```

`fulltext.analyzer` takes the same specs (`ngram`, `edge_ngram`, `reverse`, `phone`, `phonetic`, with parameters). Cypher
DDL has no per-field analyzer option; use SQL `CREATE INDEX ... METADATA` for that.

## Measured

`engine/src/test/java/performance/FullTextPartialMatchBenchmark.java` (`@Tag("benchmark")`): 1,000,000 synthetic records
(name = first name + 2-3 syllable surname, handle = `adjective_noun_NNNN`, phone in three US formats), each query through SQL
`SEARCH_INDEX`, 3 warm-up runs then median of 15. Laptop (Apple Silicon, `-Xmx8g`), shared with other build sessions, so
absolute numbers are noisy; compare within a table.

```bash
JAVA_TOOL_OPTIONS=-Xmx8g mvn -o -pl engine test -Dtest=FullTextPartialMatchBenchmark -Dgroups=benchmark -DexcludedGroups=none
# add -Darcadedb.partialTextBenchmark.mode=baseline to skip the partial-match indexes, .records=N to resize
```

**Same database, same queries, old code (HEAD before this work) vs new code** (baseline mode, standard/whitespace analyzers):

| Question | Query | Matches | Before (median ms) | After (median ms) |
|----------|-------|--------:|-------------------:|------------------:|
| fuzzy, 1 edit | `name: txnphen~1` | 1,152 | 562 | **75** (7.5x) |
| fuzzy, 2 edits | `name: txnpheq~2` | 1,152 | 463 | **109** (4.2x) |
| substring (leading wildcard scan) | `handle: *unny_4*` | 6,148 | 560 | 540 |
| suffix (leading wildcard scan) | `handle: *y_4148` | 29 | 596 | 540 |
| prefix (range scan) | `handle: angel_*` | 49,961 | 106 | 122 |
| phone last 4 (leading wildcard scan) | `phone: *5326` | 93 | 1,450 | 1,345 |

Fuzzy gains come from the automaton seek; the scans are unchanged within noise (they now evaluate each distinct term once,
but still read every term of the field: that is what the analyzers below remove).

**Same question, scan vs partial-match analyzer** (new code, one database holding both 1M-row types):

| Question | Without (scan) | With analyzer | Matches | Scan (ms) | Analyzer (ms) | Speed-up |
|----------|----------------|---------------|--------:|----------:|--------------:|---------:|
| substring | `*unny_4*` | `ngram`: `unny_4` | 6,148 | 1,743 | 51 | ~34x |
| suffix | `*y_4148` | `reverse`: `*y_4148` | 29 | 1,470 | 4.3 | ~340x |
| phone last 4 | `*5326` | `phone`: `5326` | 93 | 6,165 | 0.9 | ~7,000x |
| prefix | `angel_*` | `edge_ngram`: `angel_` | 49,961 | 557 | 459 | ~1.2x |

An earlier run of the same table on a quieter machine measured substring 1,293 → 25 ms, suffix 1,104 → 1.0 ms, phone
1,210 → 0.22 ms, prefix 207 → 194 ms. Prefix gains little here because the query matches 5% of the corpus and loading the
49,961 records dominates; edge n-grams pay off for short prefixes of selective values (type-ahead).

Load cost: the 1M `Partial` rows with four partial-match indexes (n-gram, reverse, edge n-gram, phone on one handle and one
phone each) took ~350 s to insert versus ~10 s for the `Base` type with three plain indexes; n-grams dominate. Size the gram
range to the fragments investigators actually type.

## Caveats and things deferred

- **Existing indexes with `<field>_analyzer`** were built with the index-wide analyzer. An index whose definition has no
  `ft_formatVersion` (written before this work) keeps indexing AND querying every field with the index-wide analyzer, so
  updates and deletes keep removing the tokens that were stored; the open logs a warning naming the index and the
  `REBUILD INDEX` that applies the per-field analyzers (it re-creates the index in the current format). Applying the field
  analyzer to such an index at once made every update or delete remove tokens that were never stored and leave the real
  ones, so deleted or rewritten documents kept matching their old content.
- **Downgrading** to a version before this work: the LSM data is readable, a stale block-max file is ignored, but a schema
  that names a built-in alias (`ngram`, `edge_ngram`, `reverse`, `phone`, `phonetic`) fails to load ("Cannot instantiate
  analyzer: ngram"), and per-field analyzers are silently ignored again (with the same update mismatch as above). DROP or
  REBUILD those indexes with plain Lucene analyzers before downgrading. The older version knows nothing of the
  `fulltext-blockmax` directory in the database directory: a full-text index it drops or rebuilds leaves its `.bmx` files
  there (22-46% of the index size). Upgrading again deletes those files at the first read-write open; a database that stays on
  the older version can have the whole `fulltext-blockmax` directory deleted by hand while it is closed (the files are derived
  data, the older version never reads them).
- **Leading wildcards** are accepted by the parser on any index with a `reverse` field, but answered only on fields whose
  tokens are reversed (a range scan); on another field, or an unqualified clause of an index where not every field is
  reversed, they are refused as before unless `allowLeadingWildcard` is set, since they would walk every token of the field.
- **N-gram fragments shorter than `min`** (`ab` on `ngram(3,8)`) match only a value whose whole token is that fragment: the
  index stores no gram that short. Size `min` to the shortest fragment investigators type (`ngram(2,8)` for 2 characters).
  Not changed: a prefix walk over the grams that start with the fragment finds it at the start or middle of a token but
  misses it at the end, so it would answer some substrings and not others.
- **Phone numbers:** only the configured (default) country code and trunk prefix are known. A national number of another
  country written without its country code is stored as its digits; it is still found by its last digits.
- **Phonetic encoders** read Latin script. Double Metaphone gives no code to a word in another script; Beider-Morse covers the
  languages of its rule set (Latin-script languages, Cyrillic, Greek, Hebrew). Such a word still matches by spelling.
- **`CONTAINSTEXT` keeps its OR-of-tokens semantics.** A fragment longer than the largest gram is split into windows; on the
  `CONTAINSTEXT` path they are OR-ed and ranked (documents holding all of them first), on the `SEARCH_INDEX` path they must
  all match. Use `SEARCH_INDEX` for a strict long-fragment match.
- **Index size.** `ngram(3,8)` stores roughly 6 grams per character of a token; keep it to short fields (handles,
  identifiers). `edge_ngram` and `phone` are cheaper (one entry per prefix / suffix), `reverse` doubles the tokens.
- **Partial-match indexes parse queries one whitespace-separated word at a time** (`splitOnWhitespace`) and turn a word that
  analyzes into several tokens into a conjunction (`autoGeneratePhraseQueries`). On such an index a word a standard analyzer
  splits (`wi-fi`) on another field of the same index also becomes a conjunction instead of a disjunction.
- **SQL `LIKE '%x%'` and `MATCHES`** do not use the full-text index; they still scan. Rewriting them onto an n-gram index
  is a planner change left for later.
- **Fuzzy prefix length** is only what Lucene syntax carries (none); a `fuzzyPrefixLength` metadata key would cut the walk
  further for very large vocabularies. Not added: the automaton seek already reads only the terms near the query term.
- **Regexp queries** still evaluate `java.util.regex` per distinct term (now once per term instead of once per posting); an
  automaton-driven seek like the fuzzy one would need Lucene `RegExp` syntax, which differs from what the engine accepts.
- **Phone splitting** is heuristic: two numbers separated only by spaces are read as one number.
- **A changed analyzer changes what is stored.** The phone analyzer now stores a trunk-prefixed national number in its
  international form, edge n-gram queries try the whole long word, and Beider-Morse keeps the word: an index built with the
  earlier code of this workstream (not released) needs `REBUILD INDEX` to store the new forms.
