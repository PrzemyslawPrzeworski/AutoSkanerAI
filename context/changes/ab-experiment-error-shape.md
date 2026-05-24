# A/B Experiment — API Error Shape Rule

**Date:** 2026-05-24  
**FR used:** FR-007 (risk analysis endpoint)  
**Question:** Does a rule in CLAUDE.md produce a consistent API error shape?

## Results

| Session | Phase | Time | Questions | Error shape | Package | Handles 500? | Matches spec? |
|---------|-------|------|-----------|-------------|---------|--------------|---------------|
| 1 | no rule | 13m 0s | 0 | `{status, error, messages[], timestamp}` | `analysis/dto` | ✅ | — |
| 2 | no rule | 5m 43s | 0 | `{message, errors:[{field,message}]}` | nested in handler | ❌ | — |
| 3 | no rule | 6m 7s | 0 | RFC 7807 ProblemDetail | `error` | ❌ | — |
| 4 | rule in AGENTS.md | 5m 8s | 0 | RFC 7807 ProblemDetail | `error` | ❌ | ❌ |
| 5 | rule in CLAUDE.md | 6m 39s | 0 | `{status, error, messages[], timestamp}` | `common` | ✅ | ✅ |
| 6 | rule in CLAUDE.md | 3m 26s | 0 | `{status, error, messages[], timestamp}` | `common` | ✅ | ✅ |

## Conclusions

1. **Rule needed** — 3 sessions without a rule produced 3 different shapes. No consistency.
2. **AGENTS.md is not read by Claude Code** — session 4 with the rule in AGENTS.md produced the same ProblemDetail shape as sessions without any rule.
3. **CLAUDE.md works** — sessions 5 and 6 matched the spec exactly, correct package, all three exception handlers.
4. **Time improved** — Phase 2 (CLAUDE.md) average: 5m 2s vs Phase 1 average: 8m 18s.

## Rule location

The rule lives in `CLAUDE.md` under `## API error shape`. AGENTS.md was deleted — it has no effect in Claude Code.
