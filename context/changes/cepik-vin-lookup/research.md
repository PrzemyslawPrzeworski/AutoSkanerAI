---
date: 2026-06-02T00:00:00+02:00
researcher: Przemyslaw Przeworski
git_commit: 315f9b3ec217019a0d540576217e6e76d156fc65
branch: main
repository: PrzemyslawPrzeworski/AutoSkanerAI
topic: "S-04 CEPiK VIN lookup — API structure, auth, integration strategy, and schema placement"
tags: [research, cepik-vin-lookup, cepik, historiapojazdu, vin, enrichment, schema]
status: complete
last_updated: 2026-06-02
last_updated_by: Przemyslaw Przeworski
---

# Research: S-04 CEPiK VIN Lookup

**Date**: 2026-06-02T00:00:00+02:00
**Researcher**: Przemyslaw Przeworski
**Git Commit**: 315f9b3ec217019a0d540576217e6e76d156fc65
**Branch**: main
**Repository**: PrzemyslawPrzeworski/AutoSkanerAI

## Research Question

How should S-04 (CEPiK VIN lookup) be implemented? Specifically:
1. What does the CEPiK API actually return — field names, structure, auth requirements?
2. What is the correct data source for vehicle history (registration dates, ownership count, mileage stamps, accident records)?
3. How does the "unknown, not clean" guardrail apply to CEPiK data?
4. Where does the CEPiK result slot into the locked output schema?
5. What is the VIN extraction status in the codebase — is the actual VIN string available?

## Summary

**Three critical findings that reshape planning for S-04:**

1. **The codebase only extracts a boolean (`vinPresent`), not the actual VIN string.** `ExtractedData.java:18` has `Boolean vinPresent` — the LLM prompt asks for presence only. To call any CEPiK endpoint, the schema must first be extended to extract and store the actual VIN string.

2. **There are two completely separate Polish vehicle registry systems**, and only one has the data S-04 needs:
   - `api.cepik.gov.pl` — public open API, no auth, but only has technical registration data. No accident records, no mileage history, no ownership count. Can filter by VIN but requires voivodeship + date range too.
   - `historiapojazdu.gov.pl` — the correct source (owner count, mileage stamps, significant damage), but has **no public REST API**. It is a session-based web app via moj.gov.pl. Only accessible via unofficial session scraping or a future formal B2B API.

3. **`historiapojazdu` requires three inputs: registration plate + VIN + first registration date.** VIN alone is not enough. AutoSkanerAI's listing text rarely contains all three.

**Practical recommendation for MVP:** Implement S-04 in two sub-steps:
- **Step 1 (unblocking schema change):** Extend `ExtractedData` with `String vin` (the actual value alongside the existing `vinPresent` boolean). Update LLM prompt to extract the VIN string.
- **Step 2 (integration):** Use `api.cepik.gov.pl` `/pojazdy` with `filter[numer-vin]` for what it can give (confirms VIN exists in registry, returns make/model/year/origin for cross-check). Display `historiapojazdu.gov.pl` link prominently as a "check full history" CTA — official web app, user opens in browser. Full `historiapojazdu` session scraping is high-risk for MVP (unofficial, no SLA, can break); defer to post-MVP.

## Detailed Findings

### The Three Polish Vehicle Registry Systems

#### System A: `api.cepik.gov.pl` — Public Open Data API

- **Base URL:** `https://api.cepik.gov.pl`
- **Auth:** None — completely public, no API key required
- **Swagger:** `https://api.cepik.gov.pl/doc` (requires browser; JSON spec at `/swagger/apicepik.json`, currently 403 on direct fetch)
- **Status:** Currently unreliable — freepublicapis.com reports 0% uptime over last 30 days (June 2026). Community scripts implement 30-second retry backoffs.
- **SSL issue:** Uses older cipher suites. Python clients use `@SECLEVEL=1` workaround. Java integration needs a custom `SSLContext` or legacy cipher suite configuration.

**Key endpoint for VIN lookup:**
```
GET https://api.cepik.gov.pl/pojazdy
  ?wojewodztwo=14
  &data-od=20100101
  &data-do=20251231
  &typ-daty=2
  &tylko-zarejestrowane=true
  &pokaz-wszystkie-pola=true
  &filter[numer-vin]=WVWZZZ3BZ3E123456
  &limit=500
```

**Mandatory params:** `wojewodztwo` (2-digit voivodeship code) + date range — VIN alone is not accepted. Without knowing the voivodeship, you must query all 16 voivodeships in parallel (or sequentially) to find the vehicle.

**Response format (JSON:API):**
```json
{
  "data": [
    {
      "type": "vehicles",
      "id": "uuid",
      "attributes": {
        "marka": "TOYOTA",
        "model": "COROLLA",
        "rok-produkcji": "2015",
        "data-pierwszej-rejestracji": "20150301",
        "data-pierwszej-rejestracjiwkraju": "20150601",
        "data-ostatniej-rejestracji-w-kraju": "20220101",
        "data-wyrejestrowania-pojazdu": null,
        "numer-vin": "WVWZZZ...",
        "pochodzenie-pojazdu": "...",
        "rodzaj-paliwa": "...",
        "pojemnosc-skokowa-silnika": 1598,
        "rejestracja-wojewodztwo": "MAZOWIECKIE"
      }
    }
  ],
  "links": { "last": "...page=N..." }
}
```

**What public API gives S-04:**
- ✅ Confirms VIN exists in Polish registry
- ✅ First registration date in Poland (`data-pierwszej-rejestracjiwkraju`)
- ✅ Make/model/year cross-check
- ✅ Vehicle origin (imported / domestic)
- ✅ Deregistration status (`data-wyrejestrowania-pojazdu` — if not null, car is no longer registered)
- ❌ Number of owners — NOT available
- ❌ Mileage history — NOT available
- ❌ Accident/damage records — NOT available

#### System B: `historiapojazdu.gov.pl` — Full Vehicle History

- **URL:** `https://historiapojazdu.gov.pl`
- **Type:** Session-authenticated nForms web application (no public REST API)
- **Auth:** Session via `moj.gov.pl` — XSRF token + `Nf_wid` session identifier
- **Required inputs (all three mandatory):** registration plate number + VIN + first registration date
- **Data available:**
  - Number of owners since first Polish registration
  - Mileage readings from technical inspections (from 2014)
  - Significant damage events (`szkody istotne`) — damage to chassis/brakes/steering reported by insurers
  - Technical inspection dates and validity
  - OC insurance validity
  - Stolen/deregistered status
  - Foreign risk data (US, Canada, some EU countries)

**Session flow** (from Python client `mtatko/cepik-vehicle-history-client`):
1. `GET https://moj.gov.pl/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu` — get cookies
2. `POST` same URL with `NF_WID=HistoriaPojazdu:<timestamp>` — authenticate, get `XSRF-TOKEN`
3. `POST .../data/vehicle-data` with `{ "registrationNumber": "...", "VINNumber": "...", "firstRegistrationDate": "YYYY-MM-DD" }`
4. `POST .../data/timeline-data` — same body, returns chronological history
5. `GET .../close` — close session

**Why this is risky for MVP:** Unofficial, no SLA, no ToS permission, can break at any moj.gov.pl release. The Python client is the only implementation reference.

**Key constraint for S-04:** `firstRegistrationDate` is required but not always present in Polish car listings. Difficult to extract reliably from listing text.

#### System C: CEPiK 2.0 B2B API (future)

- Formal application to `biurocepik2.0@cyfra.gov.pl`; 2–4 weeks for access
- Currently covers driver license verification only; vehicle data expansion announced but not yet available
- Rate limits (documented): 30 req/s, 1,500 req/min, 80,000 req/hour

### VIN Extraction Status in Codebase

**Current state: boolean only, no VIN string.**

`ExtractedData.java:18` — `Boolean vinPresent` — a flag, not the value.

`AnalysisPrompt.java:36` — LLM schema specifies `"vinPresent": <boolean|null>`. The model is never asked to extract the VIN string.

`AnalysisPrompt.java:99` — Risk flag example: `{ "code": "NO_VIN", "severity": "HIGH", "description": "Brak numeru VIN — nie można zweryfikować pojazdu" }`. VIN is used only to generate a risk flag, not to enable downstream lookups.

`MockAiAnalysisService.java:112` — Mock uses keyword detection: `lower.contains("vin") || lower.contains("nr identyfikacyjny")`.

**What must change before S-04 can call any CEPiK endpoint:**
1. Add `String vin` field to `ExtractedData.java` (alongside existing `Boolean vinPresent`)
2. Update `AnalysisPrompt.java` to ask LLM to extract the actual VIN string (17-char alphanumeric)
3. Update `AnalysisResponseParser.java` `ExtractedDto` and `mapExtracted()` to handle the new field
4. Update `MockAiAnalysisService.java` mock to extract and return a VIN string when present
5. Update `analysis.models.ts` `ExtractedData` interface with `vin: string | null`
6. Add VIN format validation (17 chars, alphanumeric, no I/O/Q) in the parser

This is a **prerequisite sub-step** inside S-04, not a separate change. It is additive and backward-compatible (nullable field).

### "Unknown Not Clean" Guardrail Application to CEPiK Data

**From `context/foundation/prd.md:38`:**
> "The app must not draw conclusions from the absence of accident information — missing data means unknown, not clean."

**How this applies to `historiapojazdu` data:**

The `szkoda istotna` (significant damage) field in `historiapojazdu` records damage reported to insurers. **Absence of `szkoda istotna` does NOT mean clean** for two reasons:
1. Unreported damage (cash settlements, undeclared incidents) is never recorded
2. Damage below the threshold for "significant" (cosmetic, minor) is not recorded
3. Foreign damage before import is not in the Polish registry

**Correct UI copy for S-04:**
- `szkody istotne: 0 records` → display as "Brak zgłoszonych szkód istotnych w CEPiK — nie wyklucza napraw poza ubezpieczeniem" (No significant damage in CEPiK — does not exclude uninsured repairs)
- `szkody istotne: 2 records` → display confirmed damage with dates
- CEPiK lookup failed / VIN not found → display as "Weryfikacja CEPiK niedostępna"

**From `api.cepik.gov.pl`:** The public API has no accident field at all. Even a successful VIN lookup returning clean vehicle data cannot be presented as any confirmation of accident history.

### Integration Pattern — Where CEPiK Result Slots In

Following the pattern established by S-05 research (see `context/changes/market-price-context/research.md`), the CEPiK result should be a **nullable field on `AnalysisResponse`**, not on `AnalysisResult`.

`AnalysisResult` is the locked LLM output contract. CEPiK is an async enrichment fetched independently after the analysis. Adding it to `AnalysisResponse` keeps the two layers decoupled.

**Current `AnalysisResponse.java:3`:**
```java
public record AnalysisResponse(String fetchStatus, String fetchFailureReason, AnalysisResult analysis)
```

**Proposed addition (shared upfront commit with S-05):**
```java
public record AnalysisResponse(
    String fetchStatus,
    String fetchFailureReason,
    AnalysisResult analysis,
    CepikResult cepikResult,          // nullable; null if VIN absent or lookup fails
    MarketPriceContext marketPriceContext  // nullable; null if make/model unknown or fetch fails
)
```

**Proposed `CepikResult` record:**
```java
record CepikResult(
    CepikStatus status,               // FOUND | NOT_FOUND | LOOKUP_FAILED | VIN_ABSENT
    String vin,                       // the VIN that was looked up (for display)
    String firstRegistrationDatePl,   // data-pierwszej-rejestracjiwkraju (YYYYMMDD)
    String deregisteredDate,          // data-wyrejestrowania-pojazdu (null if still registered)
    String originCountry,             // pochodzenie-pojazdu
    Integer ownerCount,               // from historiapojazdu (null if only public API used)
    List<MileageStamp> mileageStamps, // from historiapojazdu (null if only public API used)
    List<DamageRecord> damageRecords, // from historiapojazdu; empty list ≠ clean
    String lookupUrl,                 // link to historiapojazdu.gov.pl for user to verify
    Instant fetchedAt
)
```

**Corresponding TypeScript addition to `analysis.models.ts`:**
```typescript
export type CepikStatus = 'FOUND' | 'NOT_FOUND' | 'LOOKUP_FAILED' | 'VIN_ABSENT';

export interface CepikResult {
  status: CepikStatus;
  vin: string | null;
  firstRegistrationDatePl: string | null;
  deregisteredDate: string | null;
  originCountry: string | null;
  ownerCount: number | null;
  mileageStamps: MileageStamp[] | null;
  damageRecords: DamageRecord[] | null;
  lookupUrl: string | null;
  fetchedAt: string;
}
```

### Java Integration Specifics

**No Java/Spring library exists for either CEPiK surface.** Implementation uses Spring `RestClient` (same pattern as `ListingFetchService`).

**SSL issue:** The public API uses older TLS ciphers. Java's default SSLContext may reject the handshake. A custom `SSLContext` enabling legacy cipher suites is needed:
```java
// Rough pattern — exact cipher list TBD during implementation
SSLContext ctx = SSLContext.getInstance("TLS");
// ... configure with TrustManager and legacy cipher suites
RestClient.builder().requestFactory(factory).build();
```

**Voivodeship enumeration problem:** To look up a VIN on the public API without knowing the voivodeship, query all 16 in parallel (codes `02`, `04`, `06`, `08`, `10`, `12`, `14`, `16`, `18`, `20`, `22`, `24`, `26`, `28`, `30`, `32`). Use Spring's `CompletableFuture` for parallel execution with a short timeout. Return the first non-empty result.

**Java reference:** `PiotrMichalowski96/ImportedCars` — Spring Boot + Apache Camel calling `api.cepik.gov.pl/pojazdy`. Useful pattern for the HTTP call structure.

### Rate Limits and Reliability

**Public API:** No documented limits. Currently unreliable (0% uptime reported). Must be treated as best-effort with a fast timeout (5–10s) and graceful null fallback. A circuit breaker pattern (e.g. Spring's `@Retryable` with fallback) is essential.

**`historiapojazdu`:** Session-based — no documented rate limit, but session abuse will trigger blocks. For MVP scale (one lookup per user analysis) this is not a concern.

**Render production environment:** The public API SSL issue must be tested on Render's JVM. The TLS downgrade that works locally may behave differently in the Docker container.

## Code References

- `backend/src/main/java/com/example/autoskaner_ai/analysis/ExtractedData.java:18` — `Boolean vinPresent` — boolean only, no VIN string
- `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisPrompt.java:36` — LLM schema: `"vinPresent": <boolean|null>` — must be extended
- `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisPrompt.java:99` — `NO_VIN` risk flag example
- `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java:86` — `mapExtracted()` — maps VIN boolean
- `backend/src/main/java/com/example/autoskaner_ai/analysis/llm/AnalysisResponseParser.java:146` — `ExtractedDto` — `Boolean vinPresent`
- `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java:112` — Mock VIN detection by keyword
- `backend/src/main/java/com/example/autoskaner_ai/analysis/MockAiAnalysisService.java:24` — Canned question: "Czy numer VIN można zweryfikować w bazie CEPiK?"
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResponse.java:3` — Top-level response wrapper to be extended
- `backend/src/main/java/com/example/autoskaner_ai/analysis/AnalysisResult.java:5-13` — Locked LLM output schema (CEPiK result goes on AnalysisResponse, not here)
- `frontend/src/app/shared/models/analysis.models.ts:16` — `vinPresent: boolean | null` — needs `vin: string | null` sibling

## Architecture Insights

1. **VIN string extraction is a prerequisite sub-step.** S-04 cannot make any CEPiK call without the actual VIN value. Extending `ExtractedData` with a `vin` string field is the first deliverable of S-04 — done as a prompt + schema change, not as an external API integration.

2. **Parallel-to-S-05 schema commit.** Both S-04 and S-05 add nullable enrichment fields to `AnalysisResponse`. This single commit (adding both `cepikResult` and `marketPriceContext` as null-returning stubs) should land on main before either branch starts implementation, eliminating all merge conflict risk.

3. **Two-tier MVP for S-04.** The public API gives partial but real value (VIN registry confirmation, registration dates, deregistration status, make/model cross-check). The `historiapojazdu` link gives the user the full picture via the official gov.pl interface. This two-tier approach avoids unofficial session scraping while delivering visible enrichment in the MVP.

4. **Circuit breaker is mandatory.** Both CEPiK surfaces have reliability problems. `CepikResult.status = LOOKUP_FAILED` must be a first-class outcome that the frontend renders as a degraded-but-honest state — never silently omitting the section.

5. **All 16 voivodeships for VIN search.** The public API requires voivodeship + date range. Without those, the only option is to query all 16 voivodeships in parallel with a wide date range. This is 16 HTTP calls per analysis — acceptable for MVP at low scale; cache or circuit-break if volume grows.

6. **"Unknown not clean" applies at every level.** Empty `damageRecords` list is NOT a clean bill — must always be displayed with the caveat that unreported/uninsured damage is invisible to CEPiK. The frontend copy must never say "no accidents found" — only "no significant damage reported to insurers."

## Historical Context (from prior changes)

- `context/changes/llm-analysis-wiring/plan.md:38-54` — Locked output schema. `ExtractedData` has `vinPresent: Boolean` at line 18. Schema extension for S-04 (adding `vin: String`) is additive and backward-compatible.
- `context/changes/llm-analysis-wiring/plan.md:56` — "every nullable field in `extracted` is explicitly nullable — missing data means unknown." Same rule governs the new `vin` field: null means VIN was not found in listing, not that the car has no VIN.
- `context/changes/market-price-context/research.md` — S-05 established the enrichment pattern: parallel nullable field on `AnalysisResponse`, fetched independently of LLM analysis. S-04 follows the same pattern.

## Related Research

- `context/changes/market-price-context/research.md` — S-05 enrichment pattern, schema placement recommendation (Option B: field on `AnalysisResponse`), parallel worktree strategy

## Open Questions

1. **historiapojazdu MVP scope:** Include unofficial session scraping in MVP or deliver only the public API + "check full history" link? Recommendation: public API + link for MVP; session scraping as post-MVP if the link-based CTA proves insufficient for users.

2. **Voivodeship enumeration vs. narrow search:** 16-voivodeship parallel scan is ~16 HTTP calls per VIN lookup, all against an unreliable API. Acceptable for low-volume MVP. Should a timeout of 3–5s per call with early-exit-on-first-result be used? Yes — implement with `CompletableFuture.anyOf()` returning the first non-empty result.

3. **`firstRegistrationDate` availability:** `historiapojazdu` requires it as a mandatory input. Is it extractable from Polish listing text reliably enough? LLM currently extracts `year` (production year) but not first registration date. These are often different (car made in Dec 2019, first registered Jan 2020). Needs prompt extension research.

4. **SSL cipher compatibility on Render:** The older TLS ciphers on `api.cepik.gov.pl` may cause handshake failures on Render's JVM. Test in Docker environment before relying on this integration in production.

5. **VIN format validation:** A valid VIN is 17 alphanumeric characters, no I/O/Q. The parser should validate format before attempting any lookup — returning `CepikStatus.VIN_ABSENT` for malformed values rather than making a doomed API call.

6. **`cepik.gov.pl` reliability recovery:** The API is currently reported as dead. Integration must assume it can fail entirely and must degrade gracefully. Consider a health-check endpoint (`GET /version`) at application startup to log availability, and a circuit breaker (e.g. Resilience4j) for production.
