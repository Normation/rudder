# CLAUDE.md

This file exists only so that Claude Code loads the pointers below into context
automatically. **It holds no conventions of its own** — all guidance for this repository is
vendor-neutral Markdown, readable by any contributor or agent.

## Read first, always

- **[`.claude/skills/rudder-principles/SKILL.md`](.claude/skills/rudder-principles/SKILL.md)**
  — the engineering principles that apply to **every** language and **every** task here.
  Not repeated in the language skills. Two of them shape your *output* as much as your
  code: state whether a change lands on a **hot path** when you plan it, and name a bug's
  **root cause** even when only the symptom gets patched.
- **[`AGENTS.md`](AGENTS.md)** — the agent guide: responsible-use policy (AI use **must**
  be disclosed in commit trailers; an agent does not push, open PRs, or touch CI), the
  per-language skill routers, and the other sources of truth.

## Then, per language

| Working on | Read |
|---|---|
| Scala (`webapp/sources`) | [`.claude/skills/rudder-scala/SKILL.md`](.claude/skills/rudder-scala/SKILL.md) |
| Elm / JS (`rudder-web/src/main/elm`, Lift templates) | [`.claude/skills/rudder-frontend/SKILL.md`](.claude/skills/rudder-frontend/SKILL.md) |
| Rust (`policies/`, `relay/sources/`) | [`.claude/skills/rudder-rust/SKILL.md`](.claude/skills/rudder-rust/SKILL.md) |

Each router states its always-apply golden rules and indexes per-topic files named
`NNN-topic.md`. **ADRs** in [`adr/`](adr/) are authoritative over any summary in a skill.
