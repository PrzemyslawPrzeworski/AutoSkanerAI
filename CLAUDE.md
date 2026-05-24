# CLAUDE.md — AutoSkanerAI

AI-powered used-car listing analyzer for the Polish market. 3-week solo MVP. Spring Boot 4.0.6 (Java 21, Maven) + Angular 21.2 (TypeScript, SCSS, npm).

## Key business rules

- Absence of accident data means **unknown**, not clean. Never present missing data as confirmation of clean history — this applies to LLM prompts, API responses, and UI copy.
- The app may only report confirmed accident data from the listing text or a vehicle history report.

## API error shape

All Spring controllers must return errors in this exact shape — no exceptions, no `ProblemDetail`:

```json
{ "status": 400, "error": "Błąd walidacji", "messages": ["field: message"], "timestamp": "2026-05-24T12:00:00Z" }
```

- `ErrorResponse` record lives in `com.example.autoskaner_ai.common`
- `GlobalExceptionHandler` (`@RestControllerAdvice`) in the same package handles: `MethodArgumentNotValidException` (400), `HttpMessageNotReadableException` (400), catch-all `Exception` (500)
- `messages` is `List<String>`; for validation errors format each entry as `"field: message"`
- `timestamp` is `Instant.now()`

## AI service pattern

The AI layer uses a Spring interface with two Profile-switched implementations:

- `AiAnalysisService` — interface defining the contract
- `MockAiAnalysisService` — deterministic mocks, activate with `SPRING_PROFILES_ACTIVE=mock`
- `LlmAnalysisService` — real LLM calls (Claude API or OpenAI), activate with `SPRING_PROFILES_ACTIVE=llm`

Required env vars when running `llm` profile: `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`.

## Monorepo structure

```
backend/    Spring Boot 4.0.6, Java 21, Maven
frontend/   Angular 21.2, TypeScript, SCSS, npm
context/    10xDevs chain artifacts (PRD, tech-stack, shape-notes) — do not edit
```

## Build and run

```bash
# Backend
cd backend && ./mvnw spring-boot:run     # dev server on :8080
cd backend && ./mvnw test                # unit tests

# Frontend
cd frontend && npm start                 # dev server on :4200
cd frontend && npm run build             # production build → dist/
```

## Architecture decisions

- Frontend and backend are separate apps communicating via REST. Configure CORS on the Spring side or proxy `/api` in `angular.json` for dev.
- No database yet — add PostgreSQL (prod) + H2 (dev) when implementing FR-010 (persistence).
- Auth not yet implemented — Spring Security + JWT or OAuth2 planned per PRD.
- CEPiK integration (live vehicle registry queries) is post-MVP, FR-017.

## Current state

Bare scaffold — no feature code written yet. PRD is at `context/foundation/prd.md` (FR-001 to FR-017). Start feature work from FR-001.

## Deployment

- Backend: Fly.io (pipeline not yet configured)
- CI/CD: GitHub Actions, auto-deploy on merge (not yet wired)
- GitHub: https://github.com/PrzemyslawPrzeworski/AutoSkanerAI
