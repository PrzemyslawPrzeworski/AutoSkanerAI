---
title: Anti-corruption layer — isolating the one external dependency two features share
created: 2026-09-09
type: refactor-plan
git_commit: 664857b
---

> Third of three M4-L5 artifacts. Upstream: `01-domain-distillation.md` (ubiquitous
> language, subdomains, the MODEL-vs-KOD table), `02-invariant-aggregate-refactor.md`
> (the `Analysis` aggregate guarding INV-1). This document is a **plan**. No
> production code is modified by it.

---

## KROK 0 — Context

**Stack.** Spring Boot 4.0.6 on Java 21 (Maven), Angular 21.2 on the front. No
database. 235 backend tests in 25 classes, 51 frontend tests in 5 spec files, one
Playwright contract spec. Backend port 10000.

**Layers, as they actually are:**

| Layer | Where | Count |
|---|---|---|
| HTTP in | `analysis/AnalysisController`, `common/GlobalExceptionHandler` | 2 |
| Wire records | `analysis/*.java` records, `analysis/dto/` | ~14 |
| Orchestration + rules | `analysis/` (`UserOverrides`, `CepikRiskAdjuster`, `AnalysisResponseParser`) | 32 files in `analysis/` incl. subdirs |
| Ports + adapters | `analysis/llm/` 8, `cepik/` 8, `market/` 7 | 23 |
| Shared | `common/` | 3 |

**There is no domain layer and no persistence layer.** Part 2 says why that
matters for invariants; here it matters for a different reason — with no
repository and no schema, *every* external dependency is reached from the same
place its result is consumed. Nothing structural stands between a library and the
records that model the business.

**The document that declares a component swappable** — this is the axis-(c)
evidence KROK 2 asks for, and this repo has an unusually strong version of it.
Three separate declarations:

1. Root `CLAUDE.md`: *"The AI layer, CEPiK and market price all use the same
   shape: a Spring interface with a mock bean under the `mock` profile and a real
   bean under `@Profile("!mock")`. **Add a fourth integration the same way.**"*
   The pattern is named as the project's standard for external dependencies.
2. `context/archive/2026-06-02-market-price-context/research.md:184-186` — a
   three-row options table whose two losing rows are labelled, verbatim,
   **"Fallback if Jina unreliable"** (Parse.bot) and **"Fallback for
   bulk/scheduled runs"** (Apify). The dependency is not merely *assumed*
   replaceable; two named replacements were priced and written down.
3. The same file, `:208`, is the architectural insight that authorised the code
   as it stands: *"**The Jina layer is already an abstraction.** `ListingFetchService`
   wraps Jina behind a `FetchResult` return type. A `MarketPriceFetchService` can
   be an identical sibling — **same `RestClient.Builder` bean, same Jina prefix**,
   different URL input."*

Row 3 is the one to hold on to. A document from June declared the dependency
abstracted and, in the next clause, instructed the second consumer to copy its
prefix into another package. Both halves were acted on. That is the divergence
this document is about: **intent said "wrapped", implementation said "duplicated",
and the same paragraph said both.**

**What a 3-week solo MVP makes degenerate here.** Two of the prompt's leak
signals cannot fire in this repo and I am recording that rather than skipping the
step. There is no persistence, so no library type can leak into a table or an ORM
mapping. And the client/server boundary is REST between two separately-built
apps with zero shared code, so no SDK can be called on both sides of it — the
frontend's only third-party runtime dependency is PrimeNG, and no backend
dependency appears in `frontend/package.json` at all. The leaks that remain are
therefore all *within* the backend, plus one contained frontend case (§1, L6).

---

## KROK 1 — Leaking external dependencies, measured

Measured over `backend/src/main` and `frontend/src` by import and by literal, not
estimated. Every file:line below was read.

### L1 — Jina Reader (the external HTTP service both fetch paths run through)

Not a package, so no import-based tool sees it. Its knowledge is a URL prefix, an
encoding rule, a header set, a timeout pair and a response-shape quirk.

| Knows it | Where | What it knows |
|---|---|---|
| `analysis/ListingFetchService.java` | `:27-30` | The prefix `https://r.jina.ai/` as `static final String JINA_PREFIX`, plus a 3-line comment on JS rendering, Cloudflare and Zscaler |
| | `:104-107` | *Why* the embedded URL is percent-encoded (query strings and fragments would otherwise be attributed to the Jina URL) |
| | `:108` | `JINA_PREFIX + URLEncoder.encode(rawUrl, StandardCharsets.UTF_8)` |
| | `:111-114` | `client.get().uri(java.net.URI.create(jinaUrl))…` |
| | `:115-123` | Jina's failure modes mapped to reasons: `"timed out"` in the message → `timeout`, anything else → `blocked` |
| | `:125-128` | Empty body → `empty_content` |
| | `:3`, `:130-132` | `org.jsoup.Jsoup`, used *only* to clean up the case where Jina returns HTML instead of text |
| `analysis/ListingFetchConfig.java` | `:19-20` | *"Jina Reader renders JavaScript before responding — needs up to 30 s."* — `READ_TIMEOUT` justified by the vendor |
| | `:22-32` | The bean `listingFetchBuilder`, its `SimpleClientHttpRequestFactory` timeouts, and three default headers incl. `X-No-Cache: true` |
| `market/MarketPriceFetchService.java` | `:28` | **A second, independent `private static final String JINA_PREFIX = "https://r.jina.ai/"`** |
| | `:36` | `@Qualifier("listingFetchBuilder")` — a market service injecting a bean named for listings |
| | `:119` | The same `JINA_PREFIX + URLEncoder.encode(...)` |
| | `:121-125` | The same `.uri(java.net.URI.create(jinaUrl))` |
| | `:116`, `:126-133` | Jina's failures collapsed to `return null`, with no distinction between timeout and block |

Test- and doc-side knowledge, which is where the cost of a swap actually lands:

- `market/MarketPriceFetchServiceTest.java:110, 131, 147, 218, 275` — five
  `requestTo(containsString("r.jina.ai"))` assertions inside a *market-price* test.
- `analysis/ListingFetchServiceTest.java:37` (a `jinaUrl` helper) and `:42, 44,
  60, 71, 72, 83, 84, 95, 96`.
- `market/MarketPriceFetchServiceLiveTest.java:13, 22, 29` — `:29` constructs
  `new ListingFetchConfig().listingFetchBuilder()` by hand.
- `analysis/RequestTimeoutBudgetTest.java:85-86, 94-95, 161-167` — `:94-95` is a
  comment that exists purely to explain the borrowed bean: *"The market-price
  fetch shares `listingFetchBuilder` … so it inherits 5 / 30 rather than owning a
  pair."*
- `backend/src/test/resources/market/README.md:23` — the captured request,
  recording the encoded form as fact.
- `backend/CLAUDE.md:68, 70-73, 87, 88, 90, 126`; root `CLAUDE.md:119`;
  `context/map/repo-map.md:65`; `context/foundation/test-plan.md:458`;
  `context/changes/analysis-flow-analysis/research.md:50, 135`.

**Total in `src/main`: 3 files across 2 packages. Total including tests and
resources: 8 files. Including documents: 14.**

### L2 — `RestClient` (Spring's own HTTP client)

9 files across 4 packages: `analysis/{AnalysisController (javadoc only, :130),
ListingFetchConfig, ListingFetchService}`, `analysis/llm/{OpenRouterAnalysisService,
OpenRouterConfig}`, `cepik/{HistoriaPojazduConfig, HistoriaPojazduService,
HistoriaPojazduSession}`, `market/MarketPriceFetchService`. The widest spread of
any single type. It is also the framework's own client, arriving with
`spring-boot-starter-webmvc` — see KROK 2 for why widest ≠ worst.

### L3 — AWS SDK for Bedrock

2 files: `analysis/llm/BedrockClaudeAnalysisService.java:10-11`,
`analysis/llm/BedrockConfig.java:7-10`. Both sit behind `AiAnalysisService`.
Already isolated, and the proof that the declared pattern works when applied.

### L4 — jsoup

1 file: `analysis/ListingFetchService.java:3`. Contained — but filed in the wrong
place. Its sole use (`:130-132`) is a workaround for one vendor's response
variance, so it is a detail of L1, not of listing-fetching.

### L5 — Jackson

1 file by import: `analysis/llm/AnalysisResponseParser.java:4-5`. That file
straddles two major versions in two adjacent lines —
`com.fasterxml.jackson.annotation.JsonIgnoreProperties` (Jackson 2 coordinates)
and `tools.jackson.databind.ObjectMapper` (Jackson 3). Worth recording for a
different reason too: Spring's HTTP message conversion serialises every wire
record through Jackson *without any record importing it*, so an import-count of 1
understates a Jackson upgrade's blast radius. A grep-based success criterion
would read clean here and still be wrong. Noted, not chosen.

### L6 — PrimeNG (frontend)

5 files, 12 import lines: `app/app.config.ts:4-5` (`providePrimeNG`, `Aura`),
`analyzer.component.ts:3-7`, `analysis-result.component.ts:3-5`,
`vehicle-data-form.component.ts:2-4`. Its severity vocabulary reaches into method
*return types*: `analysis-result.component.ts:75`
(`'danger' | 'warn' | 'info'`) and `:97` (`'success' | 'danger' | 'secondary'`).
`cepik-result.component` and `market-price-panel.component` import none of it, so
four sibling components are styled two different ways.

### L7 — the inverted leak (internal, same shape one level in)

Not an external dependency, but the identical failure and it must be recorded:
domain records live in `analysis` while their adapters live in `cepik`/`market`,
producing two package cycles.

- `analysis → cepik` (`AnalysisController.java:3`) vs `cepik → analysis` in 6
  files (`CepikEnrichmentService.java:3-4`, `HistoriaPojazduParser.java:3-7`,
  `HistoriaPojazduService.java:3-4`, `MockCepikService.java:3-5`,
  `RealCepikEnrichmentService.java:3-6`).
- `analysis → market` (`AnalysisController.java:4-5`,
  `MarketPriceContext.java:3-4`) vs `market → analysis` in 4 files.
- The sharpest instance: **`analysis/MarketPriceContext.java:3-4` — a record in
  the domain package — imports two enums from its own adapter package.**

---

## KROK 2 — Classification, and the choice

| # | Leak | (a) layers / files | (b) risk & cost of swapping today | (c) declared swappable? |
|---|---|---|---|---|
| **L1** | **Jina Reader** | **3 main files, 2 packages; 8 files with tests; 14 with docs** | **High.** Two prefixes must change together; two different error vocabularies must be reconciled; 15 test assertions name the vendor; the HTML fallback exists on one of two paths | **Yes, three times** — root `CLAUDE.md`'s port pattern, and `market-price-context/research.md:184-186` naming two priced replacements, and `:208` calling it "already an abstraction" |
| L2 | `RestClient` | 9 files, 4 packages | Low value. Swapping Spring's client is not a scenario anyone has; it is not an integration, it is the framework | No |
| L3 | AWS Bedrock SDK | 2 files, 1 package | Low. Behind `AiAnalysisService`, with a second implementation (`OpenRouter…`) proving the port already carries two vendors | Yes — and honoured |
| L4 | jsoup | 1 file | Low, but misfiled: it belongs to L1 | No |
| L5 | Jackson | 1 file by import, all wire records implicitly | Medium, and *invisible to grep* — the criterion in KROK 6 cannot measure it | No |
| L6 | PrimeNG | 5 files, 12 imports, 2 return types | Medium, contained to the frontend, and half the components already avoid it | No |
| L7 | inverted leak (2 cycles) | 11 files | Medium. `repo-map.md` §3 already records the cycles as `[no tool]` | Partially — the port pattern implies the direction it violates |

**The worst leak is L1, Jina Reader.** Four reasons, in order of weight:

1. **It is the only dependency where intent and code diverge in writing.** L2, L4,
   L5, L6 were never declared swappable, so they cannot diverge from a
   declaration. L3 was declared swappable and *is*. L1 was declared swappable —
   with two named, priced replacements — and is the single integration that has
   no interface, no mock bean, and no `@Profile`. `ListingFetchService.java:22-23`
   is a bare `@Service` with no profile at all. The one external service on the
   critical path of two features is the one the project's own standard pattern
   was never applied to.
2. **It is the only leak with a literally duplicated constant.** `JINA_PREFIX =
   "https://r.jina.ai/"` exists twice, at `ListingFetchService.java:30` and
   `MarketPriceFetchService.java:28`, in two packages, byte-identical, with
   identical `URLEncoder.encode` and `URI.create` construction following each.
   The prompt's "duplicated reconstruction of a library object" signal is not
   inferred here; it is a copy.
3. **It is upstream of two of the three data sources.** `repo-map.md:65` draws
   `mkt --> ext3[/"Jina → Otomoto"/]`, and `backend/CLAUDE.md:68` records the
   listing path. If Jina goes down or a policy blocks it, the listing text *and*
   the market range fail — and there is no single place to put a retry, a cache,
   a fallback, or a circuit breaker. `market-price-context/research.md:237`
   already identified caching as the mitigation for Otomoto rate-limiting; it has
   nowhere to live.
4. **The docs have already stopped treating its failure as a defect.**
   `backend/CLAUDE.md:73`: *"Dev machines behind corporate proxies (e.g. Zscaler)
   will see `url_failed` — this is a network constraint, not a bug"*, and `:126`:
   *"`r.jina.ai` may still be blocked by proxy policy (403 interstitial, category
   'General AI and ML Applications')"*. Both are accurate. Both also describe a
   dependency that cannot be substituted in the one environment where development
   happens. A port with a mock would make the local block a configuration choice
   rather than a documented fact of life.

**Runner-up: L7.** Same shape, internally, and it is a prerequisite-free cleanup.
It loses because a cycle is a maintainability cost while L1 is an availability
cost on the product's critical path, and because fixing L1 removes one edge of the
`analysis ↔ market` coupling as a side effect (§5.1).

**Not chosen, recorded:** L5's grep-invisibility (the Jackson 2 / Jackson 3
straddle at `AnalysisResponseParser.java:4-5` deserves its own change) and L6's
`'danger' | 'warn' | 'info'` in a method signature.

---

## KROK 3 — Diagnosis

### 3.1 The duplication, and what it costs today

The two call sites are the same six operations in the same order, written twice:

| Operation | Listing path | Market path | Same? |
|---|---|---|---|
| Hold the prefix | `ListingFetchService.java:30` | `MarketPriceFetchService.java:28` | Identical literal, two declarations |
| Encode the target | `:108` | `:119` | Identical expression |
| Build the URI | `:112` | `:123` | Identical expression |
| Timeouts + headers | `ListingFetchConfig.java:22-32` | `@Qualifier("listingFetchBuilder")` at `:36` | Shared **by borrowing**, not by owning |
| Classify a failure | `:115-123` → `timeout` \| `blocked` | `:126-129` → `null` | **Different** |
| Handle an HTML body | `:130-132` → `Jsoup.parse` | absent | **Different** |

The last two rows are the finding. One dependency, one contract, two behaviours:

- **The error vocabulary diverges.** A Jina read timeout on the listing path
  produces `FetchResult.failed("timeout")`, which the wire reports as a distinct
  reason; the same timeout on the market path produces `null` and then
  `FETCH_FAILED`, indistinguishable from Otomoto returning a page with no
  prices. The same upstream event is diagnosable on one path and not on the other.
- **The response-shape workaround exists on one path.**
  `ListingFetchService.java:130-131` says Jsoup is run *"in case it returns HTML
  for non-standard pages"* — that is a statement about **Jina**, not about
  listings. When Jina returns HTML for an Otomoto search page,
  `PRICE_PATTERN` (which matches `### 52 300\nPLN`, i.e. markdown) simply finds
  nothing, and the user gets "no market data" for a page that had prices on it.

### 3.2 The boundary crossing: a market service reaching into `analysis`

`MarketPriceFetchService.java:36` injects `@Qualifier("listingFetchBuilder")`.
Three consequences, all verifiable:

1. A package named `market` depends on a bean defined in `analysis`, by a string
   name that describes neither of them. The name is wrong for one of its two
   consumers by construction.
2. Changing `ListingFetchConfig.READ_TIMEOUT` silently changes the market
   fetch's timeout. The repo *knows* this and compensates with prose:
   `RequestTimeoutBudgetTest.java:94-95` exists to explain that market *"inherits
   5 / 30 rather than owning a pair"*, and `:167` does the budget arithmetic for
   market using `ListingFetchConfig`'s constants. A comment is standing in for a
   type.
3. `MarketPriceFetchServiceLiveTest.java:29` instantiates
   `new ListingFetchConfig().listingFetchBuilder()` directly — a market test
   constructing an analysis-package configuration class to get an HTTP client.

### 3.3 The declaration, quoted against the code

`context/archive/2026-06-02-market-price-context/research.md:208`, in full:

> *"**The Jina layer is already an abstraction.** `ListingFetchService` wraps Jina
> behind a `FetchResult` return type. A `MarketPriceFetchService` can be an
> identical sibling — same `RestClient.Builder` bean, same Jina prefix, different
> URL input (Otomoto search URL vs listing URL)."*

Read against §3.1, the first sentence is false in a specific way. `FetchResult`
wraps the *outcome*; it does not wrap the *vendor*. Nothing in `FetchResult.java`
mentions Jina — the vendor knowledge sits above it, in the service — so a second
consumer could not reuse the abstraction and was told, correctly given the code,
to reuse the *constant* instead. The sentence describes the design that was
wanted; the clause after it describes the design that was built. And `:185-186`
names Parse.bot and Apify as fallbacks "if Jina proves unreliable" — a swap that
today means editing two packages, reconciling two error vocabularies, and
rewriting 15 assertions in three test classes, five of which live in a test about
market prices.

One correction to carry forward: `backend/CLAUDE.md:88` cites this document as
`context/changes/market-price-context/research.md`. That path does not exist —
the change was archived to `context/archive/2026-06-02-market-price-context/`.
The decision is recorded; the pointer is stale. Fixing it is Phase 7.

### 3.4 Where the UI is the only guard, and where an error is swallowed

Both prompt-signals checked honestly, because neither fires the way it usually does:

- **No UI guard exists for this leak.** The frontend never sees a URL; it sends
  one and reads `fetchStatus` / `reason`. Failure rendering is
  `analysis-result.component.ts:113` (`str` → `'—'`) and the market panel's own
  empty state. Nothing in the UI compensates for the reader.
- **The market path swallows, the listing path does not.**
  `MarketPriceFetchService.java:116` documents it as intent — *"Returns null on
  network/fetch error, empty list if no prices found"* — and `:127-128` logs
  before returning. That is degradation, and `AnalysisController.java:88-91`
  wraps the whole call in `degradeOnThrow("market-price", …)` on purpose. It is
  correct that a missing price range does not fail an analysis. What is *not*
  correct is that the reason is erased on the way: three distinct upstreams
  (Jina timed out, Jina was blocked by policy, Otomoto had no matching listings)
  arrive at the same `MarketPriceStatus.FETCH_FAILED`, so
  `backend/CLAUDE.md:126`'s 403-interstitial scenario is invisible in production
  logs on the market path while being explicit on the listing path.

---

## KROK 4 — Design: the value objects, the narrow port, the adapter

New package `com.example.autoskaner_ai.reader`. Nine files; the vendor's name
appears in exactly one of them.

```
reader/
  PageReader.java          <- THE PORT (domain interface, 1 method)
  PageUrl.java             <- VO: a target the domain considers fetchable
  PageText.java            <- VO: rendered text + provenance
  ReadOutcome.java         <- sealed: Rendered | Unavailable
  ReadFailure.java         <- enum: TIMEOUT, BLOCKED, EMPTY, UNSUPPORTED_TARGET
  MockPageReader.java      <- @Profile("mock")
  jina/
    JinaTarget.java        <- VO: THE ONLY holder of Jina's URL shape
    JinaReaderConfig.java  <- timeouts + headers (moved from ListingFetchConfig)
    JinaPageReader.java    <- @Profile("!mock") adapter
```

### 4.1 The port — one method, no vendor in the signature

```java
public interface PageReader {
    /** Render a web page to text. Never throws for an upstream failure —
     *  an unreachable page is an outcome, not an exception. */
    ReadOutcome read(PageUrl target);
}
```

That is the whole port. It says nothing about HTTP, prefixes, encoding, headers,
timeouts, markdown or HTML. Both consumers need exactly this and nothing more:
the listing path needs "render the user's URL", the market path needs "render the
URL I built". The asymmetry between them — SSRF pre-checking a user-supplied host
— stays where it belongs, in `ListingFetchService`, because it is a rule about
*untrusted input*, not about the reader.

### 4.2 The domain value objects

```java
/** A URL the app is willing to fetch. Construction is the only validation point. */
public record PageUrl(URI value) {
    public PageUrl {
        if (value == null || !value.isAbsolute()) throw new IllegalArgumentException(...);
        if (!"http".equals(value.getScheme()) && !"https".equals(value.getScheme()))
            throw new IllegalArgumentException(...);
    }
    public static PageUrl of(String raw) { ... }   // rejects malformed input here, once
    public String host() { return value.getHost(); }   // what the SSRF check needs
}

/** Text rendered from a page, and which reader rendered it. */
public record PageText(String value, String renderedBy) {
    public PageText {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(...);
    }
}

public enum ReadFailure { TIMEOUT, BLOCKED, EMPTY, UNSUPPORTED_TARGET }

public sealed interface ReadOutcome {
    record Rendered(PageText text) implements ReadOutcome {}
    record Unavailable(ReadFailure reason) implements ReadOutcome {}
}
```

`ReadOutcome` being **sealed** is the load-bearing choice: a consumer that
switches on it cannot forget a failure mode, which is precisely what the market
path forgot. `renderedBy` on `PageText` is what makes a partial swap observable —
during a Parse.bot trial, a log line or a future provenance field can say which
reader produced a given analysis, which is the same honesty requirement
`prd.md:99` states for extracted-vs-inferred data.

### 4.3 The adapter's value object — the only file that knows Jina

```java
/** The single holder of knowledge about Jina Reader's request shape.
 *  Vendor contract: GET https://r.jina.ai/<target>, target percent-encoded. */
record JinaTarget(String requestUri) {
    private static final String PREFIX = "https://r.jina.ai/";   // the ONLY occurrence

    static JinaTarget wrapping(PageUrl target) {
        // Percent-encoded so a target's own query string and fragment are not
        // attributed to the Jina URL by the URI parser.
        //   (rationale preserved from ListingFetchService.java:104-107)
        return new JinaTarget(PREFIX + URLEncoder.encode(target.value().toString(), UTF_8));
    }

    URI toUri() { return URI.create(requestUri); }
}
```

Every literal, every encoding rule, every reason-for-encoding comment currently
duplicated at `ListingFetchService.java:104-108` and
`MarketPriceFetchService.java:119` collapses into these ~10 lines.

### 4.4 The adapter

```java
@Service
@Profile("!mock")
class JinaPageReader implements PageReader {

    private final RestClient client;   // built from JinaReaderConfig's own builder

    @Override
    public ReadOutcome read(PageUrl target) {
        String body;
        try {
            body = client.get().uri(JinaTarget.wrapping(target).toUri())
                         .retrieve().body(String.class);
        } catch (RestClientException e) {
            return new Unavailable(classify(e));       // was duplicated & divergent
        }
        if (body == null || body.isBlank()) return new Unavailable(EMPTY);
        return new Rendered(new PageText(detextify(body), "jina"));
    }

    /** Jina signals a read timeout in the message text, not a status. */
    private ReadFailure classify(RestClientException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("timed out") ? TIMEOUT : BLOCKED;
    }

    /** Jina normally returns markdown; it returns HTML for non-standard pages.
     *  (moved from ListingFetchService.java:130-132 — it is a fact about Jina) */
    private String detextify(String body) {
        return body.contains("<html") ? Jsoup.parse(body).text() : body;
    }
}
```

`JinaReaderConfig` takes `CONNECT_TIMEOUT = 5s`, `READ_TIMEOUT = 30s` and the
three default headers — including `X-No-Cache: true`, which is a Jina protocol
choice currently set in a file named for listings — from
`ListingFetchConfig.java:18-32`.

`MockPageReader` under `@Profile("mock")` returns a canned `Rendered` and closes
the gap named in KROK 2 reason 1: today the `mock` profile still performs a real
DNS lookup and a real GET to `r.jina.ai`
(`analysis-flow-analysis/research.md:135` records exactly this), which is why the
locally-blocked path is documented as a fact of life rather than switchable.

### 4.5 The two consumers become thin

`ListingFetchService` keeps SSRF and keeps ownership of the wire vocabulary:

```java
PageUrl target = PageUrl.of(rawUrl);          // malformed → url_failed, as today
if (!ssrfCheck.allows(target.host())) return FetchResult.failed("blocked");

return switch (reader.read(target)) {
    case Rendered(PageText t)      -> FetchResult.ok(t.value());
    case Unavailable(TIMEOUT)      -> FetchResult.failed("timeout");
    case Unavailable(BLOCKED)      -> FetchResult.failed("blocked");
    case Unavailable(EMPTY)        -> FetchResult.failed("empty_content");
    case Unavailable(var other)    -> FetchResult.failed("url_failed");
};
```

`MarketPriceFetchService.fetchPrices` loses its prefix, its encoder, its URI
construction and its `@Qualifier`:

```java
return switch (reader.read(PageUrl.of(otomotoUrl))) {
    case Rendered(PageText t)   -> extractPrices(t.value());
    case Unavailable(var reason) -> { log.warn("market read failed reason={} url={}",
                                               reason, otomotoUrl); yield null; }
};
```

Note what that log line fixes: the reason is now in the record. §3.4's erasure of
the 403-interstitial case ends without changing `MarketPriceStatus` or the wire.

**Where the vendor-contract decisions get encoded — in the ACL, not the API
layer.** Four open questions, three answerable from repo evidence:

| Question | Resolution | Encoded in |
|---|---|---|
| Percent-encode the target, or pass it raw? | **Encode, always, once.** Both call sites already do (`:108`, `:119`); `ListingFetchService.java:104-107` gives the reason; `test/resources/market/README.md:23` records the captured request in encoded form | `JinaTarget.wrapping` |
| Is the HTML fallback the reader's business or the listing's? | **The reader's.** `ListingFetchService.java:130-131` describes Jina's behaviour, not a listing's | `JinaPageReader.detextify` |
| Is `X-No-Cache: true` a Jina protocol choice or a listing-fetch policy? | **Jina's.** It is a cache-control instruction to the reader, and market inherits it today only by accident of the borrowed bean | `JinaReaderConfig` |
| Does an `Authorization` bearer raise Jina's rate limit, and can a response format be requested explicitly? | **Unresolved — needs the vendor's documentation, and I will not guess it.** Recorded as ACL-local: whatever the answer, it changes `JinaReaderConfig` and nothing else, which is the point | `JinaReaderConfig` (deferred) |

---

## KROK 5 — Proof of isolation, before/after, and the phased plan

### 5.1 Swapping the reader touches only the adapter — the list

Replacing Jina with Parse.bot (`research.md:185`) after this refactor:

| Would it change? | Verdict | Why |
|---|---|---|
| Database tables / migrations | **No** | There is none. Recorded so the claim is not read as stronger than it is |
| `AnalysisResponse` and every wire record | **No** | `PageReader` returns `PageText`; only `FetchResult`'s existing strings reach the wire |
| `analysis.models.ts` and the frontend | **No** | No field added, renamed or retyped — the same constraint Part 2 §5.1 imposes, for the same reason: `repo-map.md` §3 records the REST mirror as hand-written with no tool behind it |
| `AnalysisController` | **No** | Never sees a reader; `degradeOnThrow` at `:82-91` is unchanged |
| `ListingFetchService` | **No** | Depends on `PageReader`; SSRF and the wire strings are its own |
| `MarketPriceFetchService` | **No** | `PRICE_PATTERN` reads text, not a vendor |
| `PageUrl` / `PageText` / `ReadOutcome` / `ReadFailure` | **No** | Vendor-free by construction |
| `reader/jina/**` | **Replaced** — 3 files | The whole cost of the swap |
| `application-*.properties` | One key, if the new reader needs one | Additive |

Today the same swap edits `ListingFetchService`, `ListingFetchConfig`,
`MarketPriceFetchService`, three test classes, a fixture README and a
timeout-budget test that reasons about market using an analysis-package constant.

### 5.2 Before / after, per duplicated site

| Site | Before | After |
|---|---|---|
| `ListingFetchService.java:30` | `static final String JINA_PREFIX` | deleted |
| `ListingFetchService.java:27-29` | 3-line Jina/Cloudflare/Zscaler comment | moves to `JinaPageReader` |
| `ListingFetchService.java:3, 130-132` | `org.jsoup.Jsoup` + HTML fallback | moves to `JinaPageReader.detextify` — **`analysis` keeps no third-party import at all** |
| `ListingFetchService.java:104-114` | encode + `URI.create` + `RestClient` call | `reader.read(target)` |
| `ListingFetchService.java:115-128` | message-sniffing → 3 reason strings | a `switch` over `ReadOutcome`; the sniffing moves to `classify` |
| `ListingFetchConfig.java:18-32` | timeouts + 3 headers + `listingFetchBuilder` | becomes `JinaReaderConfig`; the misnamed bean disappears |
| `MarketPriceFetchService.java:28` | the second `JINA_PREFIX` | deleted |
| `MarketPriceFetchService.java:36` | `@Qualifier("listingFetchBuilder")` | `PageReader` injected — **one edge of the `analysis ↔ market` coupling gone** |
| `MarketPriceFetchService.java:119-133` | encode + call + `return null` ×2 | `switch`, with the reason logged |
| `MarketPriceFetchServiceTest.java:110,131,147,218,275` | 5 × `containsString("r.jina.ai")` in a *market* test | a stubbed `PageReader`; the vendor assertions move to `JinaTargetTest` |
| `ListingFetchServiceTest.java:37,42-96` | a `jinaUrl` helper + 9 uses | a stubbed `PageReader`; SSRF cases keep their shape |
| `MarketPriceFetchServiceLiveTest.java:29` | `new ListingFetchConfig().listingFetchBuilder()` | `new JinaReaderConfig().jinaReaderBuilder()` |
| `RequestTimeoutBudgetTest.java:94-95` | a comment explaining the borrowed bean | **deleted** — market owns `JinaReaderConfig`'s pair explicitly |
| `analysis.models.ts` | — | **unchanged. No wire field is added, renamed or retyped in any phase** |

### 5.3 Phases

**Prerequisite: none.** This is independent of the committed
`context/changes/refactor-opportunities/plan.md` (which touches the cepik and LLM
mocks, not the reader) and of Part 2's `Analysis` aggregate. If all three run,
**this one goes first**: it removes jsoup and the last vendor knowledge from the
`analysis` package, which is the package the aggregate lands in.

| Phase | Delivers | Key risk |
|---|---|---|
| 1 | `PageUrl`, `PageText`, `ReadOutcome`, `ReadFailure`, `PageReader` + unit tests. Purely additive; no call site changed | `PageUrl.of` must reject exactly what `ListingFetchService` rejects today, no more — a stricter VO turns a working URL into `url_failed` |
| 2 | `JinaTarget`, `JinaReaderConfig`, `JinaPageReader` + `JinaPageReaderTest` (`MockRestServiceServer`), with the vendor assertions ported from the two existing suites | `classify` must reproduce the message-sniffing at `:116-122` exactly; a Spring version that changes exception text silently reclassifies timeouts as blocks |
| 3 | `ListingFetchService` delegates; its 10 Jina-aware test lines become a stubbed `PageReader`; SSRF and all four wire reason strings unchanged | The wire is the contract — `url_failed` / `timeout` / `blocked` / `empty_content` must map 1:1 |
| 4 | `MarketPriceFetchService` delegates; `JINA_PREFIX` and `@Qualifier` deleted; the 5 `containsString` assertions relocated | **The one deliberate behaviour change:** market gains the HTML fallback. A previously-unmatched HTML body can now yield prices. Needs its own test and a line in `test-plan.md` |
| 5 | `MockPageReader` under `mock`; the `mock` profile stops calling `r.jina.ai` | Both Playwright specs and all three quality gates run `mock` — the canned text must keep them green, and `repo-map.md`'s "the `mock` profile is a different program" warning applies to the change itself |
| 6 | `ListingFetchConfig` retired; `RequestTimeoutBudgetTest:85-95,161-167` renamed onto `JinaReaderConfig`; the compensating comment at `:94-95` deleted | The 30 s budget arithmetic must still total the same, or the PRD's 30 s NFR check moves under us |
| 7 | Docs: `backend/CLAUDE.md:68-73,88,90,126`; `repo-map.md:65` + §3/§4; `test-plan.md` §2/§7; root `CLAUDE.md`'s "add a fourth integration" (there are now four, and the fourth is the reader). Fix the stale link at `backend/CLAUDE.md:88` → `context/archive/2026-06-02-market-price-context/research.md` | Counts and paths must be read off a run, not grepped — `@Test` reads 212 against a real 235 |

One commit per phase, green only; no deliberate break is ever committed.

### 5.4 Test cases, written before the code

Legal:

1. `PageUrl.of("https://www.otomoto.pl/oferta/x?utm_source=a&id=42")` preserves
   the query string end-to-end — the regression `:104-107` was written against.
2. `JinaTarget.wrapping` produces `https://r.jina.ai/` + a percent-encoded
   target, asserted against `test/resources/market/README.md:23` verbatim.
3. A markdown body returns `Rendered` with `renderedBy == "jina"` and the text
   untouched.
4. An HTML body (`<html>…`) returns `Rendered` with tags stripped — asserted on
   **both** consumers, which is Phase 4's behaviour change made explicit.
5. `ListingFetchService` maps all four `ReadFailure` values to the four existing
   wire strings; a table-driven test, one row per value.
6. `MarketPriceFetchService` given a `Rendered` body of captured Otomoto markdown
   still extracts the same min/median/max as today (existing fixture, no new one).

Illegal / degraded:

7. A `RestClientException` whose message contains `"Read timed out"` →
   `Unavailable(TIMEOUT)`; any other message → `Unavailable(BLOCKED)`. Both
   asserted, because today only the listing path distinguishes them.
8. A blank or null body → `Unavailable(EMPTY)`, and `PageText` cannot be
   constructed blank — the invariant is in the type, not in a caller's `if`.
9. `PageUrl.of("javascript:alert(1)")` and `PageUrl.of("file:///etc/passwd")`
   throw — the scheme allow-list is a construction-time rule, so no consumer can
   skip it.
10. `Unavailable(BLOCKED)` on the market path logs a reason. The assertion is
    that the reason is *present*, which is §3.4's erasure closed.
11. **The grep criterion as a test** — see KROK 6.

### 5.5 Load-bearing names to register

`repo-map.md` §3 records that this repo's contracts have no tool behind them, so
new names must be written down or they drift.

**Introduced:** `PageReader`, `PageUrl`, `PageText`, `ReadOutcome`
(`Rendered` / `Unavailable`), `ReadFailure` (`TIMEOUT` / `BLOCKED` / `EMPTY` /
`UNSUPPORTED_TARGET`), `JinaTarget`, `JinaPageReader`, `JinaReaderConfig`,
`MockPageReader`, and the ubiquitous-language term **"reader"** — which
`01-domain-distillation.md` KROK 1 does not contain, because the domain never had
a word for the thing two of its three data sources depend on.

**Retired:** `JINA_PREFIX` (both copies), `ListingFetchConfig`,
`listingFetchBuilder`.

**Registration targets:** `test-plan.md` §2 (a Jina outage becomes one risk with
one mitigation instead of two paths degrading differently), §7 (the HTML-fallback
behaviour change), §8 (the freshness ledger); `repo-map.md` §3 and the Mermaid
graph at `:65`; `backend/CLAUDE.md` §"Listing fetch" and §"Market price"; root
`CLAUDE.md`'s integration-pattern paragraph.

---

## KROK 6 — Verification criterion

**The criterion, as a command:**

```bash
grep -rn -i "jina" backend/src/main --include=*.java
```

**Today** it returns **13 lines in 3 files across 2 packages** (counted, not
estimated):

| File | Lines | n |
|---|---|---|
| `analysis/ListingFetchService.java` | `:27`, `:30`, `:104`, `:107`, `:108`, `:112`, `:118`, `:121`, `:130` | 9 |
| `analysis/ListingFetchConfig.java` | `:19` | 1 |
| `market/MarketPriceFetchService.java` | `:28`, `:119`, `:123` | 3 |

Two of those lines are log messages (`:118`, `:121`) that name the vendor as a
`stage=jina` field — which is useful today precisely *because* the vendor is not
isolated, and becomes the adapter's own log line after Phase 2.

**After Phase 6** it must return lines only under
`backend/src/main/java/com/example/autoskaner_ai/reader/jina/`. The narrower form
is sharper still:

```bash
grep -rn "r\.jina\.ai" backend/src/main   # must return exactly ONE line: reader/jina/JinaTarget.java
```

Because a grep is a check a human forgets, Phase 6 adds it as **test case 11** —
an `ArchitectureBoundaryTest` walking `backend/src/main`, asserting that no file
outside `reader/jina/` contains the string `jina` (case-insensitive) and that no
file outside `reader/jina/` imports `org.jsoup`. That is the same trick
`RequestTimeoutBudgetTest` already uses for the timeout budget: a rule nobody can
violate quietly.

### Which files know the dependency today, and which will not after

| File | Today | After |
|---|---|---|
| `analysis/ListingFetchService.java` | prefix, encoding, URI, error classification, HTML fallback, jsoup | **nothing** |
| `analysis/ListingFetchConfig.java` | vendor-justified timeouts, headers, misnamed bean | **file gone** |
| `market/MarketPriceFetchService.java` | prefix, encoding, URI, borrowed bean | **nothing** |
| `analysis/ListingFetchServiceTest.java` | `jinaUrl` helper + 9 uses | **nothing** (stubs `PageReader`) |
| `market/MarketPriceFetchServiceTest.java` | 5 vendor assertions | **nothing** (stubs `PageReader`) |
| `analysis/RequestTimeoutBudgetTest.java` | the borrowed-bean comment | names `JinaReaderConfig`; comment deleted |
| `market/MarketPriceFetchServiceLiveTest.java` | builds the analysis-package config | names `JinaReaderConfig` |
| `test/resources/market/README.md` | the captured encoded request | unchanged — it is a verbatim capture and stays one |
| `reader/jina/JinaTarget.java` | — | **the prefix and the encoding rule, once** |
| `reader/jina/JinaPageReader.java` | — | error classification, HTML fallback, jsoup |
| `reader/jina/JinaReaderConfig.java` | — | timeouts, headers |

**3 main files → 1. 2 packages → 1. Two copies of one literal → one.** And the
`analysis` package, which `02-invariant-aggregate-refactor.md` is about to put a
domain aggregate into, ends with no third-party import in it at all.

---

## Summary

The worst external-dependency leak in this repository is Jina Reader, and it is
worst for a reason no import-counting tool can see: it is an HTTP service rather
than a package, so its only trace in code is a string literal — and that literal,
`https://r.jina.ai/`, exists twice, at `ListingFetchService.java:30` and
`MarketPriceFetchService.java:28`, with identical encoding and URI-construction
code following each. Three documents declare this dependency swappable, one of
them naming two priced replacements as "fallback if Jina unreliable", and the same
document that called the Jina layer "already an abstraction" instructed the second
consumer in its next clause to reuse the prefix — so intent and code diverged
inside a single paragraph, and both halves were built. The duplication is not
cosmetic: the two paths classify the same vendor failure differently (the listing
path distinguishes `timeout` from `blocked`, the market path collapses everything
to `null`), only the listing path handles the case where Jina returns HTML instead
of markdown, and the market service borrows a `RestClient` builder named
`listingFetchBuilder` from another package — a coupling the repo already
compensates for with a two-line comment in `RequestTimeoutBudgetTest`. The plan
introduces a `reader` package whose port is one method, `ReadOutcome read(PageUrl)`,
with a sealed outcome type so no consumer can forget a failure mode again, and a
`JinaTarget` value object that is the single holder of the vendor's URL shape.
Because there is no database and no shared client code, the isolation proof is
unusually clean: swapping the reader touches three files under `reader/jina/` and
nothing else — no table, no wire field, no line of `analysis.models.ts`, which is
the same constraint Part 2 imposes for the same recorded reason. The refactor also
pays two debts on the way: it moves jsoup out of the `analysis` package entirely,
leaving the package the `Analysis` aggregate will land in with no third-party
import at all, and it adds the `mock`-profile reader whose absence is why the
`mock` profile still performs a real network call today. Success is a grep —
`grep -rn "r\.jina\.ai" backend/src/main` returning exactly one line — and because
a grep is something a human forgets, Phase 6 makes it a test.
