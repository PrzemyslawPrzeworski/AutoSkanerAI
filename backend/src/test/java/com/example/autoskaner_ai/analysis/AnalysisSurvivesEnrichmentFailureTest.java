package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.analysis.llm.AnalysisPrompt;
import com.example.autoskaner_ai.analysis.llm.AnalysisResponseParser;
import com.example.autoskaner_ai.analysis.llm.OpenRouterAnalysisService;
import com.example.autoskaner_ai.cepik.CepikEnrichmentService;
import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceFetchService;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import com.example.autoskaner_ai.market.OtomotoSlugMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A finished analysis must reach the client even when enrichment falls over.
 *
 * <h2>The oracle</h2>
 *
 * {@code context/archive/2026-06-02-market-price-context/plan-brief.md:69}, verbatim:
 * <em>"{@code POST /api/analyses} always returns a {@code marketPriceContext} field — never absent,
 * never an uncaught exception."</em> S-05 stated that invariant and then shipped without a single
 * test on it, and {@code buildResponse} had no try/catch at all — so a throw anywhere past the LLM
 * turned a completed analysis into {@code 500 Błąd serwera}.
 *
 * <h2>Why that is the expensive kind of 500</h2>
 *
 * <p>Enrichment runs <em>after</em> the ~16 s LLM call, synchronously on the request thread. Losing
 * the response at that point means the user waited out the whole analysis and was handed a server
 * error instead of the result the server already had in a local variable. Both enrichments are
 * best-effort by construction and both own a vocabulary for "this did not work"
 * ({@code FETCH_FAILED}, {@code LOOKUP_FAILED}), so degrading is strictly better than throwing.
 *
 * <h2>What is real here and what is stubbed</h2>
 *
 * <p>{@code AnalysisController}, {@code GlobalExceptionHandler}, {@code AnalysisPrompt}, the real
 * {@code AnalysisResponseParser}, {@code OpenRouterAnalysisService}, {@code CepikRiskAdjuster} and
 * — where the subject allows — the real {@code MarketPriceFetchService} are production classes. Two
 * sockets are stubbed independently: the OpenRouter one always serves the committed
 * {@code valid-full-response.json}, so every test below starts from an analysis that succeeded.
 *
 * <p>The two throw sites the guard exists for are {@code slugMapper.makeSlug}
 * ({@code MarketPriceFetchService.java:48}) and {@code MarketPriceStatistics.of} ({@code :78}).
 * Neither can be driven to throw through the service's public surface on today's code — {@code
 * makeSlug} answers {@code Optional.empty()} for an unknown make, and {@code of} is only ever
 * called with a non-empty list. That is the point: the guard is there for the throw nobody
 * predicted, so the throw has to be injected. {@code makeSlug} is injected through a subclass, and
 * the statistics stage through a service double, each documented at its test.
 */
class AnalysisSurvivesEnrichmentFailureTest {

    private static final String MODEL = "primary/model:free";
    private static final String LLM_URL = "https://openrouter.ai/api/v1/chat/completions";

    /** The committed happy-path answer: BMW 3 Series 2018, 120 000 km, verdict NEEDS_MORE_INFO. */
    private static final String VALID_ANALYSIS = readFixture("valid-full-response.json");

    private MockRestServiceServer llmServer;
    private MockRestServiceServer marketServer;
    private MockMvc mockMvc;

    // ---------------------------------------------------------------------------------------
    // The two unguarded throw sites
    // ---------------------------------------------------------------------------------------

    /**
     * {@code MarketPriceFetchService.java:48} — the real fetch service, wired to a slug mapper that
     * throws instead of answering. Nothing on the market-price socket is expected: the throw happens
     * before the first byte, which is exactly why {@code MarketPriceFetchService}'s own
     * {@code RestClientException} handling cannot help here.
     */
    @Test
    void aThrowFromTheSlugMapperCostsTheMarketRangeAndNotTheAnalysis() throws Exception {
        buildWith(realMarketPriceService(new OtomotoSlugMapper() {
            @Override
            public Optional<String> makeSlug(String make) {
                throw new IllegalStateException("slug table unavailable");
            }
        }));
        stubValidAnalysis();

        String body = perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketPriceContext.status").value("FETCH_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertAnalysisSurvived(body);
        assertMarketPriceContextIsPresentAndNotNull(body);
        llmServer.verify();
        marketServer.verify();
    }

    /**
     * {@code MarketPriceFetchService.java:78} — the statistics stage. Stood in by a service double
     * throwing the {@code IndexOutOfBoundsException} that {@code MarketPriceStatistics.of} raises at
     * {@code kept.get(0)} if its kept list is ever empty, because {@code of} is package-private,
     * called on an already-non-empty list, and so unreachable as a throw from outside the package.
     *
     * <p>The double is deliberately not a {@code RestClientException}: a fault at this point is
     * arithmetic over bytes already received, and the service's network handling never sees it.
     */
    @Test
    void aThrowFromTheStatisticsStageCostsTheMarketRangeAndNotTheAnalysis() throws Exception {
        var throwingMarketPrice = mock(MarketPriceEnrichmentService.class);
        when(throwingMarketPrice.enrich(any()))
                .thenThrow(new IndexOutOfBoundsException("Index 0 out of bounds for length 0"));
        buildWith(throwingMarketPrice);
        stubValidAnalysis();

        String body = perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketPriceContext.status").value("FETCH_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertAnalysisSurvived(body);
        assertMarketPriceContextIsPresentAndNotNull(body);
        llmServer.verify();
    }

    /**
     * The other enrichment call. A throw out of the registry lookup degrades to
     * {@code LOOKUP_FAILED}, and the degraded result must keep its lists <b>null</b> — an empty
     * {@code damageRecords} is the UI's "nothing was reported to insurers", which is a claim this
     * branch has no standing to make. {@code CepikResult.withoutData} is what enforces that.
     */
    @Test
    void aThrowFromTheRegistryLookupDegradesToLookupFailedWithNullLists() throws Exception {
        var throwingCepik = mock(CepikEnrichmentService.class);
        when(throwingCepik.enrich(any())).thenThrow(new RuntimeException("session handshake failed"));
        buildWith(throwingCepik, stubbedMarketPrice());
        stubValidAnalysis();

        String body = perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cepikResult.status").value("LOOKUP_FAILED"))
                // The VIN the analysis extracted rides along, so the follow-up can be retried.
                .andExpect(jsonPath("$.cepikResult.vin").value("WBAAM31060GE12345"))
                // And so does the lookup URL. This is the branch whose card reads "sprawdź ręcznie
                // na historiapojazdu.gov.pl", and the template renders the field into an href
                // unguarded — a null here is a dead link under copy telling the user to click it.
                .andExpect(jsonPath("$.cepikResult.lookupUrl").value(CepikResult.LOOKUP_URL))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertAnalysisSurvived(body);
        // Raw body, not jsonPath: `.doesNotExist()` and `.value(nullValue())` both pass for a JSON
        // null, so neither can tell "null list" from "empty list" — the whole distinction here.
        assertThat(body)
                .as("a failed lookup reports unknown, never an empty damage list")
                .contains("\"damageRecords\":null")
                .doesNotContain("\"damageRecords\":[]");
        llmServer.verify();
    }

    // ---------------------------------------------------------------------------------------
    // The invariant, end to end through the real service
    // ---------------------------------------------------------------------------------------

    /**
     * An unreadable payload rather than an injected throw: the real fetch service against a socket
     * serving {@code {}} for both the model-slug search and the retry without it. No price matches
     * {@code PRICE_PATTERN}, so the service reaches its own {@code INSUFFICIENT_DATA} — a status it
     * has to report rather than a range it has to invent.
     *
     * <p>{@code {}} and not an empty body on purpose: a bodyless 200 fails inside {@code RestClient}
     * itself, which would test the transport instead of the parse.
     *
     * <p>The status assertion is the load-bearing half. Without it the test would pass on a guard
     * that swallowed the real result and reported {@code FETCH_FAILED} for every non-OK outcome,
     * collapsing "we looked and there is not enough data" into "we could not look".
     */
    @Test
    void anUnparseableMarketPayloadIsReportedAsInsufficientDataNotAsAFetchFailure() throws Exception {
        buildWith(realMarketPriceService(new OtomotoSlugMapper()));
        stubValidAnalysis();
        // Two ordered GETs: the search with the model slug, then the retry without it (prices came
        // back empty). Matched on the host rather than the full URL — the exact query-string
        // encoding is MarketPriceFetchServiceTest's subject, not this test's.
        marketServer.expect(requestTo(containsString("otomoto.pl")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        marketServer.expect(requestTo(containsString("otomoto.pl")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String body = perform()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketPriceContext.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.marketPriceContext.medianPricePln").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertAnalysisSurvived(body);
        assertMarketPriceContextIsPresentAndNotNull(body);
        llmServer.verify();
        marketServer.verify();
    }

    /**
     * The guard's scope, asserted from the outside. A failed LLM call must still be the Phase 1 502
     * that names its cause — never a 200 carrying an empty analysis and a degraded enrichment.
     *
     * <p>Structural today ({@code aiAnalysisService.analyze} is called in {@code analyze}, outside
     * the guarded region), and that is precisely what this test pins: widening the guard to cover
     * {@code buildResponse}'s caller, or reaching for a class-level {@code @ExceptionHandler} that
     * answers 200 with a hollow body, fails here rather than in production.
     */
    @Test
    void anLlmFailureIsStillA502AndNotADegraded200() throws Exception {
        buildWith(stubbedMarketPrice());
        llmServer.expect(requestTo(LLM_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        perform()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.analysis").doesNotExist())
                .andExpect(jsonPath("$.marketPriceContext").doesNotExist());

        llmServer.verify();
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    /** Shared assertion: the analysis the LLM produced is intact in the response. */
    private static void assertAnalysisSurvived(String body) {
        assertThat(body)
                .as("the completed analysis is what the user waited for")
                .contains("\"make\":\"BMW\"")
                .contains("\"code\":\"NEEDS_MORE_INFO\"")
                .contains("\"overall\":64");
    }

    /**
     * Present and non-null, asserted on the raw body. {@code jsonPath("$.x").doesNotExist()} and
     * {@code jsonPath("$.x").value(nullValue())} both pass when {@code x} is JSON {@code null}, so
     * no {@code jsonPath} matcher can separate "the field is an object" from "the field is null" —
     * which is the entire content of the invariant being asserted.
     */
    private static void assertMarketPriceContextIsPresentAndNotNull(String body) {
        assertThat(body)
                .as("never absent, never null — plan-brief.md:69")
                .contains("\"marketPriceContext\":{")
                .doesNotContain("\"marketPriceContext\":null");
    }

    private void buildWith(MarketPriceEnrichmentService marketPriceEnrichmentService) {
        var cepik = mock(CepikEnrichmentService.class);
        when(cepik.enrich(any()))
                .thenReturn(CepikResult.withoutData(CepikStatus.MISSING_INPUTS, null, null));
        buildWith(cepik, marketPriceEnrichmentService);
    }

    /**
     * Wires the controller. Called from each test rather than {@code @BeforeEach} because the
     * market-price collaborator <em>is</em> the variable under test, and {@code
     * MockRestServiceServer} expectations are ordered and single-use.
     */
    private void buildWith(CepikEnrichmentService cepikEnrichmentService,
                           MarketPriceEnrichmentService marketPriceEnrichmentService) {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService(),
                        mock(ListingFetchService.class), cepikEnrichmentService,
                        marketPriceEnrichmentService, new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** The real OpenRouter service over a stubbed socket; {@link #llmServer} is bound here. */
    private OpenRouterAnalysisService aiAnalysisService() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        llmServer = MockRestServiceServer.bindTo(builder).build();
        return new OpenRouterAnalysisService(new AnalysisPrompt(),
                new AnalysisResponseParser(new ObjectMapper()), builder, MODEL, List.of(), 70);
    }

    /** The real market-price service over a stubbed socket; {@link #marketServer} is bound here. */
    private MarketPriceFetchService realMarketPriceService(OtomotoSlugMapper slugMapper) {
        RestClient.Builder builder = RestClient.builder();
        marketServer = MockRestServiceServer.bindTo(builder).build();
        return new MarketPriceFetchService(builder, slugMapper);
    }

    /**
     * For the tests whose subject is elsewhere: a market range that simply is not available.
     *
     * <p>{@code FETCH_FAILED} rather than {@code null} — no production path returns null here, and a
     * stub that does lets an assertion downstream be satisfied by a value the system cannot produce.
     */
    private static MarketPriceEnrichmentService stubbedMarketPrice() {
        var stub = mock(MarketPriceEnrichmentService.class);
        when(stub.enrich(any())).thenReturn(new MarketPriceContext(MarketPriceStatus.FETCH_FAILED,
                null, null, null, null, null, Instant.now(), null, null));
        return stub;
    }

    private void stubValidAnalysis() {
        String envelope = "{\"choices\":[{\"message\":{\"content\":"
                + new ObjectMapper().writeValueAsString(VALID_ANALYSIS) + "}}]}";
        llmServer.expect(requestTo(LLM_URL))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    private ResultActions perform() throws Exception {
        return mockMvc.perform(analysisRequest());
    }

    private static MockHttpServletRequestBuilder analysisRequest() {
        return post("/api/analyses")
                .contentType("application/json")
                .content("{\"listingText\":\"BMW seria 3 2018, benzyna, przebieg 120 000 km\"}");
    }

    private static String readFixture(String name) {
        var resource = new ClassPathResource("fixtures/llm/" + name);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }
}
