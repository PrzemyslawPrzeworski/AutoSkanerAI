# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-08-27

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the risk
   wins. Do not promote to e2e because e2e "feels safer." Do not put a vision
   model on top of a deterministic visual diff that already catches the
   regression.
2. **User concerns are first-class evidence.** Risks anchored in "the builder
   is worried about X, and the failure would surface somewhere in <area>"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is produced
   by `/10x-research` during each rollout phase. If the plan and research
   disagree about where the failure lives, research is the ground truth.

A fourth rule is inherited from `CLAUDE.md` and is not negotiable here
either: **a test that tolerates the failure mode it exists to catch is
decoration.** Live tests assert real outcomes, never `LOOKUP_FAILED` /
`FETCH_FAILED` as a pass; fixtures under the registry fixture directory stay
verbatim captures.

Hot-spot scope used for likelihood weighting: `backend/src`, `frontend/src`
(docs, fixtures, and build output excluded; 16 commits in the last 30 days —
thin but above the signal floor).

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | User waits out the full analysis and gets nothing back — the provider pool is saturated or a slug was retired, or the request runs past the 30 s budget | High | High | interview Q1, Q2; roadmap F-01 "free-tier OpenRouter slugs are the main production fragility"; PRD NFR (no analysis runs longer than 30 s without a visible result or error); roadmap Open Roadmap Questions (~27 s synchronous); hot-spot dir `backend/src/main/java/.../analysis` (10 commits/30d) |
| 2 | The vehicle-history panel shows, or lets the user infer, "no reported damage" for a car the registry says carries a registered significant damage | High | High | interview Q1, Q4; roadmap S-04 Risk + Lesson (field names were invented; the suite stayed green because fixtures were hand-written to match them); PRD guardrail (absence of accident data means unknown, never clean); hot-spot dirs `backend/src/main/java/.../cepik` (11 commits/30d), `frontend/src/app/features/analyzer/components/cepik-result` (6 commits/30d) |
| 3 | Registry findings never reach the verdict, so a car with a registered significant damage still shows a reassuring score and label | High | Medium | roadmap S-01 carried-forward (production returned `risk: 88 / WORTH_CHECKING` for a vehicle with a registered szkoda istotna); interview Q3; hot-spot dir `backend/src/main/java/.../analysis` (10 commits/30d) |
| 4 | "Not checked" and "checked, registry reported nothing" render identically, so an unchecked history reads as a clean one | High | Medium | interview Q4; PRD guardrail; project rules (every non-`FOUND` result carries null lists, never empty ones); hot-spot dirs `frontend/src/app/features/analyzer/components/cepik-result` (6 commits/30d), `frontend/src/app/shared/models` (5 commits/30d) |
| 5 | A price sample too thin or too dispersed to mean anything is presented as a market range the buyer trusts | Medium | High | interview Q1, Q2; roadmap S-05 (a live run returned `min=22900` against `median=79900`; "the trim is statistical, not semantic"); hot-spot dir `backend/src/test/java/.../market` (4 commits/30d) |
| 6 | Listing text written to game the analyser — an accident-free assertion, or instructions aimed at the model — produces a reassuring verdict | Medium | Medium | abuse lens, untrusted input (PRD FR-002 accepts raw pasted listing text); roadmap S-01 carried-forward ("the scoring layer trusts the listing's own claims") |
| 7 | The open analysis endpoint is called in a loop, exhausting the free provider quota and the single backend instance | Medium | Medium | abuse lens, resource abuse; PRD Access Control ("unauthenticated visitors cannot access any analysis functionality") against roadmap F-03 status `proposed`; roadmap Open Roadmap Questions (~27 s of request thread per call) |

Risk #7 is scored on the same axes as the rest but is **assigned no rollout
phase**: its protection requires a control that does not exist yet (auth or a
rate limit), so a test written today could only assert current intended
behaviour. It belongs to foundation F-03 plus observability. Authorization
and ownership abuse (IDOR) is deliberately absent from the map for the same
reason — there is no persistence and no account model until F-02 and F-03
land, and the check arrives with slice S-03.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | A saturated or retired provider ends in a distinguishable, user-visible outcome — never a hang, never a success shape carrying empty content | "A 200 from the provider means we have an analysis"; "a retry always helps" | Where the request deadline is enforced; how transient is separated from permanent; what the client receives in each branch | integration with a stubbed HTTP edge | Asserting the retry count instead of the user-visible outcome |
| #2 | A captured registry payload carrying a significant damage surfaces as a reported damage all the way out to the API response | "The field mapping is right because the tests are green" | Which field names appear in a real captured payload; where a parse miss turns into an empty list rather than an error | unit + integration over verbatim captures | Fixtures composed to match the parser — the exact 2026-08-26 failure |
| #3 | A registry damage caps the risk score and downgrades the verdict regardless of what the listing claimed | "The model already scored the risk, so the later adjustment is cosmetic" | Ordering of scoring against enrichment; which statuses are allowed to adjust; that the overall score is never raised | unit | Lifting the ceiling values out of the implementation and calling them the expected result |
| #4 | Three states — not checked, checked with nothing reported, checked with findings — produce three visibly different Polish messages | "An empty list is a safe default for missing data" | The actual response shape per status, and which template branch each one drives | component test (jsdom) | Snapshotting the rendered block instead of asserting the distinction |
| #5 | A sample too thin or too dispersed to be a market range is labelled as such rather than displayed as a confident range | "A number came back, so the range is meaningful" | How sample size and discard count reach the response; what the UI does at the boundary | unit + component test | Re-deriving the expected median with the production formula |
| #6 | Listing-supplied claims cannot move the deterministic floor that registry facts set | "The model will obviously ignore manipulation" | Which parts of the verdict are deterministic and which are model-produced | unit | An eval asserting a specific model wording — non-deterministic and expensive for the signal |
| #7 | No test this rollout. Protection needs a control that does not exist yet; see the note above §2's guidance table | — | — | — | — |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Enrichment honesty | Prove a real registry damage reaches both the payload and the verdict, over verbatim captures | #2, #3 | unit + integration | change opened | `testing-enrichment-honesty` |
| 2 | Availability and failure paths | Prove every provider and fetch failure ends in an honest, distinguishable outcome inside the time budget, and that a thin price sample labels itself | #1, #5, #6 | unit + integration (stubbed HTTP edge), live-tagged where a real outcome is assertable | not started | — |
| 3 | Guardrail rendering | Prove the three history states and the small-sample caveat read differently to a Polish-speaking user | #4, #2 (UI half), #5 (UI half) | component tests (jsdom) | not started | — |
| 4 | Quality gates | Run both suites on PR and push before auto-deploy, keeping live-tagged tests out of the gate | cross-cutting | gates | not started | — |

Ordering rationale: Phase 1 defends the two highest-impact scenarios at the
cheapest layer and does so where churn is highest. Phase 2 covers the
scenario the builder rates most likely (#1) but needs a stubbing seam that
does not exist yet, so it follows. Phase 3 is where the product guardrail
actually reaches the user, and the frontend suite is the thinnest part of the
codebase. Phase 4 comes last because a gate over a suite that does not yet
cover the top risks locks in a false floor — but it must land, because
`main` auto-deploys to production on merge and nothing runs the suites today.

Browser-level e2e is deliberately not in this rollout: every risk above is
reachable at unit, integration, or component level, and an e2e layer over a
single-page flow would duplicate Phase 3 at a much higher cost. Revisit when
a risk surfaces that genuinely requires the deployed shape — auth plus token
storage plus a guarded route, once F-03 lands.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| backend unit + integration | JUnit 5 via Maven surefire | Spring Boot 4.0.6 | 19 test files across the analysis, cepik, and market packages. `live-llm` is excluded by default; the `live-tests` profile flips the include/exclude properties — `-Dgroups=live-llm` silently intersects to zero |
| frontend unit + component | vitest + jsdom via `@angular/build:unit-test` | Angular 21.2 | 3 spec files, 26 tests, ~2 s. Zoneless: no `fakeAsync` / `tick`; vitest matchers, not jasmine |
| HTTP mocking (backend) | none yet — see §3 Phase 2 | — | No stubbing seam for the provider or reader edge; every failure path is currently verified by hand against production |
| HTTP mocking (frontend) | `provideHttpClientTesting` + `vi.fn()` service doubles | Angular 21.2 | Already used by the existing specs |
| live integration | JUnit tag `live-llm` + `live-tests` profile | — | Asserts real outcomes only. A proxy 403 on the reader host fails the market-price live test on purpose, so a blocked path stays visible instead of silently green |
| e2e | none — deliberately unscheduled | — | See the note at the end of §3 |
| accessibility | none — not scheduled | — | No risk in §2 depends on it; revisit if one surfaces |
| CI | GitHub Actions, one live workflow only | — | No unit or integration gate on PR or push; see §3 Phase 4 |

**Stack grounding tools (current session):**
- Docs: none — Context7 or an equivalent framework-docs MCP is not available in current session; stack facts came from the local manifests, the Angular workspace config, and `CLAUDE.md`; checked: 2026-08-27
- Search: none — Exa.ai is not available in current session; a built-in fetch tool exists but was not needed for stack claims; checked: 2026-08-27
- Runtime/browser: none — Playwright MCP not available in current session, which is one input into leaving e2e unscheduled; checked: 2026-08-27
- Provider/platform: none — no GitHub, Render, Cloudflare, or Supabase MCP; deploy and log inspection run through REST with keys from the environment, so CI gate wiring in Phase 4 must be authored against the platform docs rather than probed; checked: 2026-08-27

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required for §3 Phase N" means the gate is enforced once that rollout phase
lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile + typecheck | local, CI after §3 Phase 4 | required after §3 Phase 4 | type and syntax drift; stale specs that no longer compile |
| backend unit + integration | local now, CI after §3 Phase 4 | required after §3 Phase 1 | logic regressions across analysis, cepik, and market |
| frontend unit + component | local now, CI after §3 Phase 4 | required after §3 Phase 3 | guardrail copy regressions and state collapse in the rendered result |
| live integration | local, plus the existing scheduled workflow | never a PR gate | real breakage in the provider, the registry, or the reader edge — kept out of the gate because a third-party outage must not block a merge |
| pre-prod smoke | between merge and production | optional after §3 Phase 4 | environment-specific failures: profile selection, missing environment variables, CORS |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once the
relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a backend unit test

- **Location**: `backend/src/test/java/com/example/autoskaner_ai/<package>/`, mirroring the package under test.
- **Naming**: `<ClassUnderTest>Test.java`.
- **Reference test**: `backend/src/test/java/com/example/autoskaner_ai/market/MarketPriceStatisticsTest.java` — pure-function tests with an independent expected value, including the "caller's list is not mutated" case.
- **Run locally**: `cd backend && ./mvnw test`.

### 6.2 Adding a backend integration test

- TBD — see §3 Phase 2. The stubbing seam for the provider and reader edge does not exist yet; today's closest reference is `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java`.
- **Mocking policy (already binding)**: mock at the network edge only, never internal collaborators. Registry fixtures under `backend/src/test/resources/cepik/` must be verbatim captures — capture a new payload rather than composing one, and add no field mapping without a capture showing that name.

### 6.3 Adding a frontend component test

- **Location**: next to the component, `<name>.component.spec.ts`.
- **Reference test**: `frontend/src/app/features/analyzer/analyzer.component.spec.ts`.
- **Binding constraints**: no `fakeAsync` / `tick` (the app is zoneless); vitest matchers only (`toBe(true)`, not `toBeTrue()`); use `await fixture.whenStable()` if real async ever appears.
- **Run locally**: `cd frontend && npm test -- --watch=false`.
- Fuller guidance on the three-state rendering pattern: TBD — see §3 Phase 3.

### 6.4 Adding a test for a new API endpoint

- **Test type**: integration, at the controller boundary.
- **Pattern**: assert the response shape *and* the guardrail semantics — which fields are null versus empty, and what a downstream consumer would render from them.
- **Reference test**: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java`.
- Fuller pattern, including the failure branches: TBD — see §3 Phase 2.

### 6.5 Adding a test for a new enrichment source or registry field

- TBD — see §3 Phase 1. The rule that already applies: no field mapping without a captured payload showing that field name, and a non-`FOUND` result carries null lists rather than empty ones.

### 6.6 Adding a CI gate

- TBD — see §3 Phase 4.

### 6.7 Per-rollout-phase notes

(Filled in as phases land.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **The mock profile's own output** — `MockAiAnalysisService` and `MockCepikService` are deterministic stubs that exist to serve other tests; asserting their canned responses proves nothing. Re-evaluate if a mock ever encodes business logic rather than a fixture. (Source: Phase 2 interview Q5.)
- **The deprecated risk endpoint** — slated for deletion since S-01 shipped; testing it entrenches something the roadmap wants gone. Re-evaluate only if the decision to remove it is reversed. (Source: Phase 2 interview Q5; roadmap S-01 carried-forward.)
- **DTO plumbing** — Java records, Angular model interfaces, getters, and straight field mapping with no logic. Re-evaluate for any type that gains defaulting, normalisation, or validation. (Source: Phase 2 interview Q5.)
- **Visual appearance is not excluded, but it is not scheduled** — no risk in §2 is visual-only, so under cost × signal nothing is spent there this rollout. If a layout or z-index failure surfaces, prefer a deterministic diff over a vision model. (Source: Phase 2 interview Q5 — the builder declined to exclude it.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-08-27
- Stack versions last verified: 2026-08-27
- AI-native tool references last verified: 2026-08-27

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
