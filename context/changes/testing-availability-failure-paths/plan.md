# Test rollout Phase 2 — Availability and failure paths — Implementation Plan

## Overview

Rollout Phase 2 of `context/foundation/test-plan.md`. The charter is to prove every provider and
fetch failure ends in an honest, distinguishable outcome inside the time budget, and that a thin
price sample labels itself — covering Risk #1 (High/High), Risk #5 (Medium/High) and Risk #6
(Medium/Medium), plus two gaps carried forward from Phase 1.

Research found that **nine of the behaviours this phase must prove are currently broken**. Writing
tests against today's behaviour would pin the failure mode, which `test-plan.md` §1 rule 4 forbids
and which Phase 1's lesson (§6.7: "a rollout phase that only writes tests will happily freeze the
bug") names explicitly. So this phase closes the defects and then proves them closed, ending with
the one response-shape change Phase 3 inherits.

## Current State Analysis

163 backend tests pass in ~15.6 s. What that green suite does not cover:

- **A 200 OK can carry a hollow analysis.** `AnalysisResponseParser.validateRequired`
  (`analysis/llm/AnalysisResponseParser.java:57-64`) null-checks six *containers*; `mapExtracted`
  (`:80-89`) accepts all sixteen leaf fields null. `ScoresDto`'s primitive `int` (`:158`) coerces a
  null score to `0` — a perfect score for a field the model never returned.
- **Three distinct failures produce byte-identical 502 bodies.** A rejected key, an empty `choices`
  array and an exhausted fallback chain all render `{"status":502,"error":"Błąd usługi LLM",…}`
  (`common/GlobalExceptionHandler.java:40-46`). "Distinguishable" is currently false at the only
  boundary a user can observe, and **there are zero controller-boundary tests for any LLM failure**.
- **Four parser routes escape as a generic 500.** A null `message`, a null `content`, a non-String
  `content`, and an unknown enum value throw something other than `IllegalArgumentException`, so the
  catches at `OpenRouterAnalysisService.java:97,109,124` miss and `LlmResponseSchemaException`'s
  honest 502 never runs.
- **The retry can fire with a zero wait.** `retryWait` clamps to the deadline remainder
  (`:259-260`) and `sleepQuietly` returns `true` for a zero or negative wait (`:265-267`) — the
  exact immediate same-model retry that turned single 429s into production 502s on 2026-08-26
  (`roadmap.md:76`). The one timing assertion (`elapsedMs >= 900`) passes either way. An HTTP-date
  `Retry-After` is not parsed and silently becomes 1 s (`:251-253`).
- **408 and 402 are misrouted.** Both fall into the catch-all at `:230-231` and are treated as
  "permanent for this model": a timeout skips a model that would likely have answered, and
  insufficient credits walks the entire chain on an error guaranteed to repeat identically.
- **The deadline-skip branch has never executed** (`:132-134`), though `deadlineSeconds` is an
  injectable constructor parameter (`:82`) that the test helper hardcodes to 70.
- **No deadline is enforced anywhere.** `prd.md:98` states the 30 s NFR; there is no
  `spring.mvc.async.request-timeout`, no filter, no interceptor, and `AnalysisController.analyze`
  (`analysis/AnalysisController.java:37-64`) is plain blocking. The configured socket timeouts sum
  to **≈341 s** worst case; `deadline-seconds=70` is itself 2.3× the NFR.
- **A finished analysis can be discarded.** `buildResponse` has no try/catch; `slugMapper.makeSlug`
  (`:48`) and `MarketPriceStatistics.of` (`:78`) are uncaught, so either throwing yields a 500 that
  throws away a completed ~16 s LLM analysis. This violates S-05's stated invariant
  (`archive/2026-06-02-market-price-context/plan-brief.md:69`).
- **The thin-sample label has an off-by-one at exactly 3.** `MIN_SAMPLE_TO_KEEP = 3`
  (`market/MarketPriceStatistics.java:46`) reports a 3-price sample *untrimmed* (`:80`) while the UI
  caveat fires on `sampleSize < 3`. And **dispersion is computed then thrown away**:
  `discardedCount` (`:68`) is logged (`market/MarketPriceFetchService.java:83-85`) and dropped at
  the mapping (`:87-88`), so Risk #5's "too dispersed" half has no observable at the response
  boundary. `MarketPriceContext` carries no dispersion field.
- **The price regex is tested only against markdown we composed inline.** There is no
  `src/test/resources/market/` directory. `market-price-context/reviews/impl-review.md:29` records
  one real `\n` regex bug that text-block normalisation masked — the 2026-08-26 fixture failure
  transposed onto Risk #5.
- **`accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` is asked for but never enforced.**
  `analysis/llm/AnalysisPrompt.java:16` states the rule and `:92-103` demonstrates it in a few-shot,
  but nothing in code checks it. `AnalysisPrompt.java:118-120` concatenates listing text with no
  delimiter, fence, or untrusted-data instruction. No `AnalysisPromptTest` exists and there are
  **zero adversarial tests repo-wide**.
- **The registry session shares one mutable builder.** `cepik/HistoriaPojazduConfig.java:13-14`
  exposes a single `RestClient.Builder` bean and `HistoriaPojazduService.java:30-33,36-38` hands
  that same instance to every session; `defaultHeader` mutates it. Cookies from lookup *N* are still
  on the builder for lookup *N+1*'s bootstrap GET, and two concurrent analyses share one cookie jar.
  `extractCookies` (`HistoriaPojazduSession.java:131-140`) does replace-not-append as charted, and
  **no test anywhere asserts `header("Cookie", …)`** — while
  `HistoriaPojazduSessionTest.java:22-23` claims one does.

## Desired End State

The backend suite grows from 163 tests and, for each availability and honesty failure, a test fails
when the protection is removed. Concretely, when this plan is done:

- A provider 200 whose body lacks the analysis spine yields a distinct 502 naming a schema problem,
  not a 200 carrying nothing and not a generic 500.
- A rejected key, an unusable provider response, and an exhausted fallback chain are three different
  Polish messages at the controller boundary.
- A `Retry-After` we cannot honour inside the remaining budget moves down the fallback chain instead
  of retrying immediately; 408 retries and 402 fails fast.
- The deadline-skip branch runs under test at zero wall-clock cost, and the configured-timeout total
  is asserted against `prd.md:98`'s 30 s so a future timeout bump fails the build.
- An enrichment throw can no longer discard a finished analysis; `marketPriceContext` is always
  present, per S-05's invariant.
- The registry session cannot leak a cookie into the next lookup, and the cookie header and XSRF
  token are asserted directly at the seam.
- A missing accident declaration produces `NO_ACCIDENT_DECLARATION` regardless of what the model
  returned.
- `MarketPriceContext` carries a sample-quality judgement decided on the server, tested over a
  captured Otomoto payload, ready for Phase 3 to render.
- `test-plan.md` §4, §2 Risk #5, §6.4, §6.7, §3 and §8 reflect what is actually true.

**Verification command** (Phase 1's, `JAVA_HOME` is the real blocker on this machine):

```bash
cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test
```

### Key Discoveries:

- `RestClient.Builder#clone()` exists in `spring-web` 7.0.7 and copies the request factory
  (verified with `javap`). Since `MockRestServiceServer.bindTo` works by calling
  `requestFactory(mockFactory)` on the shared builder, a clone taken *per session* still carries the
  mock — so the cookie-leak fix and §6.2's seam are compatible. This is the load-bearing fact for
  Phase 4.
- `NO_ACCIDENT_DECLARATION` is not an invention: `AnalysisPrompt.java:16` specifies the exact flag
  (`severity: "MEDIUM"`, description "Ogłoszenie nie zawiera deklaracji wypadkowej — historia
  nieznana"), and `MockAiAnalysisService.java:149` already emits it. The enforced version copies that
  shape — oracle source (4), a stated product guardrail.
- `MarketPriceContext` lives in the **`analysis`** package (`analysis/MarketPriceContext.java`)
  while the services live in `market` — the phase 6 record change and its consumers straddle two
  packages.
- `parser` is a Mockito mock in every `OpenRouterAnalysisServiceTest` case
  (`OpenRouterAnalysisServiceTest.java:43`), which makes `isEqualTo(expected)` tautological: it
  asserts the stub returned the stub. Any new assertion about response *content* must use the real
  parser.
- §6.2's fourth gotcha is directly relevant to this phase: a bodyless 200 fails inside `RestClient`
  and lands in the service's catch block, so serve `{}` when the intent is an *unreadable payload*
  rather than a *transport failure*.
- A failed `MockRestRequestMatchers` matcher throws `AssertionError` — an `Error`, not an
  `Exception` — so it escapes `HistoriaPojazduSession`'s `catch (Exception e)` blocks and surfaces as
  a real test failure rather than being swallowed into `LOOKUP_FAILED`. This is what makes the
  carried-forward cookie gap cheap to close.
- `extractXsrfToken` returns null silently with no log (`HistoriaPojazduSession.java:142-149`);
  production coerces that null to an empty header, but **at the mock seam it stays literally
  `null`** — a seam caveat to record in §6.2.

## What We're NOT Doing

Out of scope by explicit decision, each with its consequence stated so a later reader knows it was
chosen rather than missed:

- **Fixing `capRisk`'s short-circuit.** `CepikRiskAdjuster.java:134` returns early when
  `risk <= cap`, skipping the `overall` recomputation, so a model returning `risk: 3, overall: 97`
  for a car with a registered szkoda istotna keeps both numbers. This stays open. We do **not** write
  a test asserting that behaviour — that would pin it. It is recorded as a known gap in §6.7 and
  carried into Phase 3, the same way §6.5 records the vocabulary-canary gap.
- **Validating `verdict.label` against `verdict.code`.** The model-authored headline
  (`AnalysisResponseParser.java:126`, rendered at `analysis-result.component.html:3`) can still read
  reassuringly next to a floored verdict code. Carried into Phase 3.
- **Bounding the Jina-fetched listing body.** `ListingFetchService.java:127` bounds only a minimum
  (100 chars), so a URL remains the unbounded path into the prompt while pasted text is capped at
  20000. Carried into Phase 3.
- **Negation-awareness in the accident-claim phrase list.** `"nie jest bezwypadkowy"` — an *honest*
  seller — still false-positives into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP`. Documented as
  a known gap, not tested (a test would pin it).
- **Enforcing the 30 s NFR by construction.** No wall-clock budget threaded through the enrichment
  calls, no `spring.mvc.async.request-timeout`, no partial-result response shape. That is impl-review
  F10's deferred async work and it is not pulled forward. The ≈341 s worst case remains reachable;
  this phase makes it visible and asserted, not impossible.
- **Any frontend change.** No Node or npm exists on this machine, so the frontend suite cannot be
  run. The `sampleSize < 3` collision and the collapsed-by-default caveat in
  `market-price-panel.component` are Phase 3's, per §3's "#5 (UI half)" assignment.
- **A prompt-injection eval.** The charter forbids it: non-deterministic and expensive for the
  signal. Risk #6 is defended by deterministic invariants only.
- **Adding a delimiter or fence to the prompt.** Changing `AnalysisPrompt` alters live model
  behaviour with no deterministic oracle to verify it against. The deterministic invariant is the
  in-charter substitute.
- **Rewriting `extractCookies` into a real cookie jar.** Attribute awareness (`Max-Age=0`, empty
  values, case-insensitivity) has no captured `Set-Cookie` payload to ground it. The tests will
  document the current merge semantics as *observed*; only the cross-lookup leak is fixed.
- **Risk #7 and the deprecated `/api/analysis/risk` endpoint.** §2's note and §7's exclusion stand.

## Implementation Approach

Six code phases then a documentation phase, ordered so that each is independently verifiable and the
single response-shape change lands last:

1. Phases 1–2 close the LLM edge, which is Risk #1a and the highest-likelihood risk in the map.
   Phase 1 is about *what the caller receives*; Phase 2 is about *how long it takes to receive it*.
2. Phase 3 makes the analysis survive its own enrichment — the cheapest half of Risk #1b.
3. Phase 4 closes the carried-forward session gaps, which are Risk #2/#3's re-entry route.
4. Phase 5 is Risk #6's one deterministic invariant plus the adversarial cases that were entirely
   absent.
5. Phase 6 is Risk #5, isolated at the end because it changes `MarketPriceContext` — the only
   frontend-visible delta in the phase, and the one Phase 3 picks up.
6. Phase 7 backports every correction to `test-plan.md`.

**Every phase criterion is a mutation, not a green run** — Phase 1's method (§6.7): break the thing
the test exists to catch and confirm the test fails. Criteria below are therefore written as
"confirm X fails", not "confirm X is covered".

## Critical Implementation Details

**Ordering constraint between Phase 1 and Phase 2.** Phase 1 replaces the mocked `parser` in the
assertions that concern response content, and Phase 2's `Retry-After` rule changes how many POSTs a
failing model produces. Doing Phase 2 first would require rewriting Phase 1's POST-count expectations
twice. Phase 1 must land first.

**The 402 correction interacts with the fallback-chain tests.** Making 402 fatal means the existing
`unauthorized401_…` shape (`OpenRouterAnalysisServiceTest.java:153-159`) becomes the template for a
second case, and any test that previously expected 402 to walk the chain must be re-expressed as
"one POST, then a distinguishable failure" — the user-visible outcome, per the charter's anti-pattern
note, not the count alone.

**`MarketPriceContext` is consumed by the frontend's `analysis.models.ts`.** Adding fields is
backward-compatible for a TypeScript interface reading JSON, so Phase 6 will not break the deployed
frontend. Note separately that `analysis.models.ts:62` already declares `fetchedAt: string`
non-nullable while `MISSING_INPUTS` sends null — a pre-existing mismatch to record for Phase 3, not
to fix here.

---

## Phase 1: LLM failure paths give distinguishable outcomes

### Overview

Close the hollow-200 route and the four generic-500 routes, then make the three 502 causes tell
themselves apart — and put the first controller-boundary failure tests in the repo.

### Changes Required:

#### 1. Minimal-spine validation

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java`

**Intent**: A parse must fail when the response cannot mean anything, rather than succeeding with
every leaf null. Extend `validateRequired` past the six container checks to a named minimal spine,
and stop the primitive-`int` coercion that turns a missing score into a perfect one.

**Contract**: `validateRequired` additionally throws `LlmResponseSchemaException` (already mapped to
its own 502 at `GlobalExceptionHandler.java:48-54`, carrying the offending field path in `messages`)
when any of these is absent: the four `scores` values, `verdict.code`, and `extracted.make` /
`extracted.model`. `ScoresDto`'s four fields become boxed `Integer` so absence is representable and
distinguishable from zero. Everything else stays nullable — a free-tier model omitting VIN must
still produce an analysis. The oracle is `CLAUDE.md`'s locked output schema plus
`context/changes/llm-analysis-wiring/plan.md` § "Locked output schema", i.e. source (4).

#### 2. Provider-quirk routes stop becoming generic 500s

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java`

**Intent**: A null `message`, a null `content`, a non-String `content`, and an unrecognised enum
value are all malformed *provider responses*, so each must surface as the schema-failure 502 rather
than the catch-all 500 "Błąd serwera". Today the catches only cover `IllegalArgumentException`.

**Contract**: the response-shape extraction path raises `ResponseShapeException` (already classified
FATAL at `:217-219`, so no behaviour change to the fallback walk) for all four cases; the existing
`catch (IllegalArgumentException)` blocks at `:97,109,124` widen to cover the enum and cast routes.
No new exception type.

#### 3. Three distinct 502 messages

**Files**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/LlmCallException.java`,
`backend/src/main/java/com/example/autoskaner_ai/common/GlobalExceptionHandler.java`

**Intent**: A rejected API key, an unusable provider response, and a fully exhausted fallback chain
are three different situations for the user and currently render one string. Distinguish them inside
the locked four-field envelope — no new fields, per `CLAUDE.md`'s API error shape.

**Contract**: `LlmCallException` carries a cause kind (rejected credentials / unusable provider
response / all candidates exhausted); `GlobalExceptionHandler` maps each to its own Polish `error`
string, keeping `status: 502` and the `{status, error, messages, timestamp}` shape unchanged. The
existing "Błąd usługi LLM" stays as the default for any cause not among the three.

#### 4. Controller-boundary failure tests

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/LlmFailureReachesTheClientTest.java`

**Intent**: Name the phase's actual deliverable — what the client receives in each failure branch.
Currently every LLM failure test stops at the service, so nothing proves the mapping.

**Contract**: a new behaviour-named integration test (§6.2 naming rule), stubbing the OpenRouter
socket via `MockRestServiceServer.bindTo` and asserting the HTTP response body per branch: a
schema-failing 200, a `{"choices":[]}` 200, a 401, and an exhausted chain. Asserts the raw body
string rather than `jsonPath` where the point is that two branches *differ* — §6.2's second gotcha
means `jsonPath` cannot prove a key is present-and-null. Ends with `server.verify()`.

#### 5. De-tautologise the service tests

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisServiceTest.java`

**Intent**: `parser` is mocked in every case, so `isEqualTo(expected)` asserts the stub returned the
stub. Assertions about response *content* need the real parser; and `AnalysisMeta.model` — the field
that records which model actually answered — is never checked end to end because `dummyResult()`
returns the primary regardless.

**Contract**: cases whose subject is the returned content construct the real
`AnalysisResponseParser`; cases whose subject is the call sequence may keep the mock. One case
asserts that after the primary fails and a fallback answers, `AnalysisMeta.model` names the
**fallback**, not the configured primary.

### Success Criteria:

#### Automated Verification:

- Suite passes: `cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test`
- Mutation: reverting the minimal-spine check makes an all-null-leaves 200 return 200 again — confirm a test fails
- Mutation: un-boxing `ScoresDto` back to primitive `int` — confirm a test fails on a missing score reading as 0
- Mutation: narrowing the widened catches back to `IllegalArgumentException` only — confirm a test fails with a 500 where a 502 is expected
- Mutation: collapsing the three 502 `error` strings back to one — confirm a test fails
- Mutation: making `dummyResult()` return the primary model name — confirm the `AnalysisMeta.model` test fails

#### Manual Verification:

- The three Polish 502 strings read correctly to a Polish speaker and each says something a user can act on
- No test in the file still asserts a POST count as its only assertion about a failure branch

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding.

---

## Phase 2: Retry, fallback, and the deadline

### Overview

Make the two-axis retry contract true at its boundary conditions, correct two misrouted statuses,
and reach the deadline-skip branch that no test has ever executed.

### Changes Required:

#### 1. A wait we cannot honour skips the model

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisService.java`

**Intent**: OpenRouter answers a saturated pool with `Retry-After: 60`; we cap at 6 s and retry into
the same saturation, and at the deadline edge the clamped wait reaches zero and the retry fires
immediately — the exact 2026-08-26 regression. A wait that will not fit the remaining budget means
this model is unusable *now*, which is the next-model axis, not the retry axis.

**Contract**: `retryWait` reports the provider's requested wait; when that wait does not fit inside
the remaining deadline the disposition becomes NEXT_MODEL instead of RETRY. `sleepQuietly` no longer
returns `true` for a zero or negative wait. The `MAX_RETRY_WAIT` cap stays for the case where the
requested wait *does* fit. An HTTP-date `Retry-After` is parsed rather than silently becoming
`DEFAULT_RETRY_WAIT` — otherwise a CDN's 60 s date reads as 1 s and defeats the rule above.

#### 2. 408 transient, 402 fatal

**File**: same

**Intent**: Both currently fall into the catch-all at `:230-231` and are treated as permanent for
this model. A 408 is a timeout — transient by definition. A 402 is insufficient credits, which
rejects every model, so walking the chain multiplies latency before the same error.

**Contract**: `dispositionOf` classifies 408 as RETRY alongside 429/5xx, and 402 as FATAL alongside
401/403 (surfacing as the rejected-credentials 502 string from Phase 1). The remaining catch-all
keeps NEXT_MODEL and gains a test naming it as deliberate. Oracle: OpenRouter's published status
semantics, verified 2026-09-03.

#### 3. Reach the deadline-skip branch

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/llm/OpenRouterAnalysisServiceTest.java`

**Intent**: `:132-134` and its "fallback budget exhausted" log have never executed. The branch is
reachable at zero wall-clock cost because `deadlineSeconds` is a constructor parameter that the test
helper hardcodes to 70.

**Contract**: the helper accepts a `deadlineSeconds`; a case constructs the service with `0` and
asserts the primary is still attempted (the `:113` check is guarded by `lastFailure != null`) while
no fallback is, and that the failure the client sees is the exhausted-chain 502 from Phase 1.

#### 4. Assert the timeout budget against the NFR

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/RequestTimeoutBudgetTest.java`

**Intent**: `prd.md:98` bounds an analysis at 30 s; the configured socket timeouts sum to ≈341 s and
nothing notices. Make the arithmetic a test so a future timeout bump fails the build instead of
silently widening the gap.

**Contract**: a unit test reading the configured connect/read timeouts and call multiplicities from
the configuration classes, asserting the documented per-stage figures and the total, with the 30 s
NFR quoted in a comment as the oracle (source 4) and the current ≈341 s total asserted as a
*documented* value that the test names as exceeding the NFR. The test's purpose is to fail when the
numbers change, not to claim the budget is met — `AnalysisController` enforces no deadline, and this
phase does not add one.

### Success Criteria:

#### Automated Verification:

- Suite passes with the command above
- Mutation: restoring `sleepQuietly`'s zero-wait `true` — confirm a test fails
- Mutation: removing the "wait exceeds remaining deadline ⇒ NEXT_MODEL" rule — confirm a test fails
- Mutation: dropping HTTP-date parsing back to `DEFAULT_RETRY_WAIT` — confirm a test fails
- Mutation: moving 408 or 402 back into the catch-all — confirm a test fails in each case
- Mutation: raising the test's `deadlineSeconds` from 0 to 70 — confirm the skip-branch test fails
- Mutation: bumping any configured read timeout by 1 s — confirm `RequestTimeoutBudgetTest` fails

#### Manual Verification:

- No new test asserts a retry count as its only assertion — each names a user-visible outcome
- The `RequestTimeoutBudgetTest` comment states plainly that the total exceeds the NFR and why this
  phase does not fix it

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 3: A finished analysis survives its enrichment

### Overview

An uncaught throw on the enrichment path currently discards a completed ~16 s analysis. Close that
and prove S-05's stated invariant at the controller boundary.

### Changes Required:

#### 1. Guard the enrichment calls

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java`

**Intent**: `buildResponse` has no try/catch. CEPiK degrades safely inside its own service, but
`slugMapper.makeSlug` (`:48`) and `MarketPriceStatistics.of` (`:78`) are uncaught, so either throwing
turns a successful analysis into a 500. `archive/2026-06-02-market-price-context/plan-brief.md:69`
states the invariant this violates: the endpoint "always returns a `marketPriceContext` field —
never absent, never an uncaught exception."

**Contract**: each enrichment call is guarded so a throw degrades that enrichment to its
failure status (`FETCH_FAILED` for market price, matching `MarketPriceFetchService.java:156-158`)
and logs at WARN, while the analysis is returned. The guard covers the enrichment calls only — a
failure in the LLM step must still surface as the Phase 1 error, not a silently empty analysis.

#### 2. Prove the invariant at the boundary

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisSurvivesEnrichmentFailureTest.java`

**Intent**: Nothing today proves that a market-price failure leaves the analysis intact.

**Contract**: an integration test where the market-price edge throws (unknown slug, and a stubbed
socket returning an unparseable body — serve `{}` not an empty body, per §6.2's fourth gotcha), and
the response is 200 with the analysis populated and `marketPriceContext` present and non-null with a
failure status. Asserts the raw body for the present-and-null distinction.

### Success Criteria:

#### Automated Verification:

- Suite passes with the command above
- Mutation: removing the guard around `slugMapper.makeSlug` — confirm the test fails with a 500
- Mutation: removing the guard around `MarketPriceStatistics.of` — confirm the test fails
- Mutation: making the guard return a null `marketPriceContext` instead of a failure status — confirm the test fails

#### Manual Verification:

- The WARN log line names which enrichment degraded and why, so a production occurrence is diagnosable
- An LLM failure still produces the Phase 1 502, not a 200 with an empty analysis

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 4: Registry session — cookies and XSRF

### Overview

Close the carried-forward gaps from Phase 1, and with them a production defect: one shared mutable
builder means cookies cross lookups and two concurrent analyses share a cookie jar.

### Changes Required:

#### 1. A builder per lookup

**Files**: `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java`,
`backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java`

**Intent**: `HistoriaPojazduConfig.java:13-14` exposes one `RestClient.Builder` bean and every
session mutates it via `defaultHeader`, so lookup *N*'s cookies are on the builder for lookup
*N+1*'s bootstrap GET and concurrent lookups race. `CLAUDE.md` already notes both enrichments run on
the request thread, so this is reachable under ordinary load.

**Contract**: each session derives its own builder from the shared bean with
`RestClient.Builder#clone()` (verified present in `spring-web` 7.0.7) and mutates only the clone. The
shared bean is never mutated. Because `MockRestServiceServer.bindTo` works by calling
`requestFactory(...)` on the builder and `clone()` copies the request factory, a clone taken after
`bindTo` still routes to the mock — §6.2's seam is unaffected, and the existing
`CepikDamageReachesTheResponseTest` passing unchanged is the check on that claim.

#### 2. Assert the cookie header directly

**File**: `backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSessionTest.java`

**Intent**: The cookie merge is exercised only incidentally and **no test anywhere asserts
`header("Cookie", …)`**. A broken merge yields `LOOKUP_FAILED`, the adjuster's FOUND-only gate skips,
and the 2026-08-26 `risk: 88`-with-a-szkoda-istotna failure mode returns.

**Contract**: cases using `MockRestRequestMatchers.header` / `headerList` / `headerDoesNotExist` to
assert what the *next* request carries after a `Set-Cookie`: that a second cookie is added, that a
re-issued same-name cookie appears once (documenting the observed replace-not-append semantics), that
attributes are not forwarded, and that no `Set-Cookie` at all means no `Cookie` header is sent
rather than an empty one. Plus a case proving a cookie set during one lookup is absent from the next
lookup's bootstrap GET — the regression test for change 1. Note in the file that a failed matcher
throws `AssertionError`, which escapes the session's `catch (Exception e)` and so does surface.

#### 3. XSRF, and the false Javadoc

**Files**: same test file; `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduSession.java`

**Intent**: `HistoriaPojazduSessionTest.java:22-23` claims cookie merging is asserted in
`CepikDamageReachesTheResponseTest`. It is not — XSRF is (`:333`). A false claim about coverage is
worse than no claim. Separately, `extractXsrfToken` returns null with no log, so a handshake change
degrades invisibly.

**Contract**: correct the Javadoc to state what is actually asserted where; add a dedicated case for
XSRF extraction, including the missing-token path — recording the seam caveat that a null header
stays literally `null` at the mock seam while production coerces it to empty. Add a WARN log when the
token cannot be extracted. Also cover the uncovered null-body version branch at `:111-113`.

### Success Criteria:

#### Automated Verification:

- Suite passes with the command above, including `CepikDamageReachesTheResponseTest` unchanged
- Mutation: reverting to the shared builder — confirm the cross-lookup-leak test fails
- Mutation: changing `extractCookies`' `removeIf` to a plain `add` — confirm a test fails
- Mutation: forwarding cookie attributes instead of `name=value` — confirm a test fails
- Mutation: dropping the `X-Xsrf-Token` header — confirm a test fails
- Mutation: forcing the version regex to miss — confirm the fallback-version test still passes and the null-body test fails when its branch is removed

#### Manual Verification:

- The corrected Javadoc names a real assertion at a real location
- The clone change does not alter the number or order of requests any existing test expects

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 5: Listing-text gaming

### Overview

Risk #6's one deterministic invariant, plus the adversarial cases the repo has none of.

### Changes Required:

#### 1. Enforce the missing-declaration flag

**File**: `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java`

**Intent**: `AnalysisPrompt.java:16` requires the model to add `NO_ACCIDENT_DECLARATION` when
`accidentClaim` is null, and `:92-103` demonstrates it — but nothing checks it, so a listing that
persuades the model to omit the flag turns an unknown history into a silent one. This is the
cheapest deterministic proxy for Risk #6 and, unlike `CepikRiskAdjuster`, it holds when CEPiK
returns `MISSING_INPUTS` — which `CLAUDE.md` says is the normal outcome for a URL-only Otomoto
listing.

**Contract**: after parsing, when `extracted.accidentClaim` is null and `riskFlags` contains no
`NO_ACCIDENT_DECLARATION` entry, the flag is appended with the exact shape
`AnalysisPrompt.java:16` specifies (`severity: MEDIUM`, the same Polish description). Idempotent: a
model that did emit it is unchanged. The oracle is the prompt's own stated guardrail — source (4),
not the parser.

#### 2. Adversarial cases

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/ListingClaimsCannotMoveTheFloorTest.java`

**Intent**: There are zero adversarial tests repo-wide. Prove that where the deterministic floor
exists, listing-supplied text cannot move it — and that the new invariant holds regardless of what
the model returned.

**Contract**: a behaviour-named test covering — a listing asserting `bezwypadkowy` against a
registry damage still forces `HIGH_RISK_SKIP`; a listing carrying model-directed instructions cannot
change the floored verdict code; the two untested phrases (`"nie uczestniczy"`, `"brak szkod"`) match
as `CepikRiskAdjuster.java:37-38` intends; and a model response omitting
`NO_ACCIDENT_DECLARATION` on a null `accidentClaim` gains it. Uses derived fixtures per §6.5 for the
registry side. Does **not** assert any model wording — no LLM call is made.

#### 3. Remove the last tautology

**File**: `backend/src/test/java/com/example/autoskaner_ai/analysis/CepikRiskAdjusterTest.java`

**Intent**: `:147-155` hardcodes 65 with the comment `// (90 + 75 + 35 + 60) / 4`, embedding the cap
literal 35 in its own expectation — the mean-of-four formula copy §6.7 says was removed. It is the
one Phase 1 missed.

**Contract**: per §6.5's rule for values with no oracle outside the implementation, pin the cap
magnitude in exactly one test that says so in a comment, and assert the falsifiable property — the
ordering of the caps — separately. `CepikRiskAdjusterTest`'s existing split is the reference for how.

### Success Criteria:

#### Automated Verification:

- Suite passes with the command above
- Mutation: removing the `NO_ACCIDENT_DECLARATION` enforcement — confirm a test fails
- Mutation: making the enforcement non-idempotent (always appending) — confirm a test fails on a duplicate flag
- Mutation: deleting `"nie uczestniczy"` from the phrase list — confirm a test fails
- Mutation: changing a cap magnitude — confirm exactly one test fails, and the ordering test still passes
- Mutation: changing the cap *ordering* — confirm the ordering test fails

#### Manual Verification:

- No test in the new file depends on model wording or makes a network call
- The known gaps (negation-awareness, `capRisk`'s short-circuit) are named in comments so a reader
  does not mistake their absence for coverage

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 6: Market price — thin and dispersed (contract change)

### Overview

Ground the price regex in a captured payload, and put the thin/dispersed judgement on the server
where this environment can prove it. This is the only phase that changes the response shape.

### Changes Required:

#### 1. Capture an Otomoto payload

**Files**: `backend/src/test/resources/market/otomoto-search-results.md`,
`backend/src/test/resources/market/README.md`

**Intent**: There is no market fixture directory. The markdown the price regex reads is composed
inline, and `market-price-context/reviews/impl-review.md:29` records a real `\n` regex bug that
text-block normalisation masked — the 2026-08-26 fixture failure transposed onto Risk #5.

**Contract**: a verbatim capture of Jina-rendered Otomoto search markdown, obtained through the live
production backend (the reader host is proxy-blocked on this machine), stored byte-for-byte
including line endings. A README mirroring `src/test/resources/cepik/README.md`'s conventions:
verbatim vs `*-derived`, `_provenance` on derived files, and the vocabulary rule stated explicitly —
third-party markdown must be captured; the internal `List<Integer>` `MarketPriceStatistics` consumes
may be composed, because we own that shape. Review the capture for anything identifying before
committing; this repo is public.

#### 2. Regex tests over the capture

**File**: `backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceStatisticsTest.java`

**Intent**: `PRICE_PATTERN` is the layer where the real bug lived, and it has never seen real bytes.

**Contract**: cases extracting prices from the captured file via `ClassPathResource`, asserting the
count and the extremes from the file's own bytes (source 1). A derived fixture with the line endings
changed proves the `\r?\n` branch. Existing hand-derived statistics cases stay — they are correctly
sourced.

#### 3. A sample-quality judgement on the response

**Files**: `backend/src/main/java/com/example/autoskaner_ai/analysis/MarketPriceContext.java`,
`backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceStatistics.java`,
`backend/src/main/java/com/example/autoskaner_ai/market/MarketPriceFetchService.java`

**Intent**: `discardedCount` is computed then dropped at the mapping, so "too dispersed" has no
observable; and `MIN_SAMPLE_TO_KEEP = 3` collides with the UI's `sampleSize < 3`, leaving a sample of
exactly 3 — the most contaminated the pipeline can emit — reported untrimmed *and* uncaveated. Put
the judgement on the server: one threshold, testable here, and Phase 3 renders it.

**Contract**: `MarketPriceContext` gains a sample-quality field (sufficient / thin / dispersed) and
the discarded count. Additive only, so the deployed frontend's `analysis.models.ts` keeps
deserialising. The quality decision lives in `MarketPriceStatistics` next to the thresholds that
inform it: a sample reported untrimmed because the band would have left fewer than
`MIN_SAMPLE_TO_KEEP` is **not** `sufficient`, which is what closes the exactly-3 hole.
`MarketPriceFetchService` stops dropping the count at `:87-88`. `MockMarketPriceEnrichmentService`
gains the new fields so the mock profile stays deserialisable.

#### 4. Boundary tests, hand-derived

**File**: `backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceStatisticsTest.java`

**Intent**: The charter forbids re-deriving the expected median with the production formula. The
median is round-half-up `(a+b+1)/2` (`:108`), not a plain average, so hand arithmetic must round the
same way or the oracle is wrong for the wrong reason.

**Contract**: cases at each boundary with the arithmetic written out in a comment (source 3): the
exactly-3 untrimmed sample reports `thin` or `dispersed`, never `sufficient`; a sample whose band
drops order-of-magnitude junk reports the discarded count; an even-sized sample's median is checked
against hand-computed round-half-up; the IQR fence stays skipped when IQR is 0 and gated on
post-band size.

### Success Criteria:

#### Automated Verification:

- Suite passes with the command above
- Mutation: normalising the captured fixture's line endings to `\n` — confirm the `\r?\n` test fails
- Mutation: reverting `MarketPriceContext` to report only `sampleSize` — confirm compilation or a test fails
- Mutation: reporting the exactly-3 untrimmed sample as `sufficient` — confirm a test fails
- Mutation: replacing round-half-up with a plain average — confirm a test fails
- Mutation: dropping `discardedCount` at the mapping again — confirm a test fails
- `CepikResultSerialisationTest`'s booted-context counterpart confirms the new fields serialise under the application's own Jackson configuration, not just `standaloneSetup`'s

#### Manual Verification:

- The captured fixture carries nothing identifying, and its README states its provenance
- A live production analysis still deserialises in the deployed frontend after the additive change
- The quality thresholds read sensibly against a real captured sample, not just synthetic ones

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 7: Backport to the test plan

### Overview

Make `context/foundation/test-plan.md` true. Three corrections, two fill-ins, one status flip.

### Changes Required:

#### 1. §4's stale grounding-tools note

**File**: `context/foundation/test-plan.md`

**Intent**: Lines 147–148 record Docs and Search as "not available in current session (checked
2026-08-27)". Both are available: Context7 and Exa were exercised on 2026-09-03, and Exa produced
this phase's independent OpenRouter status-semantics oracle.

**Contract**: rewrite both lines to name the available tool and what it was used for, re-dated
`checked: 2026-09-03`. Lines 149–150 (Runtime/browser, Provider/platform) are unverified this phase
and keep their 2026-08-27 dates.

#### 2. §2 Risk #5's unobserved figure

**File**: same

**Intent**: Line 53 cites "a live run returned `min=22900` against `median=79900`". That pairing
appears in no artifact: `roadmap.md:187` records `min=39900` / `median=82900`, and `min=22900`
separately. `median=79900` appears nowhere.

**Contract**: replace the Source cell's figures with the observed ones, keeping the "the trim is
statistical, not semantic" quote. Risk #5's scoring does not change.

#### 3. §6.4's TBD

**File**: same

**Intent**: Line 207 reads "Fuller pattern for the *failure* branches (deadlines, retry vs fallback,
degraded content): TBD — see §3 Phase 2." This phase is that pattern.

**Contract**: fill in the failure-branch pattern: assert the user-visible outcome not the retry
count; distinguish the causes at the boundary rather than trusting one error string; serve `{}` for
an unreadable payload; reach a deadline branch by injecting the deadline rather than waiting.
Add the two new seam caveats to §6.2 — a null header stays literally `null` at the mock seam, and a
failed matcher's `AssertionError` escapes a `catch (Exception)`.

#### 4. §6.7 Phase 2 notes, and §3

**File**: same

**Intent**: Record what the phase proved, how, and what it deliberately left open — Phase 1's entry
is the model.

**Contract**: a §6.7 Phase 2 subsection with the new test count, the defects closed rather than
pinned, the mutation method, and the known gaps named explicitly (`capRisk`'s short-circuit and the
test that asserts it; `verdict.label` unvalidated; negation-awareness; the unbounded fetched body;
the ≈341 s worst case). §3's Phase 2 row Status → `complete` with the archive path. Add those five
items to the **Carried into Phase 3** list. §8 freshness ledger re-dated.

### Success Criteria:

#### Automated Verification:

- Suite still passes with the command above (documentation-only phase, so this is a regression check)
- No occurrence of "not available in current session" remains for Context7 or Exa in §4
- No occurrence of `79900` remains in the file

#### Manual Verification:

- §6.7's Phase 2 entry lets a future contributor tell what was proven from what was merely left alone
- The Carried-into-Phase-3 list is actionable — each item names a file and a consequence
- §3's status flip matches what is actually on disk

**Implementation Note**: This is the final phase; after it passes, the change is ready for
`/10x-archive`.

---

## Testing Strategy

### Unit Tests:

- `AnalysisResponseParser` — the minimal spine, boxed scores, the `NO_ACCIDENT_DECLARATION`
  invariant and its idempotence
- `OpenRouterAnalysisService` — the classification tree including 408/402 and the deliberate
  catch-all, `Retry-After` arithmetic against the remaining deadline, the deadline-skip branch at
  `deadlineSeconds=0`
- `MarketPriceStatistics` — regex over captured bytes, the sample-quality decision at each boundary,
  round-half-up median from hand arithmetic
- `CepikRiskAdjuster` — the caps' ordering separated from their magnitudes
- `RequestTimeoutBudgetTest` — configured-timeout arithmetic against `prd.md:98`

### Integration Tests:

- `LlmFailureReachesTheClientTest` — four failure branches, four distinguishable HTTP bodies
- `AnalysisSurvivesEnrichmentFailureTest` — a market-price throw leaves a 200 with the analysis and a
  non-null `marketPriceContext`
- `ListingClaimsCannotMoveTheFloorTest` — listing claims against registry facts, no LLM call
- `HistoriaPojazduSessionTest` — cookie header contents, cross-lookup isolation, XSRF, null-body
  version branch
- `CepikDamageReachesTheResponseTest` — unchanged, as the check that the builder clone preserves the
  seam

### Manual Testing Steps:

1. Capture the Otomoto markdown through the live production backend, then read the file for anything
   identifying before committing.
2. After Phase 6, run a real analysis against production and confirm the deployed frontend still
   renders the market panel with the additive fields present.
3. Read the three new Polish 502 strings aloud — each must tell a non-technical user something
   different and actionable.
4. Confirm every mutation listed under Automated Verification was actually applied and reverted; a
   criterion that was never run is the failure §6.7 warns about.

## Performance Considerations

Phase 2's `Retry-After` rule *reduces* worst-case latency: a wait that would not fit the budget now
skips to the next model instead of sleeping and retrying into the same saturation. Making 402 fatal
removes up to five pointless calls. Nothing in this phase increases per-request work; Phase 6 adds
two integer fields to a response already carrying seven. The ≈341 s worst case is unchanged — see
"What We're NOT Doing".

## Migration Notes

Phase 6's `MarketPriceContext` change is additive, so the deployed Cloudflare Pages frontend keeps
deserialising without a coordinated release. `MockMarketPriceEnrichmentService` must gain the new
fields in the same commit or the `mock` profile stops matching the record. No database, no stored
data, nothing to migrate.

## References

- Research: `context/changes/testing-availability-failure-paths/research.md`
- Charter: `context/changes/testing-availability-failure-paths/change.md`
- Phase charter and conventions: `context/foundation/test-plan.md` §1, §3, §6.2, §6.4, §6.5, §6.7
- Phase 1 precedent: `context/archive/2026-08-27-testing-enrichment-honesty/plan.md`,
  `.../research.md`, `.../plan-brief.md`
- Fixture convention to mirror: `backend/src/test/resources/cepik/README.md`
- Reference tests: `CepikDamageReachesTheResponseTest` (stubbed socket, whole stack real),
  `MarketPriceStatisticsTest` (pure function, independent oracle),
  `RegistryFactsReachTheScoreTest` (derived fixtures with a control)
- The S-05 invariant this phase proves: `context/archive/2026-06-02-market-price-context/plan-brief.md:69`
- The incident behind Phase 2: `context/foundation/roadmap.md:76`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: LLM failure paths give distinguishable outcomes

#### Automated

- [x] 1.1 Suite passes — 512f555
- [x] 1.2 Mutation: revert the minimal-spine check — an all-null-leaves 200 returns 200 again — 512f555
- [x] 1.3 Mutation: un-box `ScoresDto` back to primitive `int` — 512f555
- [x] 1.4 Mutation: narrow the widened catches back to `IllegalArgumentException` only — 512f555
- [x] 1.5 Mutation: collapse the three 502 `error` strings back to one — 512f555
- [x] 1.6 Mutation: make `dummyResult()` return the primary model name — 512f555

#### Manual

- [x] 1.7 The three Polish 502 strings read correctly and each is actionable — 512f555
- [x] 1.8 No test asserts a POST count as its only assertion about a failure branch — 512f555

### Phase 2: Retry, fallback, and the deadline

#### Automated

- [x] 2.1 Suite passes
- [x] 2.2 Mutation: restore `sleepQuietly`'s zero-wait `true`
- [x] 2.3 Mutation: remove the "wait exceeds remaining deadline ⇒ NEXT_MODEL" rule
- [x] 2.4 Mutation: drop HTTP-date parsing back to `DEFAULT_RETRY_WAIT`
- [x] 2.5 Mutation: move 408 back into the catch-all
- [x] 2.6 Mutation: move 402 back into the catch-all
- [x] 2.7 Mutation: raise the test's `deadlineSeconds` from 0 to 70
- [x] 2.8 Mutation: bump a configured read timeout by 1 s — `RequestTimeoutBudgetTest` fails

#### Manual

- [x] 2.9 No new test asserts a retry count as its only assertion
- [x] 2.10 `RequestTimeoutBudgetTest`'s comment states the total exceeds the NFR and why this phase does not fix it

### Phase 3: A finished analysis survives its enrichment

#### Automated

- [ ] 3.1 Suite passes
- [ ] 3.2 Mutation: remove the guard around `slugMapper.makeSlug`
- [ ] 3.3 Mutation: remove the guard around `MarketPriceStatistics.of`
- [ ] 3.4 Mutation: make the guard return a null `marketPriceContext` instead of a failure status

#### Manual

- [ ] 3.5 The WARN log names which enrichment degraded and why
- [ ] 3.6 An LLM failure still produces the Phase 1 502, not a 200 with an empty analysis

### Phase 4: Registry session — cookies and XSRF

#### Automated

- [ ] 4.1 Suite passes, including `CepikDamageReachesTheResponseTest` unchanged
- [ ] 4.2 Mutation: revert to the shared builder — the cross-lookup-leak test fails
- [ ] 4.3 Mutation: change `extractCookies`' `removeIf` to a plain `add`
- [ ] 4.4 Mutation: forward cookie attributes instead of `name=value`
- [ ] 4.5 Mutation: drop the `X-Xsrf-Token` header
- [ ] 4.6 Mutation: remove the null-body version branch

#### Manual

- [ ] 4.7 The corrected Javadoc names a real assertion at a real location
- [ ] 4.8 The clone change does not alter the number or order of requests any existing test expects

### Phase 5: Listing-text gaming

#### Automated

- [ ] 5.1 Suite passes
- [ ] 5.2 Mutation: remove the `NO_ACCIDENT_DECLARATION` enforcement
- [ ] 5.3 Mutation: make the enforcement non-idempotent
- [ ] 5.4 Mutation: delete `"nie uczestniczy"` from the phrase list
- [ ] 5.5 Mutation: change a cap magnitude — exactly one test fails, ordering test still passes
- [ ] 5.6 Mutation: change the cap ordering — the ordering test fails

#### Manual

- [ ] 5.7 No test depends on model wording or makes a network call
- [ ] 5.8 The known gaps are named in comments so their absence is not mistaken for coverage

### Phase 6: Market price — thin and dispersed

#### Automated

- [ ] 6.1 Suite passes
- [ ] 6.2 Mutation: normalise the captured fixture's line endings to `\n`
- [ ] 6.3 Mutation: revert `MarketPriceContext` to report only `sampleSize`
- [ ] 6.4 Mutation: report the exactly-3 untrimmed sample as `sufficient`
- [ ] 6.5 Mutation: replace round-half-up with a plain average
- [ ] 6.6 Mutation: drop `discardedCount` at the mapping again
- [ ] 6.7 New fields serialise under the application's own Jackson configuration, not just `standaloneSetup`

#### Manual

- [ ] 6.8 The captured fixture carries nothing identifying and its README states provenance
- [ ] 6.9 A live production analysis still deserialises in the deployed frontend
- [ ] 6.10 The quality thresholds read sensibly against the real captured sample

### Phase 7: Backport to the test plan

#### Automated

- [ ] 7.1 Suite still passes (regression check)
- [ ] 7.2 No "not available in current session" remains for Context7 or Exa in §4
- [ ] 7.3 No occurrence of `79900` remains in the file

#### Manual

- [ ] 7.4 §6.7's Phase 2 entry distinguishes what was proven from what was left alone
- [ ] 7.5 The Carried-into-Phase-3 list names a file and a consequence per item
- [ ] 7.6 §3's status flip matches what is on disk
