# Refactor opportunity #1: bind the mock beans with contract tests — Plan Brief

> Full plan: `context/changes/refactor-opportunities/plan.md`
> Upstream exploration: `context/changes/refactor-opportunities/research.md` (opportunity #1 = `C9`)

## What & Why

The three `@Profile("mock")` beans are bound to nothing — no test in the repo names any of them. That
makes the `mock` profile, which every quality gate and both E2E specs actually run, the one
implementation of each port that cannot fail. One mock has already drifted far enough to invert the
repo's hardest business rule: `MockAiAnalysisService` drops the `NO_ACCIDENT_DECLARATION` flag for a
listing that says `"Pełna historia serwisowa"` and nothing about accidents, and emits the wrong
severity when it does flag.

This change writes one parameterised contract test per port, run against both the mock and the real
implementation, asserting the properties that must hold of any implementation — chiefly that absence
of accident data never renders as absence of accidents.

## Starting Point

Backend suite 235 tests in 25 classes. `@ParameterizedTest` already used 4×, all in
`RealCepikEnrichmentServiceTest`. Zero contract-test scaffolding anywhere: no `abstract class`, no
`extends`, no `@Nested` in `backend/src/test`. `MockCepikService` is 20 lines with **zero branches**
and returns `LOOKUP_FAILED` unconditionally. `MockAiAnalysisService` decides the accident flag from a
keyword scan that disagrees with `AnalysisResponseParser` on one input class and on severity.

## Desired End State

Two new contract tests (`CepikEnrichmentServiceContractTest`, `AiAnalysisServiceContractTest`), both
mocks fixed, `CepikRiskAdjuster` reachable under `mock` with a test that proves it, and the four
documents plus two git hooks that describe the old state brought current. Nothing in `src/main` moves
except the two mock beans.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Ports in scope | `cepik` + LLM; `market` excluded | The market mock already returns a realistic `OK` and never returns a degraded status, so the natural property holds vacuously — cost with no finding behind it | Plan |
| LLM "real counterpart" | `AnalysisResponseParser`, fed an existing fixture | The accident rule lives in the parser that both network beans call; testing a network bean would test a socket | Plan |
| Real-side LLM driver | `fixtures/llm/hollow-all-leaves-null.json` | Already committed, has `accidentClaim: null` and `riskFlags: []` — no new fixture, no stub | Plan |
| `MockCepikService` target | Validate all three inputs, then `FOUND` with a `szkoda-istotna` | Keeps both E2E specs on `MISSING_INPUTS` (neither listing text has a VIN) while making the adjuster reachable under `mock` | Plan |
| Real-bean violations | Narrow the property and record the finding | Keeps #1 additive and revertible, which is the reason it ranked first | Plan |
| Validation of the new specs | Intrinsic red-green on each mock, plus one deliberate inversion of the real side per phase, reverted | A contract test green on first run against a zero-branch stub has proved nothing | Plan |
| Commit granularity | Harness + observed failure + fix in one commit per phase | The report's "watch it fail" step is a deliberate break, and a deliberate break is never committed here | Plan |
| Date-axis narrowing | Mock treats non-blank as well-formed; contract asserts only null/blank | Duplicating the six strict date formats into the mock is the one duplication `backend/CLAUDE.md` explicitly forbids | Plan |
| Contract-test naming | `<Interface>ContractTest.java` | The subject is the interface and both implementations are parameters — neither §6.1 nor §6.2 of `test-plan.md` covers it, so the convention gets written down | Plan |

## Scope

**In scope:** `CepikEnrichmentServiceContractTest` (3 port properties × 2 implementations, plus a
no-outbound-call assertion); rewrite of `MockCepikService`; `AiAnalysisServiceContractTest` (the
accident-declaration property × 2 implementations); fix of `MockAiAnalysisService`'s flag condition
and severity; two new cases in `CepikDamageReachesTheResponseTest`; updates to `test-plan.md`,
`repo-map.md`, `backend/CLAUDE.md`, root `CLAUDE.md`, and the suite counts in both git hooks.

**Out of scope:** any market-price contract test; any change to a real bean; extracting a shared input
validator; the six date formats as contract properties; `MarketPriceFetchService.missing()`'s null
`fetchedAt` (`C15b`); the `fetchStatus` enum and dead factories; ranked opportunities #2 and #3 and
all 11 unranked candidates; new fixtures, network calls, or Spring contexts in tests.

## Architecture / Approach

`@ParameterizedTest` + `@MethodSource` supplying named implementations of a port; each property
written once and executed per implementation. Every parameter is directly constructible without
Spring — `new MockCepikService()`, `new RealCepikEnrichmentService(mock(HistoriaPojazduService.class))`,
`new AnalysisResponseParser(objectMapper)`. The only production change is the two mock beans.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. cepik port contract | Contract test over both implementations; `MockCepikService` validates inputs and returns a realistic `FOUND` | A `FOUND` result has no factory — the mock must use the 21-component constructor and grows to ~55 lines |
| 2. LLM port contract | Contract test over the mock and a parser-backed adapter; `MockAiAnalysisService` fixed | The two parameters consume different inputs (listing text vs model JSON); the asymmetry must be commented, not hidden |
| 3. Reachability | `FOUND` with null `damageRecords` moves nothing; the mock's own `FOUND` reaches the adjuster | Partly adjacent to existing coverage — the new case is the `FOUND`-with-null shape, not damage-to-verdict |
| 4. Documentation + counts | `test-plan.md`, `repo-map.md`, `backend/CLAUDE.md`, root `CLAUDE.md`, both hooks | Counts must be read off a run; grep miscounts both stacks (`@Test` reads 212 against a real 235) |

**Prerequisites:** none. This was the one ranked candidate with no prerequisite step.
**Estimated effort:** 1–2 sessions across 4 phases.

## Open Risks & Assumptions

- **A correction to the upstream report, carried into this plan.** `research.md`'s "the adjuster is
  never executed end to end" reads as "untested". It is not: `CepikRiskAdjuster` has 25 unit tests and
  `CepikDamageReachesTheResponseTest` drives captured registry bytes through the real service in 4
  cases. What is true is narrower — the **`mock` profile** never reaches it. The value of this change
  is binding the mocks, not reviving dead code.
- **A second correction.** The report says the mock's inversion is that `"bezwypadkowy"` suppresses
  the flag. That branch is correct (it also sets `accidentClaim` non-null, so the real parser would
  not append either). Solving the mock's guard leaves exactly **one** violating input class: a text
  containing `"historia"` and no accident keyword. Sharper, not weaker.
- **The LLM contract's real-side parameter partly duplicates existing coverage.**
  `ListingClaimsCannotMoveTheFloorTest` already pins the parser's append rule in three tests. The new
  value is binding the *mock* to the same assertion; the real-side parameter earns its place only
  through the deliberate-inversion check in step 2.4.
- **The `MockCepikService` rewrite duplicates a validation rule.** Accepted on purpose: extracting a
  shared validator would move `src/main` and pull `C6`/`C15` into scope, and the duplication is bound
  by the contract test rather than free-floating.
- **Two properties are narrowed and the narrowings are holes.** The cepik date axis covers only null
  and blank, and `result.vin()` is never asserted (echoing the VIN is false by design). Both are
  written into `test-plan.md` §7 in Phase 4 so they are recorded rather than forgotten.
