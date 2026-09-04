# E2E Testing Rules

Read this before writing or generating a spec in this directory. `seed.spec.ts` is
the worked exemplar — model new specs on it.

## The rules

- Use `getByRole`, `getByLabel`, `getByText` as primary locators. Fall back to
  `getByTestId` only when accessibility attributes are ambiguous.
- Never use CSS selectors, XPath, or DOM structure for locating elements.
- Each test must be independently runnable — no shared state between tests.
- Never use `page.waitForTimeout()`. Wait for specific conditions:
  `toBeVisible()`, `waitForURL()`, `waitForResponse()`.
- Assert the business outcome, not implementation details.
- Use unique identifiers (e.g. timestamp suffix) for test data to avoid
  collisions in parallel runs. Clean up in `afterEach`.
- Use `storageState` for authentication — never log in through the UI in
  individual tests.

## What this layer is for in this project

`context/foundation/test-plan.md` §3 deliberately keeps e2e nearly empty: every
risk in the map is reachable at unit, integration, or component level, and an
e2e layer over a single-page flow would duplicate rollout Phase 3 at a much
higher cost. **Do not add a spec here because e2e "feels safer" — that is §1's
cost × signal rule, and it is binding.**

One gap does need this layer, and it is the reason the directory exists.
`frontend/src/app/shared/models/analysis.models.ts` is a **hand-written mirror**
of the backend's Java records. The backend suite asserts its own JSON; the
frontend suite asserts against hand-written doubles. So no other test in this
repo ever puts real backend JSON in front of the real component, and a field
rename or a shape change ships green with the panel silently rendering nothing.
That already nearly happened — `sampleQuality` shipped server-side while the
template that reads it did not, and test-plan.md §6.7 records it as "the server
field would have been read by nothing."

A new spec belongs here only if it, too, would catch a **frontend/backend
contract break that both other suites are structurally blind to**. Anything
provable with a component test belongs in a component test.

## Real vs mocked

Internal boundaries stay real — that is where the contract risk lives:

| Real                                                        | Mocked                                  |
| ----------------------------------------------------------- | --------------------------------------- |
| browser, Angular HttpClient, dev-server proxy               | the LLM (OpenRouter / Bedrock)          |
| Spring controller, Jackson serialisation, enrichment wiring | Jina Reader (URL + market-price fetches) |
| the component templates and their Polish copy               | the vehicle registry (historiapojazdu)  |

The three mocked ones are mocked **server-side** via `SPRING_PROFILES_ACTIVE=mock`,
never with `page.route()`. They have to be: the backend calls them itself, so a
browser-level route interception would never see them. That profile is also what
makes the run deterministic — a real analysis is ~27 s of live, non-deterministic
LLM call.

Consequence worth knowing: `MockMarketPriceEnrichmentService` and `MockCepikService`
are the oracle for the numbers a spec asserts. If you change a mock, the spec that
reads it must change with it — and per test-plan.md §7 the mock's own output is
never itself the thing under test.

## Running

```bash
cd frontend
npm run test:e2e                         # starts both servers itself, then runs
npx playwright test e2e/seed.spec.ts     # one spec
npx playwright test --ui                 # interactive
```

`playwright.config.ts` starts the backend (`mvnw -o spring-boot:run`, mock profile,
port 10000) and the dev server (port 4200), and reuses either if it is already up.
The backend inherits `JAVA_HOME` from the environment; if it is unset, Maven fails
loudly rather than the suite skipping.

**Not wired into any gate.** The git hooks in `.githooks/` and test-plan.md §5.2
do not run this directory: it needs two servers and a browser, which is the wrong
cost for a per-edit or pre-commit layer. Run it by hand when touching the
frontend/backend contract; it belongs in CI (§3 Phase 4), not in front of a commit.

## Placement

`frontend/e2e/<feature>.spec.ts`, one test per file. Not under `src/` — Vitest's
`tsconfig.spec.json` includes only `src/**/*.spec.ts`, and a Playwright spec
collected by Vitest fails at `test(...)`.

## No persistence yet, so "cleanup" has no teeth here

The app has no database and no auth (roadmap F-02 / F-03 are unstarted), so there
is no server-side record for a spec to leak or tear down, and no `storageState` to
build. Isolation today comes from Playwright's fresh browser context per test plus
each test doing its own `goto` and its own submit.

Keep the timestamped test data anyway — it makes a run traceable in the backend
log, and it is the line that will matter on the day S-03 lands persistence. When
it does, add the `afterEach` teardown the rule above asks for; do not assume the
absence of one here is a precedent.
