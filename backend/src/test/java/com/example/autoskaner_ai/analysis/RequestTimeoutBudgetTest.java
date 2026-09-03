package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.analysis.llm.OpenRouterConfig;
import com.example.autoskaner_ai.cepik.HistoriaPojazduConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arithmetic over every timeout that bounds one {@code POST /api/analyses}, checked against the
 * product's response-time guarantee.
 *
 * <h2>The oracle</h2>
 *
 * {@code context/foundation/prd.md:98}, verbatim: <em>"Analysis response time: a listing analysis
 * produces its result within a perceptible wait; any operation exceeding 2 seconds presents
 * continuous visible progress to the user, and no analysis runs for longer than 30 seconds without
 * a visible result or error."</em>
 *
 * <h2>What this test claims, and what it does not</h2>
 *
 * <p><strong>It does not claim the budget is met. It asserts, on purpose, that the budget is
 * blown.</strong> Every stage below runs synchronously on the request thread ({@code CLAUDE.md}
 * § "Enrichment services", async deferred as impl-review F10), {@code AnalysisController} enforces
 * no deadline of any kind, and the configured socket timeouts add up to roughly ten times the 30 s
 * the PRD promises. That gap is real and this phase does not close it — closing it means the async
 * rework, which is out of scope here.
 *
 * <p>What the test is for is that the numbers cannot drift silently. Today the NFR holds only by
 * luck of provider latency: a real analysis takes ~27 s, comfortably under 30 s, while the
 * configured ceiling is an order of magnitude above it. Raise any read timeout, add a call to a
 * stage, or widen the LLM deadline, and this test fails and makes whoever did it say so out loud.
 * A green run here means "the gap is still the documented size", not "the analysis is fast".
 *
 * <p>Every socket timeout is a <em>per-read</em> timeout on a {@link
 * org.springframework.http.client.SimpleClientHttpRequestFactory}, so the per-call figures below
 * are {@code connect + read} — the floor of one call's worst case, not a bound on a slow trickle of
 * bytes. The real ceiling is therefore no better than what this test asserts.
 */
class RequestTimeoutBudgetTest {

    /** {@code prd.md:98}. The whole reason the arithmetic below is worth writing down. */
    private static final Duration NFR_CEILING = Duration.ofSeconds(30);

    // ===============================================================================================
    // Per-stage worst cases. Call multiplicities are structural — they come from reading the call
    // sites, named here so a new call has to be reflected in a number somebody has to change.
    // ===============================================================================================

    /** {@code ListingFetchService.fetch} — one DNS pre-check per request, before any HTTP. */
    private static final int DNS_CALLS = 1;

    /** {@code ListingFetchService.fetch} — one Jina Reader GET per request. */
    private static final int LISTING_FETCH_CALLS = 1;

    /**
     * {@code HistoriaPojazduService.lookup} — five calls on one session:
     * {@code HistoriaPojazduSession.open} does GET index then POST index, then
     * {@code fetchVehicleData}, {@code fetchTimelineData}, and {@code close} in the finally block.
     */
    private static final int CEPIK_CALLS = 5;

    /**
     * {@code MarketPriceFetchService.enrich} — the search fetch, plus one retry without the model
     * slug when the first fetch returns no prices.
     */
    private static final int MARKET_PRICE_CALLS = 2;

    // ===============================================================================================
    // Configured timeouts. Read from the constants the configuration classes hand their factories,
    // so bumping a timeout means bumping one of these and failing the total below.
    // ===============================================================================================

    @Test
    void configuredTimeoutsAreTheDocumentedFigures() {
        assertThat(ListingFetchService.DNS_TIMEOUT_SECONDS).isEqualTo(5);

        assertThat(ListingFetchConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(ListingFetchConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(30));

        assertThat(OpenRouterConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(OpenRouterConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(30));

        assertThat(HistoriaPojazduConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(HistoriaPojazduConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(10));

        // The market-price fetch shares listingFetchBuilder (MarketPriceFetchService's constructor
        // takes @Qualifier("listingFetchBuilder")), so it inherits 5 / 30 rather than owning a pair.
    }

    /**
     * The LLM stage is the only one whose ceiling is not "per-call × calls": the fallback chain is
     * bounded by {@code llm.openrouter.deadline-seconds}, checked <em>between</em> models.
     *
     * <p>Hand arithmetic, with D = the deadline, A = one attempt's {@code connect + read}, and
     * W = {@code MAX_RETRY_WAIT} (6 s):
     *
     * <ul>
     *   <li>A model is entered only while elapsed &lt; D, so the last model can start at just under
     *       D. The primary is exempt — the check is guarded on {@code lastFailure != null} — but
     *       being first it starts at 0 and cannot be the worst case.</li>
     *   <li>Since Phase 2, a retry is taken only if W fits in the remaining budget, i.e. only if
     *       the first attempt failed by D − W. So a model that retries was entered by
     *       D − W − A = 70 − 6 − 40 = 24 s, and ends by 24 + (A + W + A) = 24 + 86 = <b>110 s</b>.</li>
     *   <li>A model entered later cannot retry, so it ends by (just under D) + A = 70 + 40 =
     *       <b>110 s</b>.</li>
     * </ul>
     *
     * Both branches land on D + A, which is the ceiling asserted here.
     *
     * <p>This is also the one figure Phase 2 <em>improved</em>. Before it, the wait was clamped down
     * to whatever budget remained instead of skipping the model, so a retry always fired and the
     * ceiling was D + A + W + A = 156 s (the figure in
     * {@code context/changes/testing-availability-failure-paths/research.md} § B2). The clamp is
     * gone, and 46 s of worst case went with it.
     */
    @Test
    void theLlmChainCeilingIsTheDeadlinePlusOneAttempt() {
        Duration perAttempt = OpenRouterConfig.CONNECT_TIMEOUT.plus(OpenRouterConfig.READ_TIMEOUT);
        assertThat(perAttempt).isEqualTo(Duration.ofSeconds(40));

        Duration deadline = configuredLlmDeadline();
        assertThat(deadline).isEqualTo(Duration.ofSeconds(70));

        assertThat(deadline.plus(perAttempt))
                .as("D + A — see the arithmetic on this method")
                .isEqualTo(Duration.ofSeconds(110));

        // The deadline alone is already 2.3x the NFR, before a single byte is read.
        assertThat(deadline).isGreaterThan(NFR_CEILING);
    }

    /**
     * The documented total, and the size of the gap.
     *
     * <p>Hand arithmetic over the stages, each {@code (connect + read) × calls}:
     *
     * <pre>
     *   DNS pre-check      5 x 1                 =    5 s
     *   Listing fetch      (5 + 30) x 1          =   35 s
     *   OpenRouter chain   deadline 70 + 40      =  110 s   (see the method above)
     *   historiapojazdu    (5 + 10) x 5          =   75 s
     *   Market price       (5 + 30) x 2          =   70 s
     *                                              -------
     *                                               295 s
     * </pre>
     *
     * <p>295 s against a 30 s promise. Nothing in the request path enforces the promise, so this is
     * a ceiling that a bad enough day can actually reach.
     */
    @Test
    void theConfiguredRequestBudgetIsAboutTenTimesTheNfrAndNothingEnforcesTheNfr() {
        Duration dns = Duration.ofSeconds(ListingFetchService.DNS_TIMEOUT_SECONDS).multipliedBy(DNS_CALLS);
        Duration listingFetch = perCall(ListingFetchConfig.CONNECT_TIMEOUT, ListingFetchConfig.READ_TIMEOUT)
                .multipliedBy(LISTING_FETCH_CALLS);
        Duration llm = configuredLlmDeadline()
                .plus(perCall(OpenRouterConfig.CONNECT_TIMEOUT, OpenRouterConfig.READ_TIMEOUT));
        Duration cepik = perCall(HistoriaPojazduConfig.CONNECT_TIMEOUT, HistoriaPojazduConfig.READ_TIMEOUT)
                .multipliedBy(CEPIK_CALLS);
        Duration marketPrice = perCall(ListingFetchConfig.CONNECT_TIMEOUT, ListingFetchConfig.READ_TIMEOUT)
                .multipliedBy(MARKET_PRICE_CALLS);

        assertThat(dns).isEqualTo(Duration.ofSeconds(5));
        assertThat(listingFetch).isEqualTo(Duration.ofSeconds(35));
        assertThat(llm).isEqualTo(Duration.ofSeconds(110));
        assertThat(cepik).isEqualTo(Duration.ofSeconds(75));
        assertThat(marketPrice).isEqualTo(Duration.ofSeconds(70));

        Duration total = dns.plus(listingFetch).plus(llm).plus(cepik).plus(marketPrice);

        assertThat(total)
                .as("the documented worst case; change a timeout and change this number deliberately")
                .isEqualTo(Duration.ofSeconds(295));

        // Asserted as a fact about the system, not as a passing grade. If this ever flips to
        // isLessThanOrEqualTo, the async rework landed and this comment is the thing to delete.
        assertThat(total)
                .as("the configured budget exceeds prd.md:98 by roughly 10x, and no deadline enforces it")
                .isGreaterThan(NFR_CEILING.multipliedBy(9));
    }

    private static Duration perCall(Duration connect, Duration read) {
        return connect.plus(read);
    }

    /**
     * The committed default for {@code llm.openrouter.deadline-seconds}, read out of the properties
     * file rather than restated — the file is the oracle, and production overrides it by env var
     * ({@code OPENROUTER_DEADLINE_SECONDS}) without touching this default.
     */
    private static Duration configuredLlmDeadline() {
        String properties = read("application-openrouter.properties");
        Matcher matcher = Pattern
                .compile("^llm\\.openrouter\\.deadline-seconds=\\$\\{[A-Z_]+:(\\d+)}$", Pattern.MULTILINE)
                .matcher(properties);
        assertThat(matcher.find())
                .as("application-openrouter.properties must declare llm.openrouter.deadline-seconds")
                .isTrue();
        return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
    }

    private static String read(String classpathResource) {
        try (var in = new ClassPathResource(classpathResource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("missing " + classpathResource, e);
        }
    }
}
