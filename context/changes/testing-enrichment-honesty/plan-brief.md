# Plan Brief: Enrichment Honesty (Test Rollout Phase 1)

> Condensed companion to `plan.md`. Read this to know what is being done and why;
> read `plan.md` for the contracts, oracles and verification steps.

## The problem

Rollout Phase 1 defends two risks from `test-plan.md`: a car with a registered szkoda
istotna reading as having no reported damage (#2), and registry findings never reaching the
verdict (#3).

The 2026-08-26 incident — a parser full of invented registry field names, green because the
fixtures were composed to match them — is closed. But research found the same *false-clean
shape* one layer up, reachable by two routes no test can close, plus a Risk #3 test suite
whose expected values are computed with the code under test. So this is a test-and-fix
phase, not a test-only one.

## What is wrong today

| Finding | Where | Consequence |
|---|---|---|
| `parse` returns `FOUND` unconditionally | `HistoriaPojazduParser.java:62-63` | An empty or 204 registry response becomes a "found in the registry" panel with no data in it |
| `damagesFrom` returns an empty non-null list when nothing matched, on a strict case-sensitive `equals` | `HistoriaPojazduParser.java:120-131` | Any vocabulary drift → `damageRecords: []` → the UI states "brak zgłoszonych szkód istotnych" for a damaged car |
| The parser is mocked away in the service test | `HistoriaPojazduServiceTest.java:14` | `lookup`'s success path never runs; swapping its two payload arguments keeps the repo green |
| `$.cepikResult.status` is the only asserted `cepikResult` node | `AnalysisControllerTest.java:167` | No test puts a damage record in an HTTP body; the null/empty/populated wire distinction is unprotected |
| The fixture copies production's mean-of-four formula | `CepikRiskAdjusterTest.java:28` | The never-raise guard at `CepikRiskAdjuster.java:141` is unreachable by all 14 tests |
| `PLATE_PATTERN` matched without stripping internal whitespace | `RealCepikEnrichmentService.java:78` | `WA 12345` yields `MISSING_INPUTS` and no lookup, while a spaced VIN is accepted |
| `HistoriaPojazduSession` has no test | — | `FALLBACK_API_VERSION` exists because the pinned version rotted silently in production |

## Key decisions

| Decision | Choice | Source |
|---|---|---|
| Fix the two false-clean routes, or pin the current behaviour? | Fix both in the parser — a test that pins a false-clean shape as expected is decoration | Plan (Q1) |
| How far does the Risk #2 test reach? | Full stack: capture bytes → stubbed HTTP edge → session → parser → enrichment → JSON body | Plan (Q2) |
| Oracle for the five cap magnitudes | Severity *ordering* asserted as behaviour; the integers pinned once, declared change-detection only | Frame (anti-pattern: "lifting the ceiling values out of the implementation") |
| Fixture policy for the new cases | Verbatim captures stay verbatim; new inputs are `-derived` from a named parent by deleting a node or changing a value, with `_provenance` | Frame (anti-pattern: "fixtures composed to match the parser") + `CLAUDE.md` |
| Making the never-raise guard reachable | Restructure the fixture helper so `overall` comes from hand arithmetic, not the formula | Research (§ the copied formula) |
| Session test scope | Version discovery **and** the `FALLBACK_API_VERSION` branch; cookie/XSRF coverage deferred | Plan (Q6) |
| The plate defect | Fold into Phase 1 — it silently costs the user the whole registry lookup | Research (§ plate/VIN asymmetry) |
| The wire contract | Pin `damageRecords` in all three states; the null case against the raw body, since `jsonPath` cannot tell absent from null | Plan (Q8) |
| Status for an unreadable 200 | Reuse `LOOKUP_FAILED`, not a new status and not `NOT_FOUND` — a definitive "no such vehicle" arrives as a 404 with `HIPO-0002` | Plan (Q1 follow-up) |
| `test-plan.md` corrections | Phase 1 edits it as a final step, once the claims are actually false | Plan (Q9) |

## Phases

1. **Close the false-clean routes in production.** `LOOKUP_FAILED` when neither payload was
   readable; a `KNOWN_EVENT_TYPES` canary (the ten types observed in the capture) that turns
   unrecognised vocabulary into `null` damages and mileage rather than `[]`; plate
   normalisation matched to `VinValidator`. Establishes the `-derived` fixture convention and
   rewrites the assertion that currently ratifies the unconditional `FOUND`.
2. **Captures → HTTP response.** One test spanning capture bytes to
   `$.cepikResult.damageRecords[0].*` and `$.analysis.verdict.code`, plus the three-way wire
   contract, a real `HIPO-0002` 404 body, and the session's version-discovery branches.
3. **Re-oracle the risk adjuster.** Hand-computed `overall`, the never-raise guard reached,
   cap ordering as behaviour with one pinned integer copy, and the missing interaction cases.
   Independent of 1–2.
4. **Backport to `test-plan.md`.** Correct §4 and §6.2 (the seam does exist), fill §6.5 and
   §6.7, re-point Risk #2's guidance at the residual failure mode, record the deferred
   frontend findings.

Phase 1 must precede Phase 2 so the integration test asserts fixed behaviour.

## Verification

```bash
cd backend && JAVA_HOME="D:/Software/Java/jdk-26.0.1" MAVEN_OPTS="-Xmx1g" ./mvnw -o test
```

Baseline is green at 132 tests. Research reported the suite unrunnable; the actual blocker
was `JAVA_HOME` pointing at a Zulu 8 JRE.

Each phase carries mutation checks, because a test that stays green under the mutation it
exists to catch is the failure mode this whole phase is about: swap the parser's two payload
arguments, break `DAMAGE_EVENT_TYPE`, set a global `non_null` inclusion, shift
`CAP_SIGNIFICANT_DAMAGE` below `CAP_CONTRADICTED_CLAIM`, delete the `Math.min`.

## Out of scope

Frontend component tests (two rendering gaps found and recorded for §3 Phase 3), CI wiring
(§3 Phase 4), live-test changes (a `FOUND` assertion needs a real plate+VIN+date that cannot
be committed), async/threading, market price, and expanding `ACCIDENT_FREE_CLAIMS` — that
phrase list has no external oracle.

## Notable pre-work

The plan's biggest technical assumption was tested rather than asserted: a throwaway probe
bound `MockRestServiceServer` to the `historiaPojazduBuilder`, confirmed it survives both of
`HistoriaPojazduSession`'s `builder.build()` rebuilds, and drove the committed captures to
`FOUND` / `TOYOTA` / the real `2023-02-07` PZU damage. The probe was deleted; Phase 2 builds
the real test on what it proved.
