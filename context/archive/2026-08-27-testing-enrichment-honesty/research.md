---
date: 2026-08-29T13:44:51Z
researcher: Przemyslaw Przeworski
git_commit: b8d89e092d92886ce92a67e4857ee317a5d21acf
branch: main
repository: AutoSkanerAI
topic: "Test rollout Phase 1 — enrichment honesty: where a registry damage can be lost between payload, response, and verdict, and where each assertion's independent oracle comes from"
tags: [research, codebase, cepik, historia-pojazdu, analysis, risk-adjuster, testing, oracles, fixtures]
status: complete
last_updated: 2026-08-29
last_updated_by: Przemyslaw Przeworski
---

# Research: Enrichment honesty — payload → response → verdict, and the oracle problem

**Date**: 2026-08-29T13:44:51Z
**Researcher**: Przemyslaw Przeworski
**Git Commit**: `b8d89e092d92886ce92a67e4857ee317a5d21acf`
**Branch**: `main`
**Repository**: AutoSkanerAI

## Research Question

Ground rollout Phase 1 of `context/foundation/test-plan.md` ("Enrichment honesty", Risks #2 and #3) in code:

- **Risk #2** — that a captured registry payload carrying a `szkoda istotna` surfaces as a reported damage all the way out to the API response. Which field names appear in a real captured payload; where a parse miss turns into an empty list rather than an error.
- **Risk #3** — that a registry damage caps the risk score and downgrades the verdict regardless of what the listing claimed. Ordering of scoring against enrichment; which statuses may adjust; that the overall score is never raised.
- For both: **where does each assertion's independent oracle come from**, given that expected values must not be lifted out of the implementation under test.

## Summary

Six findings, in descending order of consequence for the plan.

1. **Risk #2's failure surface has moved, and the test plan's §2 description of it is now one incident out of date.** The 2026-08-26 bug was *invented field names*; that is closed. Every field name and literal `HistoriaPojazduParser` reads is present in a committed verbatim capture, and `HistoriaPojazduParserTest` asserts the damage against the bytes of that capture. What survives is the same *false-clean shape* one layer up: [`HistoriaPojazduParser.parse`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L63) hardcodes `CepikStatus.FOUND` and has no other exit, and [`damagesFrom`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L120-L131) returns an **empty non-null** list when no event matched. A null, truncated, or vocabulary-shifted payload therefore still yields `FOUND` + `damageRecords: []`, which the adjuster skips and the UI renders as "registry reported nothing" — the exact prohibited inference, reached by a different route.

2. **The two halves of the incident are covered separately and never together.** The parser can read a capture; the controller can serialise a *hand-built* `CepikResult`. Nothing joins them. [`HistoriaPojazduService.lookup`'s `FOUND` path never executes in any test](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java#L44-L46) because `HistoriaPojazduServiceTest` mocks the parser away; swapping its two payload arguments would keep the whole repo green and produce null identity + null damages in production. `HistoriaPojazduSession` has **no test at all**. And across the entire test tree, `$.cepikResult.status` is the only `cepikResult` JSON node ever asserted — no test places a damage record in an HTTP response body.

3. **Risk #3 is the best-covered behaviour in the codebase and still has the weakest oracles.** 14 tests in `CepikRiskAdjusterTest` plus one true wiring test through MockMvc cover all five caps, both verdict floors, never-raise, the four no-op cases and flag ordering. But every cap literal is the implementation's own private constant read back — three times over for `25`. The suite is an excellent *change detector* and cannot judge *correctness*: a cap that is wrong by design (35 where the product wanted 20) is unfalsifiable by it. Three real gaps sit behind that: `overall` is unasserted at the API layer, the `Math.min(scores.overall(), overall)` never-raise guard is **unreachable by construction** in the current fixtures, and no test combines two independent registry findings.

4. **The oracle question has a clean answer, and it is not "CLAUDE.md".** Four genuinely independent oracles exist for Phase 1 — the bytes of the committed captures, the production incident observation (`risk: 88 / WORTH_CHECKING` for a car with a registered damage), hand-verifiable calendar arithmetic, and the product guardrail *absence ≠ clean*. `CLAUDE.md:139-144` states all five ceilings and both floors, but it was authored alongside the code by the same author, so it is a specification of record rather than an independent derivation. Full mapping in [§ Oracle sourcing](#oracle-sourcing--where-each-expected-value-legitimately-comes-from).

5. **Two test-plan facts are wrong and block nothing but must be corrected.** §4 says "HTTP mocking (backend) | none yet" and §6.2 says "the stubbing seam ... does not exist yet". `spring-test:7.0.7` ships `MockRestServiceServer`, it binds to `RestClient.Builder`, and three test classes already use it. The CEPiK edge is injectable the same way via [`HistoriaPojazduConfig`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduConfig.java#L13). The accurate statement is not "no seam" but "the seam exists and the CEPiK edge is the one place it was never used." This makes a genuine payload→response integration test cheap, and it partially unblocks Phase 2 early.

6. **Two candidate defects surfaced that are not in the §2 risk map.** `FOUND`-with-no-data-read (item 1 above), and an asymmetry in [`RealCepikEnrichmentService.PLATE_PATTERN`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/RealCepikEnrichmentService.java#L33) — it is applied to `plate.strip().toUpperCase()` without stripping *internal* whitespace, unlike the VIN path, so a user typing `WA 12345` silently gets `MISSING_INPUTS` and no lookup.

## Detailed Findings

### A. Registry payload → `CepikResult` (the parser and its fixtures)

**The fixtures are the strongest artefact in the suite, and they are real captures.** Two files, both under `backend/src/test/resources/cepik/`:

- `vehicle-data-found.json` — real envelope nesting `technicalData → basicData` / `detailedData`, matching what [`HistoriaPojazduParser.java:52`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L52) unwraps. The tell that it is captured, not composed: **36 fields in `detailedData` that no production code reads**, including `homologationVersion: "ZWE211L-DEXNBW(1F)"` (a genuine Toyota ZWE211 hybrid code) and `homologationCertificateNumber: "e6*2007/46*0316*05"`. Explicit `null`s for absent fields rather than omission. One documented edit: `identifyingFeature` scrubbed to the synthetic `NMTBZ3BE40R000000`, declared in the test's Javadoc.
- `timeline-data-found.json` — 14 events with `eventDate` / `eventName` / `eventType` / `eventDetails[{name,value,additionalInformation}]`. Unread siblings again betray a real capture: `reportGenerationDate: "2026-08-26"` (the incident date), `odometerReadings` carrying `odometerNumber: "FIRST"` / `error` / `errorMessage`, and registry prose the code ignores — `"(Uwaga. Dane o szkodach istotnych gromadzimy od 1 marca 2020 r.)"`, SKP station numbers `WZ/010` / `WND/014`. The ownership chain is internally consistent (świętokrzyskie → mazowieckie on the 2023-04-19 transfer, matching the top-level `registrationProvince`), which composed fixtures never are.

**A real damage is already committed.** `timeline-data-found.json:48-65`:

```json
{ "eventDate": "2023-02-07",
  "eventDetails": [ { "name": "Nazwa ubezpieczyciela", "value": "PZU" },
                    { "name": "Kategorie", "value": "Uszkodzenie elementów układu nośnego" } ],
  "eventName": "Powstanie szkody istotnej",
  "eventType": "szkoda-istotna" }
```

So Risk #2's damage-present case can be written from committed data today — and at the parser layer it already is: [`HistoriaPojazduParserTest:54-66`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParserTest.java#L54-L66) asserts date, description, insurer and the single category against those bytes. Coverage between the two fixtures is split (`registrationProvince` / `validOcInsurance` only in timeline; `make` / `model` / `registrationStatus` / `hasCurrentOCPolicy` only in vehicle-data), so both are load-bearing.

**What is not captured:** a clean-vehicle `FOUND` payload (the "clean" case exists only as a hand-composed map), a `NOT_FOUND` / `HIPO-0002` response body, a multi-event or multi-category damage, and any payload with `rolledBack: true` or `vehicleLost: true` — both flags are `false` in the capture, so the **theft and rollback caps have no captured-data path at all** and live only as hand-set `Boolean.TRUE` in the adjuster test.

**Where a parse miss becomes an empty list rather than an error** — the seam Risk #2 asked for, precisely located:

| Site | Behaviour | Consequence |
|---|---|---|
| [`parse` :63](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L63) | returns `CepikStatus.FOUND` unconditionally — no other exit | `parse(null, null, vin)` is a *found vehicle with no data* |
| `fetchVehicleData` / `fetchTimelineData` | `.body(Map.class)` yields `null` on a 204 or empty 200 | feeds the above without any error |
| [`damagesFrom` :120-131](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L120-L131) | empty **non-null** list when no event matched | indistinguishable from a genuinely clean car |
| [`:123`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java#L123) | `DAMAGE_EVENT_TYPE` matched with exact `.equals`, no trim, case-sensitive | inspection types use `startsWith` (:151) and detail labels `strip().toLowerCase().startsWith` (:199) — the damage check is the strictest of the three, on the highest-stakes field |
| `:96-97` | non-`Map` elements in `events` silently dropped | a shape change loses events, not the request |
| `readEvents` :90-94 | returns **null** for absent / non-list `events` | this one is correct — null propagates as unknown |

`readEvents` returning null is the right behaviour and is what makes `missingTimelineYieldsNullListsNotEmptyOnes` pass. The gap is narrow and exact: `events` present but *unmatched* → `[]`, and `events` absent because the fetch returned nothing → `FOUND` anyway.

### B. The join — session, service, and enrichment layers

This is where Phase 1's real coverage gap sits.

- **HTTP / session layer — nothing.** No `HistoriaPojazduSessionTest` exists. Untested: the API-version regex and its `FALLBACK_API_VERSION` branch (this is the literal that rotted from `1.0.17` to `1.1.0`), the cookie merge/dedupe, XSRF extraction, and the request body keys `registrationNumber` / `VINNumber` / `firstRegistrationDate`. A typo in any key produces 400 → `LOOKUP_FAILED` → "registry temporarily unavailable", suite fully green.
- **Service layer — the `FOUND` path is never executed.** [`HistoriaPojazduServiceTest`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/test/java/com/example/autoskaner_ai/cepik/HistoriaPojazduServiceTest.java#L13-L18) mocks the parser and subclasses the service to override the package-private `createSession()` seam. All five of its tests drive the session to throw and assert the classification branch. So [`lookup` :44-46](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduService.java#L44-L46) — fetch vehicle-data, fetch timeline-data, hand **both, in that order**, plus the vin, to `parse` — has no assertion, and `verify(session).close()` on the success path is unasserted too.
  - Useful safety property found here: the `catch` at :47-56 wraps `parser.parse`, so a parser *exception* degrades to `LOOKUP_FAILED` with null lists — safe. It is the non-throwing empty-list path that is dangerous.
  - `indicatesVehicleNotFound` string-sniffs the cause chain for the literal `"HIPO-0002"`; the tests author that string themselves, so the real coupling — that a live 404 wrapped by `HistoriaPojazduSession` actually carries the code — is asserted only in the live test.
- **Enrichment layer — no `FOUND`-with-data test.** `RealCepikEnrichmentServiceTest` only ever stubs `CepikResult.withoutData(...)`, so nothing checks that a data-carrying result passes through unmodified.

### C. `CepikResult` → API response JSON

[`CepikResult`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/analysis/CepikResult.java) is a 21-component record whose `withoutData(status, vin, lookupUrl)` factory nulls every field — the null-is-not-empty rule is structural, not conventional. There is **no Jackson customisation anywhere in `src/main`**, so nulls are emitted and `"damageRecords": null` is distinguishable on the wire from `[]`. The frontend depends on exactly that distinction.

Nothing tests it. A grep of the whole test tree for `cepikResult` returns one JSON assertion: `$.cepikResult.status` at [`AnalysisControllerTest:167`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java#L157-L168). No assertion on `$.cepikResult.damageRecords[0].date/insurer/categories`, on `$.cepikResult.events`, or on `damageRecords` serialising as `null` rather than absent or `[]`. On Jackson 3 with a 21-component record, that is a real unguarded surface — and it is the exact layer the UI reads.

Note also that [`MockCepikService`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/cepik/MockCepikService.java#L17) can never return `FOUND`, so an integration test cannot reach the damage path through the `mock` profile — a stub must be injected.

### D. Enrichment → verdict

Ordering is fixed in [`AnalysisController.buildResponse` :66-100](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisController.java#L66-L100):

```java
70  result = withExtracted(result, UserOverrides.apply(result.extracted(), request));
72  var cepikResult = cepikEnrichmentService.enrich(result.extracted());
73  var marketPriceContext = marketPriceEnrichmentService.enrich(result.extracted());
77  result = cepikRiskAdjuster.apply(result, cepikResult);
99  return new AnalysisResponse(fetchStatus, null, augmented, cepikResult, marketPriceContext);
```

Overrides → enrich → adjust, all synchronous on the request thread. The `url_failed` branch returns at :50 **before** `buildResponse`, so the adjuster never runs and `analysis` is null there. Confirmed by tracing every writer of `scores` / `verdict`: `AnalysisResponseParser:116,126`, `MockAiAnalysisService:72-73`, and `CepikRiskAdjuster:140,152` — the adjuster is the only writer outside the LLM parse, which is what makes Risk #3 a single-class unit concern.

[`CepikRiskAdjuster`](https://github.com/PrzemyslawPrzeworski/AutoSkanerAI/blob/b8d89e092d92886ce92a67e4857ee317a5d21acf/backend/src/main/java/com/example/autoskaner_ai/analysis/CepikRiskAdjuster.java) is a pure `@Component` with no fields and an implicit no-arg constructor (so it can be `new`ed in a standalone MockMvc setup, which the controller test already does). Structure worth knowing before planning tests:

- `:41` — `if (result == null || cepik == null || cepik.status() != CepikStatus.FOUND) return result;` — the FOUND-only gate.
- `:27-31` — the five ceilings: `CAP_VEHICLE_LOST 5`, `CAP_ODOMETER_ROLLBACK 20`, `CAP_CONTRADICTED_CLAIM 25`, `CAP_SIGNIFICANT_DAMAGE 35`, `CAP_NO_OC_POLICY 70`.
- `:65-66` — damages read from `cepik.damageRecords()`, guarded `!= null && !isEmpty()`. **This is the consumer of finding A's empty-list seam.**
- Rule 4 (`CEPIK_CONTRADICTS_LISTING`) is **nested inside** rule 3, so it is unreachable without a registry damage.
- `:92` — `if (added.isEmpty()) return result;` returns the *same instance*, which is why `isSameAs` assertions are valid and meaningful.
- `:98` — registry flags are **prepended** to the LLM's flags.
- `:134-136` — `capRisk` skips recomputing `overall` entirely when `risk <= cap`.
- `:138` — `overall = (risk + completeness + equipment + value) / 4`; `:140` — `Math.min(scores.overall(), overall)` never raises.
- `applyFloor` :144-153 discards the LLM's label and regenerates it via `labelFor` :171-177, but only when the code changes. `rank()` :156-162 is explicit, not `ordinal()`.
- `ACCIDENT_FREE_CLAIMS` :37-38 = `List.of("bezwypadkow","bezszkodow","nie uczestniczy","brak szkód","brak szkod")`, substring-matched on a Polish-locale lower-case.

**What Risk #3 already has:** all five caps, both floors and their asymmetry (damage alone ⇒ `NEEDS_MORE_INFO`; damage + false `bezwypadkowy` ⇒ `HIGH_RISK_SKIP`), caps-never-raise, clean / three non-FOUND statuses / null cepik / null damage-list no-ops via `isSameAs`, flag ordering, and one wiring test through HTTP with the real adjuster in the chain.

**What it lacks:**

- **`overall` is unasserted in the API response.** The controller test checks `scores.risk` only. With the fixture's `CategoryScores(83, 50, 75, 60, 67)` and a cap of 25, `overall` must fall 67 → 54. Nothing says so, and `overall` is what the UI leads with.
- **The never-raise-`overall` clause is dead to the suite.** The adjuster test builds its input as `new CategoryScores(90, 75, risk, 60, (90 + 75 + risk + 60) / 4)` — the production formula copied into the fixture. By construction the input `overall` is always the mean, and capping risk downward always lowers the mean, so `Math.min` can never fire. It fires exactly when the model returns an `overall` inconsistent with its own four categories — which is precisely what LLM output does.
- **No cap interaction.** Every multi-fact test sets one flag; only damage + contradiction co-occur. Rollback + no-OC, theft + damage, and the accumulation of several flags in one response are unverified.
- **No path from a registry payload to a verdict.** Risk #3 is only ever driven by a hand-built `CepikResult`, whose four damage values are re-typed by hand from the capture. Combined with finding B, there is no single test in the repo where a *captured registry payload* results in a downgraded verdict.
- `"nie uczestniczy"` has zero coverage, and no *un*listed phrasing is probed (`"bez wypadku"` with a space, `"nigdy nie uszkodzony"`), so the suite cannot reveal that the matcher is a narrow substring list.

### E. Oracle sourcing — where each expected value legitimately comes from

This is the answer to the Handoff B question, and the direct countermeasure to §2's anti-pattern for Risk #3 ("lifting the ceiling values out of the implementation").

**Four genuinely independent oracles are available:**

| Oracle | What it can ground | Strength |
|---|---|---|
| Bytes of the committed captures | every parser field value: `2023-02-07`, `Powstanie szkody istotnej`, `PZU`, `Uszkodzenie elementów układu nośnego`, `ownerCount == 2`, the 14-event count, the three inspection mileage stamps (including a **real duplicate** `2025-04-14=26320` that must stay unsmoothed) | Strong — external to the code, and the only oracle that can catch the 2026-08-26 class of bug |
| The production incident | `risk: 88` / `WORTH_CHECKING` observed for a car with a registered `szkoda istotna` — a legitimate *input* and the statement that the output must differ | Strong for direction; silent on magnitude |
| Hand-verifiable arithmetic and calendar rules | the whole date-normalisation table, including one row annotated verbatim from a live Otomoto listing; the mean-of-four when the four inputs are stated as literals | Strong |
| The product guardrail *absence ≠ clean* (`CLAUDE.md:7-8`, PRD) | every null-vs-empty invariant, the FOUND-only gate, the "damage floors at `NEEDS_MORE_INFO` not `HIGH_RISK_SKIP`" asymmetry, and `isSameAs` no-op assertions | Strong — a stated rule, not a code reading |

**`CLAUDE.md:139-144` states all five ceilings, both verdict floors, the never-raise direction, the mean-of-four rule and the FOUND-only gate** (corroborated for ordering and incident by `roadmap.md:121` and commit `5b7a3b3`). No file under `context/` states any ceiling *value*. Honest assessment: that memo was written alongside the code by the same author, so it is the **specification of record but not an independent derivation**. Treating it as the oracle makes the caps *pinned against accidental change* — worth having — but a cap that is wrong by design stays unfalsifiable. What *is* independently meaningful in it is the **ordering relation** the five caps encode (theft < rollback < contradiction < damage < no-OC) and the two asymmetries (damage alone ≠ skip; no-OC does not move the verdict); those follow from the product's severity reasoning, which is stated in prose. A plan that asserts the ordering plus the documented asymmetries, and treats the bare integers as change-detection with a comment saying so, is being honest about what it proves.

**Values with no external source at all — flag if a test pins them:** the `ACCIDENT_FREE_CLAIMS` phrase list, four of the five `CEPIK_*` flag codes, every flag severity (notably `CEPIK_NO_OC_POLICY` = `MEDIUM` with no verdict floor), the flag-prepending order, the theft/rollback `HIGH_RISK_SKIP` floors, the `capRisk` skip-when-under-cap behaviour, and the canonical-label replacement (`"sprawdź po doprecyzowaniu"`, currently asserted as a string copy inside a scoring unit test).

**Existing assertions that are tautological, and should not be treated as coverage when planning:**

1. `HistoriaPojazduParserTest.nullPayloadsDoNotThrowAndCarryNoData` — `assertThat(result.status()).isEqualTo(FOUND)` for `parse(null, null, VIN)` is lifted straight from the hardcoded `:63`. No business rule says a parse of two null payloads describes a *found* vehicle; the test **ratifies finding A**. Its null-list assertions in the same method are fine.
2. `HistoriaPojazduParserTest.timelineWithoutDamageEventsYieldsEmptyListNotNull` — the input is hand-composed from the parser's own expected key names (`timelineData`, `events`, `eventType`, `eventDetails`). The rule it asserts (empty ≠ null) is real; the input violates the class's own committed fixture policy and is the 2026-08-26 failure mode in miniature — if the registry renames `eventType`, this test stays green while real payloads yield `[]`.
3. `HistoriaPojazduServiceTest` — the parser (the thing that broke) is mocked out; `lookupUrl` is asserted against a copy of the private `LOOKUP_URL`; the `HIPO-0002` tests verify `String.contains` against a string the test itself wrote using the same literal as `NOT_FOUND_CODE`.
4. Every cap literal in `CepikRiskAdjusterTest` (`5`, `20`, `25`, `35`, `70`) mirrors a private constant; `25` appears a third time in `AnalysisControllerTest`. Three copies of one constant, none independent.
5. `AnalysisControllerTest` items 1–6 are shape assertions — `.exists()` / `.isArray()` pass for any non-null value of any type — and `meta.provider == "mock"` asserts the test's own stub literal.
6. `@ActiveProfiles("mock")` on `AnalysisControllerTest` is inert: `standaloneSetup` starts no Spring context, so it reads as mock-profile coverage and is not.

**Historically decisive:** the pre-fix version of `HistoriaPojazduParserTest` (recoverable at `e616ea2`) had three green tests over fabricated keys (`zdarzenia`, `szkodyIstotne`, `przebieg`), and one of them **asserted the bug as the requirement** — `parse(null, null, vin)` ⇒ `damageRecords()).isEmpty()`. None of the other four candidate layers could have caught it: the service test mocks the parser, the controller test hand-builds `CepikResult`, the live test asserts `NOT_FOUND` only. **A captured payload is the only oracle that catches this class of bug, and none existed until `8870d35` added the two fixtures.** That fix commit added no parser→API integration test and did not touch `HistoriaPojazduServiceTest`; both gaps are still open.

### F. Test infrastructure and conventions

- **Surefire** reads `${test.excludedGroups}` / `${test.includedGroups}`; the default is `live-llm` excluded, and the `live-tests` profile flips both properties. **No failsafe plugin, no JaCoCo, no `argLine`, no `**/*IT.java` convention — there is no integration-test phase; everything runs in `test`.**
- **One declared test dependency:** `spring-boot-starter-test`. Resolved transitives include JUnit Jupiter 6.0.3, Mockito 5.20.0, AssertJ 3.27.7, json-path 2.10.0, jsonassert 1.5.3 (unused), spring-test 7.0.7.
- **`MockRestServiceServer` is available and already idiomatic here** — `MockRestServiceServer.bindTo(builder).build()` in `ListingFetchServiceTest:33`, `MarketPriceFetchServiceTest:80`, `OpenRouterAnalysisServiceTest:49`. `HistoriaPojazduConfig:13` exposes `@Bean("historiaPojazduBuilder") RestClient.Builder`, the service takes the builder, and `HistoriaPojazduSession` builds from that same mutable instance — so a bound mock factory **survives its two rebuilds**. This is the seam a payload→response test should use.
- **Controller tests use `MockMvcBuilders.standaloneSetup`, never `@WebMvcTest`** (zero hits repo-wide). Collaborators are plain `mock(...)` fields re-created in `@BeforeEach` — no `@Mock`, no `@ExtendWith(MockitoExtension.class)`, no `@MockitoBean` anywhere. `GlobalExceptionHandler` is wired via `.setControllerAdvice(...)`.
- **Jackson 3 in tests** (`tools.jackson.databind.ObjectMapper`); response JSON is inspected with json-path, not deserialised to records.
- **Conventions to match:** package mirroring; `<ClassUnderTest>Test`, live variants `<Subject>LiveTest` + `@Tag("live-llm")`; classes and methods **package-private**; AssertJ only, with `.as("...")` on invariant assertions; two coexisting method-naming styles (prose camelCase in cepik/analysis units, `verb_condition` in controller tests — match the file you edit); and a strongly established comment convention where each non-obvious test names the incident or rule it defends. Fixture loading has two idioms — `getClass().getResourceAsStream("/cepik/" + name)` for the cepik maps, `getClassLoader().getResource("fixtures/llm/" + name)` for llm strings — both with a missing-fixture guard, deliberately, so a vanished fixture cannot make a test vacuously pass.
- One environment caveat: in this sandbox `MAVEN_OPTS` inherits `-Xmx12g` and the JVM refuses to start, so the suite could not be executed during this research. All classpath facts come from the committed surefire report of the 2026-08-26 23:15 run.

### G. Frontend boundary (Phase 3 territory, recorded here because Risk #2 spans it)

`cepik-result.component.ts` (155 lines) + `.html` (268 lines) have **no spec file**. `damageState` correctly derives three states from the raw field, and the four statuses have distinct Polish copy. Two rendering gaps found: `cepikResult === null` renders nothing at all — no heading, no disclaimer — and `mileageStamps === []` falls through both branches and renders no row. The registry-vs-listing mileage check (`Math.max(2000, listed * 0.05)`, registry-higher direction only) is frontend-only with no backend counterpart. Of the frontend's 26 existing tests, **none** exercises registry rendering: `analysis-result.component.spec.ts` never sets the `cepikResult` input, so the child's root `@if` is false in all seven of its tests.

## Code References

- `backend/src/main/java/com/example/autoskaner_ai/cepik/HistoriaPojazduParser.java:63` — `FOUND` hardcoded, no other exit (**the load-bearing finding**)
- `.../cepik/HistoriaPojazduParser.java:120-131` — `damagesFrom` returns empty non-null; `:123` exact `.equals` on `szkoda-istotna`
- `.../cepik/HistoriaPojazduParser.java:90-94` — `readEvents` returns null for absent `events` (correct)
- `.../cepik/HistoriaPojazduService.java:44-46` — the untested `FOUND` path; `:36` the package-private `createSession()` seam; `:47-56` exception → `LOOKUP_FAILED`
- `.../cepik/HistoriaPojazduConfig.java:13` — `RestClient.Builder` bean; the seam for a real payload→response test
- `.../cepik/RealCepikEnrichmentService.java:33,78` — `PLATE_PATTERN` applied without stripping internal whitespace (candidate defect)
- `.../cepik/MockCepikService.java:17` — never `FOUND`; the `mock` profile cannot reach the damage path
- `.../analysis/CepikResult.java:55-61` — `withoutData` nulls all 21 components
- `.../analysis/CepikRiskAdjuster.java:27-31,41,65-66,92,98,134-141,144-153,156-162,171-177` — caps, gate, damage guard, identity return, flag prepend, `overall` recompute + never-raise, floors, rank, labels
- `.../analysis/AnalysisController.java:50,66-100` — `url_failed` early return; overrides → enrich → adjust ordering
- `backend/src/test/resources/cepik/timeline-data-found.json:48-65` — the committed `szkoda istotna`
- `backend/src/test/java/.../cepik/HistoriaPojazduParserTest.java:54-66` (well-oracled), `:124-133` (tautological `FOUND`), `:138-148` (composed input)
- `backend/src/test/java/.../cepik/HistoriaPojazduServiceTest.java:13-18` — parser mocked away
- `backend/src/test/java/.../analysis/CepikRiskAdjusterTest.java:28` — production formula copied into the fixture, killing the never-raise guard
- `backend/src/test/java/.../analysis/AnalysisControllerTest.java:48-53,167,173-193` — standalone setup with a real adjuster; the only `cepikResult` JSON assertion; the FOUND-with-damage wiring test
- `backend/src/test/java/.../analysis/ListingFetchServiceTest.java:33` — the `MockRestServiceServer` idiom to copy
- `frontend/src/app/features/analyzer/components/cepik-result/cepik-result.component.ts:44-48,135-141` — `damageState`; the frontend-only mileage tolerance

## Architecture Insights

- **The null / empty / populated tri-state is the project's central safety invariant, and it is enforced structurally at three of four layers.** `withoutData` nulls everything; the service's catch path returns null lists; the enrichment short-circuits return null lists; Jackson emits nulls so the wire preserves the distinction. The one place the invariant is *not* structural is `damagesFrom` returning `[]` — and that is exactly where the residual risk lives. This suggests the durable fix is in production code (a fourth `CepikStatus`, or `null` when the payload carried nothing readable), not only in tests. Flag it for `/10x-plan`: Phase 1 is chartered as a testing phase, so if the plan wants that change it must say so explicitly rather than smuggling it in.
- **The adjuster is deliberately deterministic and the sole non-LLM writer of `scores` / `verdict`.** That is what makes Risk #3 cheap at unit level, and it is also why `CLAUDE.md` insists it must not be a second LLM call.
- **`isSameAs` as an assertion style is load-bearing, not stylistic** — the adjuster returns the same instance when nothing was added, so identity is a stronger and cheaper statement than value equality about "the registry did not touch this analysis".
- **Two seams already exist at the CEPiK edge** — the fine-grained `RestClient.Builder` (untried here, idiomatic elsewhere) and the coarse package-private `createSession()` (already exploited). The former is what a genuine payload→JSON integration test needs; the latter cannot reach the parser.
- **Fixture provenance is enforced by convention and Javadoc, not by tooling.** The one composed input already in `HistoriaPojazduParserTest` shows the convention drifts under pressure. A rule that only lives in prose will drift again.

## Historical Context (from prior changes)

- `context/changes/testing-enrichment-honesty/change.md` — the Phase 1 charter and per-risk prove/challenge/avoid intent this research grounds.
- `context/foundation/test-plan.md:50` (Risk #2), `:70-71` (Risk Response Guidance for #2/#3), `:114` and `:156` (the two now-incorrect "no stubbing seam" claims), `:157` (the binding verbatim-capture policy).
- `context/changes/llm-analysis-wiring/plan.md` § "Locked output schema" — the output schema Risk #3's `scores` / `verdict` assertions sit inside.
- `context/changes/market-price-context/research.md` — why Jina rather than Exa; relevant to Phase 2, not this phase.
- Commit `8870d35 fix(cepik): parse real registry payloads, surface szkoda istotna in UI` — the 2026-08-26 fix; `e616ea2` is the last commit carrying the fabricated-fixture tests. `5b7a3b3` introduced `CepikRiskAdjuster` after production returned `risk: 88 / WORTH_CHECKING` for a car with a registered damage.
- `roadmap.md:121` (S-01 carried-forward: the 88/WORTH_CHECKING incident) and `:169` (a `FOUND` live assertion needs a real plate+VIN+date triple, which cannot be committed).

## Related Research

None — this is the first research artifact for the test rollout. `context/changes/market-price-context/research.md` is the nearest prior art in the enrichment area and covers Phase 2's price-fetch surface rather than the registry.

## Open Questions

1. **Does Phase 1 change production code, or only tests?** The residual Risk #2 seam (`FOUND` with nothing read, `damagesFrom` → `[]`) cannot be closed by a test — a test can only pin the current, unsafe behaviour or fail. Options for `/10x-plan`: pin-and-document, add a `FOUND`-requires-data guard in the parser, or introduce a distinct status. This decision determines whether Phase 1 is a test phase or a fix phase.
2. **How should the caps be asserted, given no independent oracle for their magnitudes?** Recommended: assert the ordering relation and the two documented asymmetries as behaviour, and pin the bare integers as change-detection with an explicit comment saying that is all they are. Needs sign-off because it is a deliberate partial answer to §2's anti-pattern rather than a clean one.
3. **Is a clean-vehicle `FOUND` capture obtainable?** Without one, "checked, registry reported nothing" has no captured-data path anywhere in the stack, and Phase 3's three-state rendering test inherits the same hole. Same question for a `HIPO-0002` body, and for any payload with `rolledBack` / `vehicleLost` true — the theft and rollback caps currently have no captured input at all.
4. **Should the two candidate defects be folded into Phase 1 or raised as their own change?** `PLATE_PATTERN`'s internal-whitespace asymmetry is a two-line fix with a clear user-visible cost (a typed `WA 12345` silently loses the lookup) but is outside Risks #2/#3.
5. **Does the `HistoriaPojazduSession` gap belong to Phase 1 or Phase 2?** It is the largest single untested surface in the CEPiK path (version regex, cookie/XSRF handshake, request body keys, the 404 text `NOT_FOUND` detection depends on) and it is an HTTP-edge concern, which reads as Phase 2 — but the request body keys are on the direct path from a real vehicle to a reported damage, which reads as Phase 1.
6. **Two §2/§4 corrections to backport via `/10x-test-plan`** (recorded here, not applied): Risk #2's "Must challenge" and "Anti-pattern" columns now describe a closed failure mode and should point at the `FOUND`-with-empty-list seam; and the §4 / §6.2 "no HTTP stubbing seam" claims are factually wrong, which also means Phase 2's stated ordering rationale ("needs a stubbing seam that does not exist yet") no longer holds.
