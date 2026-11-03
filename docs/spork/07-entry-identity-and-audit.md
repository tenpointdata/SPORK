# 07. One identity and audit stack on top of Entry

Status: design plus first implementation slice (worktree `.claude/worktrees/entry-identity-audit`, branch
`claude/entry-identity-audit`, uncommitted). Owner request 2026-11-03: "unify the user account management and audit
management paradigm vertically on top of Entry".

## 1. Problem

Before this note SPORK had three identity planes and no audit code:

| Plane | Who decides | What it knows |
|---|---|---|
| Entry (`~/Documents/Projects/entry`) | TPD's identity platform, more than 1,000 people | the account, its standing (disabled, enterprise suspension), its credentials (session, PAT, MCP), its permissions, its enterprises |
| Recon gateway | `gateway/auth.ts` validates an Entry bearer; 01-architecture 5.1 planned for it to sign an Operating Context JWT (it had no SPORK client yet) | `analyst` = Entry `Principal.id`, `role` = `console` or `ops`, case, purpose, markings |
| ArcadeDB server security | `server-users.jsonl`, `server-groups.json`, `at-` API tokens | two shared service users (`console`, `ops`) that every analyst runs as |

So the engine saw two users, the audit design (01-architecture 5.2) recorded an `actor` string the gateway asserted,
nothing in SPORK reacted when Entry disabled someone, and Recon, Entry and SPORK each planned their own audit trail.

## 2. The vertical stack

Entry is the only source of who someone is and what they may do. SPORK enforces and records; it holds no human accounts.

```
 Entry account, standing, credentials, permissions, enterprises
   |  bearer credential (session / PAT / MCP), TConsumerSessionProof v1, /api/auth/user
   v
 1. Authentication   EntryAuthenticator: fresh proof from Entry, cross-checked against the account record
   v
 2. Authorization    Entry permissions -> ArcadeDB groups (server-entry.json); one engine user per person
   v
 3. Standing         EntryLifecycleReceiver: Entry's TLifecycleDelivery v2 stream, deny layer read on every request
   v
 4. Audit            AuditChain: every entry's actor is the Entry account; lifecycle changes land in the same chain,
                     in the same transaction as the state they change
```

### 2.1 Authentication

- A request carries the person's own Entry credential: `Authorization: Bearer <entry token>`. Tokens that start with
  `at-` (ArcadeDB API token) or `AU-` (ArcadeDB session) keep their existing path; everything else goes to Entry when
  `server-entry.json` is present.
- `EntryClient` makes two server-to-server reads with no redirects, no cookies, a body cap and a timeout:
  `GET {origin}/api/v1/consumer/session-proof` (Entry's `TConsumerSessionProof` v1: environment, `sourceUserId`,
  `identityId`, `credentialId` fingerprint, `accountSequence`, expiry) and `GET {origin}/api/auth/user` (email,
  `disabled`, `verified`, `api.permissions`). The validation is a port of `entry-lifecycle/src/consumerSessionProof.ts`
  (same field rules, the same 30 s clock window). The account record must have the proof's `sourceUserId` as its `id`,
  be enabled and verified, and the proof's environment must be the configured one; otherwise the request is 401.
- Verdicts are cached by SHA-256 of the token for `verdictCacheSeconds` (default 30, 0 disables), never past the
  proof's `expiresAt`. Refusals are cached for the same time, so random bearer tokens cannot turn SPORK into an
  amplifier against Entry, and at most `maxConcurrentLookups` (default 32) Entry reads run at once. Entry lookups run
  on a worker thread, never the IO thread.
- The engine user name is `entry:<sourceUserId>`. It never matches `root`, so Entry people never administer the
  server; `root` and the cluster secret stay local break-glass credentials, and `at-` tokens remain for machines.

### 2.2 Authorization

- `server-entry.json` maps an Entry permission to ArcadeDB groups per database:

  ```json
  "grants": {
    "spork.console": { "*": ["console"] },
    "spork.ops":     { "identity": ["ops"], "recdir": ["ops"] }
  }
  ```

  A held permission covers a required one when equal, or when the required one starts with the held one plus a dot,
  exactly as Recon's gateway does (`recon` covers `recon.access`). Groups are the existing `server-groups.json` groups,
  so per-type ACLs, result-set limits and timeouts apply unchanged.
- A person holding none of the configured permissions is refused (403 at authentication, before any database is read).
- This replaces the OC `role` claim selecting a shared engine user (01-architecture 5.1 "Engine user binding" and 5.4):
  the engine user is the person, so the console/ops `txt_*` boundary becomes "which Entry permission the person holds".
  The OC keeps `caseRef`, `purpose` and `markings[]`; its `analyst` must equal the authenticated `sourceUserId`, so the
  gateway can no longer assert identity, only context.

### 2.3 Standing (Entry lifecycle)

- Entry already pushes account standing to reviewed consumers (`entry-contracts/TLifecycleDelivery.ts` v2, the
  `entry-lifecycle` receiver package used by Roca). SPORK implements the same receiver in Java:
  `POST /api/v1/entry/lifecycle`, authenticated by a dedicated installation credential whose SHA-256 sits in
  `server-entry.json` (`lifecycleCredentialSha256`), 16 KiB body cap, answer = Entry's `TLifecycleAcknowledgement`.
- Rules ported from `receiver.ts`: exact environment and service; scopes `account` (always) and `enterprise-person`
  (only for the configured `enterpriseOrgId`); paths `session`, `pat`, `mcp`; idempotent replay returns the stored
  receipt; a replay with a different payload is `delivery_conflict`; sequence must increase (`stale_event`); binding
  id, subject and context are fixed per stream and the binding revision cannot go back (`binding_changed`); credential
  revocations can only move forward (`revocation_regressed`); restoration never recreates grants.
- SPORK has no local accounts, so its binding rule is identity, not a reviewed mapping table: `consumerSubjectId` must
  be the Entry `userId`, and for `enterprise-person` `consumerContextId` must be the `orgId` (else `binding_unreviewed`).
- State lives in the configured database (default `identity`): `EntryLifecycleState` (one document per stream, unique on
  `streamKey`) and `EntryLifecycleReceipt` (unique on `deliveryKey`). It replicates with the database, so every node
  enforces the same standing, and the deny check is a fresh indexed read on every request (two HASH lookups), never
  cached. A missing database refuses Entry authentication (fail closed).
- Credential retirement: SPORK holds no Entry credentials, so retiring a path evicts every cached verdict of that
  person and records the revocation sequence. A proof whose `accountSequence` is below the recorded `session`
  revocation sequence of the account stream is refused, which fences a stale proof against a newer disablement.

### 2.4 Audit

- One chain shape for Recon, SPORK and Entry-originated events: Recon's `AuditEntry`
  (`recon-next/src/ops/contracts.ts`) with `hash = sha256(prevHash + canonicalJSON(entry without hash))` and genesis
  `"0" x 64`, so Recon's existing verifier checks SPORK entries unchanged.
- `actor` is the Entry account id, which is what Recon's gateway already writes (`Principal.id`). The hashed body also
  carries `entry: {env, identity, credential, account}`, so an entry names the exact Entry credential and the
  account's lifecycle position at the time, and nobody can rewrite that without breaking the chain. Automation writes
  `actor = "system:<job>"` with `onBehalfOf` = the Entry account that created it; local break-glass users write
  `actor = "local:<name>"`.
- `AuditChain.append` must run inside the caller's transaction and fails closed: no active transaction is an error,
  and a failed append rolls the business write back. Concurrent appends conflict on the single `ChainHead` document
  and retry through ArcadeDB's MVCC, which serializes the chain without a lock.
- The hashed body is stored verbatim as canonical JSON (`AuditEntry.body`) beside indexed copies of `seq`, `at`,
  `actor`, `action`, `caseRef` and `subject` for queries; verification recomputes from `body`, so property type
  coercion can never break a hash. Detail values are strings, booleans and integers only (Recon's are counts), which
  keeps Java's canonical JSON byte-identical to JavaScript's.
- Every lifecycle delivery appends `entry.lifecycle` (actor `system:entry-lifecycle`, subject = the Entry account,
  detail = scope, state, sequence, event and delivery ids, retired paths) in the same transaction as the state change
  and the receipt, and the receipt stores the audit `seq`. "When did SPORK learn this person was disabled, and what did
  they do before" is one chain query.
- Still as 01-architecture 5.2 designs: v2 commitments (`subjectKey`, `detailKey`), the MAC sidecar, anchors and
  `AuditAccess`. They extend this body; they do not replace it.

### 2.5 Recon gateway

- The gateway (`recon-next/src/gateway/spork.ts`) forwards `/api/spork/v1/{tenant}/...` to SPORK's `spork-recon`
  namespace as the signed-in person: `Authorization: Bearer <the person's Entry credential>`, taken from the bearer the
  caller sent or from the sealed session cookie. SPORK validates that credential itself (2.1), so the gateway asserts
  nothing about identity and needs no signing key shared with SPORK.
- `X-Spork-Context` is `base64url(JSON {caseRef, purpose})`, unsigned. Both fields are the analyst's own declaration,
  which the analyst could send SPORK directly with the same credential, so a gateway signature would vouch for nothing.
  The ops UI's typed analyst name ("Name or call sign") is never forwarded: the analyst is the Entry account. `role`
  is gone (the person's permissions pick the groups, 2.2), and `markings[]` are not a request field: the `spork-recon`
  plugin is to derive the markings a person holds from their Entry permissions, so they cannot be self-asserted. The tenant is the path segment.
- A partial or unrecognized context is refused with 400 before SPORK; an absent one is forwarded without the header,
  so SPORK's sensitive routes answer 428 as 5.1 requires.
- Only the plugin namespace is reachable (`v1/{tenant}/` plus up to six plain segments; no `.`, `..`, `%` or empty
  segments), never the engine's query, command or admin API. Cookies, the Hoonify key, client IP headers and Recon's
  `x-recon-*` headers are not forwarded; `X-ArcadeDB-Read-Consistency` and `X-ArcadeDB-Read-After` are (7.1). SPORK's
  4xx answers (403 `purposeDenied`, 428) reach the console as SPORK wrote them; its 401 becomes "Sign in again", its 503
  stays 503 and any other 5xx becomes 502 without SPORK's body. Responses are capped at 32 MB and may be NDJSON.
- `RECON_SPORK_URL` (https, or http on loopback) turns it on; without it the route answers 503 and `/api/session`
  reports `spork: false`.

## 3. Configuration

`<server>/config/server-entry.json`; absent means Entry is off and nothing changes.

| Key | Default | Meaning |
|---|---|---|
| `origin` | required | Entry's canonical origin; `https`, or `http` on localhost only |
| `environmentId` | required | must equal the proof's and every delivery's `environmentId` |
| `serviceId` | `spork` | this installation's Entry service id |
| `enterpriseOrgId` | none | the Entry organization whose `enterprise-person` suspensions SPORK enforces |
| `database` | `identity` | holds lifecycle state, receipts and the chain those events land in |
| `lifecycleCredentialSha256` | none | hex SHA-256 of the lifecycle installation credential; no value, no lifecycle route |
| `verdictCacheSeconds` | 30 | 0 disables the verdict cache |
| `maxCachedVerdicts` | 10,000 | the cache is cleared when it would grow past this |
| `maxConcurrentLookups` | 32 | concurrent Entry reads; excess requests are refused, not queued |
| `timeoutMillis` | 5,000 | per Entry read |
| `grants` | required | Entry permission -> { database or `*` -> [groups] } |

## 4. Cost on the read path

A cached request adds one SHA-256 of the token, one `ConcurrentHashMap` read and two HASH-index lookups on
`EntryLifecycleState` (about 2 us each warm, 02-retrieval-speed C_hash): under 10 us, well inside P1's budget. A cache
miss costs two Entry round trips (tens of ms) once per token per 30 s, on a worker thread.

## 5. Owner decisions and open items

1. **Account id parity.** The design requires `/api/auth/user`'s `id` to equal the proof's `sourceUserId`. Entry's
   GC session proof reads `entry_users.id`; confirm the legacy `/api/auth/user` returns the same id on staging before
   enabling (otherwise every lookup is 401, which is safe but useless).
2. **Entry service registration.** Entry needs a `spork` service, an installation map entry and an operator-reviewed
   binding per person (`consumerSubjectId = userId`) before deliveries flow; until then SPORK still enforces Entry's
   own refusals at authentication, it just learns of disablement at proof time rather than by push.
3. **Permission names.** `spork.console`, `spork.ops`, `spork.audit` are proposals; Entry enterprise admins grant them.
4. **Recon gateway.** Done in slice 2 (2.5). Still open on the SPORK side: the `spork-recon` plugin (WS3) must read
   `X-Spork-Context`, take the analyst from the authenticated `EntryServerSecurityUser` and derive markings from the
   person's Entry permissions (permission names for markings are an owner decision, like item 3).
5. **HA.** Lifecycle writes go to the leader like every governed write; deliveries to a follower are refused with 503
   until the leader route of 01-architecture 3.1 exists, and Entry's dispatcher retries them.

## 6. Slice 1 (implemented, 2026-11-03)

Server module, packages `com.arcadedb.server.security.entry` and `com.arcadedb.server.audit`: configuration, proof and
account parsing, permission-to-group mapping, the synthetic per-person engine user, the verdict cache and lookup gate,
the lifecycle receiver and deny check, the hash-chained audit store with verification, `POST /api/v1/entry/lifecycle`
(documented in the OpenAPI spec under Auth) and the Bearer hook in `AbstractServerHttpHandler`. Entry bearers dispatch
off the IO thread. Not in slice 1: the v2 commitment body, MAC and anchors (WS7), the audit query API, the Recon gateway
change, the leader route.

Verification: 52 new unit cases (`CanonicalJsonTest` against a golden hash produced by Recon's own `entryHash` under
Node, `AuditChainTest`, `EntryConfigurationTest`, `EntryClientTest`, `EntryLifecycleDeliveryTest`,
`EntryLifecycleReceiverTest`, `EntryAuthenticatorTest`, `EntryBearerWorkerThreadDispatchTest`) and `EntryAuthenticationIT`
(a stub Entry on a fixture port: permission-mapped access, 403 without a SPORK permission, 401 for a rejected
credential or a wrong installation credential, a disable delivery denying a cached person on the next request, the
entry in the chain). The server's security and handler unit tests (1,156) and the auth ITs (49) pass, except
`PostBatchHandlerForwardRequestTest`, which fails identically on untouched HEAD, and the timing-sensitive
`Issue7603LeaderForwardHopTest`, which passes alone. Not verified against a real Entry (open item 1).

## 7. Slice 2: Recon gateway (implemented, 2026-11-03)

Recon repository (`~/Documents/Projects/recon`, branch `claude/eager-dirac-q4q4as`, uncommitted): new
`recon-next/src/gateway/spork.ts`, `EntryAuth.authenticate` now also returns the Entry credential it validated,
`GatewayConfig.spork` from `RECON_SPORK_URL`, the `/api/spork/` route and `spork` in `/api/session`, and the
configuration row and boundary in `recon-next/docs/hermes-gateway.md`.

Verification: `tests/gateway-spork.test.ts` (7 cases: the bearer and the cookie session both forward the person's own
credential and never the cookie, typed analyst or Hoonify key; nobody Entry refuses reaches SPORK; unconfigured is 503;
path escapes and engine routes are refused; partial context is 400 and absent context reaches SPORK's 428; SPORK's
refusals and failures map as above; query strings and read-consistency headers pass, long queries are 414). Two
mutations (dropping the path check, dropping the context header) each turn one case red. `tsc --noEmit` is clean and
Recon's whole Bun suite passes (255 tests). Not run end to end against a SPORK server, because the `spork-recon`
namespace it forwards to does not exist yet; SPORK's half of the same credential path is `EntryAuthenticationIT`.
