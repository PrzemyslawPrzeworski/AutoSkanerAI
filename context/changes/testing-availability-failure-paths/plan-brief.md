# Test rollout Phase 2 — Availability and failure paths — Plan Brief

> Full plan: `context/changes/testing-availability-failure-paths/plan.md`
> Research: `context/changes/testing-availability-failure-paths/research.md`

## What & Why

Phase 2 of the test rollout: prove every provider and fetch failure ends in an honest,
distinguishable outcome inside the time budget, and that a thin price sample labels itself —
covering Risk #1 (High/High), Risk #5 (Medium/High) and Risk #6 (Medium/Medium).

Research found that **nine of the behaviours this phase must prove are currently broken**. Tests
written against today's behaviour would pin the failure mode, which `test-plan.md` §1 rule 4 forbids
and Phase 1's own lesson names outright: "a rollout phase that only writes tests will happily freeze
the bug." So this phase closes the defects and then proves them closed.

## Starting Point

163 backend tests pass in ~15.6 s. What that green suite does not notice: a provider 200 with every
leaf field null parses fine (and a missing score coerces to a *perfect* 0); a rejected key, an empty
`choices` array and an exhausted fallback chain produce byte-identical 502 bodies with zero
controller-boundary tests between them; the retry fires with a zero wait at the deadline edge —
exactly the immediate same-model retry that turned single 429s into production 502s on 2026-08-26;
the 30 s NFR is enforced by nothing while configured socket timeouts sum to ≈341 s; an uncaught
enrichment throw discards a finished ~16 s analysis; dispersion is computed then dropped before it
reaches the response; and one shared mutable `RestClient.Builder` leaks registry cookies from each
lookup into the next.

## Desired End State

Every availability and honesty failure has a test that fails when its protection is removed. A
hollow provider response becomes a distinct 502 naming a schema problem; three different failure
causes read as three different Polish messages; a `Retry-After` we cannot honour walks the fallback
chain instead of retrying into the same saturation; an enrichment throw can no longer take a finished
analysis with it; the registry session cannot leak a cookie into the next lookup; a missing accident
declaration always produces `NO_ACCIDENT_DECLARATION`; and `MarketPriceContext` carries a
server-side sample-quality judgement, grounded in a captured Otomoto payload, ready for Phase 3 to
render.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Posture toward the nine defects | Fix, contract changes last | §1 rule 4 forbids pinning; sequencing the response-shape change late keeps the frontend-visible delta in one place Phase 3 inherits | Plan |
| 30 s NFR | Assert and bound, don't re-architect | Makes the budget visible and stops the worst symptom without pulling impl-review F10's async work forward | Plan |
| Thin/dispersed judgement | Backend decides, Phase 3 renders | The only option this environment can prove — no Node exists here — and it removes a threshold duplicated in two languages | Plan |
| Hollow 200 | Require a minimal spine | Four scores + `verdict.code` + make/model must be present; the oracle is the locked output schema, not the parser | Plan |
| 502 distinguishability | Three distinct Polish `error` strings | Assertable at the boundary today and legible without a frontend change, inside the locked four-field envelope | Plan |
| `Retry-After` vs budget | Wait that won't fit ⇒ next model | Matches the two-axis design and kills the zero-wait retry as a side effect | Plan |
| Misrouted statuses | Correct both — 408 transient, 402 fatal | Both contradict OpenRouter's published semantics; 402 currently walks the whole chain on an error guaranteed to repeat | Research |
| Otomoto markdown | Capture from production | The regex is where the real `\n` bug lived, and inline text blocks are what masked it | Plan |
| Risk #6 invariants | `accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` only | Cheapest deterministic proxy, and the only one that holds when CEPiK returns `MISSING_INPUTS` — the normal production outcome | Plan |
| Session fix depth | Per-lookup `clone()`, keep the seam | `clone()` copies the request factory, so `MockRestServiceServer.bindTo` still stubs it — verified with `javap` against `spring-web` 7.0.7 | Research |

## Scope

**In scope:** minimal-spine parse validation; the four provider-quirk routes that currently become
generic 500s; three distinct 502 causes; the first controller-boundary LLM failure tests; 408/402
correction; `Retry-After` vs remaining-deadline rule and HTTP-date parsing; the never-executed
deadline-skip branch; a timeout-budget arithmetic test against `prd.md:98`; guarding `buildResponse`
so an enrichment throw cannot discard an analysis; per-lookup builder clone plus dedicated cookie and
XSRF assertions; `NO_ACCIDENT_DECLARATION` enforcement plus the repo's first adversarial tests; a
captured Otomoto fixture and a sample-quality signal on `MarketPriceContext`; five corrections
backported to `test-plan.md`.

**Out of scope:** `capRisk`'s short-circuit (so `risk: 3, overall: 97` still survives a szkoda
istotna); validating `verdict.label` against `verdict.code`; bounding the Jina-fetched body;
negation-awareness in the accident-claim phrases; enforcing the 30 s NFR by construction; any
frontend change (no Node on this machine); a prompt-injection eval; changing `AnalysisPrompt`;
rewriting `extractCookies` into a real cookie jar. Each of the first four is carried into Phase 3
with its consequence stated — and deliberately gets **no** test, since a test would pin it.

## Architecture / Approach

Six code phases then a documentation phase. Phases 1–2 close the LLM edge (what the caller receives,
then how long it takes); Phase 3 makes the analysis survive its own enrichment; Phase 4 closes the
carried-forward session gaps and a production cookie race; Phase 5 adds Risk #6's one deterministic
invariant; Phase 6 is isolated at the end because it is the only response-shape change; Phase 7
backports. Ordering is forced in one place: Phase 2's retry rule changes POST counts that Phase 1's
tests assert, so Phase 1 must land first.

**Every phase criterion is a mutation, not a green run** — Phase 1's method. Break the thing the test
exists to catch and confirm the test fails.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. LLM failure paths | Hollow 200 rejected; four 500-routes become honest 502s; three distinct causes; first controller-boundary failure tests | A stricter spine raises the live 502 rate on free-tier models that omit a near-optional field |
| 2. Retry, fallback, deadline | 408/402 corrected; unaffordable `Retry-After` skips the model; deadline-skip branch reached; budget arithmetic asserted | Behaviour change to shipped resilience code that only production traffic fully exercises |
| 3. Analysis survives enrichment | An enrichment throw degrades that enrichment, not the whole analysis | Over-broad guard could swallow a genuine LLM failure into a 200 |
| 4. Registry session | Per-lookup builder; cookie header and XSRF asserted; false Javadoc corrected | If `clone()` did not carry the bound factory the seam breaks — mitigated, verified with `javap` |
| 5. Listing-text gaming | `NO_ACCIDENT_DECLARATION` enforced; the repo's first adversarial tests; last tautology removed | Enforcement must be idempotent or a compliant model gets a duplicate flag |
| 6. Market price | Captured Otomoto fixture; sample-quality signal; exactly-3 hole closed | Needs a production round-trip to capture; additive contract change the frontend must keep deserialising |
| 7. Backport | §4, §2 Risk #5, §6.2, §6.4, §6.7, §3, §8 made true | Low — documentation only |

**Prerequisites:** `JAVA_HOME="D:/Software/Java/jdk-26.0.1"` (the real blocker in Phase 1); access to
the live production backend for Phase 6's capture, since `r.jina.ai` is proxy-blocked here.
**Estimated effort:** ~4–6 sessions across 7 phases; Phase 1 and Phase 6 are the two largest.

## Open Risks & Assumptions

- **Phase 6 depends on an external capture.** If a production round-trip cannot produce usable
  Otomoto markdown, the regex stays tested against composed input and that gap must be recorded in
  §6.7 rather than quietly skipped.
- **No frontend verification is possible in this environment.** Phase 6's additive change is asserted
  backend-side and confirmed against the deployed app by hand only.
- **The minimal spine is a judgement call about live models.** If free-tier slugs turn out to omit
  `extracted.model` routinely, the spine narrows — the test then records the narrowing, not a silent
  loosening.
- **`capRisk`'s short-circuit stays open by decision.** A car with a registered szkoda istotna can
  still display `overall: 97`. This is the phase's largest knowingly-unclosed defect.
- **The ≈341 s worst case stays reachable.** This phase makes it asserted and visible, not impossible.

## Success Criteria (Summary)

- A user whose analysis fails sees *which* failure it was — not one generic message for three
  unrelated causes, and never a success shape carrying nothing.
- A saturated or retired provider never produces a hang or an immediate futile retry, and a completed
  analysis is never thrown away by a failing price lookup.
- A price sample too thin or too dispersed to be a market range says so in the response, decided once
  on the server.
