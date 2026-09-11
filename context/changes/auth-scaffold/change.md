---
change_id: auth-scaffold
title: Accounts and a locked API — stateless JWT, access in memory, refresh in localStorage
status: implementing
created: 2026-09-11
updated: 2026-09-11
archived_at: null
---

## Notes

Roadmap F-03, the middle link in `data-layer-setup` → `auth-scaffold` →
`save-view-delete-analyses`. F-02 shipped the `users` table and left it empty; this change writes
the first row into it and puts a lock on `/api/**`. S-03 then has an authenticated principal to
tie a saved analysis to.

**This is the first change that alters what an anonymous visitor can do.** Everything before it
was additive to a public API. `POST /api/analyses` has been open since S-01 and after this change
answers 401 without a token — which is what FR-010 requires ("unauthenticated visitors cannot
access any analysis functionality") and what the roadmap's own F-03 outcome names. The
consequence is that backend and frontend must ship in the same push: a deployed backend that
requires a token in front of a deployed frontend that does not send one is a dead app.

## Decisions

### Session mechanism: access token in memory, refresh token in `localStorage`

Chosen by the user over an httpOnly cookie. The deciding fact is that the frontend
(`autoskaner-ai.pages.dev`) and the API (`autoskanerai.onrender.com`) are **different sites**, so a
session cookie is a third-party cookie — `SameSite=None; Secure` plus `allowCredentials(true)`, and
still blocked by default in Safari and by Chrome's phase-out. The bearer header sidesteps all of it
and needs no CORS change beyond what already exists.

The cost is named rather than waved away: `localStorage` is readable by any injected script, so an
XSS steals a 14-day refresh token. Mitigations actually in place — the access token lives only in a
signal (a page reload drops it), the app renders no user-supplied HTML, and the access TTL is 15
minutes. Not in place: revocation. See "Left undone".

### No JWT library

`spring-boot-starter-security-oauth2-resource-server` already ships Nimbus JOSE through
`spring-security-oauth2-jose`, which is both a `JwtEncoder` and a `JwtDecoder`. Adding jjwt would
bind token handling to Jackson 2 — present here only as a Flyway transitive, while every HTTP
converter in the app is Jackson 3 — to buy a capability already on the classpath.

### A refresh token must not open the API, and only a claim stops it

Both tokens are signed with the same key, so a refresh token presented as `Authorization: Bearer …`
would authenticate every request unless something rejects it. A `typ` claim (`access` / `refresh`)
plus an `OAuth2TokenValidator` on each decoder is that something: the decoder the resource server
uses accepts `typ=access` only, and `TokenService`'s private refresh decoder accepts `typ=refresh`
only. Neither token is usable where the other belongs.

This is the security assertion of the whole change, so it is the one the deliberate-break check
targets.

### `/api/auth/**` is not a permitted prefix

`register`, `login` and `refresh` are listed individually and `GET /api/auth/me` is authenticated,
because a `permitAll` on the prefix is how an endpoint that reads the principal ends up public. The
matcher list is three literal paths, then `/api/**` authenticated, then everything else permitted —
the last arm exists so Render's probe on `/` keeps its old answer instead of turning into a 401.

### `CorsConfig` is gone, and its rules moved into the filter chain

The roadmap flagged this as F-03's first risk. A `WebMvcConfigurer`'s CORS mapping is applied by the
MVC handler, which runs *after* the security filter chain — so a preflight `OPTIONS` gets rejected
before the mapping is ever consulted, and the failure shows up in a browser as an opaque CORS error
rather than a 401. The origins, methods and headers now live on a `CorsConfigurationSource` bean
that `http.cors(...)` reads. `PATCH` was added to the method list while moving it: it was missing,
and S-03's rename/note edit needs it.

### The signing secret has no default, except under `mock`

`auth.jwt.secret` is `${AUTH_JWT_SECRET}` with no fallback, so an unset value fails context startup
— the same rule `OPENROUTER_API_KEY` and the `postgres` datasource follow, and for a sharper reason
here: a committed default in a public repository is a key anyone can use to mint a token for any
account. `application-mock.properties` supplies a fixed development key, because `mock` is the
offline no-credentials profile that the git hooks and the E2E specs run, and production never
activates it.

### Passwords: a delegating encoder, and 72 bytes is a real limit

`PasswordEncoderFactories.createDelegatingPasswordEncoder()` stores `{bcrypt}$2a$10$…`, so the
algorithm is recorded in the hash and can be migrated later without a schema change. The `users`
table holds no rows yet, so there is no legacy unprefixed hash to support.

The maximum length is validated at 72 characters rather than left open: BCrypt truncates its input
at 72 bytes, so without the check two different long passwords would both open the same account.

### Login does not say which half was wrong

Unknown email and wrong password return the same 401 with the same body, and an unknown email still
runs one BCrypt comparison against a throwaway hash so the two paths cost the same time.
Registration necessarily leaks existence through its 409 — that is accepted, since the alternative
(a fake success) makes the form unusable.

## Findings

### `@AutoConfigureMockMvc` is not in `spring-boot-starter-test` any more

Boot 4 moved it out of `spring-boot-test-autoconfigure` into its own `spring-boot-webmvc-test`
module **and** renamed the package to `org.springframework.boot.webmvc.test.autoconfigure`. The
symptom is a compile error naming the old package, which reads like a typo rather than a missing
dependency — the same split that made `spring-boot-flyway` a separate dependency in F-02. Fixed by
adding `spring-boot-starter-webmvc-test` at test scope; it was already cached, so `./mvnw -o test`
still works.

The dependency is not optional here. Every other controller test in this repository uses
`MockMvcBuilders.standaloneSetup`, which builds a dispatcher with **no filter chain**, so an
unauthenticated request comes back 200 no matter what `SecurityConfig` says. A test that cannot fail
when the lock is removed is not a test of the lock, which is why `ApiRequiresAuthenticationTest` is
the one class here that boots the real chain.

### `NimbusJwtEncoder` cannot mint an already-expired token

`Jwt`'s constructor asserts `expiresAt` is after `issuedAt`, so configuring a negative TTL throws
`IllegalArgumentException` at mint time instead of producing a dead token. The expiry tests therefore
hand-build a claims set whose window closed in the past. They close it **five minutes** ago, not
thirty seconds: `JwtTimestampValidator` allows 60 seconds of clock skew by default, so a token one
minute stale still validates and a −30 s test would have passed while the expiry check was broken.

### `Jwt.getIssuer()` throws on a bare-name issuer

The accessor insists the `iss` claim parse as a URL, and this service issues `autoskaner-ai`. Read it
as `getClaimAsString("iss")` — which is what `JwtConfig`'s own issuer validator already does, and the
reason it does not use `JwtValidators.createDefaultWithIssuer`.

### `TokenService` takes an id and an email, not the entity

`UserAccount.create(...)` leaves `id` null until the insert, so `String.valueOf(account.getId())`
would mint a token whose subject is the literal string `"null"` — and `AuthenticatedUser.requireId`
would then refuse it, at a call site with no obvious connection to the cause. The service now takes
`(long userId, String email)`, which also lets `TokenServiceTest` run with no database at all.
`AuthService.tokensFor` is the single seam, and it throws on a null id rather than letting one
through.

### The deliberate-break check

`TokenTypeValidator.validate` was made to return `success()` unconditionally — the shape a reviewer
would produce by deciding the `typ` claim was a formality. Worktree only, restored with
`git checkout --`, never staged. Five tests went red, and the one that names the consequence is
`ApiRequiresAuthenticationTest.aRefreshTokenDoesNotOpenTheApi`: `Status expected:<401> but was:<200>`
for a 14-day refresh token on `GET /api/auth/me`. The other four are
`TokenServiceTest.aRefreshTokenIsRefusedAsAnAccessToken`,
`anAccessTokenIsRefusedWhereARefreshTokenIsExpected`, `aTokenWithNoTypeClaimIsRefusedByBothDecoders`
and `AuthServiceTest.refreshingWithAnAccessTokenIsRefused`.

## Measurements

- Backend suite: **285 → 340** tests, 0 failures, 0 skipped, `BUILD SUCCESS` offline. The 55 new
  tests are `TokenServiceTest` (12), `ApiRequiresAuthenticationTest` (13), `AuthServiceTest` (14),
  `AuthControllerTest` (12), `AuthenticatedUserTest` (4).
- The existing 285 passed unchanged with Spring Security on the classpath, before any test was
  written. They survive for two specific reasons: the controller tests use `standaloneSetup`, which
  has no filter chain, and every `@SpringBootTest` uses `@ActiveProfiles("mock")`, which is where the
  signing secret now resolves from.
- Spring Security **7.0.5** and Nimbus JOSE **10.4**, both managed by the Boot 4.0.6 BOM. Verified
  present in the local repository (`D:/.m2`) before writing any code, and `dependency:list` run
  offline afterwards, so the git hooks' `./mvnw -o test` still resolves.

## Left undone

- **No revocation.** Logout is client-side only: the frontend drops both tokens, and the refresh
  token it discards stays valid for its full 14 days. Refresh rotates the pair but does not
  invalidate the old one, so rotation buys a sliding window rather than revocation. A stolen refresh
  token cannot be cancelled. Fixing it needs server-side state — a `refresh_tokens` table with a
  `revoked_at`, or a token version column on `users` — which is a schema change and so a change of
  its own.
- **No password reset, no email verification.** Registration is immediate and the address is never
  confirmed, so an account can be created against somebody else's email and a forgotten password is
  unrecoverable. Both need an outbound mail path, which this project has none of.
- **No rate limit on `/api/auth/login`.** The single-message 401 and the constant-time compare stop
  enumeration through the *response*; neither stops a caller trying a thousand passwords. Render's
  free tier offers nothing here, so it wants a bucket in the app.
- **`AUTH_JWT_SECRET` is not yet set on Render**, and the deploy fails at context startup without it
  — deliberately. It must be set through the per-key endpoint before this reaches production.
