# CLAUDE.md — AutoSkanerAI

AI-powered used-car listing analyzer for the Polish market. 3-week solo MVP. Spring Boot 4.0.6 (Java 21, Maven) + Angular 21.2 (TypeScript, SCSS, npm).

## Key business rules

- Absence of accident data means **unknown**, not clean. Never present missing data as confirmation of clean history — this applies to LLM prompts, API responses, and UI copy.
- The app may only report confirmed accident data from the listing text or a vehicle history report.

## Monorepo structure

```
backend/    Spring Boot 4.0.6, Java 21, Maven      -> backend/CLAUDE.md
frontend/   Angular 21.2, TypeScript, SCSS, npm    -> frontend/CLAUDE.md
context/    10xDevs chain artifacts (PRD, tech-stack, shape-notes) — do not edit
```

This file is the **root of an additive hierarchy, not an override**: work inside
`backend/` loads this file *and* `backend/CLAUDE.md`. So what stays here is what a
change anywhere needs — the business rules, the map, the gates, the deploy targets.
Anything one stack owns lives next to that stack. Anything with a decision history
behind it lives in `context/`, which is the system of record; this file summarises,
it does not duplicate.

| Looking for | Read |
|---|---|
| API error shape, endpoints, AI + enrichment services, live & mutation tests | `backend/CLAUDE.md` |
| Angular testing (vitest, zoneless), the vehicle-data form, the E2E budget | `frontend/CLAUDE.md` |
| Risk map, per-layer budgets, tool inventory, verification ledger | `context/foundation/test-plan.md` |
| Requirements FR-001 … FR-018 | `context/foundation/prd.md` |
| Why a shipped thing is shaped the way it is | `context/changes/<slug>/` |

## Build and run

```bash
# Backend
cd backend && ./mvnw spring-boot:run     # dev server on :10000 (server.port=${PORT:10000})
cd backend && ./mvnw test                # unit tests

# Frontend
cd frontend && npm start                 # dev server on :4200
cd frontend && npm run build             # production build → dist/
cd frontend && npm test -- --watch=false # unit tests (vitest via @angular/build:unit-test)
cd frontend && npm run test:e2e          # e2e (starts both servers itself)
```

The backend port is **10000**, not 8080 — `proxy.conf.json` targets 10000, and so
does the Playwright readiness probe. `/actuator/health` answers 200; a GET on the
POST-only `/api/analyses` answers 500, so do not use it as a probe.

## Local quality gates

Three automated layers, each catching what the one below cannot. There is no CI
yet and `main` auto-deploys to both hosts, so **pre-push is the last gate before
production**, not a pre-filter in front of CI.

| Layer | Trigger | Does |
|---|---|---|
| per-edit | `PostToolUse` on `Write`/`Edit` — `.claude/hooks/post-edit-check.{sh,mjs}` | `prettier --write` the edited `frontend/src` file, then the whole frontend suite for `.ts` / `.html` (6.9 s) |
| pre-commit | `.githooks/pre-commit` | `prettier --check` staged frontend sources, frontend suite, backend suite when Java or `pom.xml` is staged |
| pre-push | `.githooks/pre-push` | backend + frontend suites over the whole tree; for `main` also the production build |

**A fresh clone needs `git config core.hooksPath .githooks`** — the hooks are
versioned but git does not pick them up on its own.

- **Not Lefthook**, despite what the lesson recommended. Lefthook needs a root
  `package.json`, and Cloudflare Pages builds this repo from a subdirectory on
  every push to `main`; a new root manifest is an unverifiable risk to a live
  deploy path for a benefit that is ten lines of shell here.
- **Every layer fails loudly when its own toolchain is missing.** The hook these
  replaced had a trigger, a matcher and a handler but its signal was hard-wired
  to success — `catch → process.exit(0)` inside `2>/dev/null || true`. It was
  dead from May to September because `node` was not on PATH, and nothing said
  so; the symptom finally surfaced as `prettier --check` reporting 23 of 23
  files unformatted. `post-edit-check.sh` exists for exactly one reason: node
  runs the checker, so node cannot report its own absence.
- **Per-edit runs the whole frontend suite, not the matching spec.** Measured:
  `ng test --include <component>.ts` costs 5.9 s against 6.4 s for all 39 tests,
  because the price is the Angular bundle build, not the test count. Scoping
  buys half a second and gives up cross-component breaks. `npx vitest related`
  is not an option at all — plain Vitest has no Angular transform, so it
  collects the specs and dies at `describe(...)`, which is the same obstacle
  that blocks Stryker.
- **`.githooks/common.sh` pins the JDK and the heap itself, over whatever the
  environment says.** This machine's system-wide `JAVA_HOME` is
  `C:\Program Files (x86)\Zulu\zulu-8-jre\` — a 32-bit Java 8 JRE with no
  `javac` — and `MAVEN_OPTS` arrives as `-Xmx12g …`, which a 32-bit VM cannot
  represent. The gates therefore died on `Invalid maximum heap size: -Xmx12g`, a
  message about memory for a problem about Java. Three assumptions failed at
  once: the dev JDK was filled in only when `JAVA_HOME` was *empty*, so a wrong
  value beat the pinned one; `require_java` tested that the variable was
  non-empty, and a JRE passes that; and `MAVEN_OPTS` was defaulted rather than
  set, so a budget the environment could override was not a budget. **`require_java`
  now looks for `javac`, not for a variable** — extend that rule to any toolchain
  check added here, because "present and wrong" reads far worse than "absent".
- Backend edits are **not** gated per-edit; `./mvnw -o test` is ~15 s, a
  commit-time cost. It runs offline for speed, so a newly added dependency can
  fail pre-commit on its own — the hook says so when it fails.

See `context/foundation/test-plan.md` §5.1 for the timings and how each path was
verified.

## Architecture decisions

- Frontend and backend are separate apps communicating via REST. Configure CORS on the Spring side or proxy `/api` in `angular.json` for dev.
- No database yet — add PostgreSQL (prod) + H2 (dev) when implementing FR-010 (persistence).
- Auth not yet implemented — Spring Security + JWT or OAuth2 planned per PRD.
- CEPiK integration (live vehicle registry queries) is shipped, FR-017 — see `backend/CLAUDE.md` § "Enrichment services".
- The AI layer, CEPiK and market price all use the same shape: a Spring interface with a mock bean under the `mock` profile and a real bean under `@Profile("!mock")`. Add a fourth integration the same way.

## Current state

F-01 (LLM analysis wiring), S-01 (core analysis flow), S-04 (CEPiK VIN lookup) and S-05 (market price context) are complete, on `main`, and **verified live in production** as of 2026-08-26 — previously all four were merged but dark, because Render pinned `SPRING_PROFILES_ACTIVE=mock`. `POST /api/analyses` is live under `mock`, `bedrock`, and `openrouter` profiles.

S-02 (manual field entry + user-supplied VIN/plate/date) is implemented; see `backend/CLAUDE.md` § "Manual entry and user overrides".

A real analysis takes ~27 s end to end (~16 s LLM + a Jina fetch for the market range), all on the request thread. Free-tier LLM slugs are the main fragility: see `application-openrouter.properties`. PRD is at `context/foundation/prd.md` (FR-001 to FR-018). Next: Stream B (F-02 data layer → F-03 auth → S-03 persistence).

Suite sizes, so a drop is visible: backend **235** tests in 25 classes (~15.7 s), frontend **41** in 4 spec files (~2.5 s), plus one Playwright contract spec that no gate runs.

## Deployment

- Backend: Render Web Service (Docker, service `autoskaner-ai-backend`, URL `https://autoskanerai.onrender.com`) — live
- Frontend: Cloudflare Pages (`autoskaner-ai`, URL `https://autoskaner-ai.pages.dev`) — live; auto-deploys on push to `main`
- CI/CD: auto-deploy wired on both platforms (push to `main` triggers deploy)
- GitHub: https://github.com/PrzemyslawPrzeworski/AutoSkanerAI

<!-- BEGIN @przeprogramowani/10x-cli -->

## 10xDevs AI Toolkit - Module 3, Lesson 4 (E2E Tests)

**For E2E tests, use the `/10x-e2e` skill.** It is the single source of truth
for the workflow — risk → seed test + rules → generate → review against the five
anti-patterns → re-prompt → verify. The skill's `references/` carry the full
rules, anti-patterns, seed pattern, and prompt-template.

A few hard rules that hold even before you invoke the skill:

- **Locators:** `getByRole` / `getByLabel` / `getByText` first; `getByTestId`
  only when accessibility attributes are ambiguous. Never CSS selectors, XPath,
  or DOM structure.
- **Never `page.waitForTimeout()`.** Wait for state: `toBeVisible()`,
  `waitForURL()`, `waitForResponse()`.
- **Test independence + cleanup.** Each test runs standalone — its own setup,
  action, assertion, and cleanup; unique ids (timestamp suffix) so parallel runs
  and re-runs don't collide.

Two boundaries to keep straight:

- **DOM (snapshot) is the default.** Vision (`--caps=vision`) is a supplement for
  visual-only risks (layout, z-index, animation); for pixel regression prefer
  deterministic tools (`toMatchSnapshot`, Argos, Lost Pixel). VLM model
  selection/cost is a debugging topic (Lesson 5), not testing.
- **Healer helps on selectors, harms on logic.** A changed selector → healer
  re-finds it (route through PR review). A changed business behavior → healer
  masks the bug; that failing-test-to-fix case is Lesson 5.

<!-- END @przeprogramowani/10x-cli -->
