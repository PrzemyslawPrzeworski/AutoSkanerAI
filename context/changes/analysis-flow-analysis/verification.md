---
date: 2026-09-04T19:24:41+02:00
researcher: Claude Opus 5
git_commit: faf77d10a8418c31961205472df7c55d5db64baa
branch: main
repository: AutoSkanerAI
topic: "ast-grep verification of research.md's structural claims"
tags: [verification, ast-grep, analysis-flow, m4-l3]
status: complete
last_updated: 2026-09-04
last_updated_by: Claude Opus 5
---

# Verification of `research.md`

Step 2 of M4-L3. Every **structural** claim in [`research.md`](research.md) — one a pattern
over code shape can decide — was extracted, given an `ast-grep` pattern or a targeted grep, and
run. Claims about *judgement* (whether a gap matters, what an order-of-change trap would cost)
are not verifiable this way and are excluded; they are flagged as such in §4.

**Tooling.** `ast-grep` is not on `PATH` and `npx @ast-grep/cli` fails with "could not determine
executable to run". The working invocation is:

```bash
npx --yes --package @ast-grep/cli ast-grep run -p '<pattern>' -l java <paths>
```

Two operational notes, since both cost time:

1. **`wc -l` overcounts.** `ast-grep`'s default output is multi-line per match, so piping to
   `wc -l` counts output lines, not matches. Every count below comes from
   `--json=compact` piped through `JSON.parse(...).length`.
2. **The package root is `com.example.autoskaner_ai`, not `com.autoskaner`.** All three research
   agents reported bare filenames, so the first pattern run returned empty for the *right* reason
   and looked like a refutation. A silent empty result from a wrong path is
   indistinguishable from a genuine "no matches" — every empty result below was re-run against a
   `find`-resolved absolute path before being recorded.

**Result: 22 confirmed, 5 refined, 0 refuted.** §5 says why zero refutations is not the
reassuring number it looks like.

---

## 1. Confirmed

| # | Claim | Pattern / probe | Result |
|---|---|---|---|
| 1 | `MockCepikService` returns `LOOKUP_FAILED` unconditionally | `if ($$$) { $$$ }` over the file | **`[]` — no conditional of any kind.** Single `return CepikResult.withoutData(LOOKUP_FAILED, extracted.vin(), LOOKUP_URL)` |
| 2 | `CepikRiskAdjuster` early-returns unless `FOUND` | guard grep | `:73` exactly — `result == null \|\| cepik == null \|\| cepik.status() != CepikStatus.FOUND` |
| 3 | `ListingFetchService` has no `@Profile` | annotation grep | only `@Service` at `:22`. **Confirmed: the listing fetch is unprofiled** |
| 4 | `AnalysisResponse.ok` / `.text` have no callers | `AnalysisResponse.ok($$$)`, `AnalysisResponse.text($$$)` over main **and** test | both `[]`. Declared at `:11` and `:15`; `urlFailed` at `:19` *is* called (`AnalysisController.java:57`) |
| 5 | `cepik` fan-in is 1 | `$X.enrich($$$)` over main | exactly two matches — `cepikEnrichmentService.enrich` at `AnalysisController.java:83`, `marketPriceEnrichmentService.enrich` at `:89`. One caller each |
| 6 | `ExtractedData` has 21 construction sites | `new ExtractedData($$$)` | **3 main + 18 test = 21.** Exact |
| 7 | `MarketPriceContext` has 11 | `new MarketPriceContext($$$)` | **6 main + 5 test = 11.** Exact |
| 8 | `new CepikResult(...)` has 5 | `new CepikResult($$$)` | **2 main + 3 test = 5.** Exact |
| 9 | `fetchStatus` is a raw Java `String` | record header | `AnalysisResponse.java:4` — `String fetchStatus`, against `analysis.models.ts:145`'s 4-member union |
| 10 | no persistence anywhere | `@Entity`, `interface $R extends JpaRepository<$$$>` | both `[]` |
| 11 | `@JsonIgnoreProperties(ignoreUnknown = true)` on all 6 parser DTOs | count vs record count | 6 and 6 |
| 12 | only two async constructs, no concurrency | `CompletableFuture.$M($$$)`, `Thread.sleep($$$)`, `@Async`, `Executors.$M($$$)` | `CompletableFuture` only at `ListingFetchService.java:71-78` (a DNS timeout), `Thread.sleep` only at `OpenRouterAnalysisService.java:428`. **`@Async` and `Executors` both `[]`** |
| 13 | `MileageStamp.date` nullable in Java, non-null in TS | both declarations | `record MileageStamp(String date, Integer mileageKm)` vs `date: string`. The mismatch is exactly on `date`; `mileageKm` is nullable on both sides |
| 14 | `ManualListing.priceCurrency` is never populated | read `draftToRequest` | builds **8 keys** — make, model, year, priceAmount, mileageKm, fuel, transmission, notes. No `priceCurrency`, and `VehicleDataDraft` has no such field to send |
| 15 | enrichment containment is `catch (RuntimeException)` in the caller | read `degradeOnThrow` | `AnalysisController.java:146`, generic over `T`, supplier-based, logs stage + exception type + message |
| 16 | the three verdict labels are triplicated | grep three files | byte-identical switch arms at `CepikRiskAdjuster.java:248-252` and `MockAiAnalysisService.java:58-62`, same strings inline at `AnalysisPrompt.java:57`. Enforcement is the comment at `CepikRiskAdjuster.java:246` |
| 17 | `analysis.service.spec.ts`'s error tests are invertible | read both tests | both put their only `expect` inside the `error` callback, neither has `expect.assertions(n)`. **Confirmed as stated** |
| 18 | `VerdictCode` switch is exhaustive; the other four have `default` arms | switch/default grep | `:35` on `verdict.code` has **no** `default` (compiler-checked over the union); `:63`, `:76`, `:87`, `:98` each have one at `:70`, `:81`, `:92`, `:103` |
| 19 | `.expand-link` could be deleted with the suite green | repo-wide grep | exactly one spec reference — `analysis-result.component.spec.ts:105`, the `toBeNull()` query. No positive assertion anywhere for the button at `analysis-result.component.html:132` |
| 20 | `cepik-result.component` is referenced by no spec | grep 8 member names across all specs + e2e | **zero hits** |
| 21 | the spec census is exactly 6 files | `find` | 4 unit + 2 e2e. **No** `cepik-result`, `vehicle-data-form`, or `vehicle-data` spec |
| 22 | the 295 s budget is asserted on purpose | read the assertion | `RequestTimeoutBudgetTest.java:180` — `.isEqualTo(Duration.ofSeconds(295))`, with `:185`'s description reading *"the configured budget exceeds prd.md:98 by roughly 10x, and no deadline enforces it"* |

Two extra probes, run because they were cheap and load-bearing:

- **`vinError`'s I/O/Q branch is untested.** The branch is at `vehicle-data.ts:89-91`, returning
  *"VIN nie może zawierać liter I, O ani Q — sprawdź, czy to nie 1 lub 0."* Grep for that message
  and its distinctive fragments across every spec: **zero hits.** Confirmed.
- **`MockMarketPriceEnrichmentService` is input-independent.** It ignores `extracted` entirely and
  returns hardcoded values. Confirmed — with the one caveat that `Instant.now()` still moves, so
  "constant" is true of every field but `fetchedAt`.

---

## 2. Refined

Five claims were right in substance and wrong in a number or a line. Each is corrected in
`research.md` in place.

1. **`CepikResult.withoutData` absorbs 13 call sites, not 14.** 5 main + 8 test. The point holds
   — a 3-arg factory standing in front of a 21-component record is why `CepikResult` is the
   cheapest shape in the repo to extend — but the figure was off by one.

2. **The parser's frontend-constant comment is at `AnalysisResponseParser.java:161`, not
   `:176-178`.** `CepikRiskAdjuster.java:126` was right. Both comments say "the frontend shows
   only the first four flags"; the constant they encode is `slice(0, 4)` at
   `analysis-result.component.ts:47`. Claim confirmed, one citation wrong.

3. **`"szkoda-istotna"` appears at three production sites, not two.** `HistoriaPojazduParser.java`
   spells it **twice** — once as the `DAMAGE_EVENT_TYPE` constant at `:41` and again as a raw
   literal inside the `KNOWN_EVENT_TYPES` allow-list at `:61` — plus
   `cepik-result.component.html:193`. So the duplication is not only cross-stack: the file that
   owns the constant does not use its own constant in its own allow-list. That makes the
   vocabulary-drift failure mode slightly worse than reported, because a rename has to be made in
   three places and only one of them is named like a constant.

4. **`fetchStatus` literals are produced at five sites, not three.** `AnalysisController.java:55`
   (`"ok"`), `:66` (`"manual"`), `:70` (`"text"`) — *and* `AnalysisResponse.java:12` and `:16`,
   inside the two dead factories from confirmation #4. So the two findings compound: the dead
   factories are not merely unreachable, they are a second uncompiled spelling of a status string
   that already has no type behind it.

5. **The null-`fetchedAt` defect is `market`-only, and `cepik` handles the same case
   deliberately.** `MarketPriceFetchService.missing()` (`:154-157`) and `failed()` (`:159`) both
   pass `null` for `fetchedAt`, against `analysis.models.ts:76`'s non-nullable `fetchedAt: string`.
   But `CepikResult.withoutData` **stamps `Instant.now()`** at `:66` — so `CepikResult.fetchedAt`
   is never null even on a degraded path. Two packages, the same degraded-path question, opposite
   answers, one TS type declaring both non-null. The `cepik` side is right and the `market` side
   is the defect; a claim that the pattern was general would have been wrong.

---

## 3. Refuted

**None.** No structural claim in `research.md` was contradicted by a pattern.

---

## 4. Claims not verifiable this way

Recorded so the verification's scope is not overread. `ast-grep` matches syntax; it cannot decide
these:

- **Everything in `research.md` §4.1's right-hand column** — "fails if inverted" is a claim about
  what an assertion *means*, not about shape. The four `NO` rows are structurally verified
  (confirmation #20: no spec references the component at all), but the seven `Yes` rows rest on
  reading assertion bodies.
- **The reachable business-rule inversion under `mock` (§2.2).** The two code sites are real and
  readable; that a listing text exists which reaches both is an argument about the conjunction of
  two conditions, not a pattern.
- **The 164-item branch inventory.** Counted by reading.
- **The ~87-field contract comparison (§3.1).** A pattern can enumerate record components and TS
  properties, but "these two names mean the same field" is a judgement. Nothing here substitutes
  for a schema differ, which is the gap `research.md` §8.6 records.
- **Every cost estimate in §5.1 and every trap in §5.2.** Construction-site *counts* are verified
  (confirmations #6-#8); what a change would cost is not a syntactic property.

---

## 5. What zero refutations does and does not mean

It is tempting to read 22/5/0 as the research being accurate. A narrower reading is the honest
one.

**What it does mean:** none of the three agents invented a code site. Every file, method and guard
they cited exists and does what they said. Given that all three worked by reading files rather
than by recall, that is the expected outcome rather than a surprising one — the verification was
never likely to catch a fabricated *claim*.

**What it caught instead is a different error class, and all five instances are the same one:
counts and line numbers drift.** Two off-by-small counts, two wrong or incomplete citation sets,
and one over-generalisation. Four of the five became visible only because a pattern counts
exhaustively where a reader stops at the first few matches — which is the actual argument for
this step. The over-generalisation (#5) is the most valuable of the five, because it inverts a
recommendation: someone reading the unverified claim would have "fixed" `CepikResult` too, and
`CepikResult` was already right.

**What it does not mean:** that the analysis is complete. §4 lists five whole categories the
patterns could not touch, including the entire coverage-quality argument, which is the part of
`research.md` with the sharpest conclusions. The verification raises confidence in the *map* of
the flow and says almost nothing about the *judgements* built on it.

One structural gap worth naming: **`ast-grep` cannot parse Angular HTML templates.** Every claim
about `cepik-result.component.html` — the three `damageState` arms, the three status disclaimers,
the `szkoda-istotna` highlight — was verified by grep, not by an AST pattern. That is the same
blind spot `repo-map.md` §7.3 records for dependency-cruiser, hit again by a different tool. The
one enforcement point of the repo's hardest business rule sits in the one layer no tool in this
project can parse.
