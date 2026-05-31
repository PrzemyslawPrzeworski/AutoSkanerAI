# Linear Issues — AutoSkanerAI

Workspace: https://linear.app/autoskaner-ai  
Team: `Autoskaner-ai` (id `52b5bce0-892f-4f6b-8875-c70e42e86185`)  
Project: [AutoSkanerAI MVP](https://linear.app/autoskaner-ai/project/autoskanerai-mvp-d7e55584a147) (id `73c2ee61-78dd-44c0-b5d8-a7ddaef2ea5d`)  
Board: https://linear.app/autoskaner-ai/team/AUT/backlog  
Last updated: 2026-05-25

Mirrored from `context/foundation/tasks-github.md`. GitHub is the source of truth; Linear is for day-to-day sprint tracking.

---

## Setup

### MCP Server

Linear is connected via MCP server:

```bash
claude mcp add --transport http linear-server https://mcp.linear.app/mcp
```

Authenticated as `przemyslaw.przeworski@gmail.com`. Reconnect by running `/mcp` in Claude Code if the session expires.

---

## Labels (7)

Created 2026-05-25 for team `Autoskaner-ai`:

| Label | Color | id | Meaning |
|---|---|---|---|
| `foundation` | `#0075ca` | `16fde1c6-4eb5-4c2a-bc4b-99d623ea8498` | F-NN items — cross-cutting prerequisites |
| `slice` | `#e4e669` | `2cf78ba1-f3cf-4038-b86e-028650e8a25e` | S-NN items — user-facing vertical features |
| `stream:A` | `#d93f0b` | `5842d9f7-6aa1-46f0-ae67-3a6bfdffefb3` | Stream A: LLM Analysis Core |
| `stream:B` | `#0e8a16` | `07dac220-ce88-4fcd-ace6-2c276c412a74` | Stream B: Account & Persistence |
| `status:ready` | `#1d76db` | `959c8803-9539-4230-8f6e-e1c641f3d31b` | Prerequisites met — safe to plan now |
| `status:proposed` | `#e6e6e6` | `81f65adf-1c01-4e20-9d9f-12fadbe52503` | Has unmet prerequisites |
| `roadmap-question` | `#d876e3` | `cae9231d-aecb-4a1c-a5f7-ad3c34b06eb6` | Open question needing a decision before planning |

---

## Issues (9)

| Linear | GitHub | ID | Title | Labels | Status | Prerequisites |
|--------|--------|----|-------|--------|--------|---------------|
| [AUT-5](https://linear.app/autoskaner-ai/issue/AUT-5) | [#1](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/1) | F-01 | Wire LlmAnalysisService to real Claude/OpenAI API | foundation, stream:A, status:ready | Backlog | — |
| [AUT-6](https://linear.app/autoskaner-ai/issue/AUT-6) | [#2](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/2) | F-02 | Add PostgreSQL + Spring Data JPA + Flyway to backend | foundation, stream:B, status:ready | Backlog | — |
| [AUT-7](https://linear.app/autoskaner-ai/issue/AUT-7) | [#3](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/3) | F-03 | Wire Spring Security + JWT; Angular login/register + guards | foundation, stream:B, status:proposed | Backlog | F-02 |
| [AUT-8](https://linear.app/autoskaner-ai/issue/AUT-8) | [#4](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/4) | S-01 | Full analysis flow: URL + text paste → AI output on screen | slice, stream:A, status:proposed | Backlog | F-01 |
| [AUT-9](https://linear.app/autoskaner-ai/issue/AUT-9) | [#5](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/5) | S-02 | Manual field entry form → same AI analysis output | slice, stream:A, status:proposed | Backlog | S-01 |
| [AUT-10](https://linear.app/autoskaner-ai/issue/AUT-10) | [#6](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/6) | S-03 | Save / view list / delete saved analyses | slice, stream:B, status:proposed | Backlog | S-01, F-02, F-03 |
| [AUT-11](https://linear.app/autoskaner-ai/issue/AUT-11) | [#7](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/7) | Q-01 | Which LLM provider should LlmAnalysisService call by default? | roadmap-question, stream:A | Todo | Blocks F-01 |
| [AUT-12](https://linear.app/autoskaner-ai/issue/AUT-12) | [#8](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/8) | Q-02 | MVP auth: email+password only, or include OAuth (Google/GitHub)? | roadmap-question, stream:B | Todo | Blocks F-03 |
| [AUT-13](https://linear.app/autoskaner-ai/issue/AUT-13) | [#9](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/9) | Q-03 | URL fetch failure UX: manual switch to text paste, or automatic redirect? | roadmap-question, stream:A | Todo | Blocks S-01 |

Note: AUT-1 through AUT-4 are Linear onboarding issues — leave untouched.

---

## Status mapping

| GitHub label | Linear status | Meaning |
|---|---|---|
| `status:ready` | `Backlog` | Prerequisites met — plannable now |
| `status:proposed` | `Backlog` | Prerequisites not yet met |
| `roadmap-question` (Q-NN) | `Todo` | Needs a decision, not planning |

When a prerequisite issue is closed, manually update the dependent issue from `status:proposed` → `status:ready` in both GitHub and Linear.

---

## Workflow

1. Answer open questions (AUT-11, AUT-12, AUT-13) — close each when decided
2. Start with `status:ready` items — AUT-5 (F-01) and AUT-6 (F-02) are plannable in parallel
3. Run `/10x-plan <change-id>` to create a detailed implementation plan
4. After merging a change, run `/10x-archive` — flips the roadmap item to `done`
5. Relabel newly unblocked issues to `status:ready` in both GitHub and Linear

---

## Keeping in sync

Linear does not auto-sync with GitHub. When a GitHub issue is closed:
1. Close the corresponding Linear issue manually (or via MCP: `mcp__linear-server__save_issue` with `state: Done`)
2. Update `status:proposed` → `status:ready` on any newly unblocked issues in both trackers
3. Run `/10x-archive` to update `roadmap.md`
