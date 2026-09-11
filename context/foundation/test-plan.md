# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-09-11

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

A fourth rule is inherited from `backend/CLAUDE.md` and is not negotiable here
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
| 5 | A price sample too thin or too dispersed to mean anything is presented as a market range the buyer trusts | Medium | High | interview Q1, Q2; roadmap S-05 (`roadmap.md:187` — a live run returned `min=39900` against `median=82900`, an earlier one `min=22900` for 2017–2021 Corollas; "the trim is statistical, not semantic"); hot-spot dir `backend/src/test/java/.../market` (4 commits/30d) |
| 6 | Listing text written to game the analyser — an accident-free assertion, or instructions aimed at the model — produces a reassuring verdict | Medium | Medium | abuse lens, untrusted input (PRD FR-002 accepts raw pasted listing text); roadmap S-01 carried-forward ("the scoring layer trusts the listing's own claims") |
| 7 | The open analysis endpoint is called in a loop, exhausting the free provider quota and the single backend instance | Medium | Medium | abuse lens, resource abuse; PRD Access Control ("unauthenticated visitors cannot access any analysis functionality") against roadmap F-03 status `proposed`; roadmap Open Roadmap Questions (~27 s of request thread per call) |
| 8 | One user reads, edits or deletes another user's saved analysis by guessing its id | High | Medium | abuse lens, authorization; PRD FR-010/FR-011/FR-012 (saved analyses belong to an account); roadmap F-02 Risk ("plan the schema to include a `user_id` column from the start"); F-02 as shipped 2026-09-11 (`users` + `analyses` with a `user_id` FK) |
| 9 | A user saves analyses, and they are silently gone — the deployed app boots against a throwaway in-memory database, or a schema drift is patched under the migration instead of failing | High | Medium | roadmap F-02 Risk **corrected 2026-09-11** (Render's `DATABASE_*` vars point at a Supabase host that no longer resolves — "the vars look configured and are dead"); PRD FR-010 (saved analyses persist across sessions) |

Risk #7 was scored on the same axes as the rest and **assigned no rollout phase**,
because its protection required a control that did not exist (auth or a rate
limit) and a test written before it could only assert intended behaviour. **Half
of that control landed 2026-09-11 with F-03 (`auth-scaffold`).** `/api/**` is now
`authenticated()`, so the loop first needs an account —
`ApiRequiresAuthenticationTest` pins that `POST /api/analyses` answers 401 without
a bearer, and boots the real filter chain to do it, because every other controller
test here uses `standaloneSetup` and has no filter chain to fail against. The
scenario is not closed: registration is unauthenticated and unthrottled, so the
loop is still reachable at the cost of one `POST /api/auth/register`. **The
remaining half is a rate limit, and it is not in place** — see
`context/changes/auth-scaffold/change.md` § "Left undone". The likelihood is left
at Medium rather than lowered, since the cost to an attacker went from zero to
almost zero.

**Risks #8 and #9 entered the map on 2026-09-11, when F-02 (`data-layer-setup`)
shipped the persistence layer.** They were deliberately absent before that — there
was no persistence and no account model, so a test could only have asserted
intended behaviour. Both now have code behind them and a first line of defence,
and both are scored for the abuse they enable rather than for what F-02 alone
exposes: F-02 ships no endpoint, so nothing is reachable over HTTP until F-03 and
S-03 land. That is what keeps #8's likelihood at Medium and not High — the id is
guessable, but there is not yet a request that carries one.

- **#8** is answered structurally rather than by a check a caller can forget.
  `SavedAnalysisRepository` exposes `findByIdAndUserId` and no single-row
  `findById` path, so ownership is part of the lookup instead of an `if` after it,
  and someone else's id is indistinguishable from an id that does not exist.
  `SavedAnalysisRepositoryTest.doesNotHandAnAnalysisToAUserWhoDoesNotOwnIt` pins
  it, and was confirmed to go red when the method is rewritten to ignore its
  `userId` argument. The database backs the same rule from below: `analyses.user_id`
  is `NOT NULL` with a FK to `users` and `ON DELETE CASCADE`, so no row can exist
  unowned and deleting an account takes its rows with it — also asserted, also
  confirmed red with the cascade removed. **When S-03 adds the endpoints, the check
  that matters moves up a layer**: the `userId` must come from the authenticated
  principal and never from the request body or a query parameter, or the
  repository's guarantee is handed the attacker's own answer.
- **#9** is answered by refusing to make the dangerous configuration reachable by
  accident. The default datasource is a hardcoded in-memory H2 and *nothing on the
  boot path reads `DATABASE_URL`*; real Postgres is opt-in via the `postgres`
  profile (`SPRING_PROFILES_ACTIVE=openrouter,postgres`). A `${DATABASE_URL:<h2>}`
  default would have been the failure itself — Render still carries three dead
  `DATABASE_*` vars, so production would have fallen back to in-memory and lost
  every saved analysis without one failing request. The second half is
  `ddl-auto=validate`: Flyway owns the schema and Hibernate may only check it, so
  drift fails at startup instead of being patched into the live schema while the
  migration a new environment replays stays wrong.
  `SchemaIsOwnedByFlywayTest` asserts both — that V1 applied, and that `ddl-auto`
  is `validate`. **What it does not cover, stated so it is not mistaken for
  covered:** every test runs H2, so a type that means something different in
  PostgreSQL surfaces first as a failed Render deploy. That is a survivable blast
  radius — Render keeps serving the previous version — but it is not a test. V1
  specifically *was* verified against real PostgreSQL 17 on 2026-09-11 by deploying
  it — `ddl-auto=validate` passed against a Flyway-built schema on a dialect no test
  in this repository runs, which confirms the portable-types choice. That is a
  one-off observation about one migration, not coverage: **treat every later
  migration as unverified against PostgreSQL until a deploy says otherwise.**

**Risks #2, #3 and #4 gained a second line of defence on 2026-09-10** (change
`refactor-opportunities`), and it sits at the *port* rather than at a class.

- **#4** — "not checked" and "checked, registry reported nothing" rendering
  identically — is now asserted of **every** `CepikEnrichmentService`: a
  non-`FOUND` result carries null lists, never empty ones, across all six ways the
  three required inputs can be absent or unusable, and `fetchedAt` is stamped even
  on those paths so the panel can always say when it was attempted.
- **#2** — the panel letting a user infer "no reported damage" — keeps its
  capture-driven protection and gains the listing-side half: a null
  `accidentClaim` yields `NO_ACCIDENT_DECLARATION` at `MEDIUM` from every
  `AiAnalysisService`, the mock included. That mock had inverted the rule — it
  suppressed the flag on the substring `"historia"` and rated it `HIGH` — and
  under the `mock` profile it is the only implementation the git hooks and the
  E2E specs ever run.
- **#3** — findings that never reach the verdict — gained the leg that was
  missing rather than a duplicate one. `CepikRiskAdjuster` was covered in
  isolation and the HTTP response was covered from a capture, but nothing proved
  the `mock` profile's own `FOUND` result travels the controller path into
  `scores` and `verdict`. It does: risk capped at 35 with
  `CEPIK_SIGNIFICANT_DAMAGE` and `CEPIK_CONTRADICTS_LISTING`, and a `FOUND`
  result whose `damageRecords` is null still moves nothing in either direction.

See §6.8 for the shape these use and §7 for what they deliberately leave alone.
**No risk is retired by this** — a contract binds the implementations that exist,
and the three gaps the work exposed without closing are recorded in §8's
2026-09-10 port-contract entry alongside §6.7's older carried-forward list.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | A saturated or retired provider ends in a distinguishable, user-visible outcome — never a hang, never a success shape carrying empty content | "A 200 from the provider means we have an analysis"; "a retry always helps" | Where the request deadline is enforced; how transient is separated from permanent; what the client receives in each branch | integration with a stubbed HTTP edge | Asserting the retry count instead of the user-visible outcome |
| #2 | A captured registry payload carrying a significant damage surfaces as a reported damage all the way out to the API response | "An empty damage list means the registry reported nothing" — it equally means the parse silently matched nothing, and the two are indistinguishable downstream | Where a parse miss turns into an empty list rather than an error or a null; which of null / `[]` / populated each status and each unreadable shape produces | unit + integration over verbatim captures | Pinning a false-clean shape as expected behaviour — writing `isEmpty()` where the honest answer is `isNull()`. Fixtures composed to match the parser stay prohibited by §6.5 |
| #3 | A registry damage caps the risk score and downgrades the verdict regardless of what the listing claimed | "The model already scored the risk, so the later adjustment is cosmetic" | Ordering of scoring against enrichment; which statuses are allowed to adjust; that the overall score is never raised | unit | Lifting the ceiling values out of the implementation and calling them the expected result |
| #4 | Three states — not checked, checked with nothing reported, checked with findings — produce three visibly different Polish messages | "An empty list is a safe default for missing data" | The actual response shape per status, and which template branch each one drives | component test (jsdom) | Snapshotting the rendered block instead of asserting the distinction |
| #5 | A sample too thin or too dispersed to be a market range is labelled as such rather than displayed as a confident range | "A number came back, so the range is meaningful" | How sample size and discard count reach the response; what the UI does at the boundary | unit + component test | Re-deriving the expected median with the production formula |
| #6 | Listing-supplied claims cannot move the deterministic floor that registry facts set | "The model will obviously ignore manipulation" | Which parts of the verdict are deterministic and which are model-produced | unit | An eval asserting a specific model wording — non-deterministic and expensive for the signal |
| #7 | No test this rollout. Protection needs a control that does not exist yet; see the note above §2's guidance table | — | — | — | — |
| #8 | A request for a row the caller does not own is answered the same way as a request for a row that does not exist — no row, no leaked existence, and no reliance on the caller remembering to check | "The service layer will check the owner"; "a 403 is the honest answer" — it confirms the row exists | Where `userId` originates on each path (it must come from the authenticated principal, never from the request); whether any single-row read bypasses the ownership-scoped lookup | unit (repository, real schema) now; integration over the HTTP boundary once S-03 exposes one | Asserting the query string or the method name instead of the outcome; testing ownership only at the layer where it is easiest to reach |
| #9 | A restart, a redeploy and a fresh environment all end with the same schema and the same rows — and a configuration that cannot persist says so at startup instead of accepting writes | "The datasource is configured, so it is the right one" — Render's `DATABASE_*` vars looked configured and were dead; "Hibernate will keep the schema in step" | Which properties the boot path actually reads per profile, and what a missing one does; who creates the schema — Flyway or Hibernate — and what happens on drift | unit (a migration-applied + `ddl-auto` assertion) plus reading the platform's env-var keys before trusting a properties default | Asserting the schema by listing columns a second time — that is a copy of the migration to be edited twice; treating an H2 pass as evidence about PostgreSQL |

Risk #6's anti-pattern — "an eval asserting a specific model wording" — is about the
**product's** prompts, where the model's phrasing reaches a user and the deterministic
floor is what actually protects them. It does not prohibit evals over the repo-tooling
reviewer in §4, whose output is a schema (severity, file, verdict) rather than prose,
and whose assertions are therefore about a decision, not a wording.

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Enrichment honesty | Prove a real registry damage reaches both the payload and the verdict, over verbatim captures | #2, #3 | unit + integration | complete | `context/archive/2026-08-27-testing-enrichment-honesty` |
| 2 | Availability and failure paths | Prove every provider and fetch failure ends in an honest, distinguishable outcome inside the time budget, and that a thin price sample labels itself | #1, #5, #6 | unit + integration (stubbed HTTP edge), live-tagged where a real outcome is assertable | complete | `testing-availability-failure-paths` |
| 3 | Guardrail rendering | Prove the three history states and the small-sample caveat read differently to a Polish-speaking user | #4, #2 (UI half), #5 (UI half) | component tests (jsdom) | not started | — |
| 4 | Quality gates | Run both suites on PR and push before auto-deploy, keeping live-tagged tests out of the gate | cross-cutting | gates | not started | — |

Ordering rationale: Phase 1 defends the two highest-impact scenarios at the
cheapest layer and does so where churn is highest. Phase 2 covers the
scenario the builder rates most likely (#1) and follows because the registry
edge was the highest-risk consumer of the HTTP stubbing seam — that work is
now done, and §6.2 documents the seam Phase 2 inherits. (This originally read
"needs a stubbing seam that does not exist yet." That was wrong:
`MockRestServiceServer` shipped with `spring-test` all along and three test
classes already used it; the registry edge was simply the one place it never
had been.) Phase 3 is where the product guardrail actually reaches the user,
and the frontend suite is the thinnest part of the codebase. Phase 4 comes
last because a gate over a suite that does not yet cover the top risks locks
in a false floor — but it must land, because `main` auto-deploys to
production on merge and nothing runs the suites today.

Phase 2's row reads `complete` against the live change folder, not an archive
path: the change is finished on `main` (six phase commits, `512f555`…`5de1ce9`)
but not yet run through `/10x-archive`. When it is, the folder becomes
`context/archive/<date>-testing-availability-failure-paths`.

**Carried into Phase 2** — closed. `HistoriaPojazduSession`'s cookie merge and
dedupe and its XSRF extraction now have dedicated coverage in
`HistoriaPojazduSessionTest`, asserted at the mock seam against RFC 6265 §5.4;
the shared-builder cookie leak the coverage exposed is fixed. See §6.7 Phase 2.

**Carried into Phase 3** — the first four were found during Phase 1 and are
UI-side halves of Risk #2 and Risk #4; the last six come from Phase 2 (§6.7)
and were left open rather than pinned:

- `cepik-result.component` has no spec at all.
- `cepikResult === null` renders **nothing** — no heading, no disclaimer. A
  reader cannot distinguish "not checked" from "section absent".
- `mileageStamps === []` renders no row, so a registry that reported no dated
  readings looks identical to one that was never asked.
- The registry-vs-listing mileage tolerance (`max(2000 km, 5%)`,
  registry-higher direction only) lives **only** in the frontend component. It
  does not feed the score, and there is no backend counterpart. Per
  `frontend/CLAUDE.md`, if it ever moves into scoring, delete the TypeScript
  copy rather than keeping two.
- ~~**BLOCKING for Phase 3.** `market-price-panel.component` has **no spec**~~ —
  **closed 2026-09-04.** `market-price-panel.component.spec.ts` now covers all
  four `sampleQuality` arms (`DISPERSED`, `THIN`, `SUFFICIENT`, null), the
  `discardedCount` block including its `0`-vs-`null` truthy check, both degraded
  statuses, and the collapsed-panel case — 13 tests. Two things worth carrying
  forward from writing it:
  - **The spec was proven by reverting the bug, not by reading green.**
    Reintroducing Phase 2's old `sampleSize < 3` condition turns 3 tests red,
    one of which (`null quality → no caveat invented on the client`) is exactly
    the regression the Phase 2 edit existed to prevent. A spec written against a
    template nobody had executed is otherwise indistinguishable from one that
    asserts nothing.
  - **That revert-check is also what exposed a weak assertion of my own.** The
    distinctness test (`DISPERSED and THIN do not render the same sentence`)
    passed under the reintroduced bug, because `DISPERSED` then rendered no
    caveat at all and `undefined !== 'Mała próbka…'` is trivially true. It now
    asserts both operands exist first. A comparison is only as strong as its
    weakest side, and reading the test would not have shown that.

  The unblocking was environmental, not technical: Node existed but was
  unreadable — an elevated `nvm install` had left ACLs on `%LOCALAPPDATA%\nvm`
  excluding the normal user, so every shell reported `Access to the path
  'C:\nvm4w\nodejs' is denied` and `node` read as missing. `icacls … /grant
  "%USERNAME%":(OI)(CI)RX /T` fixed it. If the frontend row ever goes
  unverifiable again, check permissions before concluding the toolchain is
  absent (impl-review F4).
- `CepikRiskAdjuster.capRisk` (`CepikRiskAdjuster.java:134`) returns early when
  `risk <= cap` and skips the `overall` recomputation, so a model returning
  `risk: 3, overall: 97` for a car with a registered szkoda istotna keeps both
  numbers on screen.
- `verdict.label` is copied through unvalidated against `verdict.code`
  (`AnalysisResponseParser.java:208`, rendered at
  `analysis-result.component.html:3`), so the result's first line can read
  reassuringly above a floored verdict.
- ~~The accident-claim phrase list (`CepikRiskAdjuster.java:38`) is a substring
  match and not negation-aware, so an honest `"nie jest bezwypadkowy"`
  false-positives into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP` — the app
  calls a truthful seller a liar.~~ **Fixed 2026-09-04** (M3L5) — see "A bug worked
  from note to fix" at the end of this section.
- **Mutation testing covers the JVM side only.** PIT is wired and has run
  against `MarketPriceStatistics`; the frontend's 4 spec files / 41 tests have
  never been mutation-tested. Stryker is the tool for that stack, and the newest
  spec (`market-price-panel.component.spec.ts`) is exactly the shape mutation
  testing interrogates — several of its assertions are `toEqual([])`, which pass
  both when no caveat rendered and when the `.caveat` selector matched nothing.
  A manual revert-check stood in for it once and did find a weak assertion, but
  that does not scale past one deliberate break. Known obstacle before anyone
  starts: this project runs Vitest through `@angular/build:unit-test`, not a
  plain `vitest.config.ts`, so Stryker's `vitest-runner` may have no config to
  drive. Deferred, not excluded — cost is unknown until someone tries.
- `ListingFetchService.java:134` bounds only a *minimum* fetched-body length
  (100 chars), so a URL is the unbounded path into the prompt while pasted text
  is capped at 20 000. Cost and latency scale with whatever the reader returns.
- The 30 s NFR (`prd.md:98`) is asserted by `RequestTimeoutBudgetTest` and
  enforced by nothing: the configured socket timeouts still sum to **295 s**
  worst case (`RequestTimeoutBudgetTest.java:180`, which is the source of this
  figure — read it rather than trusting this line). Phase 3 cannot fix that with
  a test — it needs the deferred async work (impl-review F10) — but the number
  belongs on this list so the next rollout phase does not read the green
  assertion as protection.

Browser-level e2e is deliberately near-empty in this rollout: every risk above
is reachable at unit, integration, or component level, and an e2e layer over a
single-page flow would duplicate Phase 3 at a much higher cost. That still
holds for the risk map — but it turned out to be the wrong answer for exactly
one risk the map does not carry, so **e2e is now one spec rather than none**
(deviation recorded 2026-09-04, superseding the flat "not in this rollout"
that stood here from 2026-08-27).

The exception is the **frontend/backend contract**, and it is an exception on
this plan's own evidence, not on preference:
`frontend/src/app/shared/models/analysis.models.ts` is a hand-written mirror of
the backend's Java records. The backend suite asserts its own JSON; the
frontend suite asserts against hand-written doubles. So **no other layer in
this repo ever puts real backend JSON in front of the real component** — the
gap is structural, not a coverage hole a Phase 3 component test could close by
being more thorough. §6.7 records the near-miss it already produced, where
`sampleQuality` shipped server-side and "the server field would have been read
by nothing", and `MarketPriceContext`'s own javadoc warns that renaming a field
is unsafe precisely because the frontend binds by name. One input into the
original decision also changed: a Playwright **CLI** path exists where §4 had
recorded only the absent MCP server.

Scope is one spec, and the budget is meant to stay there:
`frontend/e2e/market-price-contract.spec.ts` drives a real browser against the
`mock`-profile backend and asserts the market-price panel renders the server's
own `minPricePln` / `medianPricePln` / `maxPricePln` / `sampleSize` /
`queryUrl`, comparing the DOM against the values in that same response rather
than against the mock's constants. `frontend/e2e/E2E-RULES.md` makes the
cost × signal rule binding for anything added later: a new spec belongs there
only if it too catches a contract break both other suites are structurally
blind to. Not wired into any gate (§5.1) — two servers plus a browser is the
wrong cost per edit; it belongs in the Phase 4 CI job.

Break-verified on 2026-09-04, per §1's fourth rule. Two deliberate breaks in
`MarketPriceContext`, each reverted immediately: renaming `medianPricePln` in
the emitted JSON made the panel render "Mediana — PLN" with the value gone and
failed the spec, and dropping `sampleQuality` from the JSON failed the
presence assertion. The second break is why that assertion is `toBeTruthy()`
and not `not.toBeNull()`: an absent field arrives as `undefined`, which passes
`not.toBeNull()`, so the first draft would have gone green on the exact
regression §6.7 describes.

Still unscheduled, for the reason originally given: the deployed shape — auth
plus token storage plus a guarded route — once F-03 lands.

**What a vision pass found, and why it still adds no spec (2026-09-04).** The
browser was driven once through the Playwright MCP server with `--caps=vision`
(§4) and the rendered result screen read as pixels rather than as a tree. Two
real defects, neither of which becomes an e2e test:

- **A scored zero renders as no score.** `MockAiAnalysisService` returns
  `scores.equipment: 0` — a genuine value, since `CategoryScores.equipment` is a
  primitive `int` and cannot be absent. PrimeNG's progressbar suppresses its
  `showValue` label at `0`, so the Wyposażenie row draws an empty track with no
  number while every other row draws a filled bar with a percentage. A user
  cannot tell "scored 0" from "not scored", which is the project's first business
  rule (absence of data must never read as a value) arriving from the opposite
  direction. **Fixed the same day** (`showValue` off, number rendered outside the
  bar) and pinned by two tests in `analysis-result.component.spec.ts` — a
  component test, not an e2e spec, since nothing about it needs two servers.
  Two things this cost that are worth not re-deriving:
  - **A `textContent` assertion would have passed on the broken code.** PrimeNG
    still *renders* the label div and merely sets `display: none`, so "0%" was in
    the DOM the whole time, invisible. The passing-on-a-bug draft was anti-pattern
    #1 in its purest form; the test asserts on the element that carries the
    visible number instead. Nor could CSS have fixed the defect: the label is
    nested inside a fill that is `width: 0%` at a score of 0, so it has nowhere to
    draw even when displayed.
  - **An earlier note here claimed the progressbar exposed no `aria-valuenow`.
    That was wrong** — PrimeNG binds it on the host regardless of the label it
    hides, so a screen reader was already announcing the 0 that a sighted user
    could not see, and the defect was visual only. The claim came from reading a
    Playwright accessibility snapshot, which shows the label text node but not
    host ARIA attributes; **an a11y snapshot is not an ARIA audit.** A test now
    pins the attribute so the correction stays true. §4's "accessibility: not
    scheduled" row therefore stands — this produced no accessibility risk.
- **A chip wrap breaks the equipment grid's baseline rhythm.** In the three-column
  Wyposażenie grid, `poduszki powietrzne` is long enough to push its
  `Niesprecyzowane` chip onto a second line, dropping its caption below the
  neighbouring column's. This one is **structurally invisible to the tree** — the
  wrapped row and an unwrapped row have identical nodes, nesting, and text. It is
  the one finding here that genuinely needs pixels, and it *still* gets no spec:
  a VLM verdict is not reproducible, so pixel regression belongs in a
  deterministic differ if it is ever worth pinning at all.

The general rule this leaves behind: **vision earns its cost as a discovery pass,
not as an assertion layer.** Screenshots under `frontend/vision/` are scratch
evidence for that one pass, not fixtures, and are not committed.

**A bug worked from note to fix (2026-09-04).** The negation-unaware
accident-claim matcher, listed as a §3 grounding gap above and again in §6.7, is
fixed. Recorded because the *route* is reusable, not because the fix is large:

- **The evidence phase was already done, in prose, and had stalled there.** The
  defect was written up twice — in this file and in
  `ListingClaimsCannotMoveTheFloorTest`'s class Javadoc, which said "it is unfixed.
  No test here asserts it: **pinning a bug makes the fix a test failure**." That
  reasoning is sound for a bug you are not fixing and is exactly what keeps one
  parked. A known defect belongs in a failing test or in a ticket; a Javadoc is
  neither, because nothing ever re-reads it.
- **An existing test looked like coverage and wasn't.**
  `anHonestListingThatAdmitsTheDamageIsNotAccusedOfLying` asserts precisely the
  right property, on the one input that cannot exercise it — `"szkoda naprawiona w
  ASO"` contains no accident-free phrase, so it reaches the contradiction branch by
  never reaching it. Worth generalising: a test named after a behaviour proves
  nothing about the matcher unless its input actually enters the matcher.
- **The failing test came before the fix, and so did the guard against it.** Two
  tests were written together: the reproduction (`"nie jest bezwypadkowy"` must not
  contradict) and its opposite (`"nie mam nic do ukrycia, auto bezwypadkowe"` must
  still contradict). The second **passed on the unfixed code**, which is what makes
  it a guard rather than a restatement — the seller writes the advert, so a
  negation check that scanned the whole claim would be a bypass they can type. The
  two error directions are not symmetric: a false accusation is unfair to one
  seller; a missed contradiction reassures a buyer about a registered wreck.
- **The observability gap was the bug's real cause, and PIT caught it recurring.**
  Nothing logged the verdict rewrite, so the false accusation was unobservable in
  production — the defect could only ever be found by reading the code (OWASP
  A10:2025). A log line was added, and PIT then reported its `if (denied)` guard as
  a **survivor**: nothing would notice the new trace firing on the wrong branch or
  not at all. Left there, the fix would have reproduced the original failure one
  level up. Two tests closed it; the second pins log-injection defence (a newline
  in an advert must not forge a log line), which is a security property rather than
  a wording. `CepikRiskAdjuster` 87% → **90%**, test strength 88% → **92%**.
- **A second way to read a false 0% from PIT.** `-Dmutation.targetTests` given a
  comma-separated list of fully-qualified class names matched nothing and reported
  `0/61 killed` over `BUILD SUCCESS`; the same run with `'…analysis.*'` reported
  87%. §4's PIT row said a score of 0 means the version; it can also mean the
  filter selected no tests. Check that before the code either way.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| backend unit + integration | JUnit 5 via Maven surefire | Spring Boot 4.0.6 | 44 test sources on disk; **40 classes / 363 tests run by default** (~23.7 s), across the analysis, cepik, market, saved, and auth packages. The other 4 are `live-llm`-tagged and excluded — they print no `Running` line at all, which is why a file count and a run count disagree here. (The 42nd source is `AutoskanerAiApplicationTests`, the only one not named `*Test.java`; surefire's default includes match it anyway.) The `live-tests` profile flips the include/exclude properties; `-Dgroups=live-llm` silently intersects to zero |
| frontend unit + component | vitest + jsdom via `@angular/build:unit-test` | Angular 21.2 | 14 spec files, 134 tests, ~5.4 s. Zoneless: no `fakeAsync` / `tick`; vitest matchers, not jasmine |
| mutation (backend, selective) | PIT via `pitest-maven`, `mutation` profile | 1.29.10 | Off by default, never in `./mvnw test`. A selective gate, not a coverage target: `mutation.targetClasses` / `mutation.targetTests` are narrow properties meant to be overridden per run. `MarketPriceStatistics` scored **89%** (47/53) and `CepikRiskAdjuster` **90%** (55/61, test strength 92%) on 2026-09-04, up from 81% and 87%. **The version is load-bearing** — 1.20.4 cannot parse the dev JDK's class files and reports `BUILD SUCCESS` with 0% coverage. A score of 0 means the tool or the filter, never the tests: a `targetTests` value given as a comma-separated list of class names also reports 0, where the same run with a `'pkg.*'` glob scored 87%. See `backend/CLAUDE.md` § "Mutation testing" |
| HTTP mocking (backend) | `MockRestServiceServer` (`spring-test`) | 7.0.7 | `MockRestServiceServer.bindTo(RestClient.Builder)`, then `server.verify()`. Used by `ListingFetchServiceTest`, `OpenRouterAnalysisServiceTest`, `MarketPriceFetchServiceTest`, since rollout Phase 1 also `CepikDamageReachesTheResponseTest` and `HistoriaPojazduSessionTest`, and since Phase 2 `LlmFailureReachesTheClientTest` and `AnalysisSurvivesEnrichmentFailureTest`. See §6.2 |
| HTTP mocking (frontend) | `provideHttpClientTesting` + `vi.fn()` service doubles | Angular 21.2 | Already used by the existing specs |
| live integration | JUnit tag `live-llm` + `live-tests` profile | — | Asserts real outcomes only. A proxy 403 on the reader host fails the market-price live test on purpose, so a blocked path stays visible instead of silently green |
| e2e (contract only) | Playwright (`@playwright/test`) + Chromium | 1.62.1 | **One risk spec**, by design — `frontend/e2e/market-price-contract.spec.ts`, ~2 s, plus `seed.spec.ts` as the exemplar generated specs are modelled on and, since F-03, `auth.setup.ts` as a `setup` project the `chromium` project depends on for `storageState` (3 tests, 29.5 s all in). The session is registered once there and never inside a spec, which is why both risk specs kept their bodies when `/api/**` went behind a login. Config starts both servers itself (backend `mock` profile on 10000, dev server on 4200) and reuses either if already up. Off every local gate (§5.1): two servers plus a browser is the wrong per-edit cost. Rules and the budget that keeps this row at one spec: `frontend/e2e/E2E-RULES.md`. Scope, evidence, and break-verification: end of §3 |
| repo tooling (code reviewer) | `node:test` + `tsx`, no build step | `ai` 7.0.94, `@openrouter/ai-sdk-provider` 3.0.0, **`@anthropic-ai/claude-agent-sdk` 0.3.267**, `zod` 4.5.4, tsx 4.23.13, TypeScript 7.0.2 | `packages/code-reviewer` — **168 tests in 11 spec files, ~6.9 s**, of which **10 skip offline** — every test needing a model or a credential, each printing the reason — unless `npm run test:live` sets `npm_lifecycle_event`. **Two runners behind one `Reviewer` contract** since 2026-09-10: `CODE_REVIEW_RUNNER=ai-sdk` (default, hand-assembled AI SDK loop on OpenRouter) or `=agent-sdk` (Claude Agent SDK, a `claude` subprocess on Bedrock); an unrecognised value exits 2 rather than falling back, because a comparison run driven by the wrong runner is worse than one that fails. Which one the evals wrap, and the 32 runs behind that: `context/changes/agent-sdk-reviewer/pick.md`. Not a product layer: nothing deploys from `packages/`, and it is here because a review tool that is itself broken reports a clean review. `tsc --noEmit` is the only thing that ever reads the types (tsx strips them), so the gate runs typecheck first. **The runner needed a guard of its own** — `node --test <pattern-that-matches-nothing>` prints `# fail 0` and exits 0, so `scripts/run-tests.mjs` enumerates the specs from disk and fails when the reported test count is zero; checked: 2026-09-10 |
| accessibility | none — not scheduled | — | No risk in §2 depends on it; revisit if one surfaces |
| CI | GitHub Actions, one live workflow only | — | No unit or integration gate on PR or push; see §3 Phase 4 |

**Stack grounding tools:**
- Docs: **Context7 MCP — available**, exercised during rollout Phase 2's research. It grounds framework behaviour, not versions: the versions in the table above still come from the local manifests, the Angular workspace config, and `CLAUDE.md`, which are the authority for what this repo actually resolves; checked: 2026-09-03
- Search: **Exa MCP — available**, and it produced rollout Phase 2's one genuinely external oracle: OpenRouter's published status semantics (408 is a timeout and therefore transient; 402 is insufficient credits and therefore fatal for every model, not permanent for one). That is what the 408/402 routing tests assert against, rather than against the classification tree they were written to correct; checked: 2026-09-03
- Runtime/browser: **Playwright CLI and Playwright MCP — both installed and exercised.** CLI: `npx playwright test`, Chromium 151.0.7922.34. MCP: `@playwright/mcp` 0.0.80, registered project-locally as `npx @playwright/mcp@latest --caps=vision`. The accessibility tree is what the locators in `frontend/e2e/` were written from, rather than templates or screenshots; checked: 2026-09-04. Three things learned by running both, none of which changes the one-spec budget:
  - **The CLI stays the default transport.** Both read the same accessibility tree, so they produce the same locators — driving the analysis flow through MCP emitted `getByRole('textbox', { name: 'lub wklej treść ogłoszenia' })` and `getByRole('button', { name: 'Analizuj' })` unprompted. MCP costs roughly 4× the tokens per scenario for that same output. It earns its cost only when the app has to be *explored* interactively; a spec that is already written is cheaper to run headless.
  - **MCP-generated code still needs the §1 review pass.** Asked to screenshot a container element, the server emitted `page.getByText('Oceny kategoriiKompletność71%')` — a concatenated-text-content locator that breaks when any score in the panel changes. The tool that naturally produces good locators for *named* elements produces a brittle one for an unnamed wrapper, so "the MCP wrote it" is not evidence a locator is sound.
  - **Vision (`--caps=vision`) is a discovery tool, not a regression layer, and so adds no spec here.** It found two real UI defects (recorded at the end of §3). One was fixed and pinned by two component tests, which are cheaper and more deterministic than any browser-level assertion of it would have been. The other is invisible to the accessibility tree — but pixel regression belongs in a deterministic differ (`toMatchSnapshot`, Argos, Lost Pixel), not in a VLM assertion whose verdict is not reproducible.
- Provider/platform: none — no GitHub, Render, Cloudflare, or Supabase MCP; deploy and log inspection run through REST with keys from the environment, so CI gate wiring in Phase 4 must be authored against the platform docs rather than probed; checked: 2026-08-27

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required for §3 Phase N" means the gate is enforced once that rollout phase
lands; before that, the gate is `planned`.

### 5.1 How "local" is enforced (since 2026-09-04)

"Local" used to mean "somebody remembers to run it". It is now three automated
layers, each catching what the one below cannot:

| Layer | Trigger | Scope | Cost | Runs |
|---|---|---|---|---|
| per-edit | Claude Code `PostToolUse` on `Write`/`Edit` (`.claude/hooks/post-edit-check.*`) | the edited file, if under `frontend/src` | 1.2 s (`.scss`) / 6.9 s (`.ts`, `.html`) | `prettier --write`, then the whole frontend suite for `.ts` / `.html` |
| pre-commit | `git commit` (`.githooks/pre-commit`) | staged paths only | 0.4 s when nothing matches, 9.2 s frontend, ~22 s backend, **8.4 s** reviewer package | `prettier --check` on staged frontend sources; frontend suite; backend suite when Java or `pom.xml` is staged; `packages/*` typecheck + suite when a `src/*.ts`, `scripts/*.mjs`, `package.json` or `tsconfig.json` under `packages/` is staged |
| pre-push | `git push` (`.githooks/pre-push`) | the whole tree, staged scope ignored | 39 s to `main` | backend suite, frontend suite, reviewer package (typecheck + suite), and — only for `main` — the production build |

The reviewer arm was **re-measured on 2026-09-10**, not re-estimated: 8413 ms and 8330 ms over
two consecutive runs, 1.4 s typecheck plus 6.9 s suite. Against the 8.2 s recorded when
`packages/` was first gated, a second runner, three new spec files and 88 more tests cost
**0.2 s** — the price of that arm is the Node and `tsc` startup, not the test count, which is
the same finding the per-edit layer produced for Angular. The arm stays credential-free by
construction: measured with `AWS_PROFILE`, `AWS_REGION`, `AWS_DEFAULT_REGION`,
`CLAUDE_CODE_USE_BEDROCK` and `OPENROUTER_API_KEY` all unset, 168 tests, 158 pass, 10 skip
with a printed reason, 0 fail.

Four things about this worth keeping straight:

- **Fresh clones need one command**: `git config core.hooksPath .githooks`. It is
  not `lefthook.yml` deliberately — Lefthook needs a root `package.json`, and
  Cloudflare Pages builds this repo from a subdirectory on every push to `main`,
  so a new root manifest is an unverifiable risk to a live deploy path.
- **pre-push is the last gate, not a pre-filter.** Normally pre-push sits in
  front of CI. Here there is no CI yet and `main` auto-deploys to Render and
  Cloudflare, so it stands where CI would. That is why it ignores staged scope
  and why the production build runs there: a template type error passes both
  suites and fails only in the AOT build that Cloudflare runs.
- **A gate that cannot report is worse than no gate**, because it reads as
  coverage. The hook this replaced had a trigger, a matcher and a handler but
  its signal was hard-wired to success (`catch → exit 0`, wrapped in
  `2>/dev/null || true`). It was dead from May to September — `node` was not on
  PATH — and the only symptom was `prettier --check` eventually reporting 23 of
  23 files unformatted. Every layer here therefore fails loudly when its own
  toolchain is missing, rather than skipping.
- **A test runner that reports success on zero tests is that same failure in a
  different tool.** `node --test src/*.test.ts` exits 0 with `# fail 0` when the
  pattern matches nothing, so "the suite passed" and "there is no suite" are the
  same signal. That mattered the moment `packages/` became a gated layer, and it
  is why the reviewer arm goes through `scripts/run-tests.mjs`: the spec list is
  read from disk rather than expanded by whichever shell invoked npm, and a run
  that reports no tests fails regardless of its exit code. Worth checking in any
  runner added here — the question is not "did it pass" but "can it say it
  didn't".

Each path was verified by watching it block, not by reading the code: the
per-edit layer against a broken caveat string, the pre-commit prettier arm
against an unformatted staged file, the backend arm and the whole
`core.hooksPath` chain against a throwaway failing test that `git commit`
refused, and the `packages/` arm against an inverted assertion in
`verdict.test.ts` (blocked, naming the assertion) plus a run with every spec
file moved aside (blocked on the empty suite, where the bare runner would have
passed).

### 5.2 The gates

| Gate | Where | Required? | Catches |
|---|---|---|---|
| frontend formatting | local (per-edit `--write`, pre-commit `--check`) | required now | diff noise that hides real changes in review. `frontend/.prettierrc` shipped with the scaffold and went unenforced until 2026-09-04, by which point every file in `src` violated it |
| compile + typecheck | local (per-edit and pre-push, via the Angular build), CI after §3 Phase 4 | required after §3 Phase 4 | type and syntax drift; stale specs that no longer compile |
| backend unit + integration | local (pre-commit when staged, pre-push always), CI after §3 Phase 4 | required after §3 Phase 1 | logic regressions across analysis, cepik, and market |
| frontend unit + component | local (per-edit, pre-commit, pre-push), CI after §3 Phase 4 | required after §3 Phase 3 | guardrail copy regressions and state collapse in the rendered result |
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

- **Location / naming**: as §6.1, except a test that spans several classes is named for the behaviour rather than a class under test — `CepikDamageReachesTheResponseTest`, not `<Class>Test`.
- **Reference tests**: `backend/src/test/java/com/example/autoskaner_ai/cepik/CepikDamageReachesTheResponseTest.java` for a full stack behind a stubbed socket; `.../analysis/AnalysisControllerTest.java` for the controller boundary alone.
- **The seam**: build the same `RestClient.Builder` production configures (mirror the `@Configuration` bean minus its request factory), call `MockRestServiceServer.bindTo(builder)`, hand that builder to the real service. The bind survives later `builder.build()` calls, so a class that rebuilds its client mid-session — as `HistoriaPojazduSession` does per cookie refresh — is still stubbed.
- **Pattern**: expect the whole call sequence in order, respond with verbatim captures via `withSuccess(new ClassPathResource(...), APPLICATION_JSON)`, assert the HTTP response body, finish with `server.verify()`.
- **Expectations are ordered by default, and that is useful**: the order is what pins which payload reaches which argument. Swapping `parser.parse`'s two arguments kept the entire repo green until this test existed.
- **Six gotchas, each learned the hard way**:
  - A session closed in a `finally` block still makes a request. Stub it, or an unexpected-request `AssertionError` fires *after* your assertions have passed and reads like a mystery.
  - `jsonPath(...).doesNotExist()` and `.value(nullValue())` both pass for a JSON `null`, so neither can prove a key is present-and-null. Read the raw body: `andReturn().getResponse().getContentAsString()`.
  - `MockMvcBuilders.standaloneSetup` builds its own message converters and never reads `application.properties`. Anything that depends on the application's Jackson configuration needs a booted context — see `CepikResultSerialisationTest`.
  - A bodyless 200 fails inside `RestClient` and lands in your service's catch block, so a status assertion can pass without the branch you meant to exercise ever running. Serve `{}` when you want an *unreadable payload* rather than a *transport failure*.
  - **A null default header is present-with-a-null-value here, not absent.** `HistoriaPojazduSession` installs `X-Xsrf-Token` with whatever it extracted, and with no token that is `null`. Production's request factory coerces it to an empty string on the wire; `MockRestServiceServer` keeps it literally present, so `headerDoesNotExist` fails with *"it exists with values: [null]"*. Assert `headerList(name, contains((String) null))` and note in a comment that this pins the seam's truth rather than the wire's — see `HistoriaPojazduSessionTest.aHandshakeWithNoXsrfCookieWarnsInsteadOfDegradingSilently`.
  - **A failed matcher throws `AssertionError`, which is an `Error`**, so it escapes a `catch (Exception)` in the code under test and surfaces as a test failure instead of being wrapped into that class's own exception. This is what lets header assertions work at all inside a method that swallows failures — and it means widening such a catch to `Throwable` would silently mute every one of them. Say so in the test, because nothing in the production code hints that a test depends on the catch's width.
- **Mocking policy (binding)**: mock at the network edge only, never internal collaborators. Registry fixtures under `backend/src/test/resources/cepik/` follow §6.5; the market-price fixtures under `backend/src/test/resources/market/` follow the same rule for the same reason — see that directory's README.
- Provider and reader *failure-branch* coverage (deadlines, retry-vs-fallback, thin samples) shipped with rollout Phase 2; the pattern lives in §6.4. This section covers the seam.

### 6.3 Adding a frontend component test

- **Location**: next to the component, `<name>.component.spec.ts`.
- **Reference test**: `frontend/src/app/features/analyzer/analyzer.component.spec.ts`.
- **Binding constraints**: no `fakeAsync` / `tick` (the app is zoneless); vitest matchers only (`toBe(true)`, not `toBeTrue()`); use `await fixture.whenStable()` if real async ever appears.
- **Run locally**: `cd frontend && npm test -- --watch=false`.
- Fuller guidance on the three-state rendering pattern: TBD — see §3 Phase 3.

### 6.4 Adding a test for a new API endpoint

- **Test type**: integration, at the controller boundary.
- **Pattern**: assert the response shape *and* the guardrail semantics — which fields are null versus empty, and what a downstream consumer would render from them.
- **Reference test**: `backend/src/test/java/com/example/autoskaner_ai/analysis/AnalysisControllerTest.java`. For an endpoint whose behaviour depends on an outbound call, stub the socket instead of the collaborator — see §6.2.

**The failure branches** (pattern established by rollout Phase 2):

- **Assert the user-visible outcome, never the retry count.** A retry that fired is not a protection; a client that can tell which failure it hit is. `LlmFailureReachesTheClientTest` is the reference: four provider behaviours in, four different Polish `error` headlines out, at the controller boundary with only the provider socket stubbed.
- **Distinguish the causes, and prove the distinction rather than each cause separately.** Before Phase 2 a rejected key, an unusable provider response and an exhausted fallback chain rendered byte-identical 502 bodies. Hold each expected headline as a named constant and add one test asserting the set has no duplicates — comparing two branches directly passes when both are wrong in the same way.
- **A malformed third-party payload is a 502 about them, not a 500 about us.** Four provider-quirk routes (null `message`, null `content`, non-String `content`, unknown enum value) used to escape as the catch-all 500. Each needs its own case; a shared "returns an error" assertion cannot see the difference.
- **Serve `{}` for an unreadable payload** rather than an empty body — §6.2's gotcha, and it bites hardest here, where the branch under test *is* the parse failure.
- **Reach a deadline or budget branch by injecting the budget, not by waiting.** `OpenRouterAnalysisService`'s `deadlineSeconds` is a constructor parameter; `0` reaches the fallback-budget-exhausted branch at zero wall-clock cost. A timing assertion of the shape `elapsedMs >= 900` is *not* evidence a wait happened — it passes equally when the wait was clamped to zero, which is exactly the defect Phase 2 found.
- **A degraded enrichment must not cost a finished analysis.** `AnalysisSurvivesEnrichmentFailureTest`: make the enrichment collaborator throw, then assert a 200 that still carries the analysis and a non-null but degraded context. Keep the negative control in the same class — an *LLM* failure must still be a 502, or the guard has quietly turned every failure into a cheerful 200.
- **Assert the configured budget against the documented NFR.** `RequestTimeoutBudgetTest` reads the timeouts out of the config classes and compares the total against `prd.md`'s 30 s, quoted in a comment as oracle source (4). It exists so a future timeout bump fails the build. It does not claim the NFR is enforced — it is not; see §6.7.

### 6.5 Adding a test for a new enrichment source or registry field

**Fixtures come in exactly two classes.** The distinction is load-bearing; see `backend/src/test/resources/cepik/README.md` for the full convention.

- **Verbatim captures** (`*-found.json`, `not-found-*.json`, no suffix) — byte copies of real responses. The only permitted edit is a documented redaction of an identifying value; the committed VIN is the synthetic `NMTBZ3BE40R000000` because this repo is public. **Add no field mapping without a captured payload showing that field name.**
- **Derived fixtures** (`*-derived.json`) — produced from a named verbatim parent by exactly one of *deleting a node* or *changing a value*. Never by introducing a key, renaming a key, or composing an object. Each carries a top-level `_provenance` naming its parent and the edit, so the claim is checkable with a diff.

**The rule generalises past JSON, and rollout Phase 2 needed it to.** *Third-party payloads must be captured; shapes we own may be composed.* The Otomoto markdown that `PRICE_PATTERN` reads is Otomoto's, rendered by Jina — neither is ours, so a hand-written markdown fixture would be the regex agreeing with itself, and that is the 2026-08-26 failure in a different file format. The `List<Integer>` that `MarketPriceStatistics` consumes **is** ours — an internal parameter, not a wire format — so composing lists to land on the band, fence and median boundaries is correct. Both live under `backend/src/test/resources/market/`; that directory's README carries the capture's provenance.

A **derived fixture can need a guard of its own.** The market capture is LF-only, so it can only ever exercise half of `\r?\n`; its CRLF sibling is derived by one mechanical edit. `core.autocrlf=true` on any clone or CI runner would rewrite both to one style and the pair would silently test one case twice, so both files are pinned `-text` in `/.gitattributes` **and** a test asserts the pair still differs only in line endings. A config file that nothing checks is not a guarantee.

A third class is prohibited: a fixture composed key-by-key from what the parser expects cannot detect a mapping failure, because it *is* that failure one file earlier. That is the 2026-08-26 incident verbatim — the parser looked for `zdarzenia` / `szkodyIstotne` / `przebieg`, names the registry has never returned, and every test passed because the fixtures had been written to match them.

**The invariant: null, empty, and populated are three states, not two.**

- `null` — we do not know. No lookup, a failed lookup, an unreadable payload, or a vocabulary we no longer recognise.
- `[]` — the registry answered and reported nothing. A positive claim.
- populated — findings.

The UI renders `[]` as "brak zgłoszonych szkód istotnych" and `null` as unknown, so collapsing them inverts the product's core guardrail. Enforcement sits in two places: `CepikResult.withoutData` makes every non-`FOUND` status null by construction, and `HistoriaPojazduParser`'s **vocabulary canary** (`KNOWN_EVENT_TYPES`) degrades to null rather than `[]` when the registry's `eventType` values drift out from under the parser. Assert the tri-state at the wire too — see §6.2's `jsonPath` gotcha.

Known gap in the canary: it accepts the vocabulary if *any one* event type is recognised, so a rename of `szkoda-istotna` alone would still yield `[]`. Narrower than the original failure, still a false-clean route.

**Expected values may come from four sources and no others.** Independence from the implementation is the whole point:

1. the bytes of a committed capture,
2. a production incident (e.g. `risk: 88 / WORTH_CHECKING` for a car with a registered szkoda istotna, 2026-08-26),
3. hand arithmetic or calendar rules, written out in a comment,
4. a stated product guardrail — *absence of accident data means unknown, not clean*.

The `CLAUDE.md` files (root plus `backend/` and `frontend/`, which load additively) are the **specification of record, not an independent derivation** — quoting them is source (4), not a fifth oracle. Reading the class under test is never a source. Where a value genuinely has no oracle outside the implementation (the risk-cap magnitudes), pin it in exactly one test and say so in that test's comment; assert the falsifiable property — their ordering — separately. `CepikRiskAdjusterTest` is the reference for that split.

- **Reference tests**: `HistoriaPojazduParserTest` (captures → model), `CepikDamageReachesTheResponseTest` (captures → HTTP response), `RegistryFactsReachTheScoreTest` (derived fixtures → score, with a control on the unmodified capture).
- **Verify with a mutation, not a green run.** Break the thing the test exists to catch and confirm it fails. A test that survives its own mutation is decoration — §1's fourth rule.

### 6.6 Adding a CI gate

- TBD — see §3 Phase 4.

### 6.7 Per-rollout-phase notes

**Phase 1 — Enrichment honesty** (`testing-enrichment-honesty`, shipped 2026-09-01). 132 → 163 backend tests.

The phase set out to *prove* Risks #2 and #3 and found two live false-clean routes in production code on the way, so it **closed them rather than pinning them**. That distinction is the phase's main lesson: a rollout phase that only writes tests will happily freeze the bug.

- `HistoriaPojazduParser` reported `FOUND` with every field empty when neither payload was readable — a "found in the registry" panel with nothing in it, which reads as a clean history. Now `LOOKUP_FAILED`.
- The same parser returned `damageRecords: []` whenever the registry's `eventType` vocabulary drifted. Now a canary (`KNOWN_EVENT_TYPES`) degrades to `null`. Known residual gap in §6.5.
- Separately, `RealCepikEnrichmentService` rejected `"WA 12345"` — the commonest way a correct plate is written — as `MISSING_INPUTS` with no lookup at all, while the same user's spaced VIN was normalised fine.

What the phase proved, and how:

- Captured registry bytes reach both the response payload and the verdict, over a stubbed socket with the whole stack real (§6.2).
- The null / `[]` / populated tri-state survives to the wire, including against the application's own Jackson configuration.
- `NOT_FOUND` is driven by a real 404 wrapped by real code, not by a test writing the exception message it then matches.
- The risk adjuster's oracles no longer include the code under test: the mean-of-four formula copy is gone, which is what had made the never-raise guard unreachable by all fourteen tests that existed.

Method worth reusing: **every phase criterion was a mutation, not a green run.** Six mutations were applied and reverted. One criterion turned out to be unreachable as specified — a raw-body assertion under `standaloneSetup` is blind to `spring.jackson.default-property-inclusion`, because that builder never reads `application.properties` — and only running the mutation revealed it. Writing the criterion as "confirm X fails" rather than "confirm X is covered" is what caught it.

**Phase 2 — Availability and failure paths** (`testing-availability-failure-paths`, shipped 2026-09-03). 163 → 224 backend tests, 23 → 28 test files.

Research found that **nine of the behaviours this phase existed to prove were broken**. Writing tests against them would have pinned the failure mode, so — Phase 1's lesson applied a second time — the phase closed them first and then proved them closed.

Closed, not pinned:

- **A 200 OK could carry a hollow analysis.** `AnalysisResponseParser.validateRequired` null-checked six *containers* while all sixteen leaf fields could be null, and `ScoresDto`'s primitive `int` coerced a null score to `0` — a perfect score for a field the model never returned. Now a schema failure that names the field.
- **Three distinct 502 causes rendered byte-identical bodies**, and four provider-quirk routes (null `message`, null `content`, non-String `content`, unknown enum) escaped as a generic 500 blaming this server for somebody else's payload. Now four distinguishable Polish headlines.
- **The retry could fire with a zero wait.** `retryWait` clamped to the deadline remainder and `sleepQuietly` returned `true` for a non-positive wait — precisely the immediate same-model retry that turned single 429s into production 502s on 2026-08-26. A wait that will not fit the remaining budget now moves down the fallback chain instead.
- **408 and 402 were both misrouted** into the "permanent for this model" catch-all: a timeout skipped a model that would likely have answered, and insufficient credits walked the entire chain on an error guaranteed to repeat. Now 408 retries and 402 fails fast.
- **A finished analysis could be discarded.** `AnalysisController.buildResponse` had no try/catch, so a throw from the slug mapper or the statistics stage turned a completed ~16 s analysis into a 500. Now guarded, and `marketPriceContext` is always present — S-05's stated invariant.
- **The registry session leaked cookies between lookups.** One shared mutable `RestClient.Builder` bean, mutated per session, meant lookup *N*'s `JSESSIONID` was still on it for lookup *N+1*'s bootstrap GET and two concurrent analyses shared one jar. Both enrichments run on the request thread, so that is ordinary load, not a stress case. Now a clone per session — `RestClient.Builder#clone()` copies the request factory, so §6.2's seam survives it.
- **`accidentClaim == null ⇒ NO_ACCIDENT_DECLARATION` was asked of the model and enforced nowhere.** Now enforced in the parser, idempotently, so an *unknown* accident history cannot render as a *silent* one.
- **The thin-sample label had an off-by-one at exactly 3**, and dispersion was computed then thrown away. `MIN_SAMPLE_TO_KEEP = 3` reports a 3-price sample untrimmed while the UI caveat fired on `sampleSize < 3` — so the most contaminated range the pipeline can emit was the one size that showed no caveat at all. `MarketPriceContext` now carries `sampleQuality` (`SUFFICIENT` / `THIN` / `DISPERSED`) and `discardedCount`, decided on the server.
- **`HistoriaPojazduSessionTest`'s own Javadoc was false**, claiming the cookie merge was asserted in `CepikDamageReachesTheResponseTest`. It was asserted nowhere in the suite.

What the phase proved, and how:

- Four provider behaviours reach a client as four different Polish headlines, asserted at the controller boundary with only the provider socket stubbed and the parser, service, controller and exception handler all real (§6.4).
- Retry-versus-fallback is decided against the remaining budget, and the deadline-skip branch runs under test at zero wall-clock cost by injecting `deadlineSeconds=0` rather than waiting.
- The configured socket-timeout total is asserted against `prd.md`'s 30 s, so a future timeout bump fails the build.
- An enrichment throw costs the market range and not the analysis — with the negative control that an *LLM* failure is still a 502, not a cheerful degraded 200.
- The registry cookie header and XSRF token are asserted directly at the seam, with RFC 6265 §5.4 as the oracle rather than a reading of `extractCookies`.
- Listing-supplied claims cannot move the deterministic floor, with a control on an *honest* disclosure so the claim strings do real work rather than passing against an adjuster that flags unconditionally.
- The price regex met real Otomoto bytes for the first time, and the capture turned out to carry real contamination: one price at 21 800 against a median of 79 900, below the ±3× band floor, dropped — so the reported minimum is 40 900 rather than a bargain that does not exist. A composed fixture could not have contained that.

Three method lessons, each of which cost something to learn:

- **A mutation can be a no-op, and only measuring tells you which.** One planned criterion was "delete `\r?` from `PRICE_PATTERN` and watch the CRLF test fail". Measured against both fixtures, deleting it changes the match on neither: `[\d\s]+` already admits `\r` and `replaceAll("\\s", "")` then removes it. `\r?` is unfalsifiable while the character class stays that wide. Two mutations that do bite were substituted — narrow the class to `[\d \n]+`, and normalise the derived fixture to LF. Report the mutation you ran, not the one the plan named.
- **§6.2's third gotcha, demonstrated rather than restated.** Appending `spring.jackson.default-property-inclusion=non_null` failed the booted `MarketPriceContextSerialisationTest` while all fifteen `AnalysisControllerTest` cases stayed green. That is the blind spot, measured — and the reason a serialisation claim needs a booted context.
- **A contract change needs its consumer wired in the same phase.** The plan assigned the new `sampleQuality` field's rendering to its Phase 3; that did not land, so the server field would have been read by nothing and the user-visible hole the work existed to close would have survived a phase built to close it. Caught two phases later. The template is now wired but **reviewed, not verified** — no Node exists on this machine, so the frontend suite could not run; the spec belongs to rollout Phase 3.

Known gaps, left open on purpose. Each would have been *pinned* by a test asserting today's behaviour, which §1 rule 4 forbids, so each is recorded here and carried into rollout Phase 3 instead:

- `CepikRiskAdjuster.capRisk` returns early when `risk <= cap`, skipping the `overall` recomputation. A model returning `risk: 3, overall: 97` for a car with a registered szkoda istotna keeps both numbers.
- `verdict.label` — the model-authored headline, rendered as the result's first line — is never validated against `verdict.code`, so it can read reassuringly next to a floored verdict.
- ~~The accident-claim phrase list is not negation-aware. `"nie jest bezwypadkowy"` — an *honest* seller — still false-positives into `CEPIK_CONTRADICTS_LISTING` and `HIGH_RISK_SKIP`.~~ **Fixed 2026-09-04**, with the guard against the loose fix and the missing log line; see "A bug worked from note to fix" at the end of §3.
- `ListingFetchService` bounds only a *minimum* body length (100 chars), so a URL remains the unbounded path into the prompt while pasted text is capped at 20 000.
- The 30 s NFR is **asserted, not enforced**. The 295 s worst case is still reachable; this phase made it visible and regression-guarded, not impossible. Enforcement is impl-review F10's deferred async work. (The figure was written here as ≈341 s, which was the pre-Phase-2 sum: Phase 2's own removal of the retry clamp took the LLM stage from 156 s to 110 s, and the Phase 7 backport instruction had been drafted before that landed. `RequestTimeoutBudgetTest.java:180` is the number's only source of truth.)

### 6.8 Pinning a rule that must hold of every implementation of a port

Numbered last only because §6.6 and §6.7 are cited by number from other change
folders and renumbering them would rot those citations. Read it *before* §6.1 when
the rule you are about to pin belongs to an interface rather than to a class.

**When it applies.** The rule is a property of the port, and a second
implementation is free to mean something else by it without anything noticing.
Both live cases came from the same shape: a profile-switched interface
(`CepikEnrichmentService`, `AiAnalysisService`) whose mock is the only
implementation the git hooks and the E2E specs ever run — so a drift in the mock
is a drift in every gate, and the type system carries none of it.

- **Location**: next to the port, in the port's own package —
  `backend/src/test/java/.../cepik/CepikEnrichmentServiceContractTest.java`,
  `backend/src/test/java/.../analysis/AiAnalysisServiceContractTest.java`.
- **Naming**: `<Interface>ContractTest.java`. **A deliberate deviation from §6.1**
  (`<ClassUnderTest>Test.java`) and from §6.2 (a test spanning several classes is
  named for the behaviour): here the subject is the interface and the
  implementations are *parameters*, so neither convention fits. Both files say so
  in their class comment and point back to this section.
- **Shape**: `@ParameterizedTest` + `@MethodSource("implementations")` over a
  private `record Implementation(String name, <Port> service, <Collaborator> …)`
  whose `toString()` returns the name — a failure then reads
  `MockCepikService: null date must yield MISSING_INPUTS` rather than `[2]`. The
  factory is a `Stream.of` with one element per implementation, so **adding a bean
  to the contract is one line**. That is the whole point of the shape: an
  implementation the contract does not run against is an implementation free to
  drift.
- **Build the parameters fresh per method.** JUnit calls the `@MethodSource`
  factory once per `@ParameterizedTest`, so a mocked collaborator constructed
  there never leaks interactions from one property into the next.
- **`assertSoftly` over the input list, never a hard assertion.** Measuring a
  candidate implementation against a contract should cost one run, not one run per
  defect.
- **A collaborator that only one implementation has is skipped, not dropped.** One
  `CepikEnrichmentService` property is about an outbound call — malformed inputs
  never reach the registry — and only the real bean has a registry to leave alone;
  a mock answers out of itself. So the record's collaborator is nullable and the
  `verifyNoInteractions` assertion is skipped where it is null, rather than the
  parameter being excluded. The status and null-list properties still bind every
  implementation. Note *why* that property cannot ride on the status: a bean could
  call the registry, discard the answer, and still return `MISSING_INPUTS`.
- **The oracle is the port's specification, never an implementation.**
  `AiAnalysisServiceContractTest`'s oracle is `AnalysisPrompt.java:16` verbatim
  plus the root `CLAUDE.md` guardrail — *not* `AnalysisResponseParser`, whose
  agreement is the thing being measured. §6.5's four-source rule holds unchanged.
- **Run locally**: `cd backend && ./mvnw -o test -Dtest='*ContractTest'`.
- **Verify with a mutation, not a green run.** Both were verified by negating the
  guard the property depends on and watching the contract go red. Note that a
  contract asserting *opposite* directions from two tests can only be broken by the
  **full negation** of a condition — widening it leaves one of them green, which
  reads as a passing break-check on a half-broken guard.

**Two asymmetries decide what a contract may assert at all**, and they are why both
files carry an explicit "what is deliberately not asserted" block:

1. **Only the stretch of the port where the implementations *must* agree is
   assertable.** With well-formed inputs `RealCepikEnrichmentService` delegates to
   the registry and returns what it said, while a mock synthesises an answer —
   there is no shared property there at all. That is why the entire inputs axis of
   the CEPiK contract is malformed-only: every property is a statement about an
   absent or unusable input.
2. **The parameters may not consume the same input, and faking it is worse than the
   asymmetry.** `AiAnalysisService.analyze` takes a listing text, but the port's
   real counterpart is `AnalysisResponseParser`, where the accident rule actually
   lives, and a parser consumes *model JSON*. No single input drives both sides, so
   what is shared is the **property**, not the input — the parser side is reached
   through an adapter whose `analyze` ignores its argument, with a deliberately
   empty string in its input list to make that visible. A reader who assumes both
   parameters saw the same text draws a wrong conclusion from the next failure.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **The mock profile's own canned *values*** — the numbers and strings `MockAiAnalysisService` and `MockCepikService` invent (a risk of 65, `TOYOTA COROLLA`, a 26 320 km stamp, PZU as the insurer) are fixture material, and asserting them pins a stub to itself. Still excluded, and the second-order consequences are excluded with it: `MockCepikService` always answers `TOYOTA COROLLA`, so under `mock` a non-Toyota listing shows a registry identity mismatch, and that is a property of the fixture rather than a defect to pin. **But the re-evaluation clause fired on 2026-09-10, and this bullet used to be too wide.** Both mocks did encode business logic, and one had inverted a guardrail: `MockAiAnalysisService` suppressed `NO_ACCIDENT_DECLARATION` on the substring `"historia"` and rated it `HIGH` where the parser rates it `MEDIUM`, and no test had ever pushed `MockCepikService`'s own `FOUND` result through `CepikRiskAdjuster`. Under the `mock` profile those are the only paths the git hooks and the E2E specs run, so the exclusion was shielding the one implementation every gate depends on. The line now falls between a mock's *values* and the *rules its port promises*: the values stay untested, and every rule is asserted against **all** implementations at once (§6.8). (Source: Phase 2 interview Q5, narrowed by change `refactor-opportunities` Phases 1–3.)
- **Anything a port's implementations may legitimately disagree about** — the exclusion a contract test draws by its own scope. Both live narrowings are worth naming here because each reads as an omission until you see why it cannot be asserted. `CepikEnrichmentServiceContractTest`'s date axis covers `null` and blank **only**: `RealCepikEnrichmentService` parses six formats under `ResolverStyle.STRICT` and rejects `31.02.2016` and `kwiecień 2022`, while a mock that treats any non-blank string as well-formed is still a *correct* implementation of the port — so format coverage stays in `RealCepikEnrichmentServiceTest`, where the behaviour lives, and the contract does not bind every future bean to one date parser. And `result.vin()` is not asserted at all: "the result echoes the requested VIN" is false by design, since `invalidVinShortCircuitsWithNullVin` pins it to null for `"NOT-A-VIN"` (there is no normalised VIN to echo) while the plate and date cases do carry it — a contract assertion would have to pick one and contradict the other. On the `AiAnalysisService` side the same rule excludes three things: the converse ("no flag when a declaration exists" — over-flagging is noise, a missing flag hands a buyer an unknown history dressed as a clean one, and the two error directions are not symmetric), flag count and ordering (the parser only ever appends), and the Polish description string, which `ListingClaimsCannotMoveTheFloorTest` pins instead. Re-evaluate a narrowing when a new implementation makes the disagreement illegitimate — not before. (Source: change `refactor-opportunities` Phases 1–2; see §6.8.)
- **The deprecated risk endpoint** — slated for deletion since S-01 shipped; testing it entrenches something the roadmap wants gone. Re-evaluate only if the decision to remove it is reversed. (Source: Phase 2 interview Q5; roadmap S-01 carried-forward.)
- **DTO plumbing** — Java records, Angular model interfaces, getters, and straight field mapping with no logic. Re-evaluate for any type that gains defaulting, normalisation, or validation. (Source: Phase 2 interview Q5.)
- **Visual appearance is not excluded, but it is not scheduled** — no risk in §2 is visual-only, so under cost × signal nothing is spent there this rollout. If a layout or z-index failure surfaces, prefer a deterministic diff over a vision model. (Source: Phase 2 interview Q5 — the builder declined to exclude it.)

## 8. Freshness Ledger

- The first CRUD surface, and the two defects a green suite could not see: 2026-09-11 — change `save-view-delete-analyses`, the last link of `data-layer-setup` → `auth-scaffold` → `save-view-delete-analyses` and the change that makes all four CRUD actions reachable by a user. Backend **340 → 363 tests in 38 → 40 classes** (~23.7 s) and frontend **99 → 134 in 11 → 14 spec files** (5.8 s → 4.8 s → **5.4 s** across three runs with no code between the last two, which is measurement noise on the Angular bundle build and not a signal about the tests — the price at this layer is still the build and not the test count). **Both count labels were written wrong on the first pass and caught by measuring rather than by reasoning**: the class count was recorded as 43 and is 40, because the three test files the backend commit touched hold 31 tests but only 23 of them are new — `SavedAnalysisRepositoryTest` came from F-02 with the entity and gained 2 of its 10. Summing per-class figures to check a suite total is exactly the arithmetic that produced the wrong number; `grep -c '^\[INFO\] Running '` and the surefire `Tests run:` line are the only two figures worth writing down. No new dependency on either side: the backend reuses Spring Data JPA from F-02 and the principal from F-03, the frontend added no package. Shipped **without a plan document**, deliberately — the hand-in is 14 September and F-02's schema plus F-03's principal had already fixed the shape, so the decisions went into `context/changes/save-view-delete-analyses/change.md` instead. **The finding that outlives the change: two real defects were found by opening a browser, over code that 131 tests passed on.** The save box prefilled the title `2019` — the extraction returned a year but no make or model, and the suggested-title function joined whatever parts existed, so the dated fallback only fired when *all three* were absent; a list of three cars from one year would have been three rows labelled `2019`. And the list rendered `64900 PLN` / `118500 km`, ungrouped. Neither was subtle once seen and neither was reachable from a unit test, because **a suite checks the claims someone thought to make, and a prefill nobody would choose is not a claim anyone writes down** — the existing test covered the all-absent case and was correct about it. Both now have tests, one of which (`does not prefill a bare year as the title`) asserts a negative about the output rather than an equality, because the set of bad titles is larger than the set of good ones. The transferable rule: *for any view whose job is to be read, the walkthrough is a verification step and not a nicety* — put it after the suite and before the commit, not after the deploy. **Two security properties, both asserted rather than described.** Ownership is in the `where` clause (`findByIdAndUserId`, `deleteByIdAndUserId`), so no row belonging to another user is ever loaded and then judged — the alternative is one forgotten `if` from a leak, and the forgotten `if` is invisible in review because the happy path is byte-identical; reverting `deleteByIdAndUserId` to `deleteById` turns 3 of 10 service tests red, both ownership assertions among them. And no request record has a `userId` field *at all* — not ignored-if-present, absent — with the frontend test asserting it **on the wire** (`Object.keys(req.request.body)`, `req.request.params.keys()`) rather than on the type, since a TypeScript type is erased and the request is what the server sees; the planted `userId` failed with `expected [ 'title', 'analysis', 'userId' ] to not include 'userId'`, which is the shape worth copying because the message names the leaked field instead of saying an assertion failed. A third break confirmed the two-click delete: removing the arming step reddened 2 tests at `component.askDelete(41)`. **Verified against a live local API rather than asserted**, all five endpoints and two accounts: 401 anonymous, 201 create with the payload round-tripping whole, a 1-row list with every summary column populated, 200 read, a 200 rename where `updatedAt` genuinely moved past a `createdAt` that stayed put (the pair a `@PreUpdate` typo breaks silently), 204 delete then 404 on a repeat — and a second account getting 404 on GET, PATCH and DELETE of the first's row plus an empty list, which is the same fact from the other side. **Three environment traps worth not re-deriving.** `git checkout -- <path>` is a **silent no-op on an untracked file**: a deliberate break in a brand-new file exits 0, still shows `??`, and is still there — it must be reverted by hand and the revert verified by grep, and what caught it was distrusting the restore rather than remembering the rule (this is the second time the break-check procedure cost something; see the `refactor-opportunities` entry, where the failure was restoring *before* staging). Git Bash **mangles non-ASCII inside inline `curl -d` arguments**, so an em dash in a PATCH body returned `400 "Nieprawidłowy JSON"` while the same character worked from a heredoc-written file and from the browser form — the symptom accuses the API of a parsing bug it does not have, and the first reading of that 404-adjacent 400 was "the PATCH endpoint is broken". And `node` under Git Bash cannot read MSYS `/tmp` paths (`require('/tmp/x.json')` resolves to `D:\tmp\…`); pipe on stdin. **Deployed and re-verified live the same day** (`2bb9674`, Render `dep-dai1rp15efls73arhdd0` + Cloudflare `966fee18`): the whole matrix again, on real Postgres this time instead of in-memory H2, with two fresh accounts — and it needed no migration and no env-var change, since F-02's `V1__init.sql` already created `analyses`, which made this the first change in the project where a deploy was code only. Two findings the local run could not produce. **A 201's `createdAt` is not byte-identical to the stored one**: the response carried `15:33:13.606778657Z` from the JVM and the row reads back `15:33:13.606779Z`, because `timestamptz` keeps microseconds and rounds — harmless for display, fatal for any client comparing a POST-returned timestamp to a later GET's for equality or using one as a concurrency token, and H2 will not show you this. And **the first verification script read `body.id` where `SavedAnalysisDetailResponse` nests it at `summary.id`**, so it called `/api/saved-analyses/undefined` five times and collected five 500s that read exactly like a broken deploy — while the 201 and the list in the same output proved the API was fine. A verification script is code and gets the same distrust as code: when every call fails and one succeeds, suspect the caller. The procedural cost was real — those two accounts' generated passwords died with the shell, so **one `analyses` row now exists in production that no API call can delete**, there being no delete-account endpoint and no endpoint that removes another user's row. A throwaway credential used for a production write must outlive the write, or the cleanup path has to exist first
- `/api/**` stopped answering anonymous callers: 2026-09-11 — change `auth-scaffold`, the middle link of `data-layer-setup` → `auth-scaffold` → `save-view-delete-analyses`, and the first change in this project that alters what a visitor who is not logged in can do. Backend **285 → 340 tests in 38 classes** (~22.7 s) and frontend **51 → 99 in 11 spec files**, 2.8 s → **5.8 s**; the `.githooks/pre-commit` and `.githooks/pre-push` labels moved with both. **The 255 → 285 backend step belongs to `data-layer-setup`, which has no entry of its own** — it landed the same day and is deliberately invisible (entities, `V1__init.sql`, in-memory H2 by default, no endpoint), so nothing in §2 changed and only the count did. Nothing new on either dependency tree: the backend signs with Nimbus, already on the classpath via `spring-boot-starter-oauth2-resource-server` (jjwt would have bound the project to Jackson 2, which Spring Boot 4 does not ship), and the frontend added no package at all. Risk #7's note is rewritten rather than closed — see §2. **The finding that outlives the change: the security property of the whole design is asserted by dumping `localStorage`.** The 15-minute access token lives in a signal and the 14-day refresh token in storage, because the SPA and the API are different sites and a session cookie would be a third-party cookie; that split is only a claim until a test enumerates every storage key and asserts the access token is not among them. A test that merely checks the token *works* passes just as well when it has been written somewhere an injected script can read it. **Three more worth not re-deriving.** First, **`POST /api/auth/register` answers 201, and only the E2E layer noticed.** Every unit spec flushes through `HttpTestingController`, which defaults to 200, so a hand-written double agrees with whatever it was written to agree with — the same structural blindness that put the one Playwright contract spec in §3, arriving this time on a status code rather than a field name. Second, **the E2E session comes from a setup project, never from a login inside a spec**, which is why both pre-existing specs passed with their bodies untouched when the guard landed: `auth.setup.ts` registers one timestamped throwaway account and saves `storageState`, and the `chromium` project declares `dependencies: ['setup']`. The `storageState` carries a *renewable* session, not a live one — only the refresh token is persisted, so `authGuard` spends one `POST /api/auth/refresh` before the first render of every spec — and one stored token can restore N parallel contexts **only because a refresh token here is a stateless JWT that use does not consume**. If refresh ever becomes single-use, the setup must mint one session per worker, and the symptom will be intermittent failures under `fullyParallel` rather than anything naming refresh. `e2e/.auth/` is gitignored: it holds a real bearer credential. Third, **stub the narrowest thing a component actually reads, not the router** — a wholesale `Router` double cannot construct a component whose template carries a `routerLink`, so both auth specs take `provideRouter([])` and spy on `navigateByUrl`; and under zoneless change detection a guard spec must read its outcome *after* the flush, not at call time, so the result goes into a holder variable the assertion reads later. Verified directly rather than through the per-edit hook: 11 spec files / 99 tests / 5.80 s, and E2E 3 tests / 29.5 s all green. **And one finding that arrived after the commit, because no gate could report it:** the production bundle went 433.50 → **526.39 kB** initial, past `angular.json`'s 500 kB `maximumWarning`, from a single `ButtonModule` import in `app.ts` — the app's only eager component, so importing any PrimeNG component there hoists PrimeNG's 86.83 kB shared base out of the lazy analyzer chunk. `maximumError` is 1 MB, so `npm run build` exits 0 and **pre-push passes while printing the warning into output nobody reads** — a budget whose only consequence is a log line is not a gate. A plain `<button>` and 20 lines of SCSS put it back to 446.81 kB, which makes F-03's real cost on the eager path 13.31 kB; the measurement was taken by building the pre-auth tree in the worktree and restoring it, not by reasoning about the delta. **The deploy has an ordering constraint no suite can check, and it held**: backend and frontend reached production in one push (`1d3ef2b`, Render `live` 13:37 UTC, Cloudflare serving the byte-identical `main-GFSW7X2V.js`) with `AUTH_JWT_SECRET` written *first*, through the per-key endpoint. Five live checks, ordered so each one means something: health 200 (the context started, so the secret is present and long enough), `POST /api/analyses` **401** where it answered 200 the day before, login for an unknown address 401 with the single indistinguishable message, register → `GET /api/auth/me` 201 then **200** (signing and verification agree across a real Postgres), and the two negatives — a tampered signature 401, and **the refresh token as a bearer 401**, which is `TokenTypeValidator` doing the one job the design rests on, in production rather than in a test. One trap worth scripting around: `openssl rand -base64 48` emits **CRLF** on this machine and `tr -d '\n'` leaves the `\r`, which is a literal carriage return inside a JSON string — Render answers a bare `400` with no explanation, so the symptom is a rejected request rather than a malformed secret
- The last unguarded post-analysis step, and the degraded value that had to be designed: 2026-09-10 — change `cepik-adjuster-not-fail-soft`, two phases, the second of the three gaps the port-contract work exposed. Backend 249 → **255 tests in 28 classes**, no new file: five cases in `CepikRiskAdjusterTest` and one end-to-end case in `AnalysisSurvivesEnrichmentFailureTest`, whose Javadoc already owned the injected-throw doctrine. `AnalysisController:95` is now a third `degradeOnThrow`. **The finding that shaped the whole change: the obvious fix was a regression.** `degradeOnThrow` needs a degraded value, and the obvious one — the analysis the adjuster failed to adjust — is precisely the `risk: 88, verdict: WORTH_CHECKING` beside a visible szkoda istotna that `CepikRiskAdjuster` was written to remove, arriving *silently*, because unlike `LOOKUP_FAILED` and `FETCH_FAILED` a skipped adjustment has no status the UI reads. Trading a loud 500 for a quiet under-report of risk is the wrong direction on the one guardrail this project treats as non-negotiable, so the skip now reports itself (`CepikRiskAdjuster.unscored`: the `FOUND` panel kept, a prepended `HIGH` `CEPIK_NOT_SCORED` flag, verdict floored to `NEEDS_MORE_INFO`, **and no cap** — which finding fired is what the throw destroyed, so a cap would be a number nobody computed). The transferable rule: *a step whose degraded value cannot say "this did not work" does not belong in a fail-soft guard* — give it a vocabulary first, and let the class that owns the semantics build it, or the caller ends up with a fourth copy of the verdict labels. **Three findings worth not re-deriving.** First, **no throw is reachable in the adjuster today**, walked statement by statement — `String.join` renders a null element as text, the three `DamageRecord` accessors are null-checked, `rank`/`labelFor` are exhaustive switches that fail at compile time in a single Maven module, and `HistoriaPojazduParser.damagesFrom` cannot emit a null element — so this is defence in depth of exactly the kind `AnalysisSurvivesEnrichmentFailureTest` already argues for in its own words, and the throw had to be injected. The one near-miss is `flags.addAll(result.riskFlags())` on a null list, which the controller's `withoutFlag` treats as representable and the adjuster does not; `AiAnalysisServiceContractTest` enforces non-nullness only by calling `.stream()` on it, never by asserting it, and that is recorded rather than fixed. Second, **the injection had to be a mocked adjuster, not a hostile payload.** A `List<DamageRecord>` that throws on access throws again inside Jackson, which serialises `damageRecords` on the way out — a 500 from the writer *after* the controller succeeded, testing the serialiser instead of the guard. Third, **a break-check restore is only exact if the staging happened first.** The first of the two deliberate breaks was restored with `git checkout --` before the phase was staged, which reverted the entire controller edit and not just the break; the procedure stages before breaking for precisely this reason, and the cost was re-applying three edits. Both breaks did fire: degrading silently returned `riskFlags[0].code: HIGH_MILEAGE`, and removing the guard returned `500` where the test wanted `200`. Manual half on a live local `mock` server: the happy path is untouched and the guard invisible — `FOUND`, damage record intact, risk capped 65 → 25, verdict `HIGH_RISK_SKIP`, no `CEPIK_NOT_SCORED`
- A derived flag stopped outliving its input: 2026-09-10 — change `no-vin-flag-survives-override`, two phases, the first of the three gaps the port-contract work exposed and left open. Backend 245 → **249 tests in 28 classes**, and the counts in `.githooks/pre-commit` / `.githooks/pre-push` / root `CLAUDE.md` moved with them. One new file, named for the behaviour rather than the class: `SuppliedVinClearsTheNoVinFlagTest` (four cases over `buildResponse`, stubbed LLM and enrichments, the real `CepikRiskAdjuster` because the rule sits after it). **The fix is a deletion of an asymmetry, not an addition of a rule.** `buildResponse` derived the `NO_VIN` flag and the "podaj VIN" seller question from the same post-override `ExtractedData` in two parallel blocks, and only the question block used it — so one method disagreed with itself, and the response shipped `cepikResult: FOUND` next to a flag saying the vehicle could not be verified. Both now go through one `vinIsVerifiable` predicate; a third consequence of "the VIN is unusable" has one place to be added. **Three findings worth not re-deriving.** First, **the reword-versus-remove question was settled by finding where the signal already lived**, not by taste: rewording was meant to preserve "the advert omitted the VIN", which `scores.completeness` already carries and which the fix never touches — so the alternative was a second copy of a reported signal, placed in the one list a buyer reads for *car* risks. Second, **the predicate had to be validity and not presence, and only a live server showed why.** `UserOverrides` sets `vinPresent` to `TRUE` for any non-blank typed value, so a typed `ABC` returns `vinPresent: true` *with* `MISSING_INPUTS` — and there the flag's text is true, so a rule keyed on non-blankness would have deleted a correct warning. That case is a named test rather than a comment. Third, **the break-check falsified the plan's own prediction, which is the only reason it is worth running on an additive change.** The plan expected three of four cases to fail on an inverted guard; all four did, because inverting also makes a `null` VIN "verifiable" and drops the flag it correctly carries. A prediction that had been believed would have hidden that both directions were pinned. The manual half was verified against a live local `mock` server on all three inputs — the same procedure that produced the original observation, which is why the observation existed at all
- Port contracts added, and the mock-output exclusion narrowed: 2026-09-10 — change `refactor-opportunities`, four phases. Backend 235 → **245 tests in 27 classes, 15.5 s** (241 after Phase 1, 243 after Phase 2, 245 after Phase 3), and the counts printed by `.githooks/pre-commit` and `.githooks/pre-push` were moved with them, since a label a reader trusts without checking is the one place a stale figure does damage. Two new files, both named for a port rather than a class: `CepikEnrichmentServiceContractTest` (three properties × two implementations × six degraded inputs) and `AiAnalysisServiceContractTest` (one property, two implementations, asymmetric inputs). §6.8 is the convention, §7 is what they leave alone, §2 records what they buy Risks #2/#3/#4. **The finding that reshaped §7: the exclusion was protecting the implementation every gate runs.** `MockAiAnalysisService` had drifted to suppressing `NO_ACCIDENT_DECLARATION` on the substring `"historia"` and rating it `HIGH` against the parser's `MEDIUM` — an inverted guardrail inside the only bean the git hooks and the E2E specs ever execute — and "the mock's own output is out of scope" is what let it sit there. A mock that is the sole implementation on a profile is not a fixture; it is production for that profile. **A second, quieter one: two tests asserting opposite directions cannot both be broken by widening a condition, only by fully negating it.** Widening `damages != null && !damages.isEmpty()` leaves one of the pair green, which reads as a passing break-check over a half-broken guard; the guard-inversion had to be the full negation. Three gaps were exposed and deliberately not pinned (§1 rule 4), all pre-existing, all confirmed against a live local `mock` server rather than inferred: **`NO_VIN`/`HIGH` is reported next to a successful `FOUND` lookup keyed on the very VIN the user supplied**, because the flag is built from the listing text and `UserOverrides.apply` runs afterwards — same ordering on the real path, so it reaches production and gets its own change — **that change landed the same day; see the entries above — two of the three gaps below closed on 2026-09-10, and only the `CepikStatus` one is still open**; `CepikRiskAdjuster` is called at `AnalysisController:95` *outside* `degradeOnThrow`, so an NPE inside it discards a complete analysis behind a catch-all 500, which is the one enrichment step that is not fail-soft; and `AnalysisController` never switches on `CepikStatus` at all. **The manual half is also where a two-clause criterion stopped being a green run**: "both E2E specs stay on the `MISSING_INPUTS` path" is not shown by a passing suite, and was verified structurally — neither spec pastes a VIN, plate or date, so `MockCepikService` short-circuits at `VinValidator.normalise(null)` before the registry shape matters
- Second reviewer runner added, and picked between: 2026-09-10 — `packages/code-reviewer` now holds two implementations of one `Reviewer` contract, `ai-sdk` (hand-assembled Vercel AI SDK loop, OpenRouter) and `agent-sdk` (`@anthropic-ai/claude-agent-sdk`, a `claude` subprocess against Bedrock `eu.anthropic.claude-sonnet-5`), selected by `CODE_REVIEW_RUNNER`; an unrecognised value exits 2 rather than falling back. Suite 152 → **168 tests in 11 spec files**, ten of which skip offline with a printed reason, and the gate arm went 8.2 s → **8.4 s** — 88 new tests for 0.2 s, re-measured rather than re-estimated (§5.1). The pick is `agent-sdk`, on 32 measured runs, written up with its tables in `context/changes/agent-sdk-reviewer/pick.md`; it is the runner M5-L3's promptfoo provider wraps. **Two empirical findings outlive the pick.** First, the Agent SDK's default `settingSources: ['user','project','local']` really does inject this repo's `CLAUDE.md` hierarchy into the model's context, silently — probed with a question whose answer is only in this repo (the backend's port is 10000, not 8080) and `tools: []` so nothing could fetch it: omitted → `10000`, `[]` → `UNKNOWN`. Left on, it would make every eval score a function of documentation edits that never appear in the prompt; measured at n=3 it cost 31% more per review and **inverted one verdict**, the `pass` run's own summary naming the right port and the right file before reporting zero findings. So the runner ships `[]`. Second, the output channel A/B (`outputFormat` versus an MCP submit tool) was settled by building the unexpected arm first: `outputFormat` is an end-turn *tool*, not a decoding constraint, and its error subtypes arrive as *results* rather than throws — so the wrong answer is an empty **passing** review, which is why `requireResult` maps every subtype to a `ReviewerError`. **A third find that was nobody's plan: the containment policy has a measurable capability cost.** All three hermetic `cross-file.diff` runs recorded `denied=[Grep]` — the model wanted an unscoped search of the repo root, which the permission hook refuses because unscoped covers `.env` — and when it cited `CLAUDE.md` from the fixture's preamble instead, `stripUnbackedEvidence` removed the citation. Three enforcement layers visible in one run, none of them decorative
- `packages/` brought under the gates: 2026-09-09 — the directory had been invisible to all three layers since it was created. The per-edit hook matches `frontend/src/` only (`post-edit-check.mjs:96`) and both git hooks matched `^frontend/src/…` and `^backend/(src/…|pom\.xml)`, so a package with 80 tests had nothing running them. Now a pre-commit arm scoped to `^packages/[^/]+/(src/.*\.ts|scripts/.*\.mjs|package\.json|tsconfig\.json)$` and an unconditional pre-push arm, both `run_reviewer_checks` in `common.sh`: typecheck then suite, 8.2 s measured. Per-edit is deliberately left alone — the package is edited in bursts and 8 s per keystroke-level edit buys nothing a commit gate does not. **The find worth carrying: the runner could not report an empty suite.** `node --test` on a pattern matching nothing exits 0, which is the `catch → exit 0` prettier failure wearing a different tool's clothes, so the arm goes through `scripts/run-tests.mjs` (specs enumerated from disk, non-zero test count required). Both arms were watched blocking — an inverted assertion, and every spec moved aside — and the deliberate break reverted before the commit
- Local enforcement last verified: 2026-09-04 (§5.1 added — the three local layers now exist and every one of them was watched blocking a real failure, including a `git commit` that `core.hooksPath` refused. Two measurements drove the layering and are worth not re-deriving: scoping the frontend run to one spec saves 0.5 s of 6.4 s because the cost is the Angular bundle build, not the test count, and `npx vitest related` cannot run these specs at all without the Angular builder's transform — the same obstacle that blocks Stryker)
- Strategy (§1–§5) last reviewed: 2026-09-04 (§5 split into 5.1 local enforcement and 5.2 the gates, with a formatting row added; §3's one BLOCKING carried item closed — `market-price-panel.component` now has a spec, verified by reverting the Phase 2 bug rather than by reading green; §4 backend row corrected to separate the 29 files on disk from the 25 classes that run, frontend row promoted from documentation to a verified figure, PIT row added). Previously reviewed 2026-09-03 (§2 Risk #5's Source figures replaced with the ones `roadmap.md:187` actually records — the min/median pairing previously cited there appears in no artifact and had been carried for two rollout phases; §3 Phase 2 flipped to `complete`, its carried-forward item closed, six new items carried into Phase 3; §4 counts and grounding-tool lines corrected — rollout Phase 2)
- Gate toolchain resolution last fixed: 2026-09-04 — `.githooks/common.sh` deferred to an inherited `JAVA_HOME` (`…\Zulu\zulu-8-jre\`, 32-bit, no `javac`) and an inherited `MAVEN_OPTS` (`-Xmx12g`), so the gates died on `Invalid maximum heap size` — a message about memory for a problem about Java. The pinned JDK now wins, `require_java` looks for `javac` rather than for a non-empty variable, and the heap budget is set rather than defaulted. **A toolchain check must test for the tool, not for the variable that should name it**; this is the third time a gate on this project failed by trusting a proxy for its own prerequisite (see the `catch → exit(0)` prettier hook, and the ACL-locked Node read as absent).
- Debugging workflow last exercised: 2026-09-04 — the negation-unaware accident-claim matcher, taken from a parked prose note to a failing test, a fix, a guard against the over-broad fix, and the log line whose absence made the defect unobservable in the first place. Backend 229 → **235 tests**. Two lessons outlive the bug: **a known defect written into a Javadoc is not tracked** — "pinning a bug makes the fix a test failure" is correct and is also how one stays parked for months; and **PIT should be pointed at a fix, not only at a module** — it flagged the brand-new log guard as a survivor, i.e. the observability added to close an A10 gap was itself unobserved.
- Stack versions last verified: 2026-09-04 (both suites re-run: backend 25 classes / 229 tests in 14.7 s, frontend 4 spec files / 39 tests in 2.30 s; `spring-test` 7.0.7 unchanged; PIT row added at 1.29.10). Both counts moved later the same day: the frontend to **41 in 2.55 s** with the scored-zero fix, the backend to **235 in 15.7 s** with the negation fix (both at the end of §3); §4 carries the current figures, and the hook labels in `.githooks/` carry them too, since a stale count there is the one place a reader would trust it without checking. **The frontend row is now a verified figure, not documentation.** The 2026-09-03 entry read "unrunnable on the current machine — no Node or npm"; that diagnosis was wrong in its cause. Node v22.22.1 / npm 10.9.4 were installed but ACL-locked by an elevated `nvm install`, so every shell reported the binary as missing rather than as forbidden. A toolchain that reads as absent may only be unreadable — check permissions before re-recording a row as unverifiable
- AI-native tool references last verified: 2026-09-04 (Playwright CLI **and** Playwright MCP both installed and exercised, replacing the "runtime/browser: none" line that had stood since 2026-08-27; the "MCP still unavailable" note recorded earlier the same day is superseded. Context7 and Exa re-confirmed available and exercised; neither is *in* the stack — they ground it. See §4). Two install facts worth not re-deriving: `claude mcp list` reported the new server as `Failed to connect — timed out after 30000ms` on first run, which was the cold `npx` package download exceeding the handshake budget and not a bad config — warming the cache once (`npx -y @playwright/mcp@latest --help`) turned it green with no config change, so **suspect the download before editing the registration**. And the MCP server writes snapshots, console logs, and screenshots to the cwd it was launched from, which here is the repo root; `.playwright-mcp/` is gitignored for that reason
- e2e scope last decided: 2026-09-04 — **one spec, deviating from the flat "no e2e" of 2026-08-27.** The deviation is recorded at the end of §3 with the evidence, and §4's e2e row now names a tool instead of "none". Two things worth not re-deriving. The mock profile is only *partly* a stable oracle: `MockMarketPriceEnrichmentService` ignores its input entirely (always 45000/55000/70000, sample 12), but `MockAiAnalysisService` is content-sensitive — the same listing scored `overall: 41` and `35` on a one-word edit — so scores and verdict must not be asserted at this layer, which is what makes the market-price panel the right target. And `CLAUDE.md`'s "dev server on :8080" was stale (corrected there the same day): the backend default is `${PORT:10000}`, which is also what `proxy.conf.json` targets and what the Playwright readiness probe hits (`/actuator/health`; a probe GET on the POST-only `/api/analyses` answers 500, which Playwright reads as "never became ready")
- Browser transports and vision last compared: 2026-09-04 — **CLI stays the default, vision adds no spec.** Both transports read the same accessibility tree and produce the same role-based locators, so MCP's ~4× token cost buys interactive exploration, not better tests. MCP-generated code is not exempt from review: asked for a container screenshot it emitted `getByText('Oceny kategoriiKompletność71%')`, a concatenated-text locator that breaks on any score change. The vision pass found two real UI defects (end of §3) and neither became an e2e test — one was fixed and pinned by component tests, the other needs pixels but wants a deterministic differ rather than a VLM verdict. One correction is recorded there rather than quietly dropped: the claim that the score bars exposed no `aria-valuenow` was wrong, and it was wrong because a Playwright accessibility snapshot shows label text nodes but not host ARIA attributes — **an a11y snapshot is not an ARIA audit**
- Agent context layout last decided: 2026-09-04 — **split from one flat file into an additive three.** The root `CLAUDE.md` had grown to 329 lines / 5,122 words (~7K tokens loaded on every turn, including a frontend turn that needs none of the registry-parser history), 16 flat sections, no per-directory files. It is now root **158 lines / 1,442 words** (business rules, monorepo map, build, the three local gates, architecture, current state, deployment, and the `10x-cli` managed block) plus `backend/CLAUDE.md` 150 and `frontend/CLAUDE.md` 67, so a single-stack turn loads roughly a third of what it used to and never loads the other stack's file. Three things worth not re-deriving:
  - **The hierarchy is additive, not override**, and that was verified rather than assumed: a headless session started in `backend/` answered the szkoda-istotna ceiling (35, backend file) *and* the absence-means-unknown rule (root file), naming both sources; the same probe run in `frontend/` answered the test-runner question and returned NOT-IN-CONTEXT for the ceiling. So the saving is real and the guardrails still reach every directory.
  - **Only the root file may hold the `<!-- BEGIN @przeprogramowani/10x-cli -->` block** — the CLI rewrites it in place, so lesson blocks cannot be moved into a child file without the next `10x-cli` sync putting them back.
  - **Cross-references had to move with the sections.** `test-plan.md` and `roadmap.md` now name `backend/CLAUDE.md` / `frontend/CLAUDE.md` where the rule actually lives, and the Javadoc pointers under `backend/` resolve to the backend file by proximity except the one aimed at "Key business rules", which says "root" explicitly. `context/archive/` still cites `CLAUDE.md:139-144` and `CLAUDE.md:7-8` **by line number**; those were already stale before this split and the folder is read-only by convention, so they stay — a line-number citation into a living file is the anti-pattern, not the split.
  - **The next rung is per-module files, and the trigger signals for it are observable, not aesthetic:** `backend/CLAUDE.md` passing ~250 lines; a second bounded context appearing under `backend/src/main/java` that shares no rules with `analysis`/`cepik`/`market`; the persistence and auth work (F-02/F-03) adding a schema-and-migration section that a pure-frontend turn should never load; or an edit landing in the wrong package because two sections of one file described two different conventions. Until one of those fires, three files is the right size and adding more is cost without signal.

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
