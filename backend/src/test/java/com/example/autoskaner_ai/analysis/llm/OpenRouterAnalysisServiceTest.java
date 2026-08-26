package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"raw\\":\\"json\\"}"}}]}
            """;

    @BeforeEach
    void setUp() {
        svc = build(List.of(FALLBACK));
    }

    /** Rebuilt per test so the candidate chain can vary. */
    private OpenRouterAnalysisService build(List<String> fallbacks) {
        parser = mock(AnalysisResponseParser.class);
        AnalysisPrompt prompt = mock(AnalysisPrompt.class);
        when(prompt.systemPrompt()).thenReturn("system");
        when(prompt.userMessage(any())).thenReturn("user: listing");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        mockServer = MockRestServiceServer.bindTo(builder).build();

        return new OpenRouterAnalysisService(prompt, parser, builder, MODEL, fallbacks, 70);
    }

    private AnalysisResult dummyResult() {
        var extracted = new ExtractedData(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var scores = new CategoryScores(70, 70, 70, 70, 70);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzic");
        var meta = new AnalysisMeta("openrouter", MODEL, 200L, Instant.now());
        return new AnalysisResult(extracted, List.of(), List.of(), List.of(), scores, verdict, meta);
    }

    @Test
    void happyPath_onePost_returnsResult() {
        mockServer.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), eq("openrouter"), eq(MODEL), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        assertThat(result.meta().provider()).isEqualTo("openrouter");
        mockServer.verify();
    }

    @Test
    void firstPost503_secondPost200_returnsResult() {
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), any(), any(), anyLong())).thenReturn(expected);

        var result = svc.analyze("listing text");

        assertThat(result).isEqualTo(expected);
        mockServer.verify();
    }

    // 4 posts: two per model (attempt + retry), primary then fallback.
    @Test
    void allAttemptsOnAllModels503_throwsLlmCallException() {
        for (int i = 0; i < 4; i++) {
            mockServer.expect(requestTo(URL)).andRespond(withServerError());
        }

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("exhausted all candidate models");
        mockServer.verify();
    }

    // The failure mode that took production down: a saturated free pool answers 429, the old
    // code retried instantly and got 429 again. The wait must be honoured and the chain must
    // move on to a model on a different upstream pool.
    @Test
    void rateLimitedPrimary_honoursRetryAfterThenFallsBackToNextModel() {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.set(HttpHeaders.RETRY_AFTER, "1");

        mockServer.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        mockServer.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), eq("openrouter"), eq(FALLBACK), anyLong())).thenReturn(expected);

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result).isEqualTo(expected);
        assertThat(elapsedMs)
                .as("Retry-After: 1 must actually be waited out, not ignored")
                .isGreaterThanOrEqualTo(900);
        mockServer.verify();
        // The model that answered is the one recorded, not the one configured as primary.
        verify(parser).parse(any(), eq("openrouter"), eq(FALLBACK), anyLong());
    }

    // A withdrawn slug 404s forever. Retrying it is pure added latency, so the chain must
    // move straight to the next model — one post for the primary, not two.
    @Test
    void retiredSlug404_skipsRetryAndGoesStraightToNextModel() {
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var expected = dummyResult();
        when(parser.parse(any(), eq("openrouter"), eq(FALLBACK), anyLong())).thenReturn(expected);

        assertThat(svc.analyze("listing text")).isEqualTo(expected);
        mockServer.verify();
    }

    // A rejected key rejects every model, so walking the chain just multiplies the latency
    // before the same failure.
    @Test
    void unauthorized401_failsImmediatelyWithoutTryingFallbacks() {
        mockServer.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
        mockServer.verify();
    }

    @Test
    void emptyChoicesResponse_failsWithoutRetryOrFallback() {
        // Response-shape failure (empty choices) is deterministic — must NOT trigger retry.
        mockServer.expect(requestTo(URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
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

    @Test
    void noFallbacksConfigured_behavesLikeSingleModelWithOneRetry() {
        svc = build(List.of());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
        mockServer.verify();
    }

    // An empty OPENROUTER_FALLBACK_MODELS binds as [""], and a copy-paste can repeat the
    // primary; neither should produce a wasted attempt.
    @Test
    void blankAndDuplicateFallbacksAreIgnored() {
        svc = build(List.of("", "   ", MODEL));
        mockServer.expect(requestTo(URL)).andRespond(withServerError());
        mockServer.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> svc.analyze("listing text"))
                .isInstanceOf(LlmCallException.class);
        mockServer.verify();
    }
}
