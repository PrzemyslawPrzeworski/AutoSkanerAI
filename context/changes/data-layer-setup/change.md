---
change_id: data-layer-setup
title: The data layer, shipped invisible — Flyway owns the schema, Postgres is opt-in
status: implemented
created: 2026-09-11
updated: 2026-09-11
archived_at: null
---

## Notes

Roadmap F-02, the first link in `data-layer-setup` → `auth-scaffold` →
`save-view-delete-analyses`. That chain exists because the 10xBuilder rubric
(`.claude/prompts/mvp-check.md`) is met on 3 of 5 criteria: CRUD on persisted data and
authentication are the two missing, and neither is reachable without a database.

Scope is the layer and nothing above it: two tables, two entities, two repositories, one
migration, and the tests that pin who owns the schema. **No endpoint, no login, no UI.** That
is deliberate — the deployed app behaves exactly as it did before this change, which is what
made it safe to ship mid-week against a 14 September hand-in.

Implemented without a `plan.md`: it is a single-phase change with a settled shape, and the
decisions that needed recording are below rather than in a plan nobody would have re-read.

## Decisions

### Postgres is a profile, not a `DATABASE_URL` default

The obvious wiring — `spring.datasource.url=${DATABASE_URL:jdbc:h2:mem:…}` — **would have
broken production the moment this change shipped.** Render still carries `DATABASE_URL`,
`DATABASE_USERNAME` and `DATABASE_PASSWORD` pointing at
`db.bahoxzvhamktpepmkaft.supabase.co`, and that host no longer resolves: `ENOTFOUND`, so the
project was deleted rather than paused. Flyway needs a connection before the context finishes
starting, so the deploy would have failed on a variable nobody set this week.

So the default datasource is a **hardcoded** in-memory H2 and nothing on the boot path reads
`DATABASE_URL` at all; real Postgres arrives by activating a profile
(`SPRING_PROFILES_ACTIVE=openrouter,postgres`), and `application-postgres.properties` gives all
three variables no defaults so an unset value fails startup rather than degrading. The rule
this is an instance of: **a variable that looks configured and is dead must not sit on the boot
path.** The inverse failure is worse than a failed deploy — a silent fallback to in-memory
would lose a user's saved analyses without one failing request.

No custom placeholder was invented for local overrides; Spring's own `SPRING_DATASOURCE_URL`
relaxed binding already covers it.

### `ddl-auto=validate`, and both tables in one migration

Flyway owns the schema and Hibernate may only check it. `update` would patch the live schema
and leave the migration — the thing a new environment replays — wrong, which is a defect that
only appears on the environment nobody tests.

`users` and `analyses` land in the same `V1__init.sql` even though the login ships in F-03,
because the roadmap's own F-02 risk note says to plan `user_id` from the start. The
alternative is a compensating migration adding a `NOT NULL` foreign key to a table already
holding rows nobody owns.

### The update operation needed an honest subject

The rubric requires Update. An analysis is a record of what the model said at a point in time,
and making it editable would stop the saved verdict being evidence — so `title` and `note` are
the only user-editable columns, and `SavedAnalysis.edit` is the only way to touch them. The
summary columns (make, model, year, price, mileage, verdict, score) are denormalised beside the
payload so the list view renders without deserialising every row.

### Ownership is structural, not a check

`SavedAnalysisRepository` exposes `findByIdAndUserId` and no single-row `findById` path, so a
request for somebody else's id is indistinguishable from a request for an id that does not
exist, and the check cannot be forgotten at one of three call sites. The FK plus
`ON DELETE CASCADE` backs the same rule from below. This is now risk #8 in
`context/foundation/test-plan.md` §2, which also records what moves up a layer when S-03 adds
endpoints: **`userId` must come from the authenticated principal.**

## Findings

### Boot 4 splits autoconfiguration per technology, and the missing module is silent

`org.flywaydb:flyway-core` on its own is an inert library: no autoconfiguration, no failure
message, nothing starts it. `spring-boot-starter-data-jpa` does not pull the module in. The
Boot 4 BOM names it `org.springframework.boot:spring-boot-flyway`, alongside
`spring-boot-hibernate`, `spring-boot-jpa`, `spring-boot-jdbc`, `spring-boot-persistence`,
`spring-boot-sql` and `spring-boot-liquibase`.

Cost: one full test run, 20 errors, all of them `Schema validation: missing table [analyses]` —
**a message about the entities, for a migration that never ran.** `dependency:list` showed
`flyway-core:11.14.1` and `flyway-database-postgresql:11.14.1` present and no
`spring-boot-flyway`. The transferable rule is in `backend/CLAUDE.md`: for any Boot 4
integration, check for a `spring-boot-<technology>` module before concluding the library is
misconfigured.

Related and worth not re-checking: **H2 support lives inside `flyway-core`** (verified — the
11.14.1 jar contains `org/flywaydb/core/internal/database/h2/H2Database.class`), so there is no
`flyway-database-h2` artifact to add. PostgreSQL *was* split out and does need its own module.

### H2 folds unquoted identifiers to UPPER CASE even under `MODE=PostgreSQL`

Compatibility mode does not change identifier casing — that is a separate `DATABASE_TO_LOWER`
setting. Flyway creates its history table with quoted lower-case names, so
`SELECT success FROM flyway_schema_history` fails with `Table "FLYWAY_SCHEMA_HISTORY" not found
(candidates are: "flyway_schema_history")`. Quoted lower-case is the one spelling both engines
read identically, since PostgreSQL folds unquoted to lower case; the unquoted form is only
accidentally portable. `SchemaIsOwnedByFlywayTest` quotes every identifier and says why.

The same trap is why `bothTablesTheMigrationDeclaresAreQueryable` runs `SELECT COUNT(*)` rather
than reading `information_schema`, whose casing differs between the two engines.

### `@DataJpaTest` would have tested a dialect nothing runs

Its `@AutoConfigureTestDatabase` replaces the configured datasource URL with a generated one,
dropping `MODE=PostgreSQL` — so the repository tests would have exercised the migration against
a dialect neither dev nor production uses. All three context tests use `@SpringBootTest` with
`@ActiveProfiles("mock")`, which is also what the rest of this repository's context tests do.

### One environment fact: Python is not installed on this machine

The deliberate-break check was first written as a Python heredoc and died on the Windows Store
stub (`nie znaleziono Python`). Node is available — the git hooks depend on it — and did the
job. Worth recording because the failure output is easy to misread: the greps in the same
command reported the cascade still present and matched `return email;` at the `getEmail()`
getter, either of which could look like a break had been applied when none had.

## Measurements

Backend suite **255 → 285 tests**, 28 → 33 classes, ~15.5 s → ~22.7 s (`./mvnw -o test`,
BUILD SUCCESS, 0 failures, 0 errors, 0 skipped). The added time is three `@SpringBootTest`
contexts booting Flyway, not the test count.

New tests, 30 across five files:

| file | tests | what it pins |
|---|---|---|
| `UserAccountTest` | 6 | normalisation is what makes two spellings one account; a null email stays null; the password hash is stored verbatim |
| `UserAccountRepositoryTest` | 4 | the unique index rejects a second account, including a differently-cased one |
| `SavedAnalysisTest` | 9 | created/updated equal at creation; `edit` moves `updatedAt` only and never the stored analysis; a blank title is rejected and leaves the old one intact |
| `SavedAnalysisRepositoryTest` | 8 | every summary column round-trips; a 40 000-char payload survives; newest-first listing; the ownership guard; `ON DELETE CASCADE` |
| `SchemaIsOwnedByFlywayTest` | 3 | V1 applied; both tables queryable; `ddl-auto` is `validate` |

`SavedAnalysisRepositoryTest` calls `entityManager.clear()` before its reads, so the
persistence context cannot answer from cache and the assertions describe the database.

### The deliberate-break check

Three breaks, worktree-only, applied with node after the files were staged and restored with
`git checkout -- <file>`:

| break | went red |
|---|---|
| `normaliseEmail` returns its argument verbatim | `UserAccountTest.loweringAndTrimmingIsWhatMakesTwoSpellingsOneAccount` (3 of 4 params) + `UserAccountRepositoryTest.aDifferentlyCasedEmailCollidesWithTheStoredOne` |
| `ON DELETE CASCADE` dropped from `V1__init.sql` | `SavedAnalysisRepositoryTest.deletingAnAccountTakesItsSavedAnalysesWithIt` — `Referential integrity constraint violation: FK_ANALYSES_USER` |
| `findByIdAndUserId` rewritten as `@Query("select a from SavedAnalysis a where a.id = :id")` | `SavedAnalysisRepositoryTest.doesNotHandAnAnalysisToAUserWhoDoesNotOwnIt` |

Two details worth keeping. The `@CsvSource` row that was already lower-case and untrimmed
stayed green under break 1 — correct, there is nothing to normalise. And
`SchemaIsOwnedByFlywayTest` stayed green through all three, which is the scoping its own
Javadoc argues for (it deliberately names no column, so it is not a second copy of the
migration) rather than a hole.

## Left undone

- **The `postgres` profile has never been run against a real database**, because there isn't
  one — the Supabase project is gone. Provisioning (a new Supabase project, Render Postgres, or
  Neon) is a deploy-time step and is undecided.
- **Every test runs H2, so nothing here is evidence about PostgreSQL.** A dialect-specific type
  mismatch surfaces first as a failed Render deploy, which keeps serving the previous version.
  Named in `test-plan.md` §2 under risk #9 rather than left implied.
- Render's three dead `DATABASE_*` variables are still set. Harmless now that nothing reads
  them, and deliberately left rather than cleaned up in the same change.
