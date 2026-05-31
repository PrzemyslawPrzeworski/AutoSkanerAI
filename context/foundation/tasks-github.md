# GitHub Issues — AutoSkanerAI

Repo: https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues  
Project board: https://github.com/users/PrzemyslawPrzeworski/projects/1  
Last updated: 2026-05-25

---

## Labels

| Label | Color | Meaning |
|---|---|---|
| `foundation` | `#0075ca` | F-NN items — cross-cutting prerequisites |
| `slice` | `#e4e669` | S-NN items — user-facing vertical features |
| `stream:A` | `#d93f0b` | Stream A: LLM Analysis Core |
| `stream:B` | `#0e8a16` | Stream B: Account & Persistence |
| `status:ready` | `#1d76db` | Prerequisites met — safe to run `/10x-plan` |
| `status:proposed` | `#e6e6e6` | Has unmet prerequisites |
| `roadmap-question` | `#d876e3` | Open question needing a decision before planning |

---

## Issues

| # | ID | Title | Type | Stream | Status | Prerequisites |
|---|----|-------|------|--------|--------|---------------|
| [#1](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/1) | F-01 | Wire LlmAnalysisService to real Claude/OpenAI API | `foundation` | `stream:A` | `status:ready` | — |
| [#2](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/2) | F-02 | Add PostgreSQL + Spring Data JPA + Flyway to backend | `foundation` | `stream:B` | `status:ready` | — |
| [#3](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/3) | F-03 | Wire Spring Security + JWT; Angular login/register + guards | `foundation` | `stream:B` | `status:proposed` | #2 F-02 |
| [#4](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/4) | S-01 | Full analysis flow: URL + text paste → AI output on screen | `slice` | `stream:A` | `status:proposed` | #1 F-01 |
| [#5](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/5) | S-02 | Manual field entry form → same AI analysis output | `slice` | `stream:A` | `status:proposed` | #4 S-01 |
| [#6](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/6) | S-03 | Save / view list / delete saved analyses | `slice` | `stream:B` | `status:proposed` | #4 S-01, #2 F-02, #3 F-03 |
| [#7](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/7) | Q-01 | Which LLM provider should LlmAnalysisService call by default? | `roadmap-question` | `stream:A` | — | Blocks #1 F-01 |
| [#8](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/8) | Q-02 | MVP auth: email+password only, or include OAuth (Google/GitHub)? | `roadmap-question` | `stream:B` | — | Blocks #3 F-03 |
| [#9](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/issues/9) | Q-03 | URL fetch failure UX: manual switch to text paste, or automatic redirect? | `roadmap-question` | `stream:A` | — | Blocks #4 S-01 |

---

## Status mapping

| Label | Meaning |
|---|---|
| `status:ready` | All prerequisites met — run `/10x-plan <change-id>` now |
| `status:proposed` | Prerequisites not yet met — see Prerequisites column |

When a prerequisite issue is closed (change archived via `/10x-archive`), manually relabel the dependent issue from `status:proposed` → `status:ready`.

---

## Workflow

1. Answer open questions (#7, #8, #9) — close each Q-issue when decided
2. Start with `status:ready` items — currently #1 (F-01) and #2 (F-02) are plannable in parallel
3. Run `/10x-plan <change-id>` to create a detailed implementation plan for a chosen issue
4. After merging a change, run `/10x-archive` — it flips the roadmap item to `done` and updates `roadmap.md`
5. Relabel any newly unblocked issues to `status:ready`
