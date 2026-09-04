<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Test rollout Phase 2 — Availability and failure paths

- **Plan**: `context/changes/testing-availability-failure-paths/plan.md`
- **Scope**: All 7 phases (full plan review)
- **Date**: 2026-09-03
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 5 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Success criteria verification

**Automated — re-run for this review:**

| Criterion | Result |
|---|---|
| Suite passes (1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1) | PASS — 224 tests, 0 failures, 0 errors, 13.7 s, BUILD SUCCESS |
| 7.2 no "not available in current session" for Context7/Exa | PASS — the sole remaining occurrence is the Playwright line, which the plan said keeps its 2026-08-27 date |
| 7.3 no occurrence of `79900` | PASS — 0 occurrences |

**Automated — mutation criteria (30 rows across phases 1–6):** each was applied and reverted at
implementation time and is stamped with its phase commit. They were **not** re-executed for this
review — 30 edit/revert cycles is not a review-cost operation. Two were re-derived analytically
instead: criterion 6.2's own Progress row already records that removing `\r?` is a **no-op** (an
independent re-measurement in this review confirms the token lists are byte-identical across both
fixtures), and criterion 2.3's guarding test is shown under F2 to validate a weaker rule than the
plan specified.

**Manual:** 20 of 21 rows carry observable evidence in the diff. Row **6.9** does not — see F3.

## Findings

### F1 — Degraded CEPiK result renders a dead "check manually" link

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java:84
- **Detail**: Phase 3's new guard degrades to `CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, extracted.vin(), null)` — `lookupUrl = null`. Every other caller of `withoutData` passes `LOOKUP_URL` (`HistoriaPojazduService.java:81`, `HistoriaPojazduParser.java:92`, `RealCepikEnrichmentService.java:132`, `MockCepikService.java:17`). The frontend non-null-asserts the field at `cepik-result.component.html:221,236,249,262`, so the `LOOKUP_FAILED` card renders `href="null"` — a dead link in the one branch whose copy tells the user to go check the registry by hand. This is a defect **introduced** by this change, not pre-existing. `AnalysisSurvivesEnrichmentFailureTest.java:160` asserts only `$.cepikResult.status`, which is why it passed.
- **Fix**: Promote `HistoriaPojazduService.LOOKUP_URL` from `private` to public (or lift it onto `CepikResult`, whose `withoutData` javadoc already says it is the canonical constructor for every non-FOUND status) and pass it at `AnalysisController.java:84`; extend the test to assert the field is non-null.
  - Strength: Restores the invariant every other call site already holds; one-line behavioural change with a test that would have caught it.
  - Tradeoff: Widens the visibility of a constant, or moves it — a small placement decision.
  - Confidence: HIGH — verified all five call sites and all four template usages directly.
  - Blind spot: The frontend cannot be run here (no Node), so the rendered `href="null"` is inferred from the template's non-null assertion rather than observed.
- **Decision**: FIXED — took the parenthetical and lifted the constant onto `CepikResult` as `public static final LOOKUP_URL`, since the literal was already copied five times and that duplication is *why* the one caller with no copy in reach passed `null`. The three private duplicates and `MockCepikService`'s inline literal now reference it. `AnalysisSurvivesEnrichmentFailureTest` asserts `$.cepikResult.lookupUrl`; verified to fail on the old code with `expected:<https://historiapojazdu.gov.pl> but was:<null>`.

### F2 — The retry cap makes the deadline-fit rule nearly unreachable

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java:345
- **Detail**: `retryWait` returns `min(requested, MAX_RETRY_WAIT)` at `:345`, and `dispositionOf(e, deadlineAt)` at `:318-320` then compares that **already-capped** value against the remaining budget. Since `MAX_RETRY_WAIT` is 6 s, the rule `wait > remaining ⇒ NEXT_MODEL` can only ever fire when fewer than 6 s remain. So the phase's centrepiece rule degenerates into "fewer than 6 seconds left", and `Retry-After: 3600` is indistinguishable from `Retry-After: 7`: with 60 s of budget left, a provider that explicitly asked for 60 s is retried after 6 s. The plan's contract said the cap "stays for the case where the requested wait *does* fit" — i.e. the fit test is judged on the **requested** wait, which is also what `retryWait`'s own javadoc at `:331-333` implies ("this method only reports what was asked"). The divergence window is `MAX_RETRY_WAIT < remaining < requested`. Criterion 2.3's guarding test, `retryAfterExceedingTheRemainingBudget_movesToTheNextModelWithoutWaiting`, uses a 3 s budget and its javadoc states the capped-value arithmetic outright ("6 s > 3 s, so NEXT_MODEL") — so it passes because 3 s < the cap, not because the requested 60 s was respected, and it cannot tell the two rules apart. Nothing is concealed; the weaker rule is simply the one that shipped and got validated.
- **Fix A ⭐ Recommended**: Test the fit against the requested wait, then cap: return the uncapped requested wait from `retryWait`, apply `MAX_RETRY_WAIT` at the sleep site only.
  - Strength: Makes the rule mean what the plan and both javadocs say, and lets a large `Retry-After` do the thing the 2026-08-26 incident argues for — walk to another model rather than re-hit a pool the provider said is saturated for a minute.
  - Tradeoff: Genuinely changes production retry behaviour: cases that today sleep 6 s and retry will move to the next model instead. Needs a test at the `MAX_RETRY_WAIT < remaining < requested` boundary that the current suite has no case for.
  - Confidence: HIGH — the mechanism is confirmed by direct read of `:318-320`, `:335-346` and the test's own javadoc at `:414-415`.
  - Blind spot: Which behaviour actually yields more successful analyses against real free-tier pools is unmeasured; the argument for A is the provider's explicit signal, not observed success rates.
- **Fix B**: Keep the behaviour, correct the documentation — state in `retryWait` and in the test that the fit test operates on the capped wait, so the rule reads as "skip when under `MAX_RETRY_WAIT` remains".
  - Strength: Zero production risk; the shipped behaviour is defensible (never sleeps more than 6 s, still spends one retry).
  - Tradeoff: Leaves the plan's stated rule unimplemented while marking it done, and keeps a rule whose threshold is an accident of the cap's value rather than a decision.
  - Confidence: MEDIUM — safe, but it resolves the contradiction by lowering the contract to the code.
  - Blind spot: Whether a 6 s wait against a requested 60 s meaningfully reproduces the original regression at production scale.
- **Decision**: Fixed via Fix A — `retryWait` now returns the provider's request unmodified and the cap moved to a new `atMostMaxWait` at the sleep site, so the fit test sees what was actually asked for. Added `retryAfterAboveTheCapButBelowTheBudget_stillMovesToTheNextModel` (`Retry-After: 20` against a 10 s budget), the one case where the two readings disagree. Verified: restoring the cap inside `retryWait` fails that test *and* takes the test class from 7.1 s to 14.3 s, because the old rule slept out the full 6 s cap before re-hitting the pool the provider had just said was busy for twenty. The magnitude of `MAX_RETRY_WAIT` stays unpinned on purpose — observing it requires really sleeping 6 s in a suite that runs in 17 — and `atMostMaxWait`'s javadoc says so rather than leaving the gap implicit.

### F3 — Manual criterion 6.9 was checked off but could not have been verified

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/testing-availability-failure-paths/plan.md:927
- **Detail**: Row 6.9 reads "A live production analysis still deserialises in the deployed frontend" and is marked `[x] — 5de1ce9`. But `git show origin/main:.../MarketPriceContext.java` carries neither `sampleQuality` nor `discardedCount`: all 8 commits of this change are unpushed, so Render and Cloudflare Pages are both still running pre-Phase-6 code. A live production analysis exercises neither the new fields nor the new template, so the criterion describes a check that was not possible at the time it was ticked. This is the same class of gap that Phase 6 caught in Phase 3 (a claimed rendering change that never landed) — one phase later, in the opposite direction.
- **Fix**: Revert 6.9 to `- [ ]` and note that it is blocked on `git push`; or push, verify against production, and re-tick it with evidence.
  - Strength: The Progress section is the change's only durable record of what was actually verified; a row that overstates it is worse than a pending row.
  - Tradeoff: None — but note `/10x-archive` treats a pending manual row as a soft warning only, so this does not block closing the change.
  - Confidence: HIGH — confirmed `origin/main` lacks both fields.
  - Blind spot: None significant.
- **Decision**: FIXED — 6.9 reverted to `- [ ]` and annotated as blocked on `git push`, naming why (production runs pre-Phase-6 code). `/10x-archive` will surface it as a manual-only soft warning, which is the honest state.

### F4 — The "no frontend change" guardrail was violated, and the change has no test

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline
- **Location**: frontend/src/app/features/analyzer/components/market-price-panel/market-price-panel.component.html:29
- **Detail**: "What We're NOT Doing" states "**Any frontend change**" is out of scope and assigns the `sampleSize < 3` collision to rollout Phase 3. Two frontend files changed: the panel template swapped `sampleSize < 3` for `sampleQuality === 'DISPERSED'` / `=== 'THIN'` branches plus a `discardedCount` block, and `analysis.models.ts` gained the matching types. There is **no `market-price-panel.component.spec.ts`** — the frontend has three spec files, none covering this component — and no Node/npm on this machine, so nothing about the new rendering has been executed. `SUFFICIENT` and `null` both fall through to no caveat, which is the correct outcome but also the silent-pass shape this change existed to eliminate. The deviation is disclosed rather than hidden (`test-plan.md:334-338` records it as a method lesson, "reviewed, not verified", and `:128-132` carries the missing spec into Phase 3), and the reasoning is sound: shipping a server field whose only consumer is unwired repeats exactly the Phase 3 failure found during Phase 6.
- **Fix A ⭐ Recommended**: Keep the change, and make the missing spec an explicit blocking item for rollout Phase 3 rather than one carried bullet among six.
  - Strength: Preserves a contract whose consumer is wired end to end; the alternative ships a server field nothing reads, which is the documented failure this change was correcting.
  - Tradeoff: Unverified template logic reaches production on the next push; a typo in an `@if` fails silently.
  - Confidence: HIGH — the guardrail text and the diff are both unambiguous.
  - Blind spot: Whether the template compiles at all is unverified here — `ng build` has never run against it on this machine.
- **Fix B**: Revert the two frontend files and let rollout Phase 3 wire the consumer.
  - Strength: Restores the stated scope exactly; the server change is additive so nothing breaks.
  - Tradeoff: Ships `sampleQuality` with no reader and leaves the live `sampleSize < 3` bug — the user-visible half of Risk #5 — in production until Phase 3 lands.
  - Confidence: MEDIUM — depends on how soon Phase 3 actually runs.
  - Blind spot: None significant.
- **Decision**: Fixed via Fix A — the two frontend files stay. `test-plan.md`'s carried-items list now marks the `market-price-panel.component` spec **BLOCKING for Phase 3**, states that it is the one carried item Phase 3 may not defer again, and records the conditional the deviation rests on: crossing the guardrail was justified by "a server field with no reader is the failure Phase 6 just caught", and that only holds if the reader is eventually verified. The required arms are named explicitly (`DISPERSED`, `THIN`, `SUFFICIENT`, null, plus `discardedCount`) so the item cannot be discharged by a token spec.

### F5 — A band-collapsed sample is still IQR-trimmed, contradicting "reported as found"

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceStatistics.java:86
- **Detail**: `kept = bandCollapsed ? sorted : banded;` is followed by an unconditional `if (kept.size() >= MIN_SAMPLE_FOR_IQR)` fence, so a collapsed sample of 8+ can still be trimmed. That contradicts the method's own comment at `:76-78` ("the honest move is to report it as found rather than to invent a tight range") and CLAUDE.md's "the sample is reported untrimmed". `qualityOf` still returns `DISPERSED`, so the user-visible label stays correct; what can silently narrow is the reported min/max. The branch appears unreachable in practice — a collapse implies a spread wide enough that the fence keeps everything — but that is a property of the data, not of the code, and no test pins it.
- **Fix**: Guard the fence with `if (!bandCollapsed && kept.size() >= MIN_SAMPLE_FOR_IQR)` and add a case asserting a collapsed 8+ sample keeps its extremes.
  - Strength: Makes the stated doctrine a property of the code instead of an accident of the sample.
  - Tradeoff: None — the branch is believed unreachable, so this is a guard against future drift rather than a live bug fix.
  - Confidence: HIGH — read directly at `:83-92`.
  - Blind spot: Have not constructed an input that provably reaches the branch, so "unreachable in practice" is an argument, not a proof.
- **Decision**: FIXED — guard added, and the blind spot above is closed: the branch *is* reachable. `aCollapsedBandIsReportedInFullRatherThanHandedToTheIqrFence` uses a bimodal sample `[1000, 1000, 1000, 10 000, 200 000, 400 000, 500 000, 5 000 000]` — band collapses to one price, and Tukey's hinges on the full sample put the upper fence at 1 116 750, so the unguarded code drops the 5 000 000 and reports max 500 000 under the DISPERSED label. Verified to fail without the guard. The existing ten-price collapse case could not catch this: its quartiles sit four orders of magnitude apart, so its fence drops nothing.

### F6 — test-plan.md records ≈341 s where the shipped test asserts 295 s

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/foundation/test-plan.md:149
- **Detail**: `test-plan.md:149` and `:346` both state the configured timeouts sum to "**≈341 s**". `RequestTimeoutBudgetTest.java:180` asserts `Duration.ofSeconds(295)`, with the per-stage arithmetic spelled out at `:152` — Phase 2's own removal of the retry clamp took the LLM stage from 156 s to 110 s. Phase 7's contract in the plan literally instructed writing "the ≈341 s worst case", so the backport was faithful to a plan that Phase 2 had already made obsolete: this is a flaw in the plan, not a deviation from it. The consequence is that the freshness-ledger document now contradicts the assertion in the exact place a future reader looks to decide whether a timeout bump matters.
- **Fix**: Replace both occurrences with 295 s, citing `RequestTimeoutBudgetTest:180` as the source of the figure.
  - Strength: Makes the document's own headline number agree with the test that enforces it.
  - Tradeoff: None.
  - Confidence: HIGH — both figures read directly.
  - Blind spot: None significant.
- **Decision**: FIXED — both occurrences now read 295 s and cite `RequestTimeoutBudgetTest.java:180` as the number's only source of truth. §8's entry also records *why* the figure was wrong (Phase 2 took the LLM stage 156 → 110 after the Phase 7 instruction had been drafted), so the next reader does not have to rediscover it.

### F7 — A 402 is reported to the operator as a rejected API key

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: backend/src/main/java/com/example/autoskaner_ai/analysis/llm/LlmCallException.java:17
- **Detail**: `rejectsEveryModel` (`OpenRouterAnalysisService.java:263`) returns true for 401, 402 and 403, all mapping to `Reason.REJECTED_CREDENTIALS` and the 502 headline "Usługa LLM odrzuciła dane dostępowe". An out-of-credits 402 therefore reads to an operator as a rejected key, when the action needed is to top up rather than rotate. The routing is exactly what the plan specified ("402 as FATAL alongside 401/403, surfacing as the rejected-credentials 502 string"), so only the documentation is stale: `LlmCallException.java:17`'s javadoc still says "(401/403)", as does CLAUDE.md's "**Fatal** (401/403, …)" line.
- **Fix**: Update the javadoc and CLAUDE.md to name 402 explicitly; optionally give 402 its own `Reason` so the operator-facing headline distinguishes credits from credentials.
  - Strength: The 502 body is the only diagnostic an operator gets; conflating two different remedies costs real debugging time.
  - Tradeoff: A new `Reason` adds a fifth headline string, slightly past the "three distinct messages" the phase set out to produce.
  - Confidence: HIGH — verified the classification and the javadoc.
  - Blind spot: None significant.
- **Decision**: FIXED, docs only — no new `Reason`, so the phase's three headline strings stand. `LlmCallException.Reason.REJECTED_CREDENTIALS` now says "401, 403, or **402**" and spells out that 402 shares the routing because it shares the property (every model refuses the same account) but not the remedy (top up, don't rotate), directing the operator to the logged status. CLAUDE.md's Fatal bullet carries the same correction.

### F8 — Two tests stub an enrichment return value production never produces

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: backend/src/test/java/com/example/autoskaner_ai/analysis/LlmFailureReachesTheClientTest.java:98
- **Detail**: `LlmFailureReachesTheClientTest:98,100` and `AnalysisSurvivesEnrichmentFailureTest:305` stub `enrich(any())` to return `null`. No production enrichment path returns null — every one yields a status-bearing record, which is the invariant the sibling assertions are about. The tests would keep passing if a service ever started returning null.
- **Fix**: Stub the degraded records (`CepikResult.withoutData(...)`, `MarketPriceContext(FETCH_FAILED, ...)`) instead of `null`.
- **Decision**: FIXED — both call sites now stub `MarketPriceContext(FETCH_FAILED, …, Instant.now(), null, null)` and `CepikResult.withoutData(LOOKUP_FAILED, null, CepikResult.LOOKUP_URL)`, each with a comment recording why: a stub returning a value no production path can return is a standing invitation for a later assertion to be satisfied by something impossible. Behaviour is unchanged (the LLM throws before enrichment runs in every one of these tests) — the point is that it stays unchanged for the right reason.

### F9 — New LLM fixtures carry a plausible-looking third-party VIN in a public repo

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/test/resources/fixtures/llm/hollow-all-leaves-null.json
- **Detail**: The six new fixtures propagate `"vin": "WBAAM31060GE12345"`, a BMW-shaped VIN inherited from `AnalysisPrompt.java`'s own illustrative example (present in `valid-full-response.json` since 2026-08-25, so pre-existing rather than introduced here) instead of the documented synthetic `NMTBZ3BE40R000000`. Nothing else identifying travels with it and no real listing was captured, so this is hygiene rather than exposure — but this repo is public and "synthetic by convention" is asserted nowhere for that value.
- **Fix**: Either normalise the fixtures and the prompt example onto `NMTBZ3BE40R000000`, or state in a fixtures README that `WBAAM31060GE12345` is the prompt's illustrative value.
- **Decision**: FIXED by documenting — new `backend/src/test/resources/fixtures/llm/README.md`. It records three things: these fixtures are **composed, not captured**, and why that is correct under the vocabulary rule in `../../market/README.md` (third-party payloads must be captured; shapes we own may be composed — and only a composed fixture can violate the schema in exactly one way); that `WBAAM31060GE12345` is `AnalysisPrompt`'s own few-shot example and **not captured from any listing**; and that `NMTBZ3BE40R000000` is the synthetic to prefer in anything new. Not renamed, because the fixtures' value in matching the prompt they were written against is real, and changing the prompt's example is a separate decision.

## Notes on what was checked and found clean

- **Plan adherence, 26 of 27 contract items: MATCH.** The one drift is F2. No contract clause was MISSING.
- **All other "What We're NOT Doing" guardrails: RESPECTED.** Verified individually — `capRisk`'s short-circuit is left open and, importantly, *not pinned* by a test (`capsNeverRaiseAScoreOrSoftenAVerdict` walks the `risk <= cap` path but asserts nothing about `overall`); `verdict.label` still unvalidated; the phrase list unchanged; `AnalysisPrompt.java` does not appear in the diff at all, so no fence or delimiter was added; no `spring.mvc.async.request-timeout`; `ListingFetchService`'s only change is extracting a DNS timeout constant, leaving the 100-char minimum and the absent maximum exactly as the plan required.
- **Unplanned changes are consequences, not scope**, except the frontend pair (F4): the three config classes gained `public static final Duration` constants because criterion 2.4 required the test to read configured values rather than restate literals; `AnalysisControllerTest` and `MockMarketPriceEnrichmentService` gained two constructor arguments; `.gitattributes` and `MarketPriceContextSerialisationTest` are demanded by criteria 6.1 and 6.7 respectively.
- **Security: clean.** No cookie value or XSRF token value reaches a log line in either changed registry class — only cookie names. No key-shaped strings anywhere under `backend/src/test` or `src/main/resources`. The market capture carries no emails, phone numbers or VIN-like tokens.
- **API contract: unchanged.** `ErrorResponse` still `{int status, String error, List<String> messages, Instant timestamp}`; the three new 502 headlines travel inside it; `MarketPriceContext`'s two fields are appended, so the deployed frontend keeps deserialising.
- **Concurrency: the cookie leak is genuinely closed.** `HistoriaPojazduSession` clones the injected builder in its constructor and every `defaultHeaders` mutation lands on the clone; no remaining call mutates the shared bean.
- **Pattern consistency: clean.** Zero `@WebMvcTest`, zero Jackson 2 imports, zero JUnit 4; all five new tests use `standaloneSetup` + `setControllerAdvice`; `MarketPriceSampleQuality` sits in `market` beside `MarketPriceStatus`, matching `CepikStatus`'s placement.
- **The market fixture pair's documented claims are literally true**, independently re-measured: parent 16 051 bytes with **zero** CR; derived 16 657 bytes with 606 CR; 16 051 + 606 = 16 657 exactly; `sed 's/$/\r/' … | cmp -` reports identical. The README's admission that removing `\r?` is a no-op also re-confirmed — token lists byte-identical across both fixtures under both patterns, while narrowing the class to `[\d \n]` drops the CRLF fixture to zero matches, which is the counter-experiment that shows why.
