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
npx playwright test e2e/seed.spec.ts     # one spec (the setup project still runs first)
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

## Authentication: one setup project, never a login inside a test

F-03 put `/` behind `authGuard`, so a spec that opens the app anonymously is
redirected to `/login` and fails at its first locator. The session comes from
`auth.setup.ts` — a Playwright *setup project* that runs once, registers one
throwaway timestamped account through the register form, and saves the browser
state to `e2e/.auth/user.json`. The `chromium` project declares
`dependencies: ['setup']` and loads that file as its `storageState`, so every
spec's `page.goto('/')` starts signed in. The path is declared in
`playwright.config.ts` and imported by the setup file, so the writer and the
reader cannot drift.

Three consequences worth knowing before you touch either file:

- **`storageState` carries a renewable session, not a live one.** The access
  token lives in memory by design (`auth.service.ts`), so only the refresh token
  is in the file. `authGuard` therefore spends one `POST /api/auth/refresh`
  before the first render of every spec — if you ever assert on request counts,
  that call is there.
- **It works because a refresh token is a stateless JWT that use does not
  consume** — no server-side record, no revocation. One stored token restores any
  number of parallel contexts. If refresh ever becomes single-use or rotating,
  this file must mint one session per worker; the symptom will be specs failing
  intermittently under `fullyParallel`, and this paragraph is the pointer back.
- **`e2e/.auth/` is gitignored, and must stay so.** It is a real bearer
  credential for a real (if throwaway) account.

## Cleanup still has no teeth, and now for a different reason

There *is* a server-side record now — the account the setup project registers —
but nothing to tear it down with: the app has no delete-account endpoint, and
under `SPRING_PROFILES_ACTIVE=mock` the datasource is in-memory H2, so the row
dies with the server. What keeps a re-run honest is the timestamped address, not
an `afterEach`. `reuseExistingServer` means a long-lived local server does
accumulate one `users` row per run; that is harmless and deliberate.

Keep the timestamped listing text too — it makes a run traceable in the backend
log, and it is the line that will matter when S-03 lands saved analyses. That is
the point at which a spec starts leaving rows it can actually delete, and the
`afterEach` the rule above asks for becomes real work; do not read the absence of
one today as a precedent.
