package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.analysis.llm.AnalysisPrompt;
import com.example.autoskaner_ai.analysis.llm.AnalysisResponseParser;
import com.example.autoskaner_ai.analysis.llm.OpenRouterAnalysisService;
import com.example.autoskaner_ai.cepik.CepikEnrichmentService;
import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
 * What a client actually receives when the LLM does not produce an analysis.
 *
 * <p>The suite had unit coverage on both halves of this and nothing across the join.
 * {@code OpenRouterAnalysisServiceTest} stops at a thrown exception; {@code LlmExceptionHandlerTest}
 * starts from one it constructs by hand. So the mapping from <em>a provider behaviour</em> to
 * <em>an HTTP body</em> — the only thing a user can observe — was asserted nowhere, and all three
 * distinct 502 causes plus two of the malformed-response routes rendered as the same string (or, in
 * the malformed cases, as a catch-all 500 blaming this server for somebody else's payload).
 *
 * <p><b>Only the OpenRouter socket is stubbed.</b> The prompt, the real
 * {@code AnalysisResponseParser}, {@code OpenRouterAnalysisService}, {@code AnalysisController} and
 * {@code GlobalExceptionHandler} are all production classes. The three mocks —
 * {@code ListingFetchService}, {@code CepikEnrichmentService}, {@code MarketPriceEnrichmentService}
 * — sit past the point where every branch below has already failed.
 *
 * <p>Oracle: the API error shape locked in {@code CLAUDE.md} § "API error shape" (four fields,
 * {@code messages} a list, no {@code ProblemDetail}) and the product guardrail that a user who
 * waited out an analysis must be told which failure they hit. Not what the handler currently emits.
 */
class LlmFailureReachesTheClientTest {

    private static final String MODEL = "primary/model:free";
    private static final String FALLBACK = "fallback/model:free";
    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

    /** Six containers present, every leaf null — a structurally valid answer that says nothing. */
    private static final String HOLLOW_ANALYSIS = readFixture("hollow-all-leaves-null.json");

    // The four headlines, as a client reads them. Held as constants so the distinctness test can
    // name what it is comparing instead of comparing two branches to each other and passing when
    // both are wrong in the same way.
    private static final String SCHEMA_ERROR = "Niepoprawny format odpowiedzi LLM";
    private static final String UNUSABLE_ERROR = "Usługa LLM zwróciła nieczytelną odpowiedź";
    private static final String CREDENTIALS_ERROR = "Usługa LLM odrzuciła dane dostępowe";
    private static final String EXHAUSTED_ERROR = "Wszystkie modele LLM są niedostępne";

    private MockRestServiceServer server;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        rebuild();
    }

    /**
     * Rebuilt from scratch per branch, and callable again mid-test: {@code MockRestServiceServer}
     * expectations are ordered and single-use, so the distinctness test below needs a fresh socket
     * for each of the four branches it collects.
     */
    private void rebuild() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();

        var prompt = new AnalysisPrompt();
        var parser = new AnalysisResponseParser(new ObjectMapper());
        var aiAnalysisService = new OpenRouterAnalysisService(prompt, parser, builder, MODEL,
                List.of(FALLBACK), 70);

        var marketPriceEnrichmentService = mock(MarketPriceEnrichmentService.class);
        when(marketPriceEnrichmentService.enrich(any())).thenReturn(null);
        var cepikEnrichmentService = mock(CepikEnrichmentService.class);
        when(cepikEnrichmentService.enrich(any())).thenReturn(null);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService,
                        mock(ListingFetchService.class), cepikEnrichmentService,
                        marketPriceEnrichmentService, new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // The four branches, one test each, asserting the body a client gets
    // ---------------------------------------------------------------------------------------

    /**
     * A 200 whose JSON is the wrong shape for an analysis. The field path is the whole value of
     * this branch: "Niepoprawny format odpowiedzi LLM" alone tells an operator nothing, and before
     * the spine check this response did not fail at all — it became a 200 with no car, no verdict
     * and five scores of {@code 0}.
     */
    @Test
    void aStructurallyHollowAnalysisBecomesASchemaFailureNamingTheField() throws Exception {
        stubContent(HOLLOW_ANALYSIS);

        mockMvc.perform(analysisRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value(SCHEMA_ERROR))
                .andExpect(jsonPath("$.messages[0]").value("scores.completeness"))
                // The locked envelope: exactly four fields, messages a list, no ProblemDetail keys.
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());

        server.verify();
    }

    /**
     * A well-formed HTTP 200 carrying no choices. One POST: a response shape is deterministic, so
     * neither the retry nor the fallback axis can help. This used to surface as the catch-all 500
     * "Błąd serwera" — this server reporting its own failure for the provider's malformed payload.
     */
    @Test
    void anUnusableProviderResponseIsA502AboutTheProviderNotA500AboutUs() throws Exception {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        mockMvc.perform(analysisRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value(UNUSABLE_ERROR))
                // Retrying is worth a try here — unlike the credentials branch below.
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("Spróbuj ponownie")));

        server.verify();
    }

    /**
     * A rejected key. One POST, and advice that says so: every model rejects the same key, so a
     * "try again in a few minutes" here sends the user to wait out something that will never clear.
     */
    @Test
    void rejectedCredentialsTellTheUserRetryingWillNotHelp() throws Exception {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        mockMvc.perform(analysisRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(CREDENTIALS_ERROR))
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("Ponowna próba nie pomoże")));

        server.verify();
    }

    /**
     * Every candidate model gone. Two 404s rather than 5xx on purpose: a retired slug is
     * permanent-for-this-model, so the chain walks straight on with no {@code Thread.sleep} — the
     * transient path would spend a real second per model waiting out its retry.
     */
    @Test
    void anExhaustedFallbackChainSaysTheModelsAreUnavailable() throws Exception {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        mockMvc.perform(analysisRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(EXHAUSTED_ERROR))
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("za kilka minut")));

        server.verify();
    }

    // ---------------------------------------------------------------------------------------
    // The load-bearing one: the four branches must not read alike
    // ---------------------------------------------------------------------------------------

    /**
     * Collects the four real response bodies and asserts they are mutually distinguishable.
     *
     * <p>This is the test the whole phase exists for. Four separate tests each asserting their own
     * expected string will all pass if a future edit collapses two of those strings into one — each
     * test would simply be updated to the new shared value. Comparing the four <em>actual</em>
     * bodies is what cannot be satisfied that way.
     *
     * <p>Compared on the raw body with the timestamp masked, not via {@code jsonPath}: the point is
     * that two bodies <em>differ</em>, and the timestamp differs between any two responses, so
     * leaving it in would make the assertion pass no matter what the error strings say.
     */
    @Test
    void theFourFailureBranchesDoNotShareAnErrorString() throws Exception {
        Map<String, String> bodyByBranch = new LinkedHashMap<>();

        bodyByBranch.put(SCHEMA_ERROR, bodyOf(() -> stubContent(HOLLOW_ANALYSIS)));
        bodyByBranch.put(UNUSABLE_ERROR, bodyOf(() -> server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON))));
        bodyByBranch.put(CREDENTIALS_ERROR, bodyOf(() -> server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED))));
        bodyByBranch.put(EXHAUSTED_ERROR, bodyOf(() -> {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        }));

        assertThat(bodyByBranch.values().stream().map(LlmFailureReachesTheClientTest::maskTimestamp))
                .as("four distinct causes, four distinct bodies")
                .doesNotHaveDuplicates();

        // And each body carries its own headline and none of the other three, so a swapped mapping
        // fails here rather than passing the distinctness check above on the wrong pairing.
        bodyByBranch.forEach((headline, body) -> {
            assertThat(body).contains("\"error\":\"" + headline + "\"");
            bodyByBranch.keySet().stream().filter(other -> !other.equals(headline))
                    .forEach(other -> assertThat(body)
                            .as("%s body must not mention %s", headline, other)
                            .doesNotContain(other));
        });
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    /** Rebuilds the socket, applies the branch's stubs, performs the request, returns the body. */
    private String bodyOf(Runnable stubs) throws Exception {
        rebuild();
        stubs.run();
        String body = mockMvc.perform(analysisRequest())
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        server.verify();
        return body;
    }

    private static String maskTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<masked>\"");
    }

    /** Wraps model output in OpenRouter's chat-completions envelope, escaped as a provider would. */
    private void stubContent(String content) {
        String envelope = "{\"choices\":[{\"message\":{\"content\":"
                + new ObjectMapper().writeValueAsString(content) + "}}]}";
        server.expect(requestTo(URL)).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    private static MockHttpServletRequestBuilder analysisRequest() {
        return post("/api/analyses")
                .contentType("application/json")
                .content("{\"listingText\":\"Toyota Corolla 2022, hybryda, przebieg 26 320 km\"}");
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
