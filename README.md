# AutoSkanerAI

**AI-powered analysis of used-car listings for the Polish market.** Paste a listing — its text,
its URL, or just the fields you know — and get back structured facts, risk flags, the questions
worth asking the seller, and a scored recommendation.

Live: **[autoskaner-ai.pages.dev](https://autoskaner-ai.pages.dev)** (frontend) ·
[autoskanerai.onrender.com](https://autoskanerai.onrender.com) (API)

> The backend runs on Render's free tier and sleeps when idle, so the **first** request after a
> quiet period takes ~75 s to wake the instance. Subsequent requests answer in ~15–25 s.
>
> **Since 2026-09-11 the app needs an account** — the analyser is behind a login, and `/api/**`
> answers 401 without one. Registration is on `/register`, takes an email and a password, and
> confirms nothing by mail.

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
  *warto sprawdzić* / *sprawdź po doprecyzowaniu* / *wysokie ryzyko — pomiń*;
- **saves it, under your account** — name an analysis, keep a private note, reopen it later with
  every panel intact, rename it, delete it. A saved analysis is a **snapshot**: the verdict and the
  market range as of the day it ran, and nothing re-checks them.

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

Outside `mock`, the backend needs `AUTH_JWT_SECRET` (32 bytes or more) and refuses to start without
one — `/api/**` is behind a login now, and a signing key with a committed default is a key anyone can
mint tokens with. `mock` carries a fixed development key so the offline path stays credential-free.

```bash
cd backend  && ./mvnw test                  # 363 tests
cd frontend && npm test -- --watch=false    # 134 tests
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

Persistence and accounts — FR-010 … FR-012 — are the chain `data-layer-setup` → `auth-scaffold` →
`save-view-delete-analyses`, and **all three links are now implemented**. That closes the must-have
scope in the roadmap.

`data-layer-setup`: JPA entities, Flyway migrations and the `users` / `analyses` schema, on an
in-memory H2 by default so the app still needs no database to run, and on a real Render Postgres in
production.

`auth-scaffold` (live since 2026-09-11): registration, login and a locked API. Stateless JWTs signed with `AUTH_JWT_SECRET`
— a 15-minute access token the SPA keeps in memory and a 14-day refresh token in `localStorage`,
because the frontend and the API are different sites and a session cookie would be a third-party
cookie. `/api/**` answers 401 without a bearer, which is the first change in this project that alters
what an anonymous visitor can do. There is no revocation: logout is client-side, and a stolen refresh
token cannot be cancelled — see `context/changes/auth-scaffold/change.md` § "Left undone".

`save-view-delete-analyses`: the five endpoints under `/api/saved-analyses` and the UI that reaches
them — save with a title and a note, list, open, rename, delete. Every one is keyed on the
authenticated principal, and the owner is part of the database lookup rather than a check after it, so
a row belonging to someone else is never loaded in the first place; a row that is not yours and a row
that does not exist answer with the same 404, deliberately, because a 403 on an existing row would
confirm it exists. **Live since 2026-09-11**, with the whole ownership matrix re-checked against the
production API on real Postgres, and the UI walked through in a browser locally.

Known gaps, all deliberate: no pagination, search or sort on the list; a saved analysis cannot be
re-run; no export, share or bulk delete; and no delete-account endpoint anywhere yet.
