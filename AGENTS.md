# AGENTS.md

Instructions for any coding agent working in this repository (Codex, Gemini CLI, Claude, Antigravity and others).

- Start at [HANDOFF.md](HANDOFF.md): what SPORK is, where its pieces live, the current state, how to build and test, the
  open work, and the known gotchas.
- The coding rules for this repository (licence allow-list, code style, test conventions, test server ports, Maven
  traps) are in [CLAUDE.md](CLAUDE.md). They apply to every agent, not only Claude.
- Several sessions share this checkout: stage by explicit path, never `git add -A` or `git commit -a`, and do not edit
  files another session owns (see HANDOFF.md, "Known issues and gotchas").

## Handoff policy: keep every project handoff-ready

Any repository you work in must stay in a state where another agent (Claude, Codex, Gemini, Antigravity, or a human) can
pick up the work cold, with no access to this conversation. Two things make that true, and you maintain both:

1. **`HANDOFF.md` at the repository root.** The single entry point for whoever comes next. It holds: what the project is
   and where its pieces live (code, data, services, deploy targets); the current state, under a dated stamp; how to set
   up, run, test and deploy; the open work, each item linked to its GitHub issue; known issues and gotchas; recent
   decisions and why; and this handoff policy itself, in full, so the instruction survives the handoff.
2. **GitHub issues for every open piece of work.** Anything unfinished, deferred, newly discovered, or decided against
   for now gets an issue with enough context to act on without this conversation: what, why, where in the code, and
   what done looks like. Close issues when the work lands and reference them from the commit or PR. The open-work list
   in `HANDOFF.md` points at issues; it never carries work that has no issue.

**When to update:** at the end of any session or turn that changed code, state, configuration, or plans, before the
final commit or push. Refresh the current-state stamp, add or close issues, and record decisions made. If a repository
has no `HANDOFF.md`, create it and the issues as your first act there. If it has no GitHub remote, say so in
`HANDOFF.md` and keep an `## Open work` list there until one exists.

**Propagation:** every `HANDOFF.md` you create or update contains this policy, so an agent that reads only the
repository learns to keep maintaining it. Also mirror it in the repository's `AGENTS.md` (read by Codex, Gemini CLI and
others) and have the repository's `CLAUDE.md` import that file with `@AGENTS.md`.

**This repository:** GitHub issues are disabled on `tenpointdata/SPORK`, so the `## Open work` list in HANDOFF.md stands
in for issues until the owner enables them; then file each item as an issue and link it from that list.
