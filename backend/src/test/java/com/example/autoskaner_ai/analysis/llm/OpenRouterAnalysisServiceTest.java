package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenRouterAnalysisServiceTest {

    private MockRestServiceServer mockServer;
    private AnalysisResponseParser parser;
    private OpenRouterAnalysisService svc;

    private static final String MODEL = "primary/model:free";
    private static final String FALLBACK = "fallback/model:free";
    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

    /** The same committed fixture {@code AnalysisResponseParserTest} reads — a real analysis. */
    private static final String VALID_ANALYSIS = readFixture("valid-full-response.json");
    /** Six containers present, every leaf null: what a model answers when it understands nothing. */
    private static final String HOLLOW_ANALYSIS = readFixture("hollow-all-leaves-null.json");

    private static final String OK_BODY = providerBody(VALID_ANALYSIS);

    @BeforeEach
    void setUp() {
        svc = buildWithMockParser(List.of(FALLBACK));
    }

    /**
     * Mocked parser. Only for cases whose subject is the <em>call sequence</em> — how many requests
     * go out, to which model, in what order. Any assertion about the analysis that comes back is
     * meaningless here: the mock returns whatever it was told to.
     */
    private OpenRouterAnalysisService buildWithMockParser(List<String> fallbacks) {
        parser = mock(AnalysisResponseParser.class);
        return construct(fallbacks, parser);
    }

    /**
     * Real parser, mandatory for any case that asserts something about the returned content.
     *
     * <p>With the mock, {@code assertThat(result).isEqualTo(expected)} asserts only that the stub
     * returned the stub. Worse, {@code AnalysisMeta.model} came from a hand-built dummy that named
     * the configured primary whichever model actually answered — so the one field that records who
     * really served the request could not be checked at all.
     */
    private OpenRouterAnalysisService buildWithRealParser(List<String> fallbacks) {
        parser = null;
        return construct(fallbacks, new AnalysisResponseParser(new ObjectMapper()));
    }

    /** Rebuilt per test so the candidate chain can vary. */
    private OpenRouterAnalysisService construct(List<String> fallbacks, AnalysisResponseParser parser) {
        AnalysisPrompt prompt = mock(AnalysisPrompt.class);
        when(prompt.systemPrompt()).thenReturn("system");
        when(prompt.userMessage(any())).thenReturn("user: listing");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        mockServer = MockRestServiceServer.bindTo(builder).build();

        return new OpenRouterAnalysisService(prompt, parser, builder, MODEL, fallbacks, 70);
    }

    /** Wraps model output in OpenRouter's chat-completions envelope, escaped as a provider would. */
    private static String providerBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + new ObjectMapper().writeValueAsString(content) + "}}]}";
    }

    private static String readFixture(String name) {
        var resource = new ClassPathResource("fixtures/llm/" + name);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }

    /** Only for the mocked-parser cases; never asserted against. */
    private AnalysisResult dummyResult() {
        var extracted = new ExtractedData(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var scores = new CategoryScores(70, 70, 70, 70, 70);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzic");
        var meta = new AnalysisMeta("openrouter", MODEL, 200L, Instant.now());
        return new AnalysisResult(extracted, List.of(), List.of(), List.of(), scores, verdict, meta);
    }

    private static LlmCallException.Reason reasonOfThrownCall(Runnable call) {
        var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(LlmCallException.class, call::run);
        assertThat(thrown).as("expected an LlmCallException").isNotNull();
        return thrown.reason();
    }

    // ===========================================================================================
    // Happy path and fallback identity — real parser, because the subject is the content returned
    // ===========================================================================================

    @Test
    void happyPath_onePost_returnsTheParsedAnalysis() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var result = svc.analyze("listing text");

        // Oracle: the bytes of valid-full-response.json.
        assertThat(result.extracted().make()).isEqualTo("BMW");
        assertThat(result.scores().overall()).isEqualTo(64);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.NEEDS_MORE_INFO);
        assertThat(result.meta().provider()).isEqualTo("openrouter");
        assertThat(result.meta().model()).isEqualTo(MODEL);
        mockServer.verify();
    }

    @Test
    void firstPost503_secondPost200_returnsTheParsedAnalysis() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var result = svc.analyze("listing text");

        assertThat(result.extracted().make()).isEqualTo("BMW");
        // Same model on both attempts — a transient 5xx is the retry axis, not the fallback axis.
        assertThat(result.meta().model()).isEqualTo(MODEL);
        mockServer.verify();
    }

    // The failure mode that took production down: a saturated free pool answers 429, the old
    // code retried instantly and got 429 again. The wait must be honoured and the chain must
    // move on to a model on a different upstream pool.
    @Test
    void rateLimitedPrimary_honoursRetryAfterThenFallsBackToNextModel() {
        svc = buildWithRealParser(List.of(FALLBACK));
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.set(HttpHeaders.RETRY_AFTER, "1");

        mockServer.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        mockServer.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs)
                .as("Retry-After: 1 must actually be waited out, not ignored")
                .isGreaterThanOrEqualTo(900);
        // The model that answered is the one recorded, not the one configured as primary. Asserted
        // on the parsed result rather than on a parser argument, so it is the value that would
        // actually reach the client.
        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        mockServer.verify();
    }

    // A withdrawn slug 404s forever. Retrying it is pure added latency, so the chain must
    // move straight to the next model — one post for the primary, not two.
    @Test
    void retiredSlug404_skipsRetryAndGoesStraightToNextModel() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var result = svc.analyze("listing text");

        assertThat(result.extracted().make()).isEqualTo("BMW");
        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        mockServer.verify();
    }

    // ===========================================================================================
    // Failure branches. Each asserts the cause the client will be told, not only the POST count —
    // a count alone says nothing about what the user ends up seeing.
    // ===========================================================================================

    // 4 posts: two per model (attempt + retry), primary then fallback.
    @Test
    void allAttemptsOnAllModels503_reportsAnExhaustedChain() {
        for (int i = 0; i < 4; i++) {
            mockServer.expect(requestTo(URL)).andRespond(withServerError());
        }

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("exhausted all candidate models")
                .extracting(e -> ((LlmCallException) e).reason())
                .isEqualTo(LlmCallException.Reason.ALL_CANDIDATES_EXHAUSTED);
        mockServer.verify();
    }

    // A rejected key rejects every model, so walking the chain just multiplies the latency
    // before the same failure — and the user must be told retrying will not help.
    @Test
    void unauthorized401_failsImmediatelyAsRejectedCredentials() {
        mockServer.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.REJECTED_CREDENTIALS);
        mockServer.verify();
    }

    @Test
    void forbidden403_failsImmediatelyAsRejectedCredentials() {
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.REJECTED_CREDENTIALS);
        mockServer.verify();
    }

    // -------------------------------------------------------------------------------------------
    // The four provider-quirk routes. Each is a well-formed 200 whose JSON cannot yield an
    // analysis. All four used to escape as the catch-all 500 "Błąd serwera" — a server error for
    // somebody else's malformed payload — because none of them throws IllegalArgumentException.
    // One POST each: a response shape is deterministic, so neither a retry nor a fallback helps.
    // -------------------------------------------------------------------------------------------

    @Test
    void emptyChoices_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    @Test
    void choicesNotAnArray_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":\"nope\"}", MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    @Test
    void messageAbsentFromTheChoice_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{}]}", MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    @Test
    void contentAbsentFromTheMessage_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{}}]}", MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    @Test
    void contentThatIsNotAString_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":42}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    @Test
    void blankContent_failsAsAnUnusableProviderResponse() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(providerBody("   "), MediaType.APPLICATION_JSON));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
        mockServer.verify();
    }

    // -------------------------------------------------------------------------------------------
    // Schema failures. A prompt/parser mismatch is not fixed by another model, so it propagates.
    // -------------------------------------------------------------------------------------------

    /**
     * The hollow 200 through the real parser: one POST, and a schema failure naming the field —
     * not a 200 carrying an analysis with no car and five zeroed scores.
     */
    @Test
    void aHollowAnalysisPropagatesAsASchemaFailureWithoutRetryOrFallback() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(providerBody(HOLLOW_ANALYSIS), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmResponseSchemaException.class)
                .extracting(e -> ((LlmResponseSchemaException) e).getFieldPath())
                .isEqualTo("scores.completeness");
        mockServer.verify();
    }

    @Test
    void parserThrowsSchemaException_propagatesWithoutRetry() {
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        when(parser.parse(any(), any(), any(), anyLong()))
                .thenThrow(new LlmResponseSchemaException("bad schema", "root"));

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmResponseSchemaException.class);
        // Only one call — a prompt/parser mismatch is not fixed by another model.
        mockServer.verify();
    }

    // ===========================================================================================
    // Candidate-chain construction. Mocked parser: the subject is which requests go out.
    // ===========================================================================================

    @Test
    void noFallbacksConfigured_behavesLikeSingleModelWithOneRetry() {
        svc = buildWithMockParser(List.of());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.ALL_CANDIDATES_EXHAUSTED);
        mockServer.verify();
    }

    // An empty OPENROUTER_FALLBACK_MODELS binds as [""], and a copy-paste can repeat the
    // primary; neither should produce a wasted attempt.
    @Test
    void blankAndDuplicateFallbacksAreIgnored() {
        svc = buildWithMockParser(List.of("", "   ", MODEL));
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.ALL_CANDIDATES_EXHAUSTED);
        mockServer.verify();
    }

    /** Guards the mocked-parser helper itself: the stub has to be wired for the sequence cases. */
    @Test
    void mockedParserHelperStillServesTheSequenceCases() {
        var expected = dummyResult();
        when(parser.parse(any(), eq("openrouter"), eq(MODEL), anyLong())).thenReturn(expected);
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertThat(svc.analyze("listing text")).isSameAs(expected);
        verify(parser).parse(any(), eq("openrouter"), eq(MODEL), anyLong());
        mockServer.verify();
    }
}
