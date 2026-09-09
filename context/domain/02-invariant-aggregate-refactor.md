---
title: Invariant → aggregate refactor plan — the accident-declaration guardrail
created: 2026-09-09
type: refactor-plan
git_commit: 664857b
---

# Invariant → aggregate refactor plan

Product of this document is a **plan**, not an implementation. No production file is modified.
Every `file:line` below was opened and read. Upstream: `context/domain/01-domain-distillation.md`
(the ubiquitous language, the subdomain split, the MODEL-vs-KOD table).

---

## KROK 0 — Context

**Stack and layers.** Spring Boot 4.0.6 / Java 21 backend (59 files in `src/main`), Angular 21.2
frontend, no database, no auth, no CI. Business logic sits in exactly two layers, and neither is a
domain layer:

| Layer | Files | What decides business outcomes here |
|---|---|---|
| **Transport adapter (LLM)** | `analysis/llm/` (8) | `AnalysisResponseParser` — validates *and repairs* the model's JSON. The app's hardest rule is enforced in a private method here. |
| **Domain service (unnamed as such)** | `analysis/CepikRiskAdjuster.java` | folds registry findings into scores and verdict; recomputes `overall`; regenerates the verdict label |
| **Controller** | `analysis/AnalysisController.java:73-118` | orchestrates five domain operations inline: override → enrich ×2 → adjust → augment questions → assemble |
| **Model** | `analysis/` records | `AnalysisResult`, `ExtractedData`, `CategoryScores`, `Verdict`, `RiskFlag` — **all data, no behaviour, no factories** |
| **UI** | `frontend/src/app/features/analyzer/` | renders; also holds one rule of its own (mileage mismatch, `cepik-result.component.ts:151`) |

**There is no domain layer.** `AnalysisResult` is a 7-component record with a public canonical
constructor and nothing else; the rules that make it *valid* live in the two classes above and in
`MockAiAnalysisService`. That single fact is what this plan addresses.

**Requirements sources.** `context/foundation/prd.md` (FR-001…FR-018, guardrails at `:37-38`,
NFRs at `:98-99`), root `CLAUDE.md` § "Key business rules", `backend/CLAUDE.md`,
`context/foundation/test-plan.md` §2 Risk Map, `context/map/repo-map.md` §4.

**Degenerate by project size, stated rather than skipped.** There is **no persistence** — `@Entity`
and `JpaRepository` both return zero matches (`context/changes/analysis-flow-analysis/verification.md`
confirmation #10). So KROK 4's "repository loading and saving the aggregate" and "one transaction"
have no code to point at today. They are designed anyway, in §4.4, because F-02 (data layer) and
S-03 (persistence) are the roadmap's next work and the *order* matters: a schema derived from an
aggregate that already owns its invariants is a different schema from one derived from the current
records.

---

## KROK 1 — Business invariants, from documents and from code

Rules that must always be true in this domain. Each with a source quote.

| Id | Invariant | Source |
|---|---|---|
| **INV-1** | If the listing makes no accident declaration, the output must say so explicitly. Silence is *unknown*, never *clean*. | „must not draw conclusions from the absence of accident information — missing data means unknown, not clean… must never imply a car is accident-free simply because no accident was mentioned" — `prd.md:38`; restated `prd.md:122`; *"every data point in the output is labelled as either extracted from the listing or inferred"* — `prd.md:99`; *"it is the one rule no artifact derived; it was given"* — `repo-map.md:193-194` |
| **INV-2** | `scores.overall` is the mean of the four category scores. | the scoring table `idea-notes.md:77-81`; *"recomputes overall the same way the scorers do — as the mean of the four"* — `CepikRiskAdjuster.java:208` |
| **INV-3** | `verdict.label` is the Polish label belonging to `verdict.code`. | *"Kept in step with MockAiAnalysisService and AnalysisPrompt's examples"* — `CepikRiskAdjuster.java:246` |
| **INV-4** | Every score is in 0..100. | `AnalysisResponseParser.java:113-117` |
| **INV-5** | A risk flag is added, never removed. | append-only construction at `AnalysisResponseParser.java:179-182` and `CepikRiskAdjuster.java:108` |
| **INV-6** | A registry finding may only lower a score, never raise it; and only a `FOUND` result may move anything. | *"Risk ceilings, never raises"* and *"Only `FOUND` results adjust anything"* — `backend/CLAUDE.md` § "Folding registry findings into the score"; enforced `CepikRiskAdjuster.java:73`, `:208-218` |
| **INV-7** | `damageRecords` is empty only when the timeline was read and held no damage; every other case is `null`. | *"Null and empty are not interchangeable here… Collapsing the two is what made this app report a clean history for a vehicle carrying a szkoda istotna."* — `CepikResult.java:9-14` |
| **INV-8** | The response always carries a `marketPriceContext` — never absent, never an uncaught throw. | *"This also honours the invariant S-05 stated and never enforced"* — `AnalysisController.java:132-134` |
| **INV-9** | `fetchedAt` is always stamped, even on a degraded path. | `CepikResult.withoutData` → `Instant.now()` at `:66` |
| **INV-10** | A malformed registry input degrades the analysis; it never rejects the request. | *"a mistyped VIN must not 400 away an otherwise useful analysis"* — `AnalysisRequest.java:19-22` |
| **INV-11** | `accidentClaim` is the *listing's* claim and is never editable by the user. | *"it is a claim the listing makes, and `CepikRiskAdjuster` compares it against the registry"* — `backend/CLAUDE.md`; enforced `UserOverrides.java:58-61` |
| **INV-12** | Every data point states its origin: extracted, inferred, or user-supplied. | `prd.md:34`, `prd.md:99` |

---

## KROK 2 — Classification, and the choice of #1

Three axes, as the prompt specifies: (a) how core to the product's meaning, (b) how spread across
layers and files, (c) enforced / declared / violable.

| Id | (a) Core-ness | (b) Spread — files that hold a piece of the rule | (c) Enforcement |
|---|---|---|---|
| **INV-1** | **Maximum.** Stated 4× in the PRD, first line of root `CLAUDE.md`, Risk #2 and #6 in `test-plan.md` §2 | **5 files, 3 layers**: `AnalysisResponseParser.java:17`, `:145-183`; `AnalysisPrompt.java:16`, `:92`, `:103`; `MockAiAnalysisService.java:147-152`; `analysis-result.component.html:94`, `:117-140`; `analysis-result.component.ts:46-50` | **Enforced on 1 of 3 implementations, inverted on the one every gate runs, and the UI can collapse the result out of view** |
| INV-2 | High — the headline number the user reads | 3 files: `CepikRiskAdjuster.java:214`, `MockAiAnalysisService.java:53`, and *not* `AnalysisResponseParser.java:197-199` | Enforced on 2 of 3 paths; **the missing path is production** |
| INV-3 | Medium — a wording, not a judgement | 3 files, byte-identical switch arms: `CepikRiskAdjuster.java:248-252`, `MockAiAnalysisService.java:58-62`, `AnalysisPrompt.java:57` | Declared by a comment (`CepikRiskAdjuster.java:246`); **violable** — `mapVerdict` (`:201-209`) copies the model's label verbatim |
| INV-4 | Medium | 1 file | **Enforced**, fail-fast (`:113-117`) |
| INV-5 | Medium | 2 files | Enforced structurally |
| INV-6 | High | 1 file | **Enforced** — 25 unit tests plus `CepikDamageReachesTheResponseTest` |
| INV-7 | High | 2 languages: `CepikResult.java:9-17` + `cepik-result.component.ts:16`, `:50` | Declared; guarded by a factory that the public 21-arg constructor bypasses |
| INV-8 | Medium | 1 file | **Enforced** (`degradeOnThrow`, `:143-155`) |
| INV-9 | Low | 2 packages, opposite answers | Enforced in `cepik`, ignored in `market` |
| INV-10 | Medium | 2 files | Enforced |
| INV-11 | Medium | 1 file | **Enforced** |
| INV-12 | High | 0 files | **Ignored** — no representation at all |

### #1 = INV-1

The prompt asks for the invariant that is simultaneously **most core** and **most weakly
enforced**. INV-1 wins both columns outright.

Why not the two nearest rivals:

- **INV-12 (provenance) is more weakly enforced — it has no enforcement at all — but it is not
  spread across layers, because it is not implemented anywhere.** An invariant with zero
  implementations is a feature to build, not a rule to consolidate; an aggregate-guardian is the
  wrong tool for it. It is ranked #2 in `01-domain-distillation.md` KROK 5 and is best done when
  `ExtractedData` next changes shape.
- **INV-2 and INV-3 are genuinely weakly enforced and genuinely spread** (3 files each, and both
  violable on the production path), but neither is core in the sense the PRD means: a wrong
  `overall` misleads, a wrong label reads oddly. INV-1 is the rule whose violation is the exact
  harm the product exists to prevent. **They are folded into the same refactor anyway** (§4.2),
  because all three are properties of a *finished analysis* and one factory can hold all three.
  That is a bonus, not the justification.

**One more reason INV-1 is the right subject, specific to this repo.** `test-plan.md` §7 excludes
the mocks from testing — *"`MockAiAnalysisService` and `MockCepikService` are deterministic stubs
that exist to serve other tests; asserting their canned responses proves nothing"* — with an
explicit trigger attached: *"Re-evaluate if a mock ever encodes business logic rather than a
fixture."* `MockAiAnalysisService.buildRiskFlags` (`:144-180`) is business logic. **The trigger has
already fired and nobody noticed**, which is the strongest available evidence that this rule needs
a home rather than more documentation.

---

## KROK 3 — Diagnosis of INV-1

### 3.1 Where the rule lives today, layer by layer

| # | Site | What it does with INV-1 |
|---|---|---|
| 1 | `AnalysisPrompt.java:16` | **States the rule to the model** as an obligation: „Gdy accidentClaim jest null, MUSISZ dodać do riskFlags wpis: { "code": "NO_ACCIDENT_DECLARATION", "severity": "MEDIUM", "description": "Ogłoszenie nie zawiera deklaracji wypadkowej — historia nieznana" }." This string is the **oracle** — `AnalysisResponseParser.java:164` says so: *"Oracle for the shape below is `AnalysisPrompt.java:16` verbatim, not this class."* |
| 2 | `AnalysisPrompt.java:92`, `:103` | Demonstrates it in few-shot example 2. A **second and third literal copy** of the code string. |
| 3 | `AnalysisResponseParser.java:17` | `private static final String MISSING_DECLARATION_FLAG = "NO_ACCIDENT_DECLARATION"` — the only named constant of the four spellings. |
| 4 | `AnalysisResponseParser.java:145-183` | **Enforces it**, correctly and idempotently: if `accidentClaim != null` return unchanged (`:170-172`); if the flag is already present return unchanged whatever its severity (`:173-175`); otherwise append `MEDIUM` with the prompt's exact wording (`:180-181`). |
| 5 | `MockAiAnalysisService.java:147-152` | **Violates it.** Guard: `!lower.contains("wypadek") && !lower.contains("bezwypadkowy") && !lower.contains("historia")`. Emits `RiskSeverity.HIGH` (`:150`) with different wording (`:151`). |
| 6 | `analysis-result.component.html:94` | Renders the claim itself as `{{ str(result().extracted.accidentClaim) }}` — `str(null)` returns `'—'` (`analysis-result.component.ts:113-115`). |
| 7 | `analysis-result.component.ts:46-50` | Shows only the **first four** risk flags unless expanded; `showExpandLink` appears above four. |
| 8 | `ListingClaimsCannotMoveTheFloorTest.java:190`, `:213`, `:234` | Three tests pin site 4. **Nothing tests site 5.** |

### 3.2 Which layers do not enforce it

- **The `AiAnalysisService` port does not.** The interface is one line:
  `AnalysisResult analyze(String listingText)` (`AiAnalysisService.java`). It promises an
  `AnalysisResult` and says nothing about what makes one valid. There are **three** implementations
  (`MockAiAnalysisService`, `BedrockClaudeAnalysisService`, `OpenRouterAnalysisService`), and only
  the two network ones route through the parser. An invariant enforced in an adapter is as strong
  as the count of adapters.
- **The model layer does not.** `AnalysisResult`'s canonical constructor is public and takes seven
  arbitrary components; `new AnalysisResult(extractedWithNullClaim, eq, List.of(), q, s, v, m)` —
  an analysis with a null accident claim and *no risk flags at all* — compiles and is assembled
  exactly that way at `AnalysisController.java:112-115`.
- **The controller does not.** `buildResponse` re-assembles the result at `:112-115` to append
  seller questions, and could drop or reorder flags without any check noticing.

### 3.3 Where it is enforced inconsistently

The parser's own javadoc records the inconsistency and then *licenses* it:

> „({@code MockAiAnalysisService.java:149} emits the same code at {@code HIGH} with different
> wording; the mock never goes through this parser, so the two do not have to agree, and the
> prompt is the one that states the contract.)" — `AnalysisResponseParser.java:165-167`

That sentence is only true because the rule lives in the adapter. Move it onto the analysis and
the two **must** agree, because there is one place left to disagree in. This is the diagnosis in
one line: **the code has documented the divergence instead of removing the ability to diverge.**

The concrete violating input class is exactly one, and it is small and ordinary: a listing
containing „historia" and no accident keyword — e.g. **„Pełna historia serwisowa"**. Under `mock`
that listing gets no `NO_ACCIDENT_DECLARATION` flag. `mock` is the profile every one of the three
local quality gates runs and the profile both Playwright specs run against.

### 3.4 Where the client is a guard it should not be

Not the *only* guard, but a guard that can hide the server's answer:

`visibleFlags` (`analysis-result.component.ts:46-47`) truncates to the first four flags. The
accident flag is **appended last** on purpose — `AnalysisResponseParser.java:176-178`: *"Appended,
not prepended… This one says only 'the advert is silent', which is the least urgent thing on the
list."* So on a listing where the model returns four or more findings of its own, the one flag
carrying INV-1 is the one placed fifth and collapsed behind a button. That button
(`analysis-result.component.html:132`) has **no positive test**: the single spec reference is a
`toBeNull()` query at `analysis-result.component.spec.ts:105`
(`analysis-flow-analysis/verification.md` confirmation #19).

The reasoning at `:176-178` is defensible on its own terms and becomes wrong in combination with
`slice(0, 4)` — which is precisely the failure mode a rule spread over two layers in two languages
produces. Neither file is wrong by itself.

### 3.5 Where an error is swallowed instead of stopping the operation

Reported honestly, because for INV-1 the answer is mostly "nowhere":

- **The parser is fail-fast throughout.** Every validation failure throws
  `LlmResponseSchemaException` — `:31`, `:62-67`, `:101`, `:115`, `:139`, `:191`, `:206` — mapped to
  **502 `BAD_GATEWAY`** with „Niepoprawny format odpowiedzi LLM" at
  `GlobalExceptionHandler.java:74-79`. INV-1's failure mode is not a swallowed error; it is a rule
  stated four times and enforced once.
- **`AnalysisController.degradeOnThrow` (`:143-155`) does swallow, deliberately and correctly.** It
  catches `RuntimeException` from the two enrichments and degrades to `LOOKUP_FAILED` /
  `FETCH_FAILED` — statuses the domain already models — rather than discarding a finished analysis
  after ~27 s. Its javadoc at `:136-138` scopes it away from the LLM call for exactly this reason.
  Not a defect; listed so the plan is not read as proposing to remove it.
- **Two genuine silent swallows, both on the mock path**: `MockAiAnalysisService.java:93-94` and
  `:104-105` catch `NumberFormatException` as `ignored`, yielding a null price or mileage. The null
  is a modelled state, so the effect is bounded — but it means the mock silently produces a
  *less complete* analysis, which lowers `completenessScore` (`:192-203`), which moves `overall`,
  which moves the verdict. Out of scope for INV-1; recorded for INV-2's phase.

---

## KROK 4 — Design: the guardian aggregate

### 4.1 The root

`Analysis` — new record in `com.example.autoskaner_ai.analysis`, replacing `AnalysisResult` as the
type every producer returns and every consumer reads. **Its canonical constructor becomes private**;
the only way in is the factory.

```java
public record Analysis(
        ExtractedData extracted,
        List<EquipmentItem> equipment,
        List<RiskFlag> riskFlags,
        List<String> sellerQuestions,
        CategoryScores scores,
        Verdict verdict,
        AnalysisMeta meta
) {
    // canonical constructor is private: every instance passes through of()
    private Analysis { /* compact form, assignment only */ }

    /**
     * The only way to build an Analysis. Derives what the domain can compute and rejects
     * what only the caller could have known.
     */
    public static Analysis of(ExtractedData extracted,
                              List<EquipmentItem> equipment,
                              List<RiskFlag> riskFlags,
                              List<String> sellerQuestions,
                              CategoryScores scores,
                              VerdictCode verdictCode,
                              AnalysisMeta meta) { ... }

    /** INV-6 + INV-2 + INV-3: fold registry findings in, recompute, re-derive. */
    public Analysis withRegistryFindings(RegistryFindings findings) { ... }

    /** INV-1's sibling: the three registry inputs the user still owes us. */
    public Analysis withRegistryInputRequests(List<String> questions) { ... }

    /** INV-11-safe: overrides may replace extracted values, never the accident claim. */
    public Analysis withExtracted(ExtractedData replacement) { ... }
}
```

Note the signature change that carries most of the design: **`of` takes a `VerdictCode`, not a
`Verdict`.** The label is not an input. That is INV-3 made unrepresentable rather than checked.

### 4.2 `of` — preconditions, derivations, and the named domain error

Pseudocode, with each line tagged by the invariant it serves:

```
static Analysis of(extracted, equipment, riskFlags, sellerQuestions, scores, verdictCode, meta):

    # --- preconditions: reject what the domain cannot compute (fail-fast) ---
    require(extracted   != null, "extracted")                       # INV-4 spine
    require(verdictCode != null, "verdict.code")
    require(scores      != null, "scores")
    for (field, value) in scores.asFourCategories():                 # INV-4
        if value < 0 or value > 100:
            throw new AnalysisInvariantException(
                "Wartość poza zakresem 0-100: " + value, field)

    # --- derivations: compute what the domain owns (total, cannot fail) ---
    flags = withAccidentDeclarationFlag(riskFlags, extracted)        # INV-1
    overall = mean(scores.completeness, scores.equipment,
                   scores.risk, scores.value)                        # INV-2
    verdict = new Verdict(verdictCode, verdictCode.label())           # INV-3

    return new Analysis(extracted, List.copyOf(equipment), flags,
                        List.copyOf(sellerQuestions),
                        scores.withOverall(overall), verdict, meta)


# INV-1, unchanged in behaviour from AnalysisResponseParser.java:169-183
static List<RiskFlag> withAccidentDeclarationFlag(flags, extracted):
    if extracted.accidentClaim() != null:            return flags        # :170-172
    if flags.anyMatch(f -> f.code() == NO_ACCIDENT_DECLARATION): return flags   # :173-175
    return flags + DomainRiskFlag.NO_ACCIDENT_DECLARATION.asRiskFlag()  # :179-182
```

**Derivation is not "silently updating state" — the distinction the fail-fast rule turns on.**
This needs stating, because the constraint at the top of this lesson's prompt is *"nielegalna
operacja zatrzymuje, nie loguje-i-jedzie dalej"* and INV-1 is enforced today by *repair*, not by
rejection. The rule that resolves it:

> **Anything the domain can compute from the inputs, it computes. Anything only the caller could
> have known, it rejects.**

`overall`, the verdict label, and the presence of the accident flag are all total functions of the
other inputs — there is no information in the caller's version of them, so accepting a different
value would be accepting noise. A score of 101, a null verdict code, a missing make: nothing in the
domain can supply those, so they throw. Making INV-1 a rejection instead would be strictly worse
for the user: a free-tier model that drops the flag under length pressure would turn a usable
analysis into a 502 after a ~16 s wait, and `AnalysisResponseParser.java:150-151` already names
that exact scenario as the reason the repair exists.

**`AnalysisInvariantException`** — new, in `com.example.autoskaner_ai.analysis`, carrying
`message` + `fieldPath`, same shape as `LlmResponseSchemaException.java:3-7`. It gets **its own**
`@ExceptionHandler` beside `GlobalExceptionHandler.java:74`, returning **500** „Błąd wewnętrzny
analizy" rather than reusing the 502 „Niepoprawny format odpowiedzi LLM". The reason is specific to
this repo's history: under `mock` a violation is *our* bug, and attributing our bug to the LLM is
the misdiagnosis pattern the freshness ledger records three separate times
(`test-plan.md` §8 — the `catch → exit(0)` hook, the ACL-locked Node, the `-Xmx12g` heap message
for a Java problem). A 502 that blames the provider for a violation the mock produced would be the
fourth.

### 4.3 Two value objects that remove the duplicated spellings

**`DomainRiskFlag`** — an enum of the flags *the app itself* emits, holding code, severity and
Polish wording in one place. The model's own codes stay free-form `String`s on `RiskFlag`, because
the LLM legitimately invents findings; only the app-owned ones are closed.

```java
public enum DomainRiskFlag {
    NO_ACCIDENT_DECLARATION(RiskSeverity.MEDIUM,
        "Ogłoszenie nie zawiera deklaracji wypadkowej — historia nieznana"),
    CEPIK_CONTRADICTS_LISTING(RiskSeverity.HIGH, ...);

    public RiskFlag asRiskFlag() { return new RiskFlag(name(), severity, description); }
    public boolean matches(RiskFlag flag) { return name().equals(flag.code()); }
}
```

Collapses four literal spellings of `NO_ACCIDENT_DECLARATION` (`AnalysisResponseParser.java:17`,
`AnalysisPrompt.java:16`, `:103`, `MockAiAnalysisService.java:149`) to one. `AnalysisPrompt`
interpolates `DomainRiskFlag.NO_ACCIDENT_DECLARATION.name()` and `.description()` into its prompt
text instead of spelling them, so the prompt — which `AnalysisResponseParser.java:164` calls the
oracle — becomes a *reader* of the enum rather than a fourth copy.

**`VerdictCode.label()`** — the three Polish labels move onto the enum they belong to, deleting the
byte-identical switch arms at `CepikRiskAdjuster.java:248-252` and `MockAiAnalysisService.java:58-62`
and the inline copies at `AnalysisPrompt.java:57`.

### 4.4 Repository and transaction — designed, not built

No persistence exists, so there is nothing to consolidate today. What the design fixes is the
*order* of the next two roadmap items:

- **F-02 / S-03 must persist the aggregate, not its parts.** `AnalysisRepository.save(Analysis)` /
  `findById(AnalysisId)`, with `ExtractedData`, `CategoryScores`, `RiskFlag` and `Verdict` as
  owned components of the root — never separately addressable. Nothing outside the repository
  reconstructs an `Analysis`, which is the same rule the private constructor already states.
- **Atomicity requirement, and it is real.** INV-1 relates two fields — `extracted.accidentClaim`
  and the presence of a flag in `riskFlags`. If those become two tables, a write that lands one and
  not the other is a persisted violation of the app's hardest rule. So the root's save is one
  `@Transactional` unit; a partial write must not be reachable.
- **Reads go through the aggregate too**, so a future comparison view or list screen cannot select
  `riskFlags` without the claim that explains them.

This is why doing the aggregate *before* F-02 is cheaper than after: the schema then falls out of
the invariant instead of the invariant having to be retrofitted onto a schema.

### 4.5 The thin controller

Today `AnalysisController.buildResponse` (`:73-118`) performs five domain operations inline:
override, two enrichments, registry adjustment, and question augmentation, then re-assembles the
record twice (`:112-115`, `withExtracted:157-163`). After:

```
POST /api/analyses:
    request  = parse and validate input                       # unchanged, Bean Validation
    text     = resolveListingText(request)                    # unchanged: url | manual | text
    analysis = aiAnalysisService.analyze(text)                # returns Analysis, already valid
    analysis = analysis.withExtracted(UserOverrides.apply(...))# INV-11 lives inside
    cepik    = degradeOnThrow("cepik", ...)                    # unchanged, INV-8
    market   = degradeOnThrow("market-price", ...)             # unchanged, INV-8
    analysis = analysis.withRegistryFindings(
                   cepikRiskAdjuster.findings(analysis, cepik))# INV-6; arithmetic inside aggregate
    analysis = analysis.withRegistryInputRequests(
                   RegistryInputs.missing(analysis.extracted()))
    return new AnalysisResponse(fetchStatus, null, analysis, cepik, market)
```

`CepikRiskAdjuster` keeps every rule it owns — the caps, the negation-aware claim matcher, the
`FOUND`-only guard, the log line — and stops owning the *arithmetic*: it returns
`RegistryFindings(List<RiskFlag> added, int riskCeiling, VerdictCode floor)` and the aggregate
applies the mean and the label. That is how INV-2 and INV-3 stop having two implementations.

**Nothing moves from the client to the server here, because nothing about INV-1 lives on the client
as a guard.** What §3.4 found is the opposite problem — a client that can *hide* the server's
answer — and its fix is not an aggregate. It is one line and one test, and it is scheduled as
Phase 6 rather than dropped: pin the accident flag ahead of the truncation boundary, or exclude it
from `slice(0, 4)`, and give `.expand-link` its first positive assertion.

---

## KROK 5 — Before / after, phases, tests, names

### 5.1 Before / after, per site holding the rule today

| Site | Before | After |
|---|---|---|
| `AnalysisPrompt.java:16`, `:103` | two literal copies of the code + wording | interpolates `DomainRiskFlag.NO_ACCIDENT_DECLARATION` |
| `AnalysisResponseParser.java:17` | `private static final String MISSING_DECLARATION_FLAG` | deleted — the enum is the name |
| `AnalysisResponseParser.java:145-183` | the one enforcement point, in an adapter | deleted; `parse` ends `return Analysis.of(...)` and the javadoc at `:145-167` moves onto `Analysis.of` (keeping the history it records) |
| `AnalysisResponseParser.java:197-199` (`mapScores`) | passes the model's `overall` through | returns the four categories; `of` computes `overall` |
| `AnalysisResponseParser.java:201-209` (`mapVerdict`) | returns `new Verdict(code, v.label())` | returns `VerdictCode`; the model's `label` is parsed and discarded |
| `MockAiAnalysisService.java:53` | computes the mean itself | deleted — `of` computes it |
| `MockAiAnalysisService.java:58-62` | switch producing the label | deleted — `VerdictCode.label()` |
| `MockAiAnalysisService.java:147-152` | wrong guard, wrong severity, wrong wording | the whole `if` block **deleted**; `of` derives the flag. This is the fix, and it is a deletion. |
| `MockAiAnalysisService.java:67-75` | `new AnalysisResult(...)` | `Analysis.of(...)` |
| `CepikRiskAdjuster.java:208-218` (`capRisk`) | caps *and* recomputes the mean | returns a ceiling; the mean moves to the aggregate |
| `CepikRiskAdjuster.java:220-229`, `:247-253` | floors the verdict *and* regenerates the label | returns a `VerdictCode` floor; `VerdictCode.label()` supplies the label |
| `AnalysisController.java:112-115`, `:157-163` | two hand-rolled re-assemblies of the record | `analysis.withRegistryInputRequests(...)`, `analysis.withExtracted(...)` |
| `analysis-result.component.ts:46-47` | may collapse the INV-1 flag out of view | INV-1 flag exempt from truncation; `.expand-link` gets a positive test |
| `analysis.models.ts` | — | **unchanged.** No wire field is added, renamed or retyped in Phases 1-5. |

That last row is a deliberate constraint, not a coincidence. `repo-map.md` §3 records that the REST
contract is a hand-written mirror with **no tool** behind it, so a refactor that leaves the wire
shape byte-identical is a refactor the frontend cannot be broken by.

### 5.2 Phases

Conventions this follows, from the repo: one green commit per phase, **never commit a deliberate
break**, stage by explicit path, and test-first where a runner exists (`test-plan.md` §6.1).
`./mvnw -o test` runs offline in ~15 s after `. .githooks/common.sh`.

**Prerequisite — not part of this plan.** `context/changes/refactor-opportunities/plan.md`
(committed at `664857b`, `status: planned`, 13 unchecked steps) writes the two **port contract
tests** — `AiAnalysisServiceContractTest` and `CepikEnrichmentServiceContractTest` — and fixes both
mocks. **Run it first.** The reasons are not stylistic:

1. Its Phase 2 makes INV-1 assertable against *every* implementation of the port. That is the
   red-green harness this refactor needs; without it, "behaviour unchanged" is a claim rather than a
   measurement.
2. The two plans overlap on exactly one file — `MockAiAnalysisService.java:147-152`. That plan
   *fixes* the guard; this one *deletes* it. Doing this one first would fix the same defect twice
   and leave the contract test written against code that no longer exists.
3. A contract test catches a violation; an aggregate makes it unrepresentable. Both are worth
   having, and in that order the second one is proved by the first.

| Phase | Delivers | Test-first? | Risk |
|---|---|---|---|
| **1** | `VerdictCode.label()`; the three switch arms and the inline copies deleted | No — pure move, existing tests are the harness | Lowest. `AnalysisPrompt.java:57`'s labels sit inside prompt prose; interpolating them changes the prompt string, so the prompt-shape tests must be read, not assumed |
| **2** | `DomainRiskFlag` enum; four spellings → one | **Yes** — a test asserting the prompt text contains the enum's own code and wording, so prompt and enforcement cannot diverge again | Low. Touches `AnalysisPrompt`, which is an LLM input: assert the rendered string, not the enum |
| **3** | `Analysis` + private constructor + `of(...)` + `AnalysisInvariantException` + its handler. `AnalysisResponseParser` and `MockAiAnalysisService` both construct through it. `MockAiAnalysisService.java:147-152` deleted. INV-1, INV-2, INV-3, INV-4 land together. | **Yes** — the ten cases in §5.3 | **The core phase.** `AnalysisResult` is referenced across the backend and named in the wire mirror; keeping the wire shape identical means the JSON serialisation of `Analysis` must match `AnalysisResult`'s field-for-field. Verify with the existing controller integration tests before anything else |
| **4** | `RegistryFindings`; `CepikRiskAdjuster` returns findings, aggregate applies them | **Yes** — the 25 existing `CepikRiskAdjusterTest` cases are the oracle and must pass unchanged | Medium. This is the file PIT measured at 90% (`backend/CLAUDE.md` § Mutation testing); re-run PIT on it after the move and compare, because a score drop here means a rule left the tested class without arriving anywhere |
| **5** | Thin `AnalysisController`; `withExtracted` / `withRegistryInputRequests` move into the aggregate | No — `degradeOnThrow` and the three question appends already have tests | Low, once 3 and 4 are in |
| **6** | The UI half: INV-1 flag exempt from `slice(0, 4)`, `.expand-link`'s first positive assertion | **Yes** — a component test with five flags | Low. Separate commit; it is the only phase that touches `frontend/` |
| **7** | Docs: `backend/CLAUDE.md`, `repo-map.md` §4, `test-plan.md` §2/§7/§8, root `CLAUDE.md`; suite counts in both `.githooks/` labels | No | The counts must be **read off a run** — grep miscounts both stacks (`@Test` reads 212 against a real 235; `it(` reads 64 against a real 51, because `submit(` contains `it(`) |

### 5.3 Test cases for INV-1 (and its co-travellers)

Legal operations — must succeed, with the stated outcome:

1. `accidentClaim == null`, no flag present → exactly one `NO_ACCIDENT_DECLARATION` at `MEDIUM`,
   wording byte-identical to `DomainRiskFlag`'s. *(port of
   `ListingClaimsCannotMoveTheFloorTest.java:190`)*
2. `accidentClaim == null`, flag already present at `HIGH` with the model's own wording → returned
   **unchanged**, exactly one occurrence. *(port of `:213`)*
3. `accidentClaim == "bezwypadkowy"` → no flag appended. *(port of `:234`)*
4. `accidentClaim == null` with **four** model flags → five flags out; and, after Phase 6, the
   INV-1 flag is among those the component renders unexpanded.
5. `accidentClaim == null`, called twice through `of` → still exactly one flag (idempotence under
   re-derivation, which the new `withRegistryFindings` path makes reachable).
6. **The violating input class, named:** listing text „Pełna historia serwisowa" through
   `MockAiAnalysisService` → the flag **is** present at `MEDIUM`. This is the case that fails on
   today's code and is the reason for the phase.
7. `scores` = (80, 60, 40, 20, overall **99**) → `overall == 50`. *(INV-2 on the path that has never
   enforced it)*
8. `verdictCode == HIGH_RISK_SKIP` with a caller-supplied label „warto sprawdzić" → impossible to
   express; the compiler is the test. Assert instead that `of(..., HIGH_RISK_SKIP, ...)` yields
   label „wysokie ryzyko — pomiń". *(INV-3)*

Illegal operations — must throw `AnalysisInvariantException`, naming the field:

9. any category score `101`, and any category score `-1`. *(INV-4, ported from
   `AnalysisResponseParser.java:113-117`'s existing tests)*
10. `verdictCode == null`; `extracted == null`; `scores == null`.

Plus one property, not a case: **every implementation of `AiAnalysisService`** satisfies 1-4. That
assertion belongs to `AiAnalysisServiceContractTest` from the prerequisite plan — cross-referenced
here rather than duplicated.

Validation of the tests themselves, per this repo's habit: each new test must be seen **red** on
today's code before the fix lands, and case 6 is the one that makes the whole phase honest —
a suite that goes green on the first run against `Analysis.of` has proved only that the factory
compiles.

### 5.4 New load-bearing names to register

The project keeps three registries, and each gets specific rows:

**`context/foundation/test-plan.md`**
- §2 Risk Map — Risk #6 (*"Listing text written to game the analyser… produces a reassuring
  verdict"*) gains the aggregate as its protection anchor; Risk #2's "Must challenge" column gains
  *"an invariant enforced in an adapter is enforced once per adapter"*.
- §7 — the exclusion *"The mock profile's own output"* must be **rewritten, not deleted**: after
  Phase 3 the mock stops encoding business logic (that is the point of deleting `:147-152`), so the
  exclusion becomes true again for the right reason. Record that its own re-evaluation trigger had
  already fired.
- §8 Freshness Ledger — one entry for the refactor, naming the derivation-vs-rejection rule from
  §4.2, because it is the decision a future contributor is most likely to relitigate.

**`context/map/repo-map.md`**
- §3 couplings — `analysis ↔ cepik` changes shape: `RegistryFindings` becomes the crossing type.
- §4 risk zones — the `mock` profile entry can be downgraded once the mock holds no business logic;
  the entry for INV-1 moves its anchor from `AnalysisResponseParser` to `Analysis`.

**`backend/CLAUDE.md`**
- § "AI service pattern" — the port's contract is no longer "returns an `AnalysisResult`" but
  "returns an `Analysis`, and `Analysis` cannot exist in a state violating INV-1…INV-4".
- § "Folding registry findings into the score" — the sentence *"`overall` is recomputed as the mean
  of the four categories but never raised"* moves from describing `CepikRiskAdjuster` to describing
  the aggregate.

**Names introduced:** `Analysis`, `Analysis.of`, `AnalysisInvariantException`, `DomainRiskFlag`,
`VerdictCode.label()`, `RegistryFindings`, `RegistryInputs.missing`, `AnalysisRepository`
(Phase F-02, designed only), `AnalysisId` (same).

**Name retired:** `AnalysisResult` — and `MISSING_DECLARATION_FLAG`, whose whole job was to be the
one named copy of a string that should never have been a string.

---

## Summary

The invariant chosen for the guardian-aggregate refactor is **INV-1: a listing that says nothing
about accidents must produce an output that says so** — the rule the PRD states four times
(`prd.md:37`, `:38`, `:99`, `:122`) and `repo-map.md:193-194` calls the one rule no artifact
derived. It scores worst on every axis the classification uses: it is the most core rule in the
product, it is smeared across five files in three layers and two languages, and it is enforced in
exactly one of the three implementations of a one-line port — with the third,
`MockAiAnalysisService.java:147-152`, inverting it for any listing containing „historia" and no
accident keyword, which is the profile all three quality gates and both E2E specs run. The parser's
own javadoc at `:165-167` names the divergent site and then licenses it — *"the mock never goes
through this parser, so the two do not have to agree"* — a sentence that is only true because the
rule is housed in a transport adapter, and that is the diagnosis in one line: the code documented
the divergence instead of removing the ability to diverge. The design is an `Analysis` record whose
canonical constructor is private and whose sole factory `of(...)` **derives** what the domain can
compute — the accident flag, `overall` as the mean, the verdict label from its code — and
**rejects**, via a named `AnalysisInvariantException` mapped to its own 500 rather than the LLM's
502, what only a caller could have known: a score outside 0..100, a null verdict code, a missing
spine. Taking `VerdictCode` instead of `Verdict` in that signature makes INV-3 unrepresentable
rather than merely checked, and folding INV-2 and INV-3 into the same factory collapses eight of
the eighteen MODEL-vs-KOD divergences from `01-domain-distillation.md` into one place. Seven phases
carry it, and the prerequisite is explicit: the already-committed
`context/changes/refactor-opportunities/plan.md` must run first, because its contract test is the
only harness that can prove this refactor changed no behaviour, and because both plans touch the
same six lines of the mock. Persistence is designed but not built — there is no database yet — and
the ordering argument is that doing the aggregate before F-02 lets the schema fall out of the
invariant, instead of the invariant having to be retrofitted onto a schema in which INV-1's two
fields have become two tables.
