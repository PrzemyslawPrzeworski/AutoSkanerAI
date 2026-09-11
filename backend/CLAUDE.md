# backend/CLAUDE.md — Spring Boot service

Loaded **in addition to** the root `CLAUDE.md`, not instead of it. The business
rules, the monorepo map, the quality gates and the deploy targets are there; what
follows is what only the backend owns. Paths are relative to the repo root
throughout, so they read the same from either file.

## API error shape

All Spring controllers must return errors in this exact shape — no exceptions, no `ProblemDetail`:

```json
{ "status": 400, "error": "Błąd walidacji", "messages": ["field: message"], "timestamp": "2026-05-24T12:00:00Z" }
```

- `ErrorResponse` record lives in `com.example.autoskaner_ai.common`
- `GlobalExceptionHandler` (`@RestControllerAdvice`) in the same package handles: `MethodArgumentNotValidException` (400), `HttpMessageNotReadableException` (400), catch-all `Exception` (500)
- `messages` is `List<String>`; for validation errors format each entry as `"field: message"`
- `timestamp` is `Instant.now()`

## API endpoints

- `POST /api/analyses` — canonical endpoint; accepts `{ "listingText": "..." }`, `{ "url": "..." }`, or `{ "manual": { ... } }`, plus optional `vin` / `registrationPlate` / `firstRegistrationDate` overrides. Returns `AnalysisResponse { fetchStatus, fetchFailureReason, analysis, cepikResult, marketPriceContext }`
- `POST /api/analysis/risk` — **deprecated** facade returning only `{ riskFlags: [...] }`; to be removed after S-01 ships

`fetchStatus` values: `"text"` (listing text analysed directly), `"ok"` (URL fetched successfully), `"manual"` (structured fields, FR-003), `"url_failed"` (fetch failed — `analysis` is null, frontend shows text-paste fallback).

## AI service pattern

The AI layer uses a Spring interface with three Profile-switched implementations:

- `AiAnalysisService` — interface defining the contract
- `MockAiAnalysisService` — deterministic mocks, activate with `SPRING_PROFILES_ACTIVE=mock`
- `BedrockClaudeAnalysisService` — Claude Haiku 4.5 via AWS Bedrock, activate with `SPRING_PROFILES_ACTIVE=bedrock`
- `OpenRouterAnalysisService` — any OpenRouter model, activate with `SPRING_PROFILES_ACTIVE=openrouter`

Required env vars: `AWS_PROFILE` (or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`) for `bedrock`; `OPENROUTER_API_KEY` for `openrouter`. The openrouter key has **no default** in `application-openrouter.properties`, so an unset value fails context startup rather than degrading.

`OpenRouterAnalysisService` retries and falls back along two independent axes, because free slugs fail in two unrelated ways:

- **Transient** (429, 5xx, IO/timeout) — retry the *same* model once, waiting out `Retry-After` (capped at 6 s, and never past the deadline). An immediate retry is useless against a saturated pool; that is what turned single 429s into production 502s on 2026-08-26.
- **Permanent for this model** (404 = slug retired, 400 = request rejected) — skip the retry, go straight to the next candidate in `llm.openrouter.fallback-models`.
- **Fatal** (401/403/**402**, or a malformed response shape) — fail immediately. A refused account is refused by every model, and walking the chain only multiplies latency before the same error. 402 (out of credits) rides the same `REJECTED_CREDENTIALS` reason and so surfaces as "odrzuciła dane dostępowe" — the remedy is topping up, not rotating the key, so check the logged status before acting on that string. Schema failures from `AnalysisResponseParser` also propagate: a prompt/parser mismatch is not fixed by another model.

`llm.openrouter.deadline-seconds` bounds how far the chain walks; it is checked only *between* models, so the primary is always attempted. `AnalysisMeta.model` records the model that actually answered, not the configured primary.

Production runs `openrouter`. `bedrock` is dev-only: the sole AWS credential source here is a corporate SSO profile (`kn.awsapps.com`, role `KN-DevelopmentEngineer`) issuing short-lived credentials, so it cannot back a hosted service — do not copy AWS credentials into Render to work around this.

**`bedrock` is therefore the local escape hatch when the OpenRouter free tier is spent**, and it is
the *only* one: the daily cap is per **account**, not per model, so rotating the slug does nothing
(§ "Live integration tests" and `application-openrouter.properties` spell out the three failure
modes). Measured 2026-09-11 — a real analysis through the authenticated local API in **9.0 s**, of
which 8.2 s was the model (Haiku 4.5, 2260 in / 1084 out tokens), against ~16 s for a free
OpenRouter slug. Run it with:

```bash
cd backend && AWS_PROFILE=przemyslawprzeworski AWS_REGION=eu-central-1 \
  AUTH_JWT_SECRET="$(openssl rand -base64 48 | tr -d '\r\n')" \
  SPRING_PROFILES_ACTIVE=bedrock ./mvnw -o spring-boot:run
```

Three things about that command are not guessable:

- **`AWS_PROFILE` is the credential source, not `.env`.** `.env` carries `AWS_ACCESS_KEY_ID=` and
  `AWS_SECRET_ACCESS_KEY=` **empty**, so `DefaultCredentialsProvider` finds nothing and the failure
  arrives as a credentials error that reads like a missing key rather than a missing profile. The
  live profile is `przemyslawprzeworski` (the only one configured); confirm the SSO session with
  `aws sts get-caller-identity` before blaming the app, and re-`aws sso login` when it has expired.
- **No truststore flag is needed here**, unlike the live tests. Verified both ways on 2026-09-11:
  `bedrock-runtime.<region>.amazonaws.com` is not TLS-intercepted on this machine, so the
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT` workaround § "Live integration tests" prescribes is
  specific to `openrouter.ai` and `r.jina.ai`. Adding it does no harm; needing it would mean the
  proxy policy changed.
- **`AUTH_JWT_SECRET` is required even locally** under any profile but `mock`, and generating it
  inline is the reliable way — see § "Auth (F-03)" for why, and the CRLF trap in
  `context/changes/auth-scaffold/change.md` for why `tr -d '\r\n'` and not `tr -d '\n'`.

Under `bedrock` the two enrichments still degrade behind the proxy: `marketPriceContext` comes back
`FETCH_FAILED` because `r.jina.ai` is blocked by policy, and `cepikResult` is `MISSING_INPUTS`
without a VIN. Both are the documented local outcome, not a regression.

Output schema is locked — see `context/changes/llm-analysis-wiring/plan.md` § "Locked output schema".

One rule binds the port, not just the parser: a null `accidentClaim` must yield `NO_ACCIDENT_DECLARATION` at `MEDIUM`. `AiAnalysisServiceContractTest` asserts it on two parameters — `MockAiAnalysisService`, and `AnalysisResponseParser` behind an adapter, since both network beans reach the rule through the parser. A fourth implementation goes into that contract. See § "Enrichment services" for the shape and `context/foundation/test-plan.md` §6.8 for the convention.

## Manual entry and user overrides (FR-003, S-02)

`ManualListing` carries the structured fields; `ManualListingComposer` renders them into Polish advert-style text so manual mode reuses the S-01 prompt and output schema rather than needing a second one. Composition lives on the server because prompt shape is a backend concern. The composer never fills a blank field with "brak danych" — the model reads a stated "brak historii serwisowej" as a fact about the car and flags it, when all that happened is the user left the box empty.

`UserOverrides.apply` runs in `AnalysisController.buildResponse` **before enrichment**, so the registry lookup and the market-price query use what the user typed. Two rules:

- **A typed value wins** over the extraction, including make/model/year/price/mileage/fuel/transmission. VIN and plate are upper-cased; the date is left verbatim because `RealCepikEnrichmentService` owns date normalisation and a second copy would drift.
- **A blank field never nulls a good extraction.** The frontend form is prefilled from the extraction, so an untouched field means "no opinion".

`accidentClaim` is deliberately **not** user-editable: it is a claim the *listing* makes, and `CepikRiskAdjuster` compares it against the registry. Letting a user "correct" it would delete the `CEPIK_CONTRADICTS_LISTING` finding it exists to raise.

A malformed VIN is not a 400 — it must not throw away an otherwise useful analysis. `RealCepikEnrichmentService` reports `MISSING_INPUTS` and the controller asks for it again. The frontend also checks the VIN shape before submitting; see `frontend/CLAUDE.md` § "Vehicle data form" for why the UI asks for the VIN and nothing else.

**A derived finding must not outlive the input it describes, and `buildResponse` is where that is enforced.** The analysis — flags included — is produced from the advert, before the user's values exist; `UserOverrides` runs after it. So `AnalysisController` re-derives, late, whatever the model could not have known:

- **`NO_VIN` is dropped once the VIN in hand validates.** Otherwise a request carrying a good VIN came back with `cepikResult.status: FOUND` next to `NO_VIN` at `HIGH` reading *"nie można zweryfikować pojazdu"* — one response contradicting itself, since its own `extracted.vinPresent` said `true`. It was never a mock artefact: `AnalysisPrompt` asks the model for the same code, and the model only ever sees the advert.
- **One predicate decides the flag and the seller question**, `vinIsVerifiable` → `VinValidator.normalise(...).isPresent()`. They used to run in parallel, and only the question was override-aware — which is how a single method came to disagree with itself. Adding a third consequence of "the VIN is unusable" goes through the same predicate.
- **Validity, not presence, and the two are not interchangeable.** `UserOverrides` sets `vinPresent` to `TRUE` for any non-blank typed value, so a typed `ABC` yields `vinPresent: true` *and* a `MISSING_INPUTS` lookup — and for that request `NO_VIN`'s text is true, so the flag stays. `SuppliedVinClearsTheNoVinFlagTest` pins that case specifically; a future simplification to non-blankness fails there rather than in production.
- **Matching on `RiskFlag.code` is a mitigation, not a guarantee.** The code is a free-form `String`, so a model may report the same finding under a code the rule does not know. It covers the documented vocabulary — the prompt's schema and what `MockAiAnalysisService` emits.

Scope is that one flag, deliberately: there is no `NO_PLATE` or `NO_DATE` flag to generalise over, and `NO_ACCIDENT_DECLARATION` is structurally excluded because `AnalysisRequest` has no accident field to override with. See `context/changes/no-vin-flag-survives-override/`.

The frontend's "Sprawdź historię pojazdu" follow-up re-runs the whole analysis rather than calling a lookup-only endpoint. That is intentional: CEPiK findings only reach `scores` / `verdict` through `CepikRiskAdjuster` on the analysis path.

## Persistence (F-02)

Spring Data JPA over Flyway. Two tables, `users` and `analyses`; two entities, `UserAccount` in
`com.example.autoskaner_ai.account` and `SavedAnalysis` in `…saved`. **Nothing is exposed over HTTP
yet** — F-02 shipped the layer, the endpoints and the login arrive with F-03 and S-03.

- **Flyway owns the schema; Hibernate only checks it.** `spring.jpa.hibernate.ddl-auto=validate`, so
  drift fails startup instead of being patched into the live schema while the migration — the thing a
  new environment replays — stays wrong. Add a column by adding `V<n>__*.sql`, never by letting
  Hibernate do it. A drifted entity fails in the commit gate, which runs H2.
- **`spring-boot-flyway` is a separate dependency and its absence does not fail the build.** Boot 4
  split every autoconfiguration into its own module, so `flyway-core` alone is an inert library that
  nothing starts, and `spring-boot-starter-data-jpa` does not pull the module in. The symptom is
  `Schema validation: missing table [analyses]` — a message about the entities, for a migration that
  never ran. Same shape for any other Boot 4 integration added here: check for a
  `spring-boot-<technology>` module before concluding the library is misconfigured.
- **One migration set serves both engines**, because the default datasource is H2 in
  `MODE=PostgreSQL`. Every type in `V1__init.sql` is chosen to mean the same thing to both:
  `SERIAL`, `jsonb`, `citext` and `TIMESTAMPTZ` are not portable, so it uses identity columns, `TEXT`
  and `TIMESTAMP WITH TIME ZONE` spelled out. The payload is `TEXT` rather than `jsonb` for exactly
  this reason.
- **H2 folds unquoted identifiers to UPPER CASE even under `MODE=PostgreSQL`** — that is a separate
  `DATABASE_TO_LOWER` setting. Flyway creates its history table with quoted lower-case names, so
  `SELECT success FROM flyway_schema_history` fails with `Table "FLYWAY_SCHEMA_HISTORY" not found
  (candidates are: "flyway_schema_history")`. Quoted lower-case is the one spelling both engines read
  identically; unquoted is only accidentally portable.
- **Real Postgres is opt-in via the `postgres` profile**, composed as
  `SPRING_PROFILES_ACTIVE=openrouter,postgres`, and `application-postgres.properties` gives
  `DATABASE_URL` / `_USERNAME` / `_PASSWORD` no defaults so an unset value fails startup. **Do not
  add a `${DATABASE_URL:<h2 default>}` to `application.properties`.** Render still carries those
  three vars pointing at a Supabase host that no longer resolves, and Flyway needs a connection
  before the context finishes starting — a default would have turned "the vars are dead" into a
  silent fallback to in-memory that loses a user's saved analyses without one failing request. For a
  different local datasource use Spring's own `SPRING_DATASOURCE_URL`.
- **Every test runs H2, so a green suite says nothing about PostgreSQL.** A dialect-specific type
  mismatch surfaces first as a failed Render deploy, which keeps serving the previous version.
- **`@SpringBootTest`, not `@DataJpaTest`, for repository tests.** The slice annotation's
  `@AutoConfigureTestDatabase` replaces the configured URL with a generated one, dropping
  `MODE=PostgreSQL` — the test would then exercise the migration against a dialect nothing runs.
- **`SavedAnalysisRepository` has no single-row `findById` path; every read goes through
  `findByIdAndUserId`.** Ownership is part of the lookup rather than a check after it, so someone
  else's id is indistinguishable from an id that does not exist and the check cannot be forgotten at
  one of three call sites. The schema backs it from below: `user_id` is `NOT NULL` with a FK and
  `ON DELETE CASCADE`. **When S-03 adds endpoints, `userId` must come from the authenticated
  principal** — taking it from the request body or a query parameter hands the guarantee to the
  caller. See `context/foundation/test-plan.md` §2 risk #8.
- **`UserAccount.create` is the only constructor, and that is the whole of the case-insensitivity
  guarantee.** The unique index is on the stored bytes, so `Foo@Example.com` and `foo@example.com`
  would otherwise be two accounts a user believes are one. A lookup must normalise through
  `UserAccount.normaliseEmail` too. Only the email is normalised — a password hash is bytes.
- **`title` and `note` are the only user-editable columns**, via `SavedAnalysis.edit`. They exist so
  the CRUD update operation has an honest subject: an analysis is a record of what the model said at
  a point in time, and making it editable would stop the saved verdict being evidence. The summary
  columns beside the payload are denormalised so the saved-list view renders without deserialising
  every row.
- Entity is `UserAccount`, not `User` — F-03 introduces Spring Security, whose own `User` is imported
  in the same files. Table is `users` because `user` is reserved in PostgreSQL.

## Auth (F-03)

Stateless JWT over Spring Security 7's OAuth2 resource server; everything lives in
`com.example.autoskaner_ai.auth`. `POST /api/auth/register` (201), `POST /api/auth/login` (200),
`POST /api/auth/refresh` (200) are public; `GET /api/auth/me` is not. **`/api/**` is
`authenticated()`**, so `POST /api/analyses` — open since S-01 — now answers 401 without a bearer.
Everything outside `/api/**` keeps its old answer, because Render probes `/`.

- **`auth.jwt.secret` is `${AUTH_JWT_SECRET}` with no fallback**, so an unset value fails context
  startup. HS256 needs 32 bytes and `JwtConfig` refuses less rather than signing weakly. Only
  `application-mock.properties` carries a fixed development key — `mock` is the offline
  no-credentials profile the git hooks and the E2E specs run, and production never activates it. A
  committed default in a public repository is a key anyone can use to mint a token for any account.
- **A `typ` claim is the only thing stopping a refresh token from opening the API.** Both tokens are
  signed with the same key, so a refresh token in an `Authorization` header would authenticate every
  request unless something rejects it. `TokenTypeValidator` — an `OAuth2TokenValidator` on each
  decoder — is that something: the resource server's decoder accepts `typ=access` only, and
  `TokenService`'s private decoder accepts `typ=refresh` only. This is the security assertion of the
  whole feature; `ApiRequiresAuthenticationTest.aRefreshTokenDoesNotOpenTheApi` is the test that
  names the consequence, and it is what the deliberate-break check targeted.
- **`/api/auth/**` is deliberately not a permitted prefix.** The three public paths are listed
  literally, because `GET /api/auth/me` reads the principal and a wildcard is exactly how that
  endpoint becomes public. The frontend interceptor repeats the same three literals for a second
  reason — see `frontend/CLAUDE.md` § "Auth on the client".
- **No JWT library was added.** `spring-boot-starter-security-oauth2-resource-server` already ships
  Nimbus JOSE through `spring-security-oauth2-jose`, which is both encoder and decoder. jjwt would
  bind token handling to Jackson 2 — present here only as a Flyway transitive — to buy something
  already on the classpath.
- **`CorsConfig` is gone; CORS lives on a `CorsConfigurationSource` bean the filter chain reads.** A
  `WebMvcConfigurer`'s mapping is applied by the MVC handler, which runs *after* security, so a
  preflight `OPTIONS` was judged before the mapping was consulted and surfaced in the browser as an
  opaque CORS error for what was really an authorization decision. `PATCH` was missing from the old
  method list and is in the new one, because S-03's rename needs it. `allowCredentials` stays false:
  the session is a bearer header, not a cookie.
- **`@AutoConfigureMockMvc` now needs `spring-boot-starter-webmvc-test`.** Boot 4 moved it into its
  own module and renamed the package to `org.springframework.boot.webmvc.test.autoconfigure`; the
  symptom is a compile error naming the old package, which reads like a typo. Same split as
  `spring-boot-flyway` in F-02. It is not optional: every other controller test here uses
  `MockMvcBuilders.standaloneSetup`, which builds a dispatcher with **no filter chain**, so an
  unauthenticated request comes back 200 no matter what `SecurityConfig` says. A test that cannot
  fail when the lock is removed is not a test of the lock, which is why
  `ApiRequiresAuthenticationTest` is the one class that boots the real chain.
- **Login answers unknown-email and wrong-password identically**, and an unknown email still runs one
  BCrypt comparison against a throwaway hash computed at startup, so the two paths cost the same
  time. Without that, "no such account" answers in microseconds and is a usable oracle for whether an
  address is registered. Registration necessarily leaks existence through its 409; that is accepted.
- **Passwords cap at 72 characters because BCrypt truncates there.** Without the check, two different
  long passwords open the same account. The encoder is delegating, so the hash records its own
  algorithm (`{bcrypt}$2a$10$…`) and can be migrated without a schema change.
- **`TokenService` takes `(long userId, String email)`, not the entity.** `UserAccount.create` leaves
  `id` null until the insert, so passing the entity would mint a token whose subject is the literal
  `"null"` — and `AuthenticatedUser.requireId` would refuse it far from the cause.
  `AuthService.tokensFor` is the single seam and throws on a null id.
- Two traps in the tests: `NimbusJwtEncoder` cannot mint an already-expired token (`Jwt`'s
  constructor asserts `expiresAt` is after `issuedAt`), so expiry tests hand-build a claims set whose
  window closed **five** minutes ago — `JwtTimestampValidator` allows 60 s of skew by default, so a
  −30 s test passes while the expiry check is broken. And `Jwt.getIssuer()` throws on a bare-name
  issuer like `autoskaner-ai`; read it as `getClaimAsString("iss")`.

Full reasoning, including what is deliberately missing (no revocation, no password reset, no rate
limit): `context/changes/auth-scaffold/change.md`.

## URL fetching

Listing URLs are fetched via **Jina Reader** (`https://r.jina.ai/<url>`), which handles JavaScript rendering and Cloudflare bypass for free. No API key needed.

- `ListingFetchService` prepends `https://r.jina.ai/` to the user-supplied URL
- SSRF protection runs on the user-supplied host before the Jina call
- Read timeout: 30 s (Jina needs time to render the page)
- Dev machines behind corporate proxies (e.g. Zscaler) will see `url_failed` — this is a network constraint, not a bug; production on Render works correctly
- `ListingFetchConfig` — provides `@Bean("listingFetchBuilder") RestClient.Builder`

## Enrichment services

`AnalysisController.buildResponse()` calls two enrichment services synchronously and attaches both to `AnalysisResponse` as nullable fields (`cepikResult`, `marketPriceContext`). Both follow the profile-switched interface pattern of the AI layer: a mock bean under `mock`, the real bean under `@Profile("!mock")`.

- **CEPiK (FR-017)** — `CepikEnrichmentService` / `MockCepikService` / `RealCepikEnrichmentService` in `com.example.autoskaner_ai.cepik`. Needs **all three** of VIN + registration plate + first registration date, all extracted by the LLM; any one missing or malformed yields `MISSING_INPUTS` and `AnalysisController` appends a seller question asking for it. `HistoriaPojazduService` scrapes `moj.gov.pl` with a fresh per-lookup session. Empty damage records mean **no damage reported to insurers**, never "no accidents" — UI copy must respect this, which is why every non-`FOUND` result carries `null` lists rather than empty ones.
  - **`MockCepikService` validates all three inputs and can answer `FOUND`.** It used to return `LOOKUP_FAILED` unconditionally, which meant the `mock` profile — the only profile the git hooks and the E2E specs ever run — never executed `CepikRiskAdjuster` at all. It now runs the same `VinValidator` + plate-pattern + non-blank-date checks as the real bean, reports `MISSING_INPUTS` when any of the three is absent or unusable, and on a well-formed triple answers a realistic `FOUND`: one `szkoda-istotna` event with an insurer and a date, one dated mileage stamp, `registrationProvince`, and the identity fields. It always answers `TOYOTA COROLLA`, so under `mock` any other listing shows a registry identity mismatch — that is a property of the fixture, not a defect. The synthetic VIN `NMTBZ3BE40R000000` is the committed value; this repository is public, so no real vehicle's VIN may appear in the mock or in a test.
  - **`api.cepik.gov.pl` cannot look up a vehicle by VIN.** Verified against the live endpoint 2026-08-25: the `pojazdy` resource returns 68 attributes and **none is a VIN**, `filter[numer-vin]` is rejected with "nie istnieją", `wojewodztwo` is mandatory, and `data-od`/`data-do` are capped at a 2-year span. Do not reintroduce a VIN-keyed date lookup against it — a "fixed" date range would return whichever unrelated car was registered first in that window and feed a wrong date into historiapojazdu.
  - `CepikStatus` distinguishes `NOT_FOUND` (registry answered 404 / `HIPO-0002` — no such vehicle) from `LOOKUP_FAILED` (session or scrape broke). The UI words these differently; never collapse them.
  - **historiapojazdu accepts `firstRegistrationDate` only as `yyyy-MM-dd`** — its `nfv_regex` validator 400s on anything else. `AnalysisPrompt` now asks the LLM for ISO directly, and `RealCepikEnrichmentService.toIsoDate` normalises whatever arrives anyway: numeric Polish forms (`dd.MM.yyyy`, `dd-MM-yyyy`, `dd/MM/yyyy`) plus prose (`12 kwietnia 2022`, genitive and nominative, case-insensitive). Keep both layers — the prompt is a request, not a guarantee. Do not pass the extracted string through raw, and do not write a test that hardcodes an already-ISO date; that is precisely what hid the original bug until a real listing hit production, and a prose date from Otomoto then slipped through the numeric-only fix for the same reason. A value with no day (`kwiecień 2022`) must stay `MISSING_INPUTS` rather than being rounded to the 1st.
  - **`HistoriaPojazduParser` maps only field names observed in a captured response.** Until 2026-08-26 the `FOUND` branch had never run against a real vehicle, and every field name in it was invented (`zdarzenia`, `szkodyIstotne`, `przebieg`, `liczbaWlascicieli`, `BADANIE_TECHNICZNE`) — none exists. The registry returns `technicalData.basicData` for identity and `timelineData.events[]` with `eventDate` / `eventType` / `eventName` / `eventDetails[{name,value}]`; a significant damage is `eventType: "szkoda-istotna"` with details `nazwa ubezpieczyciela` and `kategorie`. The parse silently produced `damageRecords: []`, which the UI rendered as "brak zgłoszonych szkód istotnych" for a car carrying a registered szkoda istotna. The tests behind it passed because the fixtures were hand-written to match the invented names. **Fixtures in `backend/src/test/resources/cepik/` must stay verbatim captures** — capture a new one rather than composing it, and do not add a field mapping without a captured payload showing that name. `deregisteredDate` and `originCountry` are deliberately left null for exactly this reason.
  - Dated mileage comes from the inspection events, not from the registry's own `odometerReadings`, which carry no dates.
  - **The API version in the path is discovered from the bootstrap HTML, not pinned.** `HistoriaPojazduSession` regexes `/nforms/api/HistoriaPojazdu/<version>/` out of the `NF_WID` response; the hardcoded `1.0.17` had rotted to `1.1.0`. `FALLBACK_API_VERSION` is a last resort, not the contract.
  - **Otomoto publishes the plate and first-registration date anonymously but gates the VIN behind login.** Verified 2026-08-26 on `toyota-corolla-ID6HG6ZH`: Jina Reader returned `registrationPlate` and a prose date, and `vinPresent: true` with `vin: null`. So a URL-only analysis can supply two of the three inputs and never the third — `MISSING_INPUTS` on real listings is the expected outcome until S-02 (manual field entry) lets the user type the VIN, which is the single field that unlocks CEPiK.
- **Market price (FR-018)** — `MarketPriceEnrichmentService` / `MockMarketPriceEnrichmentService` / `MarketPriceFetchService` in `com.example.autoskaner_ai.market`. Builds an Otomoto search URL from make/model/year/mileage, fetches it through Jina Reader, regex-extracts prices, returns min/median/max + sample size. Not Exa — see `context/changes/market-price-context/research.md`.

**Two of the three ports carry a contract test; the third deliberately does not.** A port contract test is named after the interface — `CepikEnrichmentServiceContractTest`, `AiAnalysisServiceContractTest` — and asserts the port's rules against *every* implementation at once via `@ParameterizedTest` + `@MethodSource`, so **adding a bean means adding one `Stream.of` element**. The convention, including why it deviates from one-test-class-per-class, is `context/foundation/test-plan.md` §6.8.

- `CepikEnrichmentService` pins three properties across all six ways the three required inputs can be absent or unusable: `MISSING_INPUTS`, never a call to `HistoriaPojazduService`, null lists rather than empty ones, and `fetchedAt` stamped even on the degraded paths. It stops at `null` and blank dates on purpose — the real bean's six-format `ResolverStyle.STRICT` parsing is *its* behaviour, not the port's, and stays in `RealCepikEnrichmentServiceTest`.
- `AiAnalysisService` pins one: a null `accidentClaim` yields `NO_ACCIDENT_DECLARATION` at `MEDIUM`. Its oracle is `AnalysisPrompt.java:16` and the root `CLAUDE.md` guardrail, not `AnalysisResponseParser`. It exists because `MockAiAnalysisService` had drifted the other way — suppressing the flag on the substring `"historia"`, at `HIGH` instead of `MEDIUM`.
- **`MarketPriceEnrichmentService` has none, and that is the decision, not an omission.** `MockMarketPriceEnrichmentService` ignores its input entirely, and the port promises nothing about absence that an implementation could invert — a thin or dispersed sample is a statistical-honesty problem owned by `MarketPriceStatistics`, which has its own tests and its own PIT run. Revisit if a second real implementation lands, or if the port gains a degraded-result shape the way `CepikResult` has.

Only assert at this layer what **must** hold of every implementation. With well-formed inputs the real bean returns whatever the registry said and a mock synthesises an answer — there is no shared property there, which is why the CEPiK contract's whole inputs axis is malformed-only.

Known tradeoff: both run on the request thread, so one analysis makes **5** historiapojazdu calls plus a Jina fetch — `HistoriaPojazduSession` spends a bootstrap GET, the `NF_WID` POST, `/vehicle-data`, `/timeline-data`, and `/close` in a `finally`, which is what `HistoriaPojazduConfig.java:16-17` already says. (This line read "up to 3" until 2026-09-04 and understated the timeout budget by two calls.) Async handling is deferred (impl-review F10).

Verification status (2026-08-26): both paths are confirmed against production, which now runs `SPRING_PROFILES_ACTIVE=openrouter`. A real analysis returned `marketPriceContext.status=OK` with `sampleSize=40` (so `PRICE_PATTERN` does match live Otomoto markdown) and `cepikResult.status=MISSING_INPUTS` for a listing with no VIN/plate/date. The CEPiK `FOUND` path is confirmed against the live registry as of 2026-08-26 — before that date only the `NOT_FOUND` branch had ever been exercised, which is why the parser's fabricated field names went unnoticed. `HistoriaPojazduServiceLiveTest` still only asserts `NOT_FOUND`; a `FOUND` assertion needs a real plate+VIN+date triple, which cannot be committed.

### Folding registry findings into the score

`CepikRiskAdjuster` folds registry findings into `scores` and `verdict` after enrichment, because the LLM scores the listing *before* the lookup runs and so never sees the CEPiK payload. Without it, production returned `risk: 88, verdict: WORTH_CHECKING` for a vehicle with a registered szkoda istotna — the damage visible in the panel and absent from the judgement.

- The adjustment is deterministic, not a second LLM call: a registered structural damage must not be able to score 88 because a model weighed it mildly.
- Risk ceilings, never raises — theft marker 5, odometer rollback 20, damage contradicting an accident-free claim 25, szkoda istotna 35, no OC policy 70. A listing the model already scored lower keeps its score, and `overall` is recomputed as the mean of the four categories but never raised.
- Damage alone floors the verdict at `NEEDS_MORE_INFO`, not `HIGH_RISK_SKIP`: a properly repaired damage with a positive post-repair inspection can be a fair purchase at the right price. A listing that claims `bezwypadkowy` *and* carries a registry damage is a separate, worse finding (`CEPIK_CONTRADICTS_LISTING`) and does force `HIGH_RISK_SKIP`.
- **The accident-free matcher is negation-aware, and deliberately only just.** `ACCIDENT_FREE_CLAIMS` is still substring-matched, but each occurrence is checked against the text immediately before it, so `"nie jest bezwypadkowy"` — an honest seller disclosing the damage — no longer earns `CEPIK_CONTRADICTS_LISTING` and a forced `HIGH_RISK_SKIP`. Before that fix the honest listing scored *worse* than one that said nothing, which is the incentive backwards. Three constraints hold the fix in place: the negation must be **attached** to the phrase (a "nie" anywhere in the claim would be a bypass the seller can type, since they write the advert — `"nie mam nic do ukrycia, auto bezwypadkowe"`); `"nie uczestniczy"` is **not** in `NEGATABLE_CLAIMS` because its own "nie" is the claim; and the two error directions are not equal — a false accusation is unfair to one seller, a missed contradiction reassures a buyer about a registered wreck. When in doubt, flag.
- **The adjuster logs when a denial clears the contradiction.** It is the only trace of a decision that rewrites a verdict, and its absence is why the defect above sat in a test-file comment rather than a bug report (OWASP A10). The claim is seller-supplied text on its way into a log file, so `forLog` strips control characters and bounds the length — a newline in an advert would otherwise forge a log line.
- **Only `FOUND` results adjust anything.** `NOT_FOUND` / `LOOKUP_FAILED` / `MISSING_INPUTS`, and a `FOUND` result whose `damageRecords` is null, must leave the score untouched in both directions — same null-is-not-empty rule as above. Tested explicitly.
- **All three post-analysis steps are fail-soft, and the adjuster's degraded value had to be designed rather than reused.** `buildResponse` runs the registry lookup, the market-price query and the adjustment after the ~16 s LLM call is already in hand, so an uncaught throw in any of them discards a finished analysis and answers 500. The two enrichments degrade to a status the UI already renders (`LOOKUP_FAILED`, `FETCH_FAILED`). The adjuster has no status, and the value that looks obvious for it — the analysis it failed to adjust — *is* the `risk: 88, WORTH_CHECKING` defect at the top of this section, arriving silently. So `CepikRiskAdjuster.unscored` is its degraded value: it keeps the whole `FOUND` panel, prepends a `HIGH` `CEPIK_NOT_SCORED` flag saying the score does not include the registry, and floors the verdict to `NEEDS_MORE_INFO`. **It caps nothing** — which finding fired is exactly what the throw destroyed, so any cap would be a number nobody computed; naming the score incomplete is honest, inventing a replacement is not. The rule to carry forward: *a step whose degraded value cannot say "this did not work" does not belong in `degradeOnThrow`* — give it a vocabulary first. `unscored` is a degraded supplier, so a throw inside it is the 500 the guard removes; it is written to be total (null flag list, null verdict) and that totality is tested, unlike the fold in `apply`, which would NPE on a null `riskFlags` no implementation currently produces.

One check that looks like it belongs here does not: the registry-vs-listing mileage comparison lives only in the frontend and does not feed the score. If it moves into scoring, delete the TypeScript copy rather than keeping two — see `frontend/CLAUDE.md` § "Vehicle data form".

### Trimming the market-price range

The market-price range is trimmed in `MarketPriceStatistics` before it is reported, because the raw regex output is not a set of asking prices. Two passes, because there are two kinds of contamination:

- **A band of ±3× the median** drops order-of-magnitude junk — a monthly financing instalment renders in the same `### <n>\nPLN` block as a price and clears the `1_000..10_000_000` guard easily. An IQR fence cannot catch this: enough junk drags the quartiles down with it, while the median is what junk cannot move. If the band would leave fewer than 3 prices the sample is reported untrimmed — a tight range invented from three survivors that happened to agree is worse than a visibly wide one.
- **Tukey's 1.5×IQR fence** (samples of 8+, skipped when IQR is 0) drops the right-order-of-magnitude-wrong-car cases: salvage titles, other trims. This is what fixes the live `min=39900` against `median=82900`.

`sampleSize` counts the **kept** prices, so the UI's "small sample" caveat describes the listings the numbers actually came from. The discarded count is logged, not returned. The median is a real median — averaged over both middle elements on an even sample, where the old `prices.get(size / 2)` was the upper-middle element.

## Live integration tests

```bash
cd backend && ./mvnw test -Plive-tests        # requires credentials in env
```

Tests are tagged `@Tag("live-llm")` and skipped by default in `./mvnw test`.

- **These now need `AUTH_JWT_SECRET` too.** The three live `@SpringBootTest` classes activate
  `openrouter` or `bedrock`, not `mock`, so `auth.jwt.secret` resolves from the environment and an
  unset value fails context startup — before any credential is even reached. The failure names the
  placeholder, not the auth feature.
- Use the `live-tests` **profile**. `-Dgroups=live-llm` does not work: the base surefire config sets `excludedGroups`, so adding an include just intersects to zero and reports BUILD SUCCESS over 0 tests. The profile flips the `test.excludedGroups` / `test.includedGroups` properties instead.
- Behind a TLS-intercepting corporate proxy the JVM does not trust the injected chain and every outbound call dies with `PKIX path building failed`. Add `-DargLine="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` to use the Windows certificate store.
- `r.jina.ai` may still be blocked by proxy *policy* (403 interstitial, category "General AI and ML Applications") even once TLS is trusted. That fails `MarketPriceFetchServiceLiveTest`, which is intentional — the test asserts `OK` rather than tolerating `FETCH_FAILED`, so a blocked path is visible instead of silently green.
- Live tests must assert real outcomes. Accepting `LOOKUP_FAILED` / `FETCH_FAILED` as a pass makes them useless — they went green for months while all three integrations were failing.

## Mutation testing

```bash
cd backend && ./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
```

PIT, behind the `mutation` profile, off by default and never wired into `./mvnw test`. A green suite answers "did the line run"; PIT answers "would a test notice if the line were wrong". Report: `target/pit-reports/index.html`.

- **A selective gate, not a coverage target.** `mutation.targetClasses` / `mutation.targetTests` are properties with narrow defaults, meant to be overridden per run (`-Dmutation.targetClasses=...`). Runtime scales with the mutant count, not the test count, so point it at the module a change or a `test-plan.md` risk actually touches.
- **Do not chase 100%.** Survivors are questions, not a task list: would a real bug of this shape hurt a user? Add an assertion only when the answer is yes. A test written to kill an *equivalent* mutant — one whose change is unobservable — is a mirror of the implementation and breaks on the next refactor.
- **The PIT version is load-bearing.** The dev JDK is 26 (class file major version 70). `pitest-maven` 1.20.4 cannot parse it and reports `BUILD SUCCESS` with `0/53 killed, 0% line coverage` — a mutation score of zero caused entirely by the tooling. 1.29.10 works. **If the score ever reads 0, suspect the tool before the tests.**
- **A comma-separated list of test classes in `mutation.targetTests` is the second way to get a false 0%.** `-Dmutation.targetTests=a.b.FooTest,a.b.BarTest` matched nothing and reported `0/61 killed` over `BUILD SUCCESS`; `-Dmutation.targetTests='a.b.*'` on the same code reported 87%. So the rule above generalises: **a score of 0 is a tooling result until proven otherwise** — check the version, then check that the filter selected any tests at all.
- `live-llm` is excluded, for the same reason it is excluded from `./mvnw test`: a mutant must never be judged by whether somebody's API key worked today.

`MarketPriceStatistics` on 2026-09-04: **89%** (47/53 killed, 95% line coverage, 0 uncovered mutants), up from a first baseline of 81% (43/53, 1 uncovered). The first run found two real gaps, and three tests closed them:

- **`MIN_SAMPLE_FOR_IQR` was unguarded at its own edge.** `kept.size() >= 8` → `> 8` survived: every existing test either had 7 survivors after the band or reached the line with `bandCollapsed` already true, so the size comparison never decided anything. `aSampleOfExactlyEightSurvivorsIsFenced` pins the "or more".
- **Neither arm of the Tukey hinge was pinned, and one had no coverage at all** — no test reached `withoutIqrOutliers` with an odd sample, so `sorted.size() % 2 == 0 ? half : half + 1` could have used either arm unnoticed. Two tests, because the two arms need opposite samples: `anOddSamplePutsItsMedianInBothHalvesWhenComputingTheHinges` kills the negate and `half + 1` → `half - 1` mutants, and `anEvenSampleTakesExactlyHalfIntoEachHinge` kills the `% 2` → `* 2` mutant, which on an *odd* sample selects the same arm as the original and so is unkillable there.

The 6 remaining survivors are left alive deliberately. Five are `ConditionalsBoundary` mutants on the inclusivity of the ±3× band edges (124, 125) and the IQR fence edges (93, 142 ×2): pinning the exact `>=`-vs-`>` of a constant the code's own comment calls wide on purpose mirrors the implementation rather than defending a behaviour. The sixth is genuinely **equivalent** — flipping the `iqr == 0` early return from `sorted` to empty changes nothing, because the caller rejects a too-small fenced list and keeps the untrimmed sample either way, so no test can kill it.

`CepikRiskAdjuster` on 2026-09-04: **90%** (55/61 killed, 99% line coverage, test strength 92%), up from 87% / 88% when the negation fix first went green. PIT earned its run here by asking a question the green suite could not: the `if (denied)` guard around the new **log line survived**, which meant nothing would notice the trace firing on the wrong branch or not firing at all. That log exists *because* the defect was unobservable, so leaving it unobserved reproduced the original failure one level up — the two log tests came from that survivor, and the log-injection one pins a security property rather than a wording. Of the six left alive, five are pre-existing and outside the change (`capRisk` / `applyFloor` / `describeDamage` / `moreSevere`, one of them a defensive `b == null` guard no caller can reach), and the sixth is the `<=` on the log-truncation constant.
