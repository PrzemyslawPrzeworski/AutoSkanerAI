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

Tests run on **vitest through `@angular/build:unit-test`** (`test` target in `angular.json`, jsdom — no browser needed). 41 tests in 4 spec files, ~2.5 s. Two things to know:

- **No `fakeAsync` / `tick`.** The app has no zone.js at all (Angular 21 is zoneless by default), so `fakeAsync` throws "zone-testing.js is needed". Adding zone.js only for tests would make tests run under different change-detection semantics than production. Every service call in the specs is a synchronous `of(...)`, so awaiting nothing is correct — if a spec ever needs real async, use `await fixture.whenStable()`.
- **Vitest matchers, not jasmine.** `vi.fn()`, `mockReturnValue`, `toBe(true)` — `toBeTrue()` does not exist and fails to compile, which is how the stale specs were caught.

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
  are scratch evidence, never fixtures, and are not committed.
