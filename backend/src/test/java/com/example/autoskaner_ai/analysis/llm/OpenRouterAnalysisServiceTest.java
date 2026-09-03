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
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * Wide enough that the budget never interferes — the default any case that is not <em>about</em>
     * the budget should use. {@code llm.openrouter.deadline-seconds} defaults to 70.
     */
    private static final long GENEROUS_DEADLINE = 70;

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
        return buildWithMockParser(fallbacks, GENEROUS_DEADLINE);
    }

    private OpenRouterAnalysisService buildWithMockParser(List<String> fallbacks, long deadlineSeconds) {
        parser = mock(AnalysisResponseParser.class);
        return construct(fallbacks, parser, deadlineSeconds);
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
        return buildWithRealParser(fallbacks, GENEROUS_DEADLINE);
    }

    private OpenRouterAnalysisService buildWithRealParser(List<String> fallbacks, long deadlineSeconds) {
        parser = null;
        return construct(fallbacks, new AnalysisResponseParser(new ObjectMapper()), deadlineSeconds);
    }

    /**
     * Rebuilt per test so the candidate chain <em>and the budget</em> can vary. The budget used to
     * be hardcoded at 70 s, which is why the deadline-skip branch had never executed: it is
     * unreachable in a fast test at 70 s and free at 0.
     */
    private OpenRouterAnalysisService construct(List<String> fallbacks, AnalysisResponseParser parser,
                                               long deadlineSeconds) {
        AnalysisPrompt prompt = mock(AnalysisPrompt.class);
        when(prompt.systemPrompt()).thenReturn("system");
        when(prompt.userMessage(any())).thenReturn("user: listing");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.ai/api/v1");
        mockServer = MockRestServiceServer.bindTo(builder).build();

        return new OpenRouterAnalysisService(prompt, parser, builder, MODEL, fallbacks, deadlineSeconds);
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
        mockServer.expect(requestTo(URL)).andRespond(rateLimited("1"));
        mockServer.expect(requestTo(URL)).andRespond(rateLimited("1"));
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

    // ===========================================================================================
    // The budget. Every case below was unreachable while the helper hardcoded 70 s, which is why
    // the skip branch at OpenRouterAnalysisService:116 had never executed in a test.
    // ===========================================================================================

    /**
     * Budget already spent on arrival. The primary must still be attempted — the check at
     * {@code :116} is guarded on {@code lastFailure != null} precisely so a request that arrives
     * late gets one real try rather than an instant 502 — and no fallback may be, because there is
     * no budget left to walk the chain with. The client then sees the Phase 1 exhausted-chain cause.
     *
     * <p>404 on the primary, so the skip being asserted is the chain-level one and not the
     * wait-does-not-fit rule tested below.
     */
    @Test
    void budgetAlreadySpent_stillAttemptsThePrimaryAndSkipsEveryFallback() {
        svc = buildWithMockParser(List.of(FALLBACK), 0);
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.ALL_CANDIDATES_EXHAUSTED);
        // Exactly one POST: a second would mean the fallback was tried past the deadline.
        mockServer.verify();
    }

    /**
     * A wait that does not fit the remaining budget makes this model unusable now, so the chain
     * moves on — it does not clamp the wait down and retry immediately, which is the 2026-08-26
     * regression.
     *
     * <p>Budget 3 s against a capped 6 s wait ({@code Retry-After: 60} → {@code MAX_RETRY_WAIT}).
     * Hand arithmetic: 6 s &gt; 3 s, so NEXT_MODEL.
     */
    @Test
    void retryAfterExceedingTheRemainingBudget_movesToTheNextModelWithoutWaiting() {
        svc = buildWithRealParser(List.of(FALLBACK), 3);
        mockServer.expect(requestTo(URL)).andRespond(rateLimited("60"));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // The second POST went to the fallback, not to the primary on a clamped-to-zero retry.
        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        assertThat(elapsedMs).as("no wait may be taken for a model we gave up on").isLessThan(900);
        mockServer.verify();
    }

    /**
     * {@code Retry-After} as an HTTP-date, which a CDN or reverse proxy in front of the provider
     * emits routinely.
     *
     * <p>Budget 3 s is the whole point: a date 60 s out caps to a 6 s wait, which does not fit, so
     * the chain moves on. Read as {@code DEFAULT_RETRY_WAIT} instead, 1 s <em>would</em> fit and
     * the primary would be retried — which is exactly what the assertion on the answering model
     * catches. A generous budget cannot tell the two apart.
     */
    @Test
    void retryAfterAsAnHttpDate_isParsedRatherThanFallingBackToOneSecond() {
        svc = buildWithRealParser(List.of(FALLBACK), 3);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60));
        mockServer.expect(requestTo(URL)).andRespond(rateLimited(httpDate));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        assertThat(elapsedMs).isLessThan(900);
        mockServer.verify();
    }

    /**
     * {@code Retry-After: 0} is the provider saying "retry immediately". We decline: an immediate
     * retry into a saturated free pool is what turned single 429s into production 502s on
     * 2026-08-26. A different model is the better use of the request, so the chain moves on.
     */
    @Test
    void retryAfterZero_movesToTheNextModelInsteadOfRetryingImmediately() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL)).andRespond(rateLimited("0"));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        var result = svc.analyze("listing text");

        // Budget is generous here, so nothing but the zero-wait rule can move this off the primary.
        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        mockServer.verify();
    }

    /**
     * 408 Request Timeout is transient by definition, so the same model on a second attempt is the
     * right response. It used to fall into the catch-all and be treated as permanent for this
     * model, spending a fallback slug on a blip.
     */
    @Test
    void requestTimeout408_retriesTheSameModel() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result.meta().model()).isEqualTo(MODEL);
        assertThat(elapsedMs)
                .as("a transient fault with no Retry-After waits DEFAULT_RETRY_WAIT before retrying")
                .isGreaterThanOrEqualTo(900);
        mockServer.verify();
    }

    /**
     * 402 Payment Required is account-level, like 401/403: every candidate model rejects the request
     * the same way. It used to fall into the catch-all, which sent the chain off to spend one more
     * request per slug on an account that cannot pay for any of them.
     */
    @Test
    void paymentRequired402_failsImmediatelyAsRejectedCredentials() {
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThat(reasonOfThrownCall(() -> svc.analyze("listing text")))
                .isEqualTo(LlmCallException.Reason.REJECTED_CREDENTIALS);
        // One POST. A second would mean the fallback was walked on an unpayable account.
        mockServer.verify();
    }

    /**
     * An unrecognised 4xx walks on to the next model without spending a wait first — it is far
     * likelier to be a retired slug or a rejected request than a transient blip. Asserted so that
     * moving a status out of the catch-all is a decision someone makes rather than a default nobody
     * noticed.
     */
    @Test
    void unrecognised4xx_walksToTheNextModelWithoutWaiting() {
        svc = buildWithRealParser(List.of(FALLBACK));
        mockServer.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        mockServer.expect(requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        long t0 = System.nanoTime();
        var result = svc.analyze("listing text");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result.meta().model()).isEqualTo(FALLBACK);
        assertThat(elapsedMs).as("no retry wait is spent on a status classified as permanent")
                .isLessThan(900);
        mockServer.verify();
    }

    /** A 429 carrying {@code Retry-After} in whichever of the two spec forms the case is about. */
    private static ResponseCreator rateLimited(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        return withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers);
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
