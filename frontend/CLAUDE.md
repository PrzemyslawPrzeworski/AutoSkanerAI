# frontend/CLAUDE.md — Angular app

Loaded **in addition to** the root `CLAUDE.md`, not instead of it. The business
rules, the monorepo map, the quality gates and the deploy targets are there; what
follows is what only the frontend owns. Paths are relative to the repo root
throughout, so they read the same from either file.

## Toolchain

Builds need Node ≥ v20.19 / v22.12 (Angular 21 requirement); `node`/`npm` are not
on PATH by default in this environment — on this machine they live in
`/c/nvm4w/nodejs`, which is why `.githooks/common.sh` prepends it.

## Unit tests

Tests run on **vitest through `@angular/build:unit-test`** (`test` target in `angular.json`, jsdom — no browser needed). 99 tests in 11 spec files, ~5.8 s. Two things to know:

- **No `fakeAsync` / `tick`.** The app has no zone.js at all (Angular 21 is zoneless by default), so `fakeAsync` throws "zone-testing.js is needed". Adding zone.js only for tests would make tests run under different change-detection semantics than production. Every service call in the specs is a synchronous `of(...)`, so awaiting nothing is correct — if a spec ever needs real async, use `await fixture.whenStable()`.
- **Vitest matchers, not jasmine.** `vi.fn()`, `mockReturnValue`, `toBe(true)` — `toBeTrue()` does not exist and fails to compile, which is how the stale specs were caught.

**`cepik-result.component.spec.ts` is where the business rule is read back.** The backend spends five test classes keeping `null` and `[]` distinguishable onto the wire; this spec is the only thing checking the other end, where `damageState()` decides between "the registry was not read" and "the registry reported nothing". Two conventions in it are worth copying, not just keeping:

- **Every arm asserts its own sentence *and* the absence of the sentence it must not be confused with.** A test that only asserts its own copy still passes after an edit merges two arms — you would simply update the expected string. The negative companion, plus the distinctness test that compares the three *rendered* texts, is what closes that. `market-price-panel.component.spec.ts` established the pattern; `analysis.models.ts`'s own doc comment explains the stakes.
- **Both guards were mutation-checked and the result is recorded in the file's header.** Inverting `damageState()` to read `damages()` (whose `?? []` collapses the distinction) fails 2 tests; weakening the template's `mileageStamps === null` branch to a length check fails 1. **Before that spec existed, the first mutation left all 276 tests green** — which is the only reason to trust the spec at all. If you touch either guard, re-run the mutation rather than the suite.

## Auth on the client (F-03)

`core/services/auth.service.ts`, `core/interceptors/auth.interceptor.ts`, `core/guards/auth.guard.ts`,
`features/auth/`. The backend half is `backend/CLAUDE.md` § "Auth (F-03)"; the reasoning for both is
`context/changes/auth-scaffold/change.md`.

**The 15-minute access token lives in a signal and never touches storage; only the 14-day refresh
token is in `localStorage`.** That split is the whole security posture — the frontend and the API are
different sites, so a session cookie would be a third-party cookie, and a bearer header sidesteps it.
The property is asserted by a test that enumerates every `localStorage` key and checks the access
token is absent (`never writes the access token to localStorage`); if that ever goes red, an injected
script can read an API bearer without touching the running app. `AuthService` is the only place either
token is written, and both storage calls are wrapped — Safari private mode throws on `setItem`, and a
throw inside `accept()` would leave an access token with no way to renew it.

Four hazards, each pinned by a named test, because each one is a plausible "simplification":

- **`PUBLIC_AUTH_PATHS` is three literal paths, not the `/api/auth/` prefix.** `/api/auth/me` reads
  the principal, so a prefix match sends it out unauthenticated → 401 → and the interceptor answers a
  401 by refreshing. `/api/auth/refresh` under the same match means a *failed refresh* is a 401 the
  interceptor tries to refresh. `does attach the token to /api/auth/me` and `does not try to refresh a
  failed refresh` are the two tests. The retry also goes through `next` rather than re-entering the
  chain, so a 401 on the retry propagates instead of starting a second refresh.
- **One in-flight refresh, shared** (`shareReplay({bufferSize: 1, refCount: false})` + `finalize`
  clearing the field). Without it, N parallel 401s each rotate the pair, the last write wins, and
  every other holder is logged out at a random later moment with nothing connecting the two events.
- **`authGuard` calls `restoreSession()`; it does not read `isAuthenticated`.** A reload drops the
  in-memory access token, so a naive check bounces a logged-in user who pressed F5. Consequence worth
  knowing: every guarded navigation may spend one `/api/auth/refresh` before the first render.
- **`returnUrl` is attacker-supplied**, so `safeReturnUrl` filters it — a login form that navigates
  wherever the query says is an open redirect. It is a standalone module rather than a private method
  *so the property is testable*; `//host` and `/\host` both start with a slash and still leave the site.

The two forms are asymmetric on purpose: **login validates emptiness only**, because the server
answers a malformed address with the same 401 as a wrong password and a client-side "that is not an
email" would tell a stranger which addresses look registered. **Register validates shape**, because
its 409 already discloses existence. Neither form lower-cases the address — the server owns
normalisation, and a second copy of that rule is a second thing to drift.

Both forms follow the app's no-`FormsModule` convention (signals plus `[value]` + `(input)`, with
`<label for>` so `getByLabel` keeps working) and use per-component PrimeNG imports.

## Vehicle data form

**The VIN is the only field the UI asks a user to type.** The registry needs VIN + plate + first registration date, but the advert publishes the last two — only the VIN is encrypted for logged-out fetches. So `VehicleDataFormComponent` has three modes with one job each: `vin` (input screen, always visible, in a titled block named by the outcome), `registry` (all three, shown only after a `MISSING_INPUTS` result, prefilled from the extraction), `listing` (make/model/…/notes, behind "I have no link" — they substitute for a missing advert and have nothing to do with the registry). An earlier single drawer labelled by field name read as a pile of optional boxes and was rebuilt for exactly that reason. `missingRegistryFields` names the fields still empty rather than restating that three are required, since an empty field means the advert did not carry it either.

The form checks the VIN shape (17 chars, no I/O/Q) before submitting, because a typo otherwise costs a ~30 s analysis whose empty history panel reads as the registry's fault. A malformed VIN is deliberately **not** a 400 on the server — see `backend/CLAUDE.md` § "Manual entry and user overrides".

The registry-vs-listing mileage check lives **only here** (`max(2000 km, 5%)` tolerance, registry-higher direction only) and does not feed the score. If it ever moves into scoring, delete the TypeScript copy rather than keeping two.

## E2E: one spec, on purpose

`frontend/e2e/` holds **one** risk spec plus a seed exemplar, and `test-plan.md` §3
records why the layer is that small: every risk in the map is cheaper to reach at
unit, integration, or component level. The one thing no other layer reaches is the
**frontend/backend contract** — `shared/models/analysis.models.ts` is a hand-written
mirror of the Java records, the backend suite asserts its own JSON, the frontend
suite asserts against hand-written doubles, so a field rename ships green with the
panel rendering nothing. That already nearly happened with `sampleQuality`.

- `market-price-contract.spec.ts` compares the DOM against **the same response's own
  JSON**, not against the mock's constants — otherwise it would be a test of
  `MockMarketPriceEnrichmentService`, which `test-plan.md` §7 excludes.
- **The session comes from a setup project, never from a login inside a spec.**
  `/` is behind `authGuard` now, so an anonymous `goto('/')` lands on `/login`.
  `e2e/auth.setup.ts` registers one timestamped throwaway account, saves the
  browser state, and the `chromium` project loads it as `storageState` — which is
  why both existing specs kept their bodies unchanged when auth landed. It works
  only because a refresh token is a stateless JWT that use does not consume; if
  refresh becomes single-use, the setup must mint one session per worker and the
  symptom will be intermittent failures under `fullyParallel`. `e2e/.auth/` is
  gitignored: it is a real bearer credential. Details in `e2e/E2E-RULES.md`.
- One thing only the E2E layer noticed: **`POST /api/auth/register` answers 201**,
  not 200. Every unit spec flushes through `HttpTestingController`, which defaults
  to 200, so a hand-written double agreed with whatever it was written to agree
  with — the same structural blindness this layer exists for.
- **Don't assert scores or verdict here.** `MockMarketPriceEnrichmentService` ignores
  its input (always 45000/55000/70000, sample 12), but `MockAiAnalysisService` is
  content-sensitive — one word of listing text moved `overall` from 41 to 35.
- Mocking is **server-side**, via `SPRING_PROFILES_ACTIVE=mock`. It has to be: the
  backend calls the LLM, Jina, and the registry itself, so `page.route()` never sees
  them. Everything the contract risk lives on — HTTP, Jackson, Angular DI, templates
  — stays real.
- **Not wired into any gate.** Two servers plus a browser is the wrong per-edit cost;
  it belongs in CI (§3 Phase 4). Run it by hand when touching the contract.
- Before adding a spec here, read `frontend/e2e/E2E-RULES.md` — the cost × signal
  budget is binding, and "e2e feels safer" is not a reason.
- The Playwright `webServer` command needs `.\mvnw.cmd` on Windows and `./mvnw`
  elsewhere; `cmd.exe` rejects both `./mvnw` and a bare `mvnw.cmd`.
- **Playwright MCP is installed (`--caps=vision`), and the CLI is still the
  default.** Both read the same accessibility tree and emit the same role-based
  locators, so MCP's ~4× token cost buys interactive *exploration*, not better
  tests — reach for it when the app has to be poked at, not to run a spec that is
  already written. Its generated code is not pre-reviewed: it produced
  `getByText('Oceny kategoriiKompletność71%')` for an unnamed wrapper element.
  It writes scratch output to the repo root, hence `.playwright-mcp/` in
  `/.gitignore`. If `claude mcp list` shows it timing out, that is the cold `npx`
  download, not the registration — warm the cache and re-check.
- **Vision found real bugs and still justifies no spec.** See `test-plan.md` §3's
  vision paragraph for the two defects and why one belongs in a component test
  and the other in a deterministic differ. Screenshots under `frontend/vision/`
  are committed (`a2d5839`) because nothing regenerates them — a one-off manual
  pass against a live local server — but they are **evidence, never fixtures and
  never a pixel baseline**: no spec reads them, and nothing should start.
