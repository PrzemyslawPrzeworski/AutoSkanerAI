---
date: 2026-09-10
researcher: Claude (Opus 5)
git_commit: 225303f
branch: main
repository: AutoSkanerAI
topic: "Bringing CepikRiskAdjuster.apply under fail-soft handling without silently under-reporting risk"
tags: [research, backend, analysis, fail-soft, cepik]
status: complete
last_updated: 2026-09-10
last_updated_by: Claude (Opus 5)
---

# Research: the risk adjuster outside the fail-soft guard

## Research question

`AnalysisController.java:95` calls `cepikRiskAdjuster.apply` outside `degradeOnThrow`, while the two
enrichment calls immediately above it are guarded. What is the right guard, and — the part that is
not obvious — what is the right *degraded value*?

## Summary

Three findings, in order of how much they changed the answer.

1. **The degraded value is the whole problem.** `degradeOnThrow` degrades to a value; the obvious
   value here (the un-adjusted result) is the exact state `CepikRiskAdjuster`'s Javadoc names as the
   bug it was written to fix. Wrapping the call naively converts a loud 500 into a silent
   under-report of risk, which is worse.
2. **The degraded value must be built by the adjuster, not by the controller** — otherwise the
   verdict-label strings get a fourth copy and the controller starts owning registry semantics. A
   public static factory on `CepikRiskAdjuster`, passed as `degradeOnThrow`'s degraded supplier,
   keeps the guard uniform with its two neighbours and the vocabulary with its owner.
3. **No throw is reachable on today's code**, with one near-miss worth recording (below). So this is
   defence in depth of the same kind the repo already accepted for the market-price guard — and the
   test therefore has to inject the throw.

## Detailed findings

### The call site, and why it is the odd one out

`AnalysisController.buildResponse` runs three post-analysis steps in a row
(`AnalysisController.java:82-95`):

```java
var cepikResult      = degradeOnThrow("cepik", …, () -> CepikResult.withoutData(LOOKUP_FAILED, …));
var marketPriceContext = degradeOnThrow("market-price", …, () -> new MarketPriceContext(FETCH_FAILED, …));
result = cepikRiskAdjuster.apply(result, cepikResult);          // :95 — unguarded
```

`degradeOnThrow`'s Javadoc (`:126-148`) argues the cost in the first person — *"the user waited out
the whole LLM call to be told the server broke"* — and scopes itself deliberately: *"Scoped to the
enrichment calls on purpose. An LLM failure must still reach the client as the 502 that names its
cause."* The adjuster is neither an enrichment nor the LLM, so it fell between the two rules rather
than being excluded by either.

### Why this became worth fixing on 2026-09-10 and not earlier

`apply` returns its argument untouched for every status except `FOUND`
(`CepikRiskAdjuster.java:73-75`), and until `refactor-opportunities` fixed `MockCepikService` the
mock returned `LOOKUP_FAILED` unconditionally. So no end-to-end path — no git hook, no E2E spec —
executed a single line past that early return. 254 lines of the most consequential logic in the
application became reachable that day, on the profile every gate uses, and in production its inputs
are scraped from a government HTML page.

### Reachability: no throw today, one near-miss

Walked every statement `apply` reaches on a `FOUND` result:

| Candidate | Verdict |
|---|---|
| `describeDamage` dereferences `damage.date()`, `damage.insurer()`, `damage.categories()` | all three explicitly null-checked (`:142-148`) |
| `String.join(", ", damage.categories())` on a list containing null | renders the text `"null"`; `String.join` does not throw on a null element |
| `HistoriaPojazduParser.damagesFrom` producing a null list element | cannot — it appends `new DamageRecord(...)` per row (`:182-205`) |
| `capRisk` arithmetic | integer arithmetic over five bounded `int`s; no division by a variable |
| `rank` / `labelFor` exhaustive switches | a new `VerdictCode` breaks compilation, not the runtime — single Maven module |
| `claimsAccidentFree` / `forLog` | null-checked entry, regex over a non-null string |
| **`flags.addAll(result.riskFlags())` (`:129`) on a null list** | **NPE — the one candidate.** Not reachable through either `AiAnalysisService` implementation, but nothing states it as a contract |

That last row is the near-miss and it is asymmetric in an interesting way: the controller's own
`withoutFlag` helper (added yesterday, `AnalysisController.java:186`) treats `flags == null` as
representable and returns early, while `CepikRiskAdjuster:129` would NPE on it.
`AnalysisResponseParser.validateRequired` rejects a null `riskFlags` container (`:64`) and
`mapRiskFlags` returns a non-null list, so the real path is safe; `AiAnalysisServiceContractTest`
constrains only what `riskFlags` *contains*, never that it is non-null — it merely calls
`.stream()` on it (`:218`), so nullness is enforced by accident rather than by assertion.

This is the same standing `AnalysisSurvivesEnrichmentFailureTest` already documents for the guard it
protects: its two throw sites *"cannot be driven to throw through the service's public surface on
today's code… That is the point: the guard is there for the throw nobody predicted, so the throw has
to be injected."*

### The degraded value: three options

**A — degrade `cepikResult` to `LOOKUP_FAILED`.** Rejected. It reuses an existing UI path (the
"sprawdź ręcznie" card), but it throws away registry data the lookup *successfully* retrieved, and
`CepikResult`'s class comment forbids the shortcut: every list must go null on a non-FOUND status,
so the damage records the user is entitled to see would be deleted to report a failure that happened
downstream of them.

**B — keep the `FOUND` panel; say the findings were not scored. Chosen.** The response keeps every
registry fact, gains one prepended `HIGH` flag stating that the risk score does not include them,
and the verdict is floored to `NEEDS_MORE_INFO`. That mirrors what the damage branch itself does
(`:103`) and what the root `CLAUDE.md` demands: report unknown as unknown, never let missing
processing read as a clean result.

**Deliberately no risk cap on the degraded path.** Which finding fired is exactly what was lost, so
any cap would be a number nobody computed. The honest move is to leave the model's score and say in
the flag that it is incomplete — the flag is `HIGH`, so it is prepended and survives the frontend's
collapse-after-four.

**C — leave the 500.** Rejected on the same grounds `degradeOnThrow` was introduced: the analysis is
already in hand, and the 500 discards ~27 s of work along with the registry panel.

### Where the degraded value is built

Not in the controller. `applyFloor`/`moreSevere`/`labelFor` and the label strings live in
`CepikRiskAdjuster`, and `labelFor` already notes they are *"kept in step with
`MockAiAnalysisService` and `AnalysisPrompt`'s examples"* — a fourth copy in a caller is exactly the
drift that comment is guarding against. So: a public static factory on `CepikRiskAdjuster`, handed
to `degradeOnThrow` as its degraded supplier. The guard then reads identically to its two
neighbours, and the class that owns registry semantics owns both of its outcomes.

The alternative — catching inside `apply` — was considered and rejected. It makes `apply` total,
which is a nice property, but it cannot be tested through the controller without also mocking the
adjuster (at which point the inner guard is bypassed), and it leaves the controller's three
post-analysis steps looking inconsistent for no reader-visible reason.

### Frontend blast radius: none

`analysis-result.component.html` renders `flag.description` and maps `flag.severity` through
`severityLabel`/`severitySeverity`; nothing switches on `flag.code`. A new code needs no frontend
change, and `HIGH` puts it inside `visibleFlags()` (`slice(0, 4)`) without touching the ordering.

### Test blast radius

- `AnalysisSurvivesEnrichmentFailureTest` — the natural home for the end-to-end case; its Javadoc
  already frames itself around injected throws in post-analysis steps. Adding a third throw site
  means one `mock(CepikRiskAdjuster.class)` whose `apply` throws, matching the shape of the two
  existing enrichment cases exactly.
- `CepikRiskAdjusterTest` — the home for the degraded value's own properties (flag first, verdict
  floored, other flags kept, scores untouched, non-FOUND left alone).
- `RiskAnalysisControllerTest`, `ListingClaimsCannotMoveTheFloorTest`, `AnalysisControllerTest` — all
  pass the real adjuster and none injects a throw, so none should change.

Note for the test: the throw cannot be injected through a hostile `List<DamageRecord>` in an
end-to-end test, because Jackson serialises `cepikResult.damageRecords` on the way out and would
trip the same hostility *after* the controller returned — a 500 from the serialiser, testing nothing.
Mocking the adjuster is the injection that works.

## Code references

- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java:95` — the unguarded call
- `…/AnalysisController.java:126-148` — `degradeOnThrow` and its scope argument
- `…/AnalysisController.java:186` — `withoutFlag`, which treats a null flag list as representable
- `…/CepikRiskAdjuster.java:13-25` — the defect the class exists to prevent
- `…/CepikRiskAdjuster.java:122-135` — the fold; `:129` is the near-miss NPE
- `…/CepikRiskAdjuster.java:220-253` — `applyFloor`, `rank`, `moreSevere`, `labelFor`
- `…/CepikResult.java:6-19` — null vs empty, and why option A is not available
- `…/llm/AnalysisResponseParser.java:64` — the check that keeps `riskFlags` non-null in practice
- `backend/src/test/java/…/AnalysisSurvivesEnrichmentFailureTest.java:60-80` — the injected-throw doctrine

## Open questions

- Whether `AnalysisResult`'s list fields should be contractually non-null, asserted in
  `AiAnalysisServiceContractTest`. That is a port-contract change, not this one — recorded here so
  the near-miss is not rediscovered.
- Whether `degradeOnThrow` should log at `error` for this stage rather than `warn`. Risk going
  unscored on a registry-flagged car is not the same event class as a market range going missing,
  but adding a level parameter for one caller is not worth the shared signature; the user-visible
  flag is the signal that matters.
