<!--
SPDX-FileCopyrightText: 2026 Ten Point Data
SPDX-License-Identifier: Apache-2.0
-->

# SPORK: project standup and engineering guide

SPORK is the `tenpointdata` fork of the ArcadeDB multi-model database. The pinned
source is upstream-derived Java and web software, retaining ArcadeDB names,
coordinates and licenses. It is not a cleanroom implementation. This guide is
based on `main` at `4bc577f11c444cad707f06b40040da6894664d66` (reviewed 2026-11-03 UTC).
See [HANDOFF.md](../../HANDOFF.md) for the current fork state and SPORK-specific work.

## Start here

| Task | Direct entrypoint |
| --- | --- |
| Understand the database and user-facing setup | [Upstream README retained in this fork](../../README.md) |
| Build, test or change a module | Workflow below, [CONTRIBUTING.md](../../CONTRIBUTING.md), [CLAUDE.md](../../CLAUDE.md) |
| Work on the browser UI | [Studio manifest](../../studio/package.json), [Studio instructions](../../studio/CLAUDE.md) |
| Understand distribution/module selection | [Package builder guide](../../package/README-BUILDER.md), [plugins](../../PLUGINS.md) |
| Check provenance and obligations | [LICENSE](../../LICENSE), [NOTICE](../../NOTICE), [ATTRIBUTIONS.md](../../ATTRIBUTIONS.md), [REUSE.toml](../../REUSE.toml) |
| Hand off this fork | Current status and next-action table below, [dated evidence review](REVIEW-2026-11-03.md) |

## Source origin and responsibilities

GitHub fork metadata identifies [ArcadeData/arcadedb](https://github.com/ArcadeData/arcadedb)
as both parent and source. The fresh fork head matches the captured inventory pin.
The root [pom.xml](../../pom.xml) identifies `com.arcadedb:arcadedb-parent:26.11.1-SNAPSHOT`.
The existing README and release links describe upstream artifacts; their presence
is not evidence that this organization has built or deployed this exact fork.
No upstream release archive or binary was executed for this standup.

| Boundary | Responsibility |
| --- | --- |
| `engine/` | Embedded storage, transactions, indexes and query engines; reusable database core |
| `network/`, `server/` | Network infrastructure, server lifecycle and HTTP surface |
| `ha-raft/` | High-availability mechanisms; use its module instructions and fixtures |
| `gremlin/`, `graphql/`, `mongodbw/`, `redisw/`, `postgresw/`, `bolt/`, `grpcw/` | Protocol/query adapters around the server; preserve dependency scopes and public behavior |
| `grpc/`, `grpc-client/`, `mcp/` | Protocol/client definitions and integration surfaces |
| `studio/` | Browser UI and bundled assets served with the database |
| `metrics/`, `tracing/` | Observability integrations |
| `console/`, `integration/`, `package/` | Console, integration utilities and assembled distributions |
| `gremlin-it/`, `gremlin-consumer-it/`, `e2e/`, `e2e-ha/`, `load-tests/`, `coverage/` | Reactor validation, integration/load tests and aggregate reporting |
| `e2e-js/`, `e2e-studio/` | Separate npm consumers: driver tests and Playwright UI tests |

Reuse the core and existing protocol contracts; keep service/product policies in
configuration or adapters. Follow the dedicated engine concurrency guidance
before changing pools, locking or hot paths. For Studio, favor directly available
frequent actions and tables for multi-item scanning, preserving accessibility
and consequential confirmations.

## Local build and test workflow

The current reactor sets Java release **21**. Use a compatible JDK; legacy Java
17 support belongs to the separately named upstream branch. The committed
Maven wrapper selects **Maven 3.10.0**. Docker is needed for container-dependent
integration/image workflows, not for reading or reviewing this documentation.
The following commands are backed by committed contribution/build instructions;
they were not executed in this docs-only review.

From repository root:

```sh
./mvnw --version
./mvnw clean install -DskipTests
./mvnw verify
```

The second command builds without acceptance tests. The third runs the reactor
through verification; a bare full-reactor `test` phase cannot supply Gremlin's
package-phase shaded/test artifacts. For a changed server module, use:

```sh
./mvnw -pl server -am test
```

The `-am` option includes local prerequisites so the test does not silently use
stale installed dependencies. For module sets containing Gremlin and consumers
of its shaded jar, use `verify` instead of `test`. Existing contribution instructions
also define `./mvnw clean install -P integration`. Do not broaden to all HA/load,
nightly or native-image jobs without a concrete affected contract and resources.

After the distribution build, the pinned version's assembled runtime is under
`package/target/arcadedb-26.11.1-SNAPSHOT.dir/arcadedb-26.11.1-SNAPSHOT/`.
Run its `bin/server.sh` (or `bin/server.bat` on Windows) in a disposable test
installation, select test credentials privately and preserve its configuration
and database directory. The README's Docker example uses a public upstream image
and demo import: it is not an exact-fork source smoke test. Do not reuse a demo
password or import customer data for qualification.

For a direct Studio check, use Node **20.20.0** (matching `studio/.nvmrc` and
`studio/pom.xml`) and npm compatible with the manifest (Maven pins **10.2.3**):

```sh
cd studio
npm ci
npm test
npm run build
```

These run package lifecycle scripts and may fetch public dependencies. Execute
in a scratch checkout when validating; the build writes bundled assets.
The Maven Studio module also installs its pinned Node/npm and runs build/tests.
The UI's `npm run clean` removes dependencies and built assets, so it is not a
setup prerequisite. UI changes also require browser verification of the actual
workflow, keyboard behavior and desktop/touch geometry. Do not infer UI
acceptance from a successful webpack build.

## Validation and current limits

| Surface | Existing mechanism | What this standup establishes |
| --- | --- | --- |
| Java/reactor | [mvn-test.yml](../../.github/workflows/mvn-test.yml), native fixtures and contributor commands | Commands and module boundaries inspected; no compile/runtime/test rerun |
| Studio | Node test runner, webpack, [security audit workflow](../../.github/workflows/studio-security-audit.yml) | Manifest scripts inspected; no dependency installation or browser acceptance |
| License/source origin | [REUSE workflow](../../.github/workflows/reuse-compliance.yml), license workflow and retained notices | New Markdown carries SPDX metadata; existing license/provenance untouched |
| Organization web-source policy | [AGENTS.md](../../AGENTS.md): <=600 lines, no TypeScript `any`, shared types | No web code changes; strict changed-source scan selects zero code files |
| Whole-repository policy debt | Needs a separate baseline inventory and review of generated/vendor/framework coverage | Not assessed by this small docs change; no full-compliance claim |
| Remote deployment/registry/consumer | Requires exact fork build, artifact identity and separate owner evidence | Not verified or changed |

Before future web-source changes, reuse the organization source checker and its
pinned TypeScript parser, retain a full debt inventory, and enforce changed-source
rules against an immutable base. Do not waive generated/vendor code or manufacture
a green gate by excluding framework files. Integrate with the existing workflow
activation model after verifying parser coverage; no workflow is added or
activated by this standup.

## Security, licensing and publication

The existing [SECURITY.md](../../SECURITY.md) gives upstream coordinated-disclosure
channels and explicitly excludes third-party forks from upstream support scope.
For a fork-specific finding, coordinate privately with this repository's owner;
for an upstream issue, use the upstream private reporting route. Do not expose
credentials, database snapshots, customer queries or exploit details in the
repository. Source documentation does not verify remote security settings or
service configuration.

The Apache-2.0 license, notices, REUSE metadata and dependency attributions remain
canonical. Follow the contribution rules for new source SPDX headers and license
compatibility. Existing instructions define contribution licensing without a
CLA. Preserve those instructions and upstream credits; a fork rename does not
transfer upstream authorship.

Studio explicitly uses `https://registry.npmjs.org/` in its committed `.npmrc`.
The root reactor and release workflows retain upstream Maven coordinates and
publication behavior. This standup adds no private registry and performs no
release, deployment, tag, registry write or consumer change. Any future fork
release needs an explicit artifact/version/namespace decision, legal attribution
review, exact CI and artifact readback, and an independent consumer acceptance
plan before promotion.

## Handoff and next actions

| Next action | Owner role | Acceptance criteria |
| --- | --- | --- |
| Decide this fork's intended divergence and release/consumer scope | Fork maintainer | Written compatibility requirements, named consumers and artifact namespace; retain upstream attribution |
| Qualify a source build on the pinned fork | Build owner | JDK/wrapper versions, exact commit, distribution hash and focused native test results |
| Inventory web-source policy debt | UI/tooling maintainer | Complete report including generated/vendor/framework files, bounded remediation and strict changed-source enforcement |
| Qualify the intended UI workflow | UI maintainer | Browser evidence for frequent tasks, keyboard access and desktop/touch geometry |
| Reconcile remote acceptance before shipping | Release owner | Current CI, exact artifact/registry identity, configuration and consumer checks; no inference from upstream badges |

A handoff must carry the base/head, changed paths, local versus remote checks,
artifact hashes, data-preservation assumptions and remaining gates. Keep findings
linked to existing issues/PRs when available; do not duplicate upstream tracking
or treat this guide's suggested work as assigned implementation.
