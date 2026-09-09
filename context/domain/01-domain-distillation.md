---
title: Domain distillation — AutoSkanerAI
created: 2026-09-09
type: domain-distillation
git_commit: 664857b
---

# Domain distillation — AutoSkanerAI

The product here is a **map of the domain, not code**. Nothing in `src/` is touched by this
document. Every term, invariant and divergence below carries a `file:line` that was opened and
read; where a term has no home in the code, it says **BRAK w kodzie** rather than guessing at one.

Language note: the domain is Polish and the code is bilingual — Java identifiers are English,
every user-facing string and every LLM prompt is Polish. Terms are given in the form the *domain*
uses, with the code's name beside it, because the gap between the two is itself a finding.

---

## KROK 0 — Project context

**Stack.** Spring Boot 4.0.6 on Java 21 (Maven) + Angular 21.2 (TypeScript, SCSS). No database,
no auth, no CI. Backend port 10000. 59 Java files in `src/main`, spread over five packages:
`analysis` (32), `analysis/llm` (8), `cepik` (8), `market` (7), `common` (3).

**Where the business logic lives.** Almost all of it is in two classes:

- `backend/.../analysis/llm/AnalysisResponseParser.java` — 251 lines; validates the model's JSON
  and *repairs* it, which is where the app's hardest rule is enforced.
- `backend/.../analysis/CepikRiskAdjuster.java` — 255 lines; folds registry findings into the
  score and the verdict after the LLM has already judged the listing.

Orchestration is `AnalysisController.buildResponse` (`AnalysisController.java:73-118`) — override,
enrich twice, adjust, augment questions, assemble. There is no service layer between the
controller and these two classes.

**Three eras of documentation, and they disagree.** The distillation has to name which one is
authoritative, because two of them are still checked in:

| Artifact | Date | Standing |
|---|---|---|
| `idea-notes.md` (181 lines, Polish) | pre-chain | the original narrative; **the only place the domain speaks in its own words** |
| `context/foundation/shape-notes.md` | 2026-05-24 | 16 FRs, `quality_check_status: accepted` — **two of its non-goals were later reversed** |
| `context/foundation/prd.md` | later | 18 FRs; **authoritative** |
| `context/foundation/tech-stack.md` | earliest | contradicted by reality on four fields (see KROK 4, row 14) |

**What a 3-week solo MVP makes degenerate.** Two steps this kind of exercise usually spends effort
on are trivial here and are recorded as such rather than skipped: there is exactly **one bounded
context** (one deployable, one team of one, one language of one), so no context map is warranted;
and there is **no legacy schema** to distill against, because there is no persistence at all
(`@Entity` and `JpaRepository` both return zero matches — `context/changes/analysis-flow-analysis/verification.md`
confirmation #10). The distillation therefore runs against *documents and behaviour*, not against
tables.

---

## KROK 1 — Ubiquitous Language

Twenty terms. Each has a definition, a source quote with `file:line`, and where it lives in
code — or an explicit **BRAK w kodzie**.

| # | Term (domain) | Definition | Source quote (`file:line`) | Where it lives in code |
|---|---|---|---|---|
| 1 | **Oferta / ogłoszenie** | One car for sale, as advertised: the thing a user adds, analyses, and later compares against others. The central noun of the whole product. | „Dodanie oferty samochodu" — `idea-notes.md:12`; „czy konkretna oferta samochodu ma sens" — `idea-notes.md:7` | **BRAK w kodzie.** Nearest thing is `AnalysisRequest` (`AnalysisRequest.java`) — a request, not a thing — and a bare `String listingText`. No `Listing`, no `Offer`, no id. |
| 2 | **Analiza** | The app's whole reading of one listing: extracted data, equipment, risk flags, questions, scores, verdict, provenance metadata. | „Najważniejsze jest działające narzędzie do analizy ofert." — `idea-notes.md:154` | `AnalysisResult.java` — a 7-component record, assembled in `AnalysisController.java:112-115`. No behaviour, no factory. |
| 3 | **Dane wyodrębnione** (ExtractedData) | The facts the app read out of the advert: make, model, year, price, mileage, fuel, gearbox, origin, seller type, service-history mention, accident claim, VIN, plate, first-registration date. | „Aplikacja wyciąga z ogłoszenia najważniejsze informacje:" — `idea-notes.md:21`, followed by a list of **12** fields at `:22-34` | `ExtractedData.java` — 16 components, all bare values. Eleven of the domain's twelve map across; the twelfth is „wersja wyposażenia" (`idea-notes.md:29`) — see term 11. |
| 4 | **Deklaracja wypadkowa** (accidentClaim) | What the *listing* says about accident history. A claim, not a fact — the listing's assertion, to be compared against the registry. | „unikać deklaracji bezwypadkowości" — `idea-notes.md:6`; „may report a confirmed accident declaration (from the listing or a vehicle history report) as a fact" — `prd.md:38` | `ExtractedData.accidentClaim` (a `String`). Deliberately not user-editable: `UserOverrides.java:58-61`. |
| 5 | **Brak deklaracji ⇒ nieznane** (`NO_ACCIDENT_DECLARATION`) | Silence about accidents is an *unknown*, never a clean record. The app's single hardest rule. | „must not draw conclusions from the absence of accident information — missing data means unknown, not clean… must never imply a car is accident-free simply because no accident was mentioned" — `prd.md:38`; restated `prd.md:122` | A **raw string at four production sites**: `AnalysisResponseParser.java:17` (the one named constant, `MISSING_DECLARATION_FLAG`), `AnalysisPrompt.java:16` and `:103`, `MockAiAnalysisService.java:149`. Enforced in `AnalysisResponseParser.java:169-183`. |
| 6 | **Flaga ryzyka** (RiskFlag) | One named, severity-graded concern about the listing. | „Analiza ryzyk oferty" — `idea-notes.md:56` | `RiskFlag.java:3` — `record RiskFlag(String code, RiskSeverity severity, String description)`. **`code` is an untyped `String`**; `RiskSeverity` is an enum. |
| 7 | **Oceny kategorii** (CategoryScores) | Four sub-scores — completeness, equipment, risk, value — plus an overall. | the scoring table „Kompletność ogłoszenia 7/10 / Wyposażenie 8/10 / Ryzyko 5/10 / Opłacalność 6/10" — `idea-notes.md:77-80` | `CategoryScores.java:3` — five `int`s. **Scale silently changed from x/10 to 0..100**; the 0..100 range is enforced at `AnalysisResponseParser.java:113-117`. |
| 8 | **Ocena ogólna** (overall) | The single headline number, defined by the domain as the mean of the four categories. | „Ogólna ocena 6.5/10" — `idea-notes.md:81` (the arithmetic mean of the four rows above it) | `CategoryScores.overall`. The mean rule is written down **only** in `CepikRiskAdjuster.java:208` javadoc and computed **only** at `:214`; `AnalysisResponseParser.mapScores` (`:197-199`) passes the model's `overall` through untouched. |
| 9 | **Werdykt** (Verdict) | The recommendation: worth checking / needs more info / high risk, with a Polish label. | „oferta wygląda dobrze, ale brakuje kluczowych informacji" — `idea-notes.md:67` | `Verdict.java:3` + `VerdictCode` (`WORTH_CHECKING`, `NEEDS_MORE_INFO`, `HIGH_RISK_SKIP`). The three labels are **triplicated**: `CepikRiskAdjuster.java:248-252`, `MockAiAnalysisService.java:58-62`, and inline in `AnalysisPrompt.java:57`. |
| 10 | **Wyposażenie** (Equipment) | Per-item presence: confirmed, missing, or unclear from the advert. | „Aplikacja sprawdza, jakie elementy wyposażenia są obecne, nieobecne albo niejasne." — `idea-notes.md:38` | `EquipmentItem.java:3` + `EquipmentStatus{CONFIRMED,MISSING,UNCLEAR}`. A faithful mapping — one of the few. |
| 11 | **Wersja wyposażenia** (trim level) | The named factory trim, listed by the domain as an extracted field *and* as a seller question. | „wersja wyposażenia," — `idea-notes.md:29`; „Czy podana wersja wyposażenia zgadza się z dokumentami auta?" — `idea-notes.md:91` | **BRAK w kodzie.** `ExtractedData` has no trim component; `EquipmentItem` is per-feature, not per-trim. |
| 12 | **Pytania do sprzedawcy** | The list to ask before viewing the car. | „Na podstawie konkretnej oferty aplikacja generuje listę pytań, które warto zadać przed oględzinami." — `idea-notes.md:84` | `AnalysisResult.sellerQuestions`, appended to by `AnalysisController.java:97-110` (three registry-input requests). |
| 13 | **Raport historii pojazdu / CEPiK** | The state registry's own record: identity, timeline, damages, mileage stamps, insurance and theft markers. | FR-017; „User can paste or enter data from a CEPiK / historiapojazdu.gov.pl report" — `shape-notes.md:79` | `CepikResult.java:19-49` (21 components) + `CepikStatus{FOUND,NOT_FOUND,LOOKUP_FAILED,MISSING_INPUTS}`. |
| 14 | **Szkoda istotna** | A structural damage registered with an insurer — the single most consequential registry event. | „a significant damage is `eventType: \"szkoda-istotna\"`" — `backend/CLAUDE.md` § Enrichment services | **Three production spellings, one of them named**: `HistoriaPojazduParser.java:41` (the `DAMAGE_EVENT_TYPE` constant), `HistoriaPojazduParser.java:61` (the same literal inside its own allow-list), `cepik-result.component.html:193`. Measured in `analysis-flow-analysis/verification.md` § refined #3. |
| 15 | **Stan szkód: nieznany / brak zgłoszonych / zgłoszone** | Three states, not two. "No damage reported" and "we do not know" must never render alike. | „`damageRecords` may be an empty list only when the registry's timeline was read successfully and contained no damage event… Any other case… must be `null`" — `CepikResult.java:9-14` | Named as a **type only in TypeScript**: `export type DamageState = 'unknown' \| 'none-reported' \| 'reported'` — `cepik-result.component.ts:16`, decided at `:50`. In Java the three states are `null` vs `[]` vs non-empty, guarded by javadoc and the `withoutData` factory (`CepikResult.java:64`). |
| 16 | **Kontekst cenowy rynku** | Where this asking price sits against comparable listings, with an explicit statement of how trustworthy the sample is. | FR-018 | `MarketPriceContext` + `MarketPriceStatus{OK,FETCH_FAILED,INSUFFICIENT_DATA,MISSING_INPUTS}` + `MarketPriceSampleQuality{SUFFICIENT,THIN,DISPERSED}`. `SampleQuality` carries a long javadoc explaining it is a server-side judgement — one of the few places a domain concept was given a name on purpose. |
| 17 | **Pochodzenie danych** (extracted / inferred / user-supplied) | Every data point must say where it came from. | „every data point in the output is labelled as either extracted from the listing or inferred by the app" — `prd.md:99`; „The app clearly separates confirmed facts from inferences and shows the user where each piece of information came from." — `prd.md:34` | **BRAK w kodzie.** No provenance component anywhere. `UserOverrides.apply` (`UserOverrides.java`) takes an `ExtractedData` and returns an `ExtractedData`, so the one place in the system that *knows* a value is user-supplied throws that knowledge away on return. |
| 18 | **Porównanie kilku ofert** | Put two or more analysed listings side by side. | „Porównanie kilku ofert" — `idea-notes.md:94`; „porównać kilka ofert," — `idea-notes.md:167` | **BRAK w kodzie.** There is no aggregate to compare and no id to compare by (see term 1). |
| 19 | **Użytkownik / konto** | The owner of analyses; analyses are tied to an account. | PRD § Access Control (email/password or OAuth, flat user model) | **BRAK w kodzie.** No `User` type, no Spring Security, no persistence — `verification.md` confirmation #10 (`@Entity` and `JpaRepository` both empty). |
| 20 | **Preferencje kupującego** | What this particular buyer cares about, used to shape the judgement. | „jakich wersji wyposażenia szukać," — `idea-notes.md:119`; „personal buyer preferences" — `shape-notes.md:95` | **BRAK w kodzie.** Nothing in `AnalysisRequest` carries a preference. |

**Count: 20 terms, 6 with no representation in code at all** (1, 11, 17, 18, 19, 20), and 3 more
represented only as untyped strings or as a null/empty convention (5, 6, 15).

---

## KROK 2 — Subdomains

Classification is tied to the product goal stated at `prd.md:34` — *"The app clearly separates
confirmed facts from inferences and shows the user where each piece of information came from"* —
and to the success statement at `idea-notes.md:179`: cutting first-pass assessment from tens of
minutes to a few, and helping the user avoid bad offers.

### Core

**Ocena wiarygodności oferty** — extraction → risk flags → four scores → verdict → seller
questions. This is what the user came for and nothing off the shelf does it.

Inside it, one sub-domain is more core than the rest and deserves naming separately:

**Uczciwość faktu (factual honesty).** The rule that silence is not evidence. It is the only
requirement the PRD states **three times** (`prd.md:37`, `:38`, `:99`) and repeats in the
guardrails (`:122`), and it is the rule the root `CLAUDE.md` puts above everything else. It is
also the rule with the worst enforcement story in the repo (KROK 3, I4). A distillation that files
this under "validation" has mis-classified the product.

Code: `analysis` + `analysis/llm` — 40 of 59 files.

### Supporting

- **Wzbogacanie rejestrowe (registry enrichment)** — `cepik`, 8 files. Necessary to make the core
  judgement trustworthy, but the domain logic is thin: fetch, parse, hand to the adjuster. The
  value is in the *comparison* (`CepikRiskAdjuster`), which is Core.
- **Kontekst cenowy (market price)** — `market`, 7 files. Same shape: fetching is generic,
  trimming a contaminated sample (`MarketPriceStatistics`) is genuine domain judgement.
- **Wejście danych (data entry and overrides)** — `ManualListing`, `ManualListingComposer`,
  `UserOverrides`, `VinValidator`. Supporting because it exists to make the Core reachable when
  the advert withholds a field, not because a buyer wants to type.

**A finding that belongs here rather than in KROK 4:** two of these three Supporting subdomains
were **explicit non-goals** four months ago. `shape-notes.md:84` — *"No automatic market scraping
(Otomoto, OLX, Gratka, mobile.de, etc.) — scraping is fragile, legally grey, and unnecessary"* —
and `shape-notes.md:86` — *"No full CEPiK / historiapojazdu.gov.pl API integration… no live
state-registry connection in MVP"*. Both are now must-have FRs (FR-017, FR-018) and together hold
15 of 59 backend files. The reversal is defensible; that it was never written down anywhere but a
still-`accepted` artifact is not.

### Generic

- **URL fetching** — `ListingFetchService` + Jina Reader + SSRF check.
- **LLM transport** — `BedrockClaudeAnalysisService`, `OpenRouterAnalysisService`, the retry and
  fallback ladder. Sophisticated, and entirely about someone else's uptime.
- **Registry session handling** — `HistoriaPojazduSession`, cookie and API-version discovery.
- **Error shape and validation** — `common` (3 files), Bean Validation annotations.

---

## KROK 3 — Aggregate candidates and their invariants

Status vocabulary: **enforced** (the code makes the violation unrepresentable or throws),
**declared** (a comment, javadoc or prompt states the rule and one path honours it), **ignored**
(the rule exists in a document only).

### Candidate A — `Analysis` (today: `AnalysisResult`, an anemic 7-component record)

| Id | Invariant | Source quote | Status |
|---|---|---|---|
| **I1** | `overall` is the mean of the four category scores | the scoring table `idea-notes.md:77-81`; *"recomputes overall the same way the scorers do — as the mean of the four"* — `CepikRiskAdjuster.java:208` javadoc | **declared, and enforced on one path only.** Computed at `CepikRiskAdjuster.java:214`; the plain LLM path passes the model's own `overall` straight through — `AnalysisResponseParser.java:197-199`. Under the `mock` profile it *is* computed, at `MockAiAnalysisService.java:53`. So the rule holds on two of three paths and the third is production. |
| **I2** | `verdict.label` is the label that belongs to `verdict.code` | *"Kept in step with MockAiAnalysisService and AnalysisPrompt's examples"* — `CepikRiskAdjuster.java:246` | **declared.** Regenerated from the code at `CepikRiskAdjuster.java:220-229` / `:247-253`, but `AnalysisResponseParser.mapVerdict` (`:201-209`) takes `label` **verbatim from the model**. A model that returns `HIGH_RISK_SKIP` labelled „warto sprawdzić" is accepted. |
| **I3** | every score is within 0..100 | scale implied by the whole scoring section, `idea-notes.md:77-81` | **enforced.** `AnalysisResponseParser.java:113-117` throws `LlmResponseSchemaException`. Not re-checked after adjustment, but the caps (`5,20,25,35,70`) and the mean cannot escape the range. |
| **I4** | `accidentClaim == null` ⇒ a `NO_ACCIDENT_DECLARATION` flag is present | *"must never imply a car is accident-free simply because no accident was mentioned"* — `prd.md:38` | **enforced on the real path, violated on the mock path.** `AnalysisResponseParser.java:169-183` appends the flag idempotently at `MEDIUM`, matching `AnalysisPrompt.java:16`. `MockAiAnalysisService.java:147` guards on `!contains("wypadek") && !contains("bezwypadkowy") && !contains("historia")`, so **„Pełna historia serwisowa"** — a listing saying nothing whatever about accidents — gets no flag; and when it does flag, it flags `HIGH` (`:150`) with different wording (`:151`). This is the one profile every quality gate and both E2E specs run. |
| **I5** | a risk flag is added, never removed | the append-only shape of `withAccidentDeclarationFlag` and of `CepikRiskAdjuster.java:108` | **enforced structurally** — both sites build a new list by appending; no code path deletes a flag. |
| **I6** | the response always carries a `marketPriceContext` — never absent, never an uncaught throw | *"This also honours the invariant S-05 stated and never enforced"* — `AnalysisController.java:132-134` | **enforced**, by `degradeOnThrow` (`AnalysisController.java:143-155`). Named in a javadoc as an invariant that used to be ignored — the one place in the repo where the promotion is recorded. |

### Candidate B — `CepikResult` (already the best-guarded shape here)

| Id | Invariant | Source quote | Status |
|---|---|---|---|
| **I7** | `damageRecords` is empty **only** when the timeline was read and held no damage; otherwise `null` | *"Null and empty are not interchangeable here… Collapsing the two is what made this app report a clean history for a vehicle carrying a szkoda istotna."* — `CepikResult.java:9-14` | **declared, guarded by discipline.** The `withoutData` factory (`:64-70`) makes it unbreakable *if used* — and `:16-17` says so outright: *"Use `withoutData` for every non-FOUND status so that rule cannot be broken by forgetting an argument."* But the 21-argument canonical constructor is public, so `new CepikResult(FOUND, …, List.of(), …)` still compiles. The type does not carry the rule; a comment and a habit do. |
| **I8** | `fetchedAt` is always stamped | `CepikResult.withoutData:66` — `Instant.now()` on every degraded path | **enforced in `cepik`, ignored in `market`.** `MarketPriceFetchService.missing()` passes `null` against a non-nullable `fetchedAt: string` in `analysis.models.ts:76`. Two packages, one question, opposite answers — `verification.md` § refined #5. |
| **I9** | only a `FOUND` registry result may move a score or a verdict | *"Only `FOUND` results adjust anything… a `FOUND` result whose `damageRecords` is null must leave the score untouched in both directions"* — `backend/CLAUDE.md` § Folding registry findings | **enforced** at `CepikRiskAdjuster.java:73`, and only ceilings are applied (`capRisk:208-218`) so no registry finding can *raise* a score. |

### Candidate C — `Listing` / `Oferta` — the aggregate that does not exist

| Id | Invariant | Source quote | Status |
|---|---|---|---|
| **I10** | an analysis belongs to exactly one listing, and a listing to exactly one user | „Dodanie oferty samochodu" — `idea-notes.md:12`; PRD § Access Control, FR-010 (persistence) | **ignored.** No entity, no id, no owner, no persistence. The consequence is not abstract: „Porównanie kilku ofert" (`idea-notes.md:94`) cannot be built until there is something to compare, and FR-010 has nothing to persist. |

### Candidate D — a provenance-carrying field

| Id | Invariant | Source quote | Status |
|---|---|---|---|
| **I11** | every data point in the output states whether it was extracted, inferred, or supplied by the user | *"every data point in the output is labelled as either extracted from the listing or inferred by the app"* — `prd.md:99`; *"shows the user where each piece of information came from"* — `prd.md:34` | **ignored, and actively erased.** `ExtractedData` has 16 bare components. `UserOverrides.apply` is the one function in the system that knows a value came from the user and it returns a plain `ExtractedData`, so the distinction dies at the `return`. |

---

## KROK 4 — MODEL vs KOD: where the document and the code disagree

The most valuable part of this distillation. Each row: what a document says, what the code
actually does, and the evidence.

| # | The document says | The code does | Evidence |
|---|---|---|---|
| 1 | Every output data point is labelled extracted-vs-inferred | Nothing is labelled. There is no provenance type, field, or wrapper anywhere in either stack. | `prd.md:99` vs `ExtractedData.java` (16 bare components); `UserOverrides.apply` returns `ExtractedData` |
| 2 | Absence of accident data is never rendered as absence of accidents | Under `mock`, a listing containing „historia" and no accident keyword gets **no** `NO_ACCIDENT_DECLARATION` flag; when the mock does flag, it uses `HIGH` where the prompt mandates `MEDIUM`, with different wording | `prd.md:38` and `AnalysisPrompt.java:16` vs `MockAiAnalysisService.java:147-152` |
| 3 | Scores are out of 10, overall 6.5/10 | Scores are `int` 0..100, and the range is *enforced* at 0..100 | `idea-notes.md:77-81` vs `CategoryScores.java:3` + `AnalysisResponseParser.java:113-117`. The rescaling is nowhere recorded as a decision. |
| 4 | `overall` is the mean of the four categories | Only the CEPiK-adjusted path computes it; the production LLM path trusts the model's number | `CepikRiskAdjuster.java:208` (javadoc), `:214` (body) vs `AnalysisResponseParser.java:197-199` |
| 5 | The verdict label matches the verdict code | `mapVerdict` copies the model's label verbatim; the three labels are triplicated across three files with no shared constant | `CepikRiskAdjuster.java:246-253` vs `AnalysisResponseParser.java:201-209`; triplication at `CepikRiskAdjuster.java:248-252`, `MockAiAnalysisService.java:58-62`, `AnalysisPrompt.java:57` |
| 6 | Damage has three states (unknown / none reported / reported) | Named as a **type** only in TypeScript; Java encodes it as `null` vs `[]`, protected by javadoc and one factory | `cepik-result.component.ts:16` and `:50` vs `CepikResult.java:9-17`, `:64-70` |
| 7 | No automatic market scraping — *"fragile, legally grey, and unnecessary"* | A whole `market` package (7 files) fetches Otomoto search results through Jina Reader | `shape-notes.md:84` vs `backend/.../market/`, PRD FR-018 |
| 8 | No live state-registry connection in the MVP; users paste report data manually | A whole `cepik` package (8 files) scrapes `moj.gov.pl` live, five HTTP calls per analysis | `shape-notes.md:86` vs `backend/.../cepik/`, PRD FR-017 |
| 9 | Completion *"within a few seconds"*, no spinner past 10 s → later relaxed to 30 s | A real analysis takes **~27 s**, and the configured request budget asserted in a test is **295 s** — roughly 10× the PRD's own number, with nothing enforcing a deadline | `shape-notes.md:105`, `prd.md:98` vs `RequestTimeoutBudgetTest.java:180` and its own description at `:185` |
| 10 | „wersja wyposażenia" is one of the extracted fields, and a question to ask the seller | `ExtractedData` has no trim component; `EquipmentItem` is per-feature | `idea-notes.md:29`, `:91` vs `ExtractedData.java` |
| 11 | A user may optionally attach a screenshot, PDF, or notes from talking to the seller | Never became an FR; no upload path, no field | `idea-notes.md:17` vs `AnalysisRequest.java` |
| 12 | Compare several offers | No aggregate, no id, nothing to compare | `idea-notes.md:94`, `:167` vs the absence of any `Listing` type |
| 13 | Analyses are tied to a user account (Access Control, FR-010) | No `User`, no auth, no persistence | PRD § Access Control vs `verification.md` confirmation #10 |
| 14 | `deployment_target: fly`, `ci_provider: github-actions`, `ci_default_flow: auto-deploy-on-merge`, `has_auth: true` | Render + Cloudflare Pages, **no CI at all**, no auth | `tech-stack.md` (all four fields) vs root `CLAUDE.md` § Deployment and § Local quality gates |
| 15 | Registry-vs-listing mileage mismatch is a risk | The comparison exists **only in the frontend**, one-directional (registry higher than advertised), tolerance `max(2000, listed × 5%)`, and **feeds no score** | `cepik-result.component.ts:151-158`; `backend/CLAUDE.md` § Folding registry findings admits it: *"One check that looks like it belongs here does not"* |
| 16 | `fetchStatus` is one of four values (`text`, `ok`, `manual`, `url_failed`) | Java types it as a bare `String`, produced at five sites — three live, two inside dead factories | `backend/CLAUDE.md` § API endpoints and `analysis.models.ts:145` vs `AnalysisResponse.java:4`, `:12`, `:16`; `verification.md` confirmation #4 and refined #4 |
| 17 | `NO_ACCIDENT_DECLARATION` is *the* domain concept | A raw string literal at four production sites; only one is a named constant, and the frontend never names it at all | `AnalysisResponseParser.java:17` (named) vs `AnalysisPrompt.java:16`, `:103`, `MockAiAnalysisService.java:149`; `RiskFlag.java:3` types `code` as `String` |
| 18 | `szkoda-istotna` has one canonical spelling | Three production sites, and the file owning the constant does not use its own constant in its own allow-list | `HistoriaPojazduParser.java:41` (constant) vs `:61` (literal) vs `cepik-result.component.html:193`; `verification.md` refined #3 |

**The pattern across these 18 rows.** Rows 7, 8, 9 and 14 are *stale documents* — the code moved
and an artifact did not; cheap to fix, and only misleading. Rows 10, 11, 12, 13 and 1 are *unbuilt
scope* — honest gaps, some of them the roadmap's next work. The dangerous class is rows 2, 4, 5, 6,
15, 16, 17 and 18: **an invariant the domain treats as one rule, implemented independently on two
or three code paths, with only one of them enforcing it.** Every one of these is the same failure
shape, and it is the shape a proper aggregate removes — not by testing harder, but by leaving only
one place where the rule can be stated.

---

## KROK 5 — Ranking: value × risk

**Value** = how core the invariant is to the product (Core subdomain, stated in the PRD, visible
to the user). **Risk** = how weakly the code enforces it today (paths that honour it ÷ paths that
exist, and whether a violation can be constructed).

| Rank | Candidate | Invariants | Value | Risk | Value × risk |
|---|---|---|---|---|---|
| **#1** | **`Analysis` aggregate with a guarded factory** | I4 (accident declaration), I1 (`overall` = mean), I2 (label ↔ code) | **Highest** — I4 is the only requirement the PRD states four times (`:37`, `:38`, `:99`, `:122`) and the first line of the root `CLAUDE.md` | **Highest** — I4 is enforced on 1 of 3 implementations and *inverted* on the one every gate runs; I1 on 2 of 3; I2 on 1 of 2 | **Highest** |
| #2 | Provenance-carrying `ExtractedData` | I11 | High — a secondary success criterion (`prd.md:34`) **and** an NFR (`prd.md:99`) | Maximal — zero enforcement, zero representation | High, but it is a **feature to build**, not an invariant to relocate; cross-stack cost |
| #3 | `CepikResult` with the three damage states in the type | I7, I8 | High — this exact collapse already shipped a false-clean report | Moderate — a factory and a javadoc hold it, and `withoutData` absorbs 13 call sites | Moderate |
| #4 | `Listing` / `Oferta` aggregate with an identity | I10 | High long-term — unblocks FR-010, comparison, and the user model | Total absence, but nothing depends on it *yet* | Deferred: this is the roadmap's F-02 → S-03, not a distillation finding |
| #5 | `MarketPriceContext` | I6, I8 | Moderate — supporting subdomain | Low — I6 is already enforced; only `missing()`'s null `fetchedAt` is open | Low |

### #1 to refactor, and why

**An `Analysis` aggregate that cannot be constructed in a state violating I4, I1 and I2.**

Three reasons it wins on value × risk rather than on either axis alone:

1. **The invariant is the product.** *"Missing data means unknown, not clean"* is the one rule
   this repo's own map says no artifact derived — *"it is the one rule no artifact derived; it was
   given"* (`context/map/repo-map.md:193-194`).
   If exactly one rule deserves to be unbreakable by construction, it is this one.
2. **It is currently enforced at the wrong layer.** I4 lives in a private method of
   `AnalysisResponseParser` — a *transport adapter*, one of three implementations of
   `AiAnalysisService`. An invariant enforced in an adapter is only as strong as the count of
   adapters, and the count is three; the third one already inverts it
   (`MockAiAnalysisService.java:147`). The parser's own javadoc at `:165-167` names the divergent
   site exactly — *"`MockAiAnalysisService.java:149` emits the same code at `HIGH` with different
   wording; the mock never goes through this parser, so the two do not have to agree"* — and that
   sentence is the finding: it is only true because the rule is housed in the adapter. Housed in
   the analysis, the two **would** have to agree, because there would be one place to disagree in.
3. **The same move fixes I1 and I2 for free.** All three rules are properties of a finished
   analysis, checked (or not) independently on each path. A single factory — `Analysis.of(...)`
   that recomputes `overall`, derives the label from the code, and appends the flag when
   `accidentClaim == null` — collapses eight of the eighteen KROK 4 rows into one place. That is
   the argument for an aggregate rather than for more tests: `context/changes/refactor-opportunities/plan.md`
   (committed, `status: planned`) binds the three implementations to the rule with contract tests,
   which catches a violation; the aggregate makes the violation unrepresentable. They are
   complementary, and the aggregate is the deeper of the two.

**Runner-up worth stating explicitly**, because value×risk understates it: provenance (#2) is the
largest gap between the PRD and the code in this whole document. It is ranked second only because
it is a new capability rather than a mis-housed invariant, and because it touches ~87 fields across
the hand-written REST mirror. It should not be forgotten on the way to #1 — the natural moment is
when `ExtractedData` next changes shape.

---

## Summary

AutoSkanerAI's domain speaks Polish about *oferty* and the code speaks English about *analyses* —
and the central noun of the product has no type at all: there is no `Listing`, no id, no owner, no
persistence, so „Porównanie kilku ofert" and FR-010 are both blocked on the same missing aggregate.
Of twenty terms in the ubiquitous language, six exist only in documents and three more survive only
as untyped strings or as a null-versus-empty convention, the most important of which is
`NO_ACCIDENT_DECLARATION` — a raw string literal at four production sites with exactly one named
constant among them. The Core subdomain is not "analysis" in general but **factual honesty**: the
rule that silence about accidents means *unknown*, never *clean*, stated four times in the PRD and
first in the root `CLAUDE.md`. That rule is enforced in a private method of one transport adapter,
and the adapter every quality gate and both E2E specs actually run inverts it — a listing reading
„Pełna historia serwisowa" gets no flag under `mock`, and when the mock does flag it uses the wrong
severity. The MODEL-vs-KOD table records eighteen divergences, and the dangerous eight share one
shape: a rule the domain treats as single, implemented independently on two or three paths, honoured
on one. Two of the three Supporting subdomains — live CEPiK and market scraping — were explicit
non-goals in a `shape-notes.md` still marked `accepted`, and now hold 15 of the 59 backend files;
the reversal is defensible, its silence is not. The ranked #1 refactor is therefore an `Analysis`
aggregate with a guarded factory that recomputes `overall` as the mean, derives the verdict label
from its code, and appends the accident-declaration flag when the claim is null — one place instead
of three, collapsing eight of the eighteen divergences and making the app's hardest rule
unrepresentable to violate rather than merely tested for.
