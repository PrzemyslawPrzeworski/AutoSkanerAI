---
date: 2026-09-03T17:04:31+02:00
researcher: Przemyslaw Przeworski
git_commit: 915f84e96fec332f3666e61e9df68bed9ef2dc3a
branch: main
repository: AutoSkanerAI
topic: "Test rollout Phase 2 — availability and failure paths (Risks #1, #5, #6)"
tags: [research, codebase, testing, openrouter, resilience, market-price, prompt-injection, cepik-session, nfr]
status: complete
last_updated: 2026-09-03
last_updated_by: Przemyslaw Przeworski
---

# Research: Test rollout Phase 2 — availability and failure paths

**Date**: 2026-09-03T17:04:31+02:00
**Researcher**: Przemyslaw Przeworski
**Git Commit**: `915f84e96fec332f3666e61e9df68bed9ef2dc3a`
**Branch**: `main`
**Repository**: AutoSkanerAI

## Research Question

Ground rollout Phase 2 of `context/foundation/test-plan.md` — "Availability and failure paths".
Goal as chartered in `change.md`: *prove every provider and fetch failure ends in an honest,
distinguishable outcome inside the time budget, and that a thin price sample labels itself.*

Three risks are in scope:

- **Risk #1** (High/High) — the user waits out the full analysis and gets nothing back: the
  provider pool is saturated or a slug was retired, or the request runs past the 30 s budget.
- **Risk #5** (Medium/High) — a price sample too thin or too dispersed to mean anything is
  presented as a market range the buyer trusts.
- **Risk #6** (Medium/Medium) — listing text written to game the analyser produces a
  reassuring verdict.

Plus two gaps carried forward from Phase 1 (`HistoriaPojazduSession`'s cookie merge/dedupe and
its XSRF extraction) and one documentation correction (§4's stale "Stack grounding tools" note).

The research question behind all of it: **for each failure mode, what does the code actually do
today, what is the honest oracle for what it should do, and where is the seam that lets a test
assert it without asserting an implementation detail?**

## Summary

Phase 2 is not a test-only phase. Of the fourteen distinct behaviours the charter asks us to
prove, **nine are currently defects** — the code does not do the honest thing, so a test written
against current behaviour would pin the failure mode rather than catch it. Per `test-plan.md` §1
rule 4, "a test that tolerates the failure mode it exists to catch is decoration." Phase 1 hit the
same fork and resolved it by fixing; the same call is now due, and it is the single largest open
question below.

The load-bearing findings, ordered by consequence:

1. **A 200 OK can carry a hollow analysis.** `AnalysisResponseParser.validateRequired` null-checks
   six *containers* and nothing inside them; all sixteen leaf fields may be null and the parse
   succeeds. So the charter's exact challenge — "a 200 from the provider means we have an
   analysis" — is not merely a mindset to test against, it is a live route to a success-shaped
   response with empty content. The only live test at that boundary asserts non-nullity of the
   containers, so it cannot see it.

2. **There is no enforced deadline anywhere in the request.** The PRD's 30 s NFR
   (`prd.md:98`) has no code enforcing it: no `spring.mvc.async.request-timeout`, no filter, no
   interceptor, and `AnalysisController.analyze` is plain blocking. The configured socket timeouts
   sum to **≈341 s worst case**, and the LLM step alone can reach ~156 s. `deadline-seconds=70`
   is itself already 2.3× the NFR. The 30 s budget is presently a claim in a document and a
   sentence in the UI (`analyzer.component.html:31`), enforced by nothing.

3. **The retry can fire with a zero wait.** `retryWait` clamps to the remaining deadline and
   `sleepQuietly` returns `true` for a zero or negative wait — so at the edge of the deadline the
   service performs exactly the immediate same-model retry that turned single 429s into
   production 502s on 2026-08-26. The one timing test asserts `elapsedMs >= 900`, which passes
   whether or not this hole exists.

4. **Three distinct failures produce byte-identical 502 bodies.** A rejected API key, an
   empty `choices` array, and a fully exhausted fallback chain all surface as
   `{"status":502,"error":"Błąd usługi LLM",…}`, and the frontend maps every 502 to one string.
   "Distinguishable outcome" — the charter's word — is currently false at the only boundary the
   user can observe.

5. **Four parser routes turn a provider quirk into a 500 "Błąd serwera".** A null `message`,
   a null `content`, a non-String `content`, and an unknown enum value all escape as something
   other than `IllegalArgumentException`, so the `LlmResponseSchemaException` mapping never runs
   and the user gets the generic server error instead of the honest "bad LLM response" one.

6. **The thin-sample label has an off-by-one at exactly 3.** `MIN_SAMPLE_TO_KEEP = 3` means a
   3-price sample is reported *untrimmed*; the UI's caveat fires on `sampleSize < 3`. A sample of
   exactly three — the most contaminated case the pipeline can emit — renders a bare confident
   range with no caveat. Worse, the caveat sits inside `@if (expanded())` while the header shows
   the range, so the default collapsed view never shows it at all.

7. **Dispersion is measured, then discarded.** `discardedCount` is computed and logged and then
   dropped at the mapping; `MarketPriceContext` has no dispersion field. So the charter's "too
   dispersed to mean anything" half of Risk #5 has **nothing to assert against** — the signal
   exists inside the service and never reaches the response.

8. **In the common production case, 100% of scores and verdict are model-produced.**
   `CepikRiskAdjuster` is the only deterministic writer, but it is gated on `FOUND` — and
   `MISSING_INPUTS` is the *normal* outcome for a URL-only Otomoto analysis (the VIN is
   login-gated). The "deterministic floor that registry facts set" that Risk #6 asks us to prove
   is unmoveable is, most of the time, absent. Additionally `capRisk` short-circuits when
   `risk <= cap`, so `risk: 3, overall: 97` survives a registered szkoda istotna — and
   `capsNeverRaiseAScoreOrSoftenAVerdict` asserts that hole rather than catching it.

9. **The prompt has no untrusted-data boundary.** `AnalysisPrompt` ends with
   `"Oceń to ogłoszenie:\n\n" + listingText` — no delimiter, no fence, no instruction that the
   listing is data and not instructions. The guardrail *is* encoded in the prompt prose, but the
   cheapest deterministic proxy for it — `accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` — is
   enforced nowhere, and `verdict.label` is unvalidated model free text rendered as the headline.

10. **The carried-forward cookie gap is a production defect, not just a coverage gap.**
    `extractCookies` does replace-not-append as the charter states (confirmed), but the larger
    finding is that the `RestClient.Builder` is a **shared singleton** and `defaultHeader`
    mutates it — so cookies from lookup *N* leak into lookup *N+1*'s bootstrap GET, and two
    concurrent analyses race on one cookie jar. The same aliasing is what makes the test seam
    work. A broken cookie or XSRF yields `LOOKUP_FAILED`, the adjuster skips, and the
    2026-08-26 `risk: 88`-with-a-szkoda-istotna failure mode is re-entered — load-dependent and
    invisible to single-threaded tests.

Two smaller corrections fall out and should be backported with §4's:

- **`test-plan.md:53`'s "`min=22900` against `median=79900`" pairing exists nowhere.**
  `roadmap.md:187` records `min=39900` / `median=82900`, and `min=22900` separately.
  `median=79900` appears in no artifact. The risk row cites a number that was never observed.
- **`HistoriaPojazduSessionTest.java:22-23` is false.** Its Javadoc claims cookie merging is
  asserted in `CepikDamageReachesTheResponseTest`. XSRF is genuinely asserted there
  (`:333`); **no test anywhere asserts `header("Cookie", …)`.**

Confirmed for §4: **Context7 and Exa are both available** as of 2026-09-03 (both exercised during
this research; Exa produced the independent OpenRouter oracle material below). The two "not
available in current session" lines are wrong and should be re-dated.

Baseline: `JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test` from
`backend/` → **BUILD SUCCESS, 163 tests, 0 failures, 0 skipped, 15.6 s.** **No Node or npm exists
anywhere on this machine**, so the frontend suite cannot run locally — which constrains where
Risk #5's label fix can be verified this phase (§3 already assigns "#5 (UI half)" to Phase 3).

## Detailed Findings

### A. Risk #1a — the provider pool is saturated or a slug was retired

#### A1. The classification tree, and what falls through it

`dispositionOf` ([OpenRouterAnalysisService.java:215-237](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/915f84e96fec332f3666e61e9df68bed9ef2dc3a/backend/src/main/java/com/example/autoskaner_ai/llm/OpenRouterAnalysisService.java#L215-L237)):

| Input | Disposition | Line |
|---|---|---|
| `ResponseShapeException` | FATAL | `:217-219` |
| 429, any 5xx | RETRY same model | `:223-224` |
| 401, 403 | FATAL | `:226-228` |
| **any other status** — 404, 400, 402, 408, 409, 422 | NEXT_MODEL | `:230-231` |
| `RestClientException`, `IOException` | RETRY same model | `:233-234` |
| anything else | FATAL | `:236` |

This matches `CLAUDE.md`'s documented two-axis contract for the cases it names. The gap is the
catch-all at `:230-231`: **408 Request Timeout and 429-adjacent 402 are treated as
"permanent for this model"** and skip the retry. Independently verified against OpenRouter's
published status semantics (via Exa, 2026-09-03): 408 is a timeout — transient by definition —
and 402 is insufficient credits, which is fatal for *every* model, not permanent for one. Both
are misrouted. 404 (`not_found`, "No endpoints found for `<slug>`") is correctly NEXT_MODEL;
a captured retired-slug body is `{"error":{"message":"No endpoints found for anthropic/claude-3.7-sonnet:thinking.","code":404}}`.

Also confirmed externally: **OpenRouter returns `Retry-After` on 503 as well as 429**, with a
documented `Retry-After: 60` example. This repo caps the wait at `MAX_RETRY_WAIT = 6 s`
([:256-258](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/915f84e96fec332f3666e61e9df68bed9ef2dc3a/backend/src/main/java/com/example/autoskaner_ai/llm/OpenRouterAnalysisService.java#L256-L258)),
so a provider asking for 60 s gets 6 — the retry is then near-certain to hit the same saturated
pool. That is a deliberate tradeoff (the 30 s budget forbids honouring 60 s), but it means
**"a retry always helps" is false in exactly the documented 429 case**, which is the charter's
second challenge and is directly assertable.

And: OpenRouter *already* retries other providers for the same model before the error surfaces.
So a 429 reaching us means the whole pool for that slug is saturated, not one endpoint —
strengthening the case that same-model retry is the weaker axis.

#### A2. The zero-wait retry

`retryWait` ([:240-261](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/915f84e96fec332f3666e61e9df68bed9ef2dc3a/backend/src/main/java/com/example/autoskaner_ai/llm/OpenRouterAnalysisService.java#L240-L261)):

- a numeric `Retry-After` is honoured, capped at 6 s;
- an **HTTP-date** `Retry-After` — legal per RFC 9110 and emitted by real CDNs — is not parsed and
  silently falls back to `DEFAULT_RETRY_WAIT = 1 s` (`:46`, `:251-253`);
- the wait is then clamped to the deadline remainder (`:259-260`);
- `sleepQuietly` **returns `true` for a zero or negative wait** (`:265-267`), so the retry fires.

Net: near the deadline the code performs an immediate same-model retry. `roadmap.md:76` records
that this exact behaviour is what "turned single 429s into production 502s on 2026-08-26."
The remedy the incident produced is defeated at its own boundary condition.

#### A3. The deadline is checked in only two places

Read at `:82`/`:86`, computed at `:103`, checked at `:113` and `:259`. The `:113` check is guarded
by `lastFailure != null`, so the primary is always attempted (as documented). But the check runs
only *between* models — with 6 candidates × (30 s read + up to 6 s wait) the LLM step alone
reaches **~156 s**. The skip branch and its "fallback budget exhausted" log
([:132-134](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/915f84e96fec332f3666e61e9df68bed9ef2dc3a/backend/src/main/java/com/example/autoskaner_ai/llm/OpenRouterAnalysisService.java#L132-L134))
**has never executed in any test.** It is reachable at zero wall-clock cost:
`deadlineSeconds` is an injectable constructor parameter (`:82`) and the test helper hardcodes 70
(`OpenRouterAnalysisServiceTest.java:52`) — passing `0` exercises the branch instantly.

#### A4. The hollow 200

`validateRequired` ([AnalysisResponseParser.java:57-64](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/915f84e96fec332f3666e61e9df68bed9ef2dc3a/backend/src/main/java/com/example/autoskaner_ai/llm/AnalysisResponseParser.java#L57-L64))
null-checks six containers: `extracted`, `scores`, `verdict`, `riskFlags`, `sellerQuestions`,
`inspectionChecklist`. `mapExtracted` (`:80-89`) accepts **all sixteen leaf fields null**. So
`{"extracted":{},"scores":{…},"verdict":{},"riskFlags":[],"sellerQuestions":[],"inspectionChecklist":[]}`
parses cleanly and the controller returns 200 with an analysis that says nothing. `ScoresDto`
declares primitive `int` (`:158`), so a null score coerces to `0` — i.e. a *perfect* score for a
field the model never returned. (The coercion path is inferred from the declaration, not yet
executed under test — flagging it as unverified.)

`buildRequestBody` (`:288-297`) sends **no `response_format: {"type":"json_object"}`** and
`max_tokens: 8192` (`:295`); `finish_reason` is read nowhere in the repo, so a truncated response
is indistinguishable from a complete one. Truncation detection was explicitly deferred
(`llm-analysis-wiring/reviews/impl-review.md:118`) — this is where that debt lands.

`OpenRouterLiveTest:36-48` asserts only non-nullity, so it cannot detect a hollow analysis either.

#### A5. What the user actually sees

`GlobalExceptionHandler`: `LlmCallException` → 502 "Błąd usługi LLM" (`:40-46`);
`LlmResponseSchemaException` → 502 "Niepoprawny format odpowiedzi LLM" with
`messages: [fieldPath]` (`:48-54`); catch-all → 500 "Błąd serwera" (`:56-61`).

So 401, empty `choices`, and exhausted-chain are **byte-identical**, and the frontend collapses
all 502s to one string (`analyzer.component.ts:184-193`) and never reads `messages` for a 502.
`AnalysisMeta.model` — assigned at `:119`, passed at `:150`, the one field that records *which*
model answered — is never asserted end to end; `dummyResult()`
(`OpenRouterAnalysisServiceTest.java:54-60`) returns the primary regardless.

**There are zero controller-boundary tests for any LLM failure.** Every existing failure test
stops at the service.

#### A6. The existing tests, and what they assert instead

Seam at `OpenRouterAnalysisServiceTest.java:48-49` (§6.2's `MockRestServiceServer` pattern).
`parser` is a Mockito mock in every test (`:43`), so `assertThat(result).isEqualTo(expected)` is
**tautological** — it asserts the stub returned the stub. Four tests assert only POST counts,
exactly the anti-pattern the charter names ("avoid asserting the retry count instead of the
user-visible outcome"): `retiredSlug404_…` (`:139-148`), `unauthorized401_…` (`:153-159`),
`noFallbacksConfigured_…` (`:187-195`), `blankAndDuplicateFallbacks…` (`:200-208`).
Only `{"choices":[]}` (`:165`) is served as a degenerate 200. The single timing assertion is
`elapsedMs >= 900` (`:123-129`).

### B. Risk #1b — the 30 s budget

#### B1. The oracle

`prd.md:98`, verbatim: *"Analysis response time: a listing analysis produces its result within a
perceptible wait; any operation exceeding 2 seconds presents continuous visible progress to the
user, and no analysis runs for longer than 30 seconds without a visible result or error."*

Two separable, independently assertable oracles: **(i)** >2 s ⇒ visible progress; **(ii)** ≤30 s
⇒ a result *or an error*. Note (ii) is satisfied by a fast honest error — it does not require a
successful analysis. That makes it testable without any real provider.

#### B2. Nothing enforces it

`AnalysisController.analyze` (`:37-64`) is plain blocking. `application.properties` is six lines
with no `spring.mvc.async.request-timeout`. No filter, no interceptor, no `@Async`, no `Clock`
seam. Configured timeouts (all explicit, none defaulted; all `SimpleClientHttpRequestFactory`, so
the read timeout is **per socket read**, not per request):

| Stage | connect / read | Calls | Worst case |
|---|---|---|---|
| DNS pre-check (`ListingFetchService.java:64-72`) | 5 s | 1 | 5 s |
| Listing fetch via Jina (`ListingFetchConfig.java:14,16`) | 5 / 30 | 1 | 35 s |
| OpenRouter (`OpenRouterConfig.java:24,25`) | 10 / 30 | ≤6 | ~156 s (incl. waits) |
| historiaPojazdu (`HistoriaPojazduConfig.java:16,17`) | 5 / 10 | 5 | 75 s |
| Market price (`MarketPriceFetchService.java:36,58,68`) | 5 / 30 | ≤2 | 70 s |

**Sum ≈ 341 s.** `llm.openrouter.deadline-seconds=70` (`application-openrouter.properties:28`) is
the only budget in the codebase and is already 2.3× the NFR. Observed reality is ~27 s
(`roadmap.md:220`), all on the request thread, with async deferred (impl-review F10) — so the
NFR holds today by luck of provider latency, not by construction.

#### B3. A finished analysis can be thrown away

`buildResponse` has **no try/catch**. CEPiK degrades safely inside its own service
(`HistoriaPojazduService.java:47-56`), but two calls on the enrichment path are uncaught:
`slugMapper.makeSlug` (`:48`) and `MarketPriceStatistics.of` (`:78`). Either throwing yields a
500 that **discards a completed 16 s LLM analysis**. There is no partial-result path.

This directly violates a stated invariant from the S-05 archive
(`market-price-context/plan-brief.md:69`): *"always returns a `marketPriceContext` field — never
absent, never an uncaught exception."* That sentence is the oracle; the code does not honour it.

#### B4. Frontend

`analysis.service.ts:11-16` is a bare `post` — zero hits repo-wide for
`timeout|AbortController|retry|catchError|interceptor`. The loading skeleton
(`analyzer.component.html:44-55`) rotates four messages (`analyzer.component.ts:60-65`) every
7000 ms (`:174`), so the cycle **wraps at 28 s** — the user watching a 30 s+ request sees message
one a second time, which reads as a stall. `analyzer.component.html:31` carries the only
"~30 sekund" copy in the app. `mapError` (`:184-193`): 400 → 'Błąd walidacji danych.',
502 → 'Serwis AI jest tymczasowo niedostępny. Spróbuj ponownie.', catch-all → 'Błąd serwera.
Spróbuj ponownie.' — **`status: 0` (network drop, browser timeout) collapses into the catch-all**,
so a dead connection is reported as a server error. `:123` gives 'Otrzymano niepełną odpowiedź
serwera.'

No frontend test observes `loading() === true`; the specs cover 400 and 502 only, never
`status: 0` and never 500.

### C. Risk #5 — a thin or dispersed price sample

#### C1. The off-by-one at exactly 3

`MarketPriceStatistics.MIN_SAMPLE_TO_KEEP = 3` (`:46`), and the ±3× band falls back to the
untrimmed sample when the band would leave fewer than 3 (`:80`,
`kept.size() >= 3 ? kept : sorted`). The UI's only caveat fires on `sampleSize < 3`
(`market-price-panel.component.html:23`), copy at `:25`:
`Mała próbka ({{ sampleSize }} ogłoszeń) — traktuj zakres orientacyjnie`.

So at **exactly 3** the two thresholds collide: the sample is reported untrimmed *and* uncaveated.
`[2_000, 60_000, 900_000]` — the shape already in `MarketPriceStatisticsTest.java:145-152` —
renders `2 000 – 900 000 PLN` as a confident range. This is the charter's Risk #5 in one input.

Compounding it: the caveat lives inside `@if (expanded())`
(`market-price-panel.component.html:12`) with `expanded = signal(false)`
(`market-price-panel.component.ts:14`), while the header (`:5-10`) shows the bare range. **The
default view never shows the caveat at all.**

#### C2. Dispersion is computed and thrown away

`discardedCount` is computed at `:68`, logged at `MarketPriceFetchService.java:83-85`, and dropped
at the mapping (`:87-88`). `MarketPriceContext` is
`(MarketPriceStatus, Integer min, Integer median, Integer max, Integer sampleSize, String queryUrl, Instant fetchedAt)`
— **no dispersion field, no discarded count, no trim indicator.** The "too dispersed" half of
Risk #5 therefore has no observable to assert on at the response boundary. Any test for it must
either reach inside `MarketPriceStatistics` (weak — it is a pure function whose output is already
the trimmed triple) or the response must gain a field.

#### C3. The pipeline, for oracle purposes

Regex `###\s*([\d\s]+)\r?\nPLN` (`:30`, `:135-148`); sanity guard `1_000..10_000_000` (`:140`);
±3×-median band (`:71-81`); Tukey 1.5×IQR fence (`:83-97`) gated on **post-band** size ≥8 (`:60`)
and skipped when `iqr == 0` (`:91-92`). The median is **round-half-up** `(a+b+1)/2` (`:108`), not
a plain average — worth knowing precisely because the charter forbids re-deriving it with the
production formula: hand arithmetic must round the same way or the oracle is wrong for the wrong
reason.

Status → field population: `OK` populated (`:87-88`); `FETCH_FAILED` (`:156-158`);
`INSUFFICIENT_DATA` (`:75`); `MISSING_INPUTS` with null `queryUrl`/`fetchedAt` (`:152-154`) —
while `analysis.models.ts:62` declares `fetchedAt: string`, **non-nullable**. A frontend/backend
contract mismatch on a null the backend really does send.

Two silent widenings that make a reported `OK` mean less than it looks: on zero prices the model
slug is **dropped and the search re-run make-only**, still reporting `OK` and reassigning
`queryUrl` (`:65-72`); a missing year drops the year filter entirely (`:100-104`); mileage is an
upper bound only (`:107`). So `OK` with a healthy `sampleSize` can describe *all Toyotas of any
year*.

#### C4. What exists, and the fixture gap

`MarketPriceStatisticsTest` — 13 methods, all hand-derived literals. **Zero instances of the
anti-pattern the charter warns about**; the non-mutation case at `:43-50` is verified good. But
**no `market-price-panel` spec exists at all**, so the label — the actual deliverable of Risk #5 —
has no test on either side. `MockMarketPriceEnrichmentService.java:16-21` always returns
`OK, 45_000/55_000/70_000, sampleSize=12`, i.e. the mock profile can never show a thin sample.
`MarketPriceFetchServiceLiveTest.java:46` correctly asserts `OK` (verified — it does not tolerate
`FETCH_FAILED`). The price range never feeds the verdict, so a bad range misleads the buyer
without moving the score — which is why the *label* is the whole mitigation.

**There is no `src/test/resources/market/` directory.** The Otomoto markdown the regex reads is
composed inline in tests. Given `market-price-context/reviews/impl-review.md:29` — text-block
normalisation of `\n` masked a real regex bug — this is the 2026-08-26 invented-field-names
failure transposed onto Risk #5. See Architecture Insights for how §6.5's rule generalises here.

### D. Risk #6 — listing text written to game the analyser

#### D1. The deterministic floor exists, and is usually absent

`CepikRiskAdjuster` is confirmed the sole deterministic writer of `scores`/`verdict` (`:140`,
`:152`, `:104`). But it is gated on `FOUND` (`:43`) and on a fact actually firing (`:92-94`).
Per `CLAUDE.md`, **`MISSING_INPUTS` is the expected outcome for a real URL-only Otomoto listing**
(plate and date are public, the VIN is login-gated). So in the common case **every score and the
verdict are model-produced**, and the "deterministic floor" Risk #6 asks us to prove immovable is
not there to be moved. That is the honest framing for this phase: the floor must be proven
immovable *where it exists*, and the absence documented where it does not.

#### D2. `capRisk` short-circuits before the never-raise guard

`capRisk` returns early when `risk <= cap` (`:134`), so the never-raise guard at `:141` is only
reachable on the capping path. Consequence: a model that returns `risk: 3, overall: 97` for a car
with a registered szkoda istotna keeps both numbers — the cap of 35 never applies because 3 is
already below it, and `overall` is recomputed as the mean but **never raised**. The verdict floors
to `NEEDS_MORE_INFO`, so the outcome is a "needs more info" verdict sitting next to a 97 overall.
`capsNeverRaiseAScoreOrSoftenAVerdict` (`CepikRiskAdjusterTest.java:289-295`) **asserts this
hole** — it is a test that pins the failure mode.

#### D3. The contradiction rule reads model-extracted text

Rule 4 compares registry damage against `accidentClaim` (`:77`, `:125`, `:128`) — a field the
*model* extracted from the listing. Phrases at `:37-38`:
`["bezwypadkow", "bezszkodow", "nie uczestniczy", "brak szkód", "brak szkod"]`, matched as
substrings after a pl-locale lowercase — not trimmed, not accent-folded, not negation-aware.
Real consequences, both verified: `"nie jest bezwypadkowy"` (an *honest* seller) **false-positives**
into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP`; and trivially reworded claims bypass the
list entirely. `"nie uczestniczy"` and `"brak szkod"` are untested. Rule 4 is nested at `:77-83`
inside `:66-84`, so a null/absent `accidentClaim` loses only the contradiction finding — the
damage cap still applies.

#### D4. Free text on the trust boundary

`verdict.label` is **unvalidated model output** (`AnalysisResponseParser.java:126`), preserved by
`applyFloor` whenever the code does not change (`:149-151`), and rendered as the page headline
(`analysis-result.component.html:3`). `riskFlags[].code` is likewise a free `String` (`:111`).
So a listing that persuades the model to emit a reassuring *label* gets it displayed even when the
enum-backed `code` was floored — the headline and the machine verdict can disagree.

#### D5. The prompt boundary

`AnalysisPrompt.java:118-120`: `return "Oceń to ogłoszenie:\n\n" + listingText;` — **no delimiter,
no fence, no "the following is untrusted data" instruction** anywhere in `:9-115`. The business
guardrail *is* encoded at `:12-16` with two few-shot examples (`:69-114`), which is the
non-deterministic mitigation. The **deterministic** proxy for it —
`accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` — is enforced nowhere, and it is exactly the
kind of check the charter's "avoid an eval asserting a specific model wording" steers us toward
instead of an eval.

Length caps: `AnalysisRequest.java:13` 20000, `ManualListing.java:44` 10000.
`ListingFetchService.java:127` bounds only a **minimum** (100 chars) — **the Jina-fetched body has
no upper bound**, so a URL is the unbounded path into the prompt while pasted text is capped.

No `AnalysisPromptTest` exists. **Zero adversarial tests repo-wide.** One tautology remains from
Phase 1's sweep at `CepikRiskAdjusterTest.java:147-155` (hardcoded 65 with the comment
`// (90 + 75 + 35 + 60) / 4`, embedding the cap literal 35 in the expectation).
`RegistryFactsReachTheScoreTest:83` sets `accidentClaim: null`, so it contributes nothing to
Risk #6.

### E. Carried-forward gaps — cookie merge/dedupe and XSRF

#### E1. The handshake

Five calls per lookup, one fresh session each (`HistoriaPojazduSession`):
bootstrap GET (`:47-50`) → `NF_WID` POST (`:58-63`, form-urlencoded,
`NF_WID=HistoriaPojazdu:<millis>` from `:44`) → vehicle-data POST (`:81-87`, headers
`X-Xsrf-Token` `:83` and `Nf_wid` `:84`, body keys `registrationNumber` / `VINNumber` /
`firstRegistrationDate` `:85`) → timeline-data POST (`:96-102`) → close GET (`:125`, no XSRF or
`Nf_wid`, failure swallowed at `:126-128`).

#### E2. The cookie claim is CONFIRMED, and worse than stated

`extractCookies` (`:131-140`): `:135` keeps only `name=value`; `:136`
`removeIf(startsWith(name + "="))`; `:137` `add`. Consequences, all real:

- a re-issued cookie **moves to the tail** of the `ArrayList` (`:32`), changing the joined header
  order (`"; "` at `:55` / `:70`);
- **attributes are discarded**, so a `Max-Age=0` deletion is stored as a live cookie;
- an **empty value evicts a good one** (`SESSION=` removes `SESSION=abc`);
- no `Set-Cookie` at all ⇒ an **empty `Cookie:` header** is still sent;
- matching is case-sensitive and the dedupe ignores `Path`/`Domain`.

#### E3. D1 — the shared builder is a production defect

`HistoriaPojazduConfig.java:13-14` exposes **one `RestClient.Builder` bean**, and
`HistoriaPojazduService.java:30-33`, `:36-38` hand that same instance to every session;
`defaultHeader` **mutates it**. So cookies set during lookup *N* are still on the builder for
lookup *N+1*'s bootstrap GET, and two concurrent analyses share one cookie jar — a live race,
since both enrichments run on the request thread. This is also precisely the aliasing that makes
the `MockRestServiceServer` seam work (§6.2's `bindTo` mutation), so any fix must keep the seam.

#### E4. XSRF

`extractXsrfToken` (`:142-149`) reads from the cookie jar by literal
`startsWith("XSRF-TOKEN=")` — no regex, **no log on failure**, silent null. In production a null
coerces to an empty header; **at the mock seam it stays literally `null`** — a seam caveat worth
recording in §6.2 alongside the existing four.

Version discovery: regex `:23-24`, `FALLBACK_API_VERSION = "1.1.0"` `:25`. Both branches are
covered (`HistoriaPojazduSessionTest.java:44-57`, `:59-72`); the **null-body branch `:111-113` is
uncovered**.

#### E5. Why it matters and how to assert it

A broken cookie or XSRF ⇒ `LOOKUP_FAILED` ⇒ the adjuster skips (FOUND-only gate) ⇒ the
2026-08-26 `risk: 88` with a registered szkoda istotna failure mode is re-entered. Load-dependent,
and invisible to a single-threaded test.

**The cookie merge is directly assertable at the existing seam** via
`MockRestRequestMatchers.header` / `headerList` / `headerDoesNotExist`. Important mechanical
detail: a failed matcher throws `AssertionError` — an `Error`, not an `Exception` — so it slips
past `HistoriaPojazduSession`'s `catch (Exception e)` blocks and surfaces as a genuine test
failure rather than being swallowed into `LOOKUP_FAILED`. That is what makes this gap cheap to
close now.

### F. Seam, oracles, and what this phase may fabricate

The `MockRestServiceServer` seam (§6.2) carries over unchanged, with its four documented gotchas
plus the two new caveats above (XSRF null at the seam; `AssertionError` escapes the catch).

One consequence of `bindTo` replacing the request factory: **configured socket timeouts are not
testable at that seam.** So Risk #1b's timeout table can be asserted as *configuration*
(`ListingFetchConfig`, `OpenRouterConfig`, `HistoriaPojazduConfig`, `MarketPriceFetchService`) and
as *arithmetic* against the 30 s NFR, but not by making a stub hang. Testing the budget end to
end needs either a deadline the controller enforces (which does not exist yet) or an injected
`deadlineSeconds: 0` at the service.

§6.5's four permitted oracle sources hold: bytes of a committed capture; a production incident; hand
arithmetic or calendar rules written out in a comment; a stated product guardrail. `CLAUDE.md` is
the specification of record, not an independent derivation, and reading the class under test is
never a source. For this phase the concrete mapping is:

| Risk | Oracle | Source class |
|---|---|---|
| #1a status classification | OpenRouter's published status semantics + a captured 404 body | external capture / third-party vocabulary |
| #1a retry timing | the 2026-08-26 incident (`roadmap.md:76`) | production incident |
| #1b budget | `prd.md:98` (30 s, 2 s progress) | product guardrail |
| #1b partial results | `market-price-context/plan-brief.md:69` | product guardrail |
| #5 trim maths | hand arithmetic, round-half-up spelled out | hand arithmetic |
| #5 label | the `sampleSize < 3` product decision | product guardrail |
| #6 floor | `CLAUDE.md`'s risk-ceiling table + the 2026-08-26 `risk: 88` | incident + guardrail |
| E cookie/XSRF | the registry's own wire behaviour | capture required |

### G. Environment and baseline

- Baseline suite: **163 tests green, 15.6 s**, via
  `JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test` from `backend/`.
  (`JAVA_HOME` was Phase 1's real blocker — see `plan-brief.md` in that archive.)
- **No Node or npm on this machine** ⇒ the frontend vitest suite cannot be run or verified
  locally. Any `market-price-panel` spec written this phase is unverifiable here; §3 already
  assigns "#5 (UI half)" to Phase 3, and that split now has a hard environmental reason.
- **Context7 and Exa both available**, exercised 2026-09-03. §4's two "not available in current
  session (checked 2026-08-27)" lines are stale and must be corrected and re-dated.
- `context/foundation/lessons.md` does not exist.

## Code References

**Risk #1a — provider failure**
- `backend/src/main/java/com/example/autoskaner_ai/llm/OpenRouterAnalysisService.java:215-237` — classification tree; `:230-231` is the catch-all that misroutes 408/402
- `…/OpenRouterAnalysisService.java:240-267` — `retryWait` + `sleepQuietly`; HTTP-date fallback `:251-253`, 6 s cap `:256-258`, deadline clamp `:259-260`, zero-wait retry `:265-267`
- `…/OpenRouterAnalysisService.java:103,113,132-134` — deadline computed, checked, and the never-executed skip branch
- `…/OpenRouterAnalysisService.java:95,106,121` + `:97,109,124` — three `valueOf` calls whose catches are `IllegalArgumentException`
- `…/OpenRouterAnalysisService.java:119,150` — `AnalysisMeta.model`, never asserted end to end
- `…/OpenRouterAnalysisService.java:288-297` — no `response_format`, `max_tokens: 8192`, `finish_reason` unread
- `…/llm/AnalysisResponseParser.java:57-64,80-89,158` — container-only validation, 16 nullable leaves, primitive `int` scores
- `…/common/GlobalExceptionHandler.java:40-46,48-54,56-61` — the three error mappings; 401 / empty-choices / exhausted-chain are byte-identical
- `backend/src/test/java/…/llm/OpenRouterAnalysisServiceTest.java:43,48-49,52,54-60,123-129,139-148,153-159,165,187-195,200-208` — mocked parser (tautology), hardcoded deadline 70, POST-count assertions, the single `>= 900 ms` timing assertion
- `backend/src/test/java/…/llm/OpenRouterLiveTest.java:36-48` — non-nullity only

**Risk #1b — the budget**
- `context/foundation/prd.md:98` — the NFR, two separable oracles
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java:37-64,40,47,50,56,62,70,72,73,77,99` — the synchronous chain; `url_failed` early return at `:50`; no try/catch around `:48` and `:78`
- `…/listing/ListingFetchService.java:64-72,127` — DNS 5 s; minimum-length-only guard
- `…/listing/ListingFetchConfig.java:14,16` · `…/llm/OpenRouterConfig.java:24,25` · `…/cepik/HistoriaPojazduConfig.java:16,17` · `…/market/MarketPriceFetchService.java:36,58,68` — the timeout table (≈341 s worst case)
- `backend/src/main/resources/application-openrouter.properties:28` — `deadline-seconds=70`
- `frontend/src/app/…/analysis.service.ts:11-16` — bare post, no timeout
- `frontend/src/app/…/analyzer.component.ts:60-65,113,123,174,184-193` — four messages, 7000 ms rotation (wraps at 28 s), `mapError` with `status: 0` in the catch-all
- `frontend/src/app/…/analyzer.component.html:31,44-55` — the "~30 sekund" copy; the skeleton

**Risk #5 — market price**
- `backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceStatistics.java:30,46,60,68,71-81,83-97,108,135-148` — regex, `MIN_SAMPLE_TO_KEEP`, IQR gate, `discardedCount`, band fallback `:80`, round-half-up median `:108`, guard `:140`
- `…/market/MarketPriceFetchService.java:65-72,83-85,87-88,100-107,152-158` — silent slug widening, discarded count logged then dropped, status→field mapping
- `frontend/src/app/…/market-price-panel.component.html:5-10,12,23,25` — bare range in the header, caveat behind `@if (expanded())`
- `frontend/src/app/…/market-price-panel.component.ts:14` — `expanded = signal(false)`
- `frontend/src/app/…/analysis.models.ts:62` — `fetchedAt: string`, non-nullable, contradicted by `MISSING_INPUTS`
- `backend/src/test/java/…/market/MarketPriceStatisticsTest.java:43-50,145-152` — clean non-mutation case; the `[2_000, 60_000, 900_000]` shape
- `backend/src/main/java/…/market/MockMarketPriceEnrichmentService.java:16-21` — always `OK`, `sampleSize=12`
- `backend/src/test/java/…/market/MarketPriceFetchServiceLiveTest.java:46` — asserts `OK` (verified honest)

**Risk #6 — gaming**
- `backend/src/main/java/com/example/autoskaner_ai/cepik/CepikRiskAdjuster.java:37-38,43,66-84,77-83,92-94,104,125,128,134,140-141,152` — phrase list, FOUND gate, nested rule 4, `capRisk` short-circuit and the unreachable never-raise guard
- `…/llm/AnalysisPrompt.java:12-16,69-114,118-120` — the guardrail in prose, two few-shots, and the unfenced concatenation
- `…/llm/AnalysisResponseParser.java:111,126` — free-String `riskFlags[].code` and `verdict.label`
- `…/analysis/UserOverrides.java:61` — `accidentClaim` deliberately not overridable
- `…/analysis/AnalysisRequest.java:13` · `…/analysis/ManualListing.java:44` — 20000 / 10000 caps
- `frontend/src/app/…/analysis-result.component.html:3` — `verdict.label` as the headline
- `backend/src/test/java/…/cepik/CepikRiskAdjusterTest.java:147-155,289-295` — the remaining tautology; the test that asserts the `capRisk` hole
- `backend/src/test/java/…/cepik/RegistryFactsReachTheScoreTest.java:83` — `accidentClaim: null`

**Carried-forward gaps**
- `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java:23-25,32,44,47-50,55,58-63,70,81-87,96-102,111-113,125-128,131-140,142-149` — version discovery and fallback, the cookie `ArrayList`, all five calls, `extractCookies`, `extractXsrfToken`
- `…/cepik/HistoriaPojazduConfig.java:13-14` + `…/cepik/HistoriaPojazduService.java:30-33,36-38,47-56` — the shared mutable builder (D1) and the safe-degrade catch
- `backend/src/test/java/…/cepik/HistoriaPojazduSessionTest.java:22-23,44-57,59-72` — **the false Javadoc**; both version branches
- `backend/src/test/java/…/cepik/CepikDamageReachesTheResponseTest.java:333` — the one genuine XSRF assertion

## Architecture Insights

**§6.5's capture rule generalises past the registry, along a vocabulary boundary.** The reason
hand-composed registry fixtures were fatal is that the *field names are a third party's
vocabulary* — composing them encodes our guess about someone else's schema, which is the bug.
That test applies cleanly to two more surfaces in this phase:

- **Requires captures** (third-party vocabulary): the OpenRouter response envelope and its error
  bodies; Otomoto markdown as rendered through Jina Reader. There is **no `src/test/resources/market/`
  directory at all**, so the price regex is currently tested against markdown we invented — and
  `market-price-context/reviews/impl-review.md:29` already records one real `\n` bug that inline
  text blocks masked. Phase 2 should capture Otomoto markdown the way Phase 1 captured registry
  payloads.
- **May legitimately be composed** (our own locked schema): the inner analysis JSON, and the
  internal `List<Integer>` of prices `MarketPriceStatistics` consumes. We own that shape; there
  is no third party to be wrong about. This is why `MarketPriceStatisticsTest`'s hand-built lists
  are fine while composed Otomoto markdown is not.

**The honest-outcome property has a shape, and it is three-valued, not two.** The project's
null/`[]`/populated invariant on registry lists is the same idea as this phase's charter: a
failure must be *distinguishable*, not merely non-crashing. Today three availability failures
collapse to one 502 string, `status: 0` collapses into "server error", and `OK` on the market
path can mean "all Toyotas of any year". Each is the two-valued collapse of a three-valued
reality. The Phase 2 tests worth writing are the ones that pull those apart at the boundary the
user can see.

**The deterministic floor is the only defence that can be tested cheaply, and it is thin.**
Risk #6's mitigation is split between prose in the prompt (non-deterministic, expensive to test,
explicitly out of scope per the charter) and `CepikRiskAdjuster` (deterministic, cheap) — but the
adjuster only runs on `FOUND`, which is not the common case. The cheap, in-charter move is to add
deterministic invariants that hold regardless of registry outcome:
`accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION`, and a validated `verdict.label`. Both are
guardrails already stated in `CLAUDE.md`; neither is enforced.

**Every timeout in the codebase is a socket timeout; the NFR is a wall-clock budget.** These are
different units and nothing converts between them. `deadline-seconds` is the only wall-clock
budget and it covers one of five stages. Whatever Phase 2 asserts about the budget, the finding
to record is that the NFR is currently met by provider latency rather than by construction.

**`bindTo` mutating the caller's builder is simultaneously the test seam and a production
aliasing defect.** §6.2 documents the mutation as a convenience (it survives mid-session
`builder.build()` rebuilds). D1 shows the same aliasing leaking cookies between lookups. A fix
must not close the seam — which argues for a per-lookup builder derived from the shared bean
(`builder.clone()`) rather than removing the shared bean.

## Historical Context (from prior changes)

- `context/foundation/roadmap.md:76` — free-tier slugs are the main fragility; **an immediate
  retry turned single 429s into production 502s on 2026-08-26.** The oracle for A2.
- Commits `a8525ee`, `64feb46`, `2331ddd` — the retry/fallback work; `glm-5.2:free` went globally
  429 within fifteen minutes of being made primary. Saturation is a *pool* property, not an
  endpoint property.
- `commit 3a10fe7` — the two-pass trim. `roadmap.md:187`/`:189`: "the trim is statistical, not
  semantic"; `:121`: "the scoring layer trusts the listing's own claims" — the pre-existing
  statement of Risk #6.
- `commit f9e3762` — prompt hardening. The guardrail prose at `AnalysisPrompt.java:12-16` dates
  from here; the deterministic proxy was never added.
- `context/changes/llm-analysis-wiring/reviews/impl-review.md:118` — **truncation detection
  explicitly deferred.** This is where that debt surfaces (A4).
- `context/archive/2026-06-02-market-price-context/plan-brief.md:69` — "always returns a
  `marketPriceContext` field — never absent, never an uncaught exception." Currently violated
  (B3).
- `context/archive/2026-06-02-market-price-context/reviews/impl-review.md:29` — text-block
  normalised fixtures masked a `\n` regex bug. The precedent for capturing Otomoto markdown.
- `context/archive/2026-06-02-market-price-context/research.md:233` and `plan.md:111,114` — a
  null-if-under-3 design was **considered and rejected** during S-05; the trim post-dates that
  archive, which is how the `MIN_SAMPLE_TO_KEEP` / `sampleSize < 3` collision arose without
  anyone deciding it.
- `context/changes/s-01/reviews/impl-review.md:84` — `ListingFetchService` swallows failures with
  no log line. Same silent-degrade family as E4's unlogged XSRF null.
- `context/foundation/ab-experiment-error-shape.md:15` — the error envelope, which is why
  `messages` is the only field available to distinguish 502s and why the frontend ignoring it
  matters.
- `context/foundation/roadmap.md:220` (~27 s synchronous; impl-review F10 async deferred) and
  `:168` — the measured latency behind B2.

**Correction to fold in:** `test-plan.md:53` cites "`min=22900` against `median=79900`". No
artifact records that pairing. `roadmap.md:187` has `min=39900` / `median=82900`; `min=22900`
appears separately; `median=79900` appears nowhere. The risk row should cite the observed numbers.

## Related Research

- `context/archive/2026-08-27-testing-enrichment-honesty/research.md` — Phase 1. Sets the format
  and the bar (findings ordered by consequence, oracle-sourcing table, tautology inventory), and
  is where §6.2's seam, §6.5's fixture rule, and the four-oracle policy were established.
- `context/archive/2026-08-27-testing-enrichment-honesty/plan-brief.md` — the working verification
  command and the `JAVA_HOME` note reused in §G.
- `context/archive/2026-06-02-market-price-context/research.md` — why Jina and not Exa; the
  rejected null-if-thin design.
- `backend/src/test/resources/cepik/README.md` — the fixture convention this phase extends to a
  second provider.

## Open Questions

1. **Does Phase 2 fix or only pin?** Nine findings above are live defects: the `MIN_SAMPLE_TO_KEEP`
   / `sampleSize < 3` collision (C1), the hollow 200 (A4), the four NPE/CCE-to-500 routes (A5),
   the zero-wait retry (A2), the uncaught throws that discard a finished analysis (B3),
   `capRisk`'s short-circuit (D2), the unvalidated `verdict.label` (D4), the missing
   `accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` invariant (D5), and the shared-builder cookie
   leak (E3). Writing tests against current behaviour would pin every one of them, which §1 rule 4
   forbids. Phase 1 answered this by fixing; the scope difference here is that some fixes touch
   production resilience code, not test code. **Recommendation: fix, but sequence the response-shape
   changes (a dispersion field on `MarketPriceContext`, a validated `verdict.label`) as explicit
   sub-phases, since they change the API contract the frontend reads.**
2. **Does the 30 s NFR get enforced this phase, or only measured?** Enforcing it needs a real
   deadline mechanism (`spring.mvc.async.request-timeout`, or a budget threaded through the
   enrichment calls) plus a partial-result shape. Measuring it needs only the configuration
   arithmetic and an injected `deadlineSeconds: 0`. The second is squarely in charter; the first is
   arguably F10's deferred async work arriving early.
3. **Where does the thin/dispersed label live if the frontend cannot be verified here?** No Node
   on this machine. Options: put the label decision on the backend (a field or an enum on
   `MarketPriceContext`, fully testable in the 163-test suite) and let Phase 3 render it; or write
   an unverifiable frontend spec now. The first is better engineering *and* the only one this
   environment can prove.
4. **Is a captured Otomoto markdown fixture obtainable?** The live path is blocked by proxy policy
   on this machine (`r.jina.ai` 403, category "General AI and ML Applications"), so a capture may
   need to come from production. Without it, Risk #5's regex stays tested against composed input —
   the exact gap §6.5 exists to close.
5. **Do the misrouted statuses (408 → NEXT_MODEL, 402 → NEXT_MODEL) get corrected?** Both are
   small, both contradict OpenRouter's documented semantics, and 402 in particular walks the whole
   chain on an error that will repeat identically for every model — the precise cost `CLAUDE.md`
   cites for treating a fatal error as retryable.
6. **How should `Retry-After: 60` be handled given a 30 s budget?** Honouring it breaks the NFR;
   capping at 6 s makes the retry near-useless in the documented 429 case. A third option —
   skip straight to the next model when the requested wait exceeds the remaining budget — matches
   the two-axis design better than either, but is a behaviour change and needs the user's call.
