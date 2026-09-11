# AutoSkanerAI

**AI-powered analysis of used-car listings for the Polish market.** Paste a listing — its text,
its URL, or just the fields you know — and get back structured facts, risk flags, the questions
worth asking the seller, and a scored recommendation.

Live: **[autoskaner-ai.pages.dev](https://autoskaner-ai.pages.dev)** (frontend) ·
[autoskanerai.onrender.com](https://autoskanerai.onrender.com) (API)

> The backend runs on Render's free tier and sleeps when idle, so the **first** request after a
> quiet period takes ~75 s to wake the instance. Subsequent requests answer in ~15–25 s.

## The problem

Buying a used car is slow and risky. Listings are incomplete, imprecise, or written with marketing
spin: sellers omit service history, dodge accident declarations, and describe equipment vaguely. A
private buyer has to read each listing carefully, spot the gaps, prepare questions, and decide
whether the offer is worth pursuing — tens of minutes per listing, most of it spent on bad offers.

AutoSkanerAI compresses that to a couple of minutes.

## What it does

Given a listing, the app:

- **extracts** the facts — make, model, year, price, mileage, fuel, transmission, seller type,
  service history, accident declaration, VIN, plate, first registration date;
- **audits the equipment** — what the listing confirms, what it omits, what is too vague to trust;
- **flags risks** — missing VIN, no service history, mileage out of line for the year, price below
  the market range, and the absence of an accident declaration;
- **enriches from live sources** — the Polish vehicle registry (CEPiK / historiapojazdu) for
  registered damage and technical data, and a scraped market price range for comparable cars;
- **folds the registry findings back into the score**, because the model scored the listing before
  the registry was queried and never saw them;
- **writes 3–5 questions** to ask the seller, in Polish;
- **scores and recommends** — completeness, equipment, risk, value and overall, plus a verdict of
  *warto sprawdzić* / *sprawdź po doprecyzowaniu* / *wysokie ryzyko — pomiń*.

### The rule the whole app is built around

**Absence of accident data means _unknown_, never _clean_.** The app may report a confirmed accident
declaration from the listing or a history report as a fact, but it must never imply a car is
accident-free simply because nothing was said. This is enforced in the LLM prompt (a silent listing
*must* yield `accidentClaim: null` plus a mandatory `NO_ACCIDENT_DECLARATION` flag), in the API
response shape, and in the UI copy — and it is checked when picking an LLM model, because a cheap
model that fabricates "bezwypadkowy" is rejected however fast it is.

For the same reason, an empty registry damage record means *no damage reported to insurers* — not
*no accidents*.

## Stack

| | |
|---|---|
| Backend | Spring Boot 4.0.6, Java 21, Maven |
| Frontend | Angular 21.2 (zoneless), TypeScript, SCSS |
| LLM | AWS Bedrock (Claude Haiku 4.5) or OpenRouter, selected by Spring profile |
| Hosting | Render (backend, Docker) + Cloudflare Pages (frontend) |
| Tests | JUnit 5 + Mockito (backend), Vitest (frontend), Playwright (E2E) |

Every external integration — the LLM, the vehicle registry, market price — is a Spring interface
with a mock bean under the `mock` profile and a real bean under `@Profile("!mock")`, so the whole
app runs offline with no credentials.

## Running it

```bash
# Backend — dev server on :10000 (not 8080)
cd backend && ./mvnw spring-boot:run

# Frontend — dev server on :4200, proxies /api to :10000
cd frontend && npm install && npm start
```

Pick a profile with `SPRING_PROFILES_ACTIVE`: `mock` (no credentials, no network),
`bedrock` (AWS), or `openrouter`. Copy `.env.example` to `.env` for keys.

```bash
cd backend  && ./mvnw test                  # 285 tests
cd frontend && npm test -- --watch=false    # 51 tests
cd frontend && npm run test:e2e             # Playwright; starts both servers itself
```

## Layout

```
backend/    Spring Boot API                  -> backend/CLAUDE.md
frontend/   Angular SPA                      -> frontend/CLAUDE.md
context/    the written foundation this app was generated from
  foundation/  prd.md, roadmap.md, test-plan.md, tech-stack.md
  changes/     one folder per change: why it is shaped the way it is
  map/         a measured onboarding map of the codebase
packages/   dev tooling — an AI code reviewer and an Agent Skill; nothing deploys from here
terraform/  a private npm registry on AWS CodeArtifact: authored, never applied
```

## Quality gates

There is no CI, and `main` auto-deploys to both hosts — so **pre-push is the last gate before
production**. Three layers run locally: `prettier` + the frontend suite on every edit, the affected
suites on commit, and everything plus the production build on push to `main`.

A fresh clone needs one command to enable them: `git config core.hooksPath .githooks`.

## Where to read next

| Looking for | Read |
|---|---|
| Orientation in the code — layers, real couplings, risk zones | `context/map/repo-map.md` |
| Requirements FR-001 … FR-018 | `context/foundation/prd.md` |
| What ships next and in what order | `context/foundation/roadmap.md` |
| Risk map, per-layer test budgets, verification ledger | `context/foundation/test-plan.md` |
| Conventions, gates and business rules for agents | `CLAUDE.md` |
| Why a shipped thing is shaped the way it is | `context/changes/<slug>/` |

## Status

The analysis path is complete and verified in production: listing input (URL, pasted text, or
manual fields), extraction, equipment audit, risk flags, CEPiK lookup, market price context,
seller questions, scoring and verdict — FR-001 … FR-009, FR-017, FR-018.

In progress: persistence and accounts — saving an analysis, listing what you saved, and deleting it
(FR-010 … FR-012), which is the chain `data-layer-setup` → `auth-scaffold` →
`save-view-delete-analyses` in the roadmap. The first link is done: JPA entities, Flyway
migrations and the `users` / `analyses` schema are in, on an in-memory H2 by default so the app
still needs no database to run, and on a real Render Postgres in production. Nothing is exposed
over HTTP yet — the endpoints and the login arrive with the next two links.
