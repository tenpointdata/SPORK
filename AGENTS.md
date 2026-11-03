<!--
SPDX-FileCopyrightText: 2026 Ten Point Data
SPDX-License-Identifier: Apache-2.0
-->

# SPORK repository instructions

Start with [project standup and engineering guide](docs/engineering/README.md)
and the [dated review](docs/engineering/REVIEW-2026-11-03.md).

- This is the organization fork of ArcadeData/arcadedb. Preserve upstream attribution, public package coordinates, API/format compatibility and existing license/notice files. Do not describe upstream code as an independent implementation.
- Read [CONTRIBUTING.md](CONTRIBUTING.md), [CLAUDE.md](CLAUDE.md) and applicable module-level instructions before editing. These documents retain their existing guidance; explicit owner authorization governs requested commits/PRs.
- Preserve the Java module boundaries and existing native test fixtures. Use the reactor's `verify` or `install` phase when shaded Gremlin artifacts are required; use `-am` for a module subset.
- Keep Studio's public npm resolution and upstream build/release model intact. No private registry, deployment credentials, publishing, or CI activation is implicit in documentation or source work.
- Use disposable databases and native fixtures for runtime checks; preserve existing user data. Follow the private reporting guidance in `SECURITY.md`, recognizing its upstream scope.
- New files must retain appropriate SPDX attribution. Do not change `LICENSE`, `NOTICE`, `ATTRIBUTIONS.md`, provenance or security policy as a side effect of a standup.

<!-- tenpointdata-engineering-standards:v1 -->
## Engineering standards

- Keep every TypeScript and web-language code file at or below **600 physical lines**. Split by cohesive responsibility; do not minify, compress, or remove useful context to meet the limit. Fix generators when their checked-in output exceeds the limit.
- Keep code in other languages concise and well organized, with cohesive modules and clear responsibilities.
- Avoid TypeScript `any`, including assertions, generic defaults and declaration files. Reuse canonical domain types and schemas; accept `unknown` at untrusted boundaries and validate/narrow it. Keep strict type checking; do not bypass checks with `@ts-ignore`, `@ts-nocheck` or disabled lint rules.
- Start from the problem, constraints and invariants. Inspect existing libraries, shared types and related project capabilities before building. Extend sound implementations instead of creating parallel ones.
- Keep reusable mechanisms cohesive and independently usable. Keep product rules, UI and external integrations in configuration or thin adapters. Use explicit typed contracts, declarative configuration and composable primitives.
- Preserve the useful capability of shared mechanisms for other consumers. Document assumptions, limits, extension points and compatibility. Use the smallest sound abstraction supported by actual requirements; do not add speculative frameworks or forced cross-project coupling.
- Validate shared contracts, meaningful variations and the affected workflow. For substantial changes, explain what is reusable and what remains project-specific.

## Interaction design

- **Fewer clicks = better design.** Make frequent actions directly available, remove redundant navigation and nonconsequential confirmation steps, apply reversible filters immediately, use sensible defaults and preserve context. Evaluate the complete workflow by interactions required to reach the result.
- Retain confirmations for destructive or consequential actions. Preserve accessibility, clarity and user control.
- Prefer **tables over cards** when users scan, compare or act on multiple items. Choose another layout when it better serves the content, accessibility or task.

## Evidence and documentation

- Preserve existing repository-specific safety, data-access, ownership and workflow rules. Read instructions in the affected directories before changing code.
- Record the source revision, exact checks and results. Distinguish proposed, implemented, locally tested, merged, deployed and accepted behavior; never infer one from another.
- Keep setup, architecture and operational instructions near their owning code. Link the canonical issue for next work instead of maintaining contradictory task lists.
- For a new defect or unresolved design decision, provide evidence, impact, smallest next action and acceptance criteria. Identify hypotheses and untested boundaries explicitly; keep credentials and sensitive data out of issues and logs.
- Before publishing, run applicable source-standards, type, contract and workflow checks using the repository's documented commands. Existing debt remains visible and does not waive the rules for changed code.
<!-- /tenpointdata-engineering-standards:v1 -->
