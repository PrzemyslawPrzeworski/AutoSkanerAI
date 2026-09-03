package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenRouter chat-completions client with a two-dimensional failure strategy: retry the same
 * model once for transient faults, then move to the next candidate model.
 *
 * <p>Both dimensions exist because free OpenRouter slugs fail in two distinct ways that a
 * single immediate retry cannot cover. On 2026-08-26 {@code z-ai/glm-5.2:free} went from
 * passing to globally rate-limited within fifteen minutes, and the earlier default
 * {@code meta-llama/llama-3.3-70b-instruct:free} was withdrawn from the free tier and began
 * 404ing. A saturated shared pool answers 429 with {@code Retry-After}, which the previous
 * immediate retry ignored — turning one transient fault into two instant failures and a 502
 * for the user. A retired slug never recovers, so retrying it at all is wasted latency; only
 * a different model helps.
 */
@Service
@Profile("openrouter")
public class OpenRouterAnalysisService implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAnalysisService.class);

    /** Used when a 429 carries no usable {@code Retry-After}. */
    private static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(1);
    /** Upper bound on honouring {@code Retry-After} — this wait sits on the request thread. */
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(6);

    /** What to do with a failed attempt. */
    private enum Disposition {
        /** Transient: same model may succeed after a wait. */
        RETRY,
        /** Permanent for this model (slug retired, request rejected) — try the next one. */
        NEXT_MODEL,
        /** Pointless to try anything else (bad credentials, malformed response shape). */
        FATAL
    }

    /** Marks a well-formed HTTP response whose JSON shape is unusable. Never retried. */
    private static final class ResponseShapeException extends RuntimeException {
        ResponseShapeException(String message) {
            super(message);
        }
    }

    private record Attempt(Map<?, ?> response, String content) {
    }

    private final AnalysisPrompt prompt;
    private final AnalysisResponseParser parser;
    private final RestClient restClient;
    private final List<String> models;
    private final Duration deadline;

    public OpenRouterAnalysisService(
            AnalysisPrompt prompt,
            AnalysisResponseParser parser,
            @Qualifier("openRouterBuilder") RestClient.Builder openRouterBuilder,
            @Value("${llm.openrouter.model}") String model,
            @Value("${llm.openrouter.fallback-models:}") List<String> fallbackModels,
            @Value("${llm.openrouter.deadline-seconds:70}") long deadlineSeconds) {
        this.prompt = prompt;
        this.parser = parser;
        this.restClient = openRouterBuilder.build();
        this.deadline = Duration.ofSeconds(deadlineSeconds);

        // LinkedHashSet: primary first, order preserved, a slug repeated in the fallback list
        // silently collapsed rather than tried twice.
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(model.strip());
        for (String fallback : fallbackModels) {
            if (fallback != null && !fallback.isBlank()) {
                ordered.add(fallback.strip());
            }
        }
        this.models = List.copyOf(ordered);
    }

    @Override
    public AnalysisResult analyze(String listingText) {
        long t0 = System.nanoTime();
        long deadlineAt = t0 + deadline.toNanos();

        Attempt attempt = null;
        String usedModel = null;
        LlmCallException lastFailure = null;
        List<String> skipped = new ArrayList<>();

        for (String candidate : models) {
            // Guarded on lastFailure so the first model is always attempted, however late the
            // request arrives; the budget only limits how far the fallback chain walks.
            if (lastFailure != null && System.nanoTime() >= deadlineAt) {
                skipped.add(candidate);
                continue;
            }
            try {
                attempt = attemptWithRetry(candidate, listingText, deadlineAt);
                usedModel = candidate;
                break;
            } catch (LlmCallException e) {
                lastFailure = e;
                if (dispositionOf(e) == Disposition.FATAL) {
                    log.error("LLM call failed provider=openrouter model={} cause={} (not retryable, no fallback)",
                            candidate, e.getMessage());
                    throw e;
                }
                log.warn("LLM model exhausted provider=openrouter model={} cause={}", candidate, e.getMessage());
            }
        }

        if (!skipped.isEmpty()) {
            log.warn("LLM fallback budget of {}s exhausted, never tried: {}", deadline.toSeconds(), skipped);
        }

        if (usedModel == null) {
            log.error("LLM call failed provider=openrouter models={} — all candidates exhausted", models);
            throw new LlmCallException("OpenRouter exhausted all candidate models: " + models,
                    lastFailure, LlmCallException.Reason.ALL_CANDIDATES_EXHAUSTED);
        }

        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        int inputTokens = extractTokens(attempt.response(), "prompt_tokens");
        int outputTokens = extractTokens(attempt.response(), "completion_tokens");
        log.info("LLM call provider={} model={} latencyMs={} inputTokens={} outputTokens={} listingChars={}",
                "openrouter", usedModel, latencyMs, inputTokens, outputTokens, listingText.length());

        // Deliberately outside the fallback loop: a schema failure means the prompt and the
        // parser disagree, and burning two more models on the same mismatch hides that behind
        // tripled latency. Keeps parity with the Bedrock contract.
        return parser.parse(attempt.content(), "openrouter", usedModel, latencyMs);
    }

    /** One model, at most two attempts. Throws the failure that ended the sequence. */
    private Attempt attemptWithRetry(String model, String listingText, long deadlineAt) {
        Map<String, Object> requestBody = buildRequestBody(model, listingText);

        LlmCallException failure;
        try {
            return callAndExtract(model, requestBody);
        } catch (LlmCallException e) {
            failure = e;
        }

        if (dispositionOf(failure) != Disposition.RETRY) {
            throw failure;
        }

        Duration wait = retryWait(failure, deadlineAt);
        log.warn("LLM call retry provider=openrouter model={} waitMs={} cause={}",
                model, wait.toMillis(), failure.getMessage());
        if (!sleepQuietly(wait)) {
            throw failure;
        }
        return callAndExtract(model, requestBody);
    }

    private Attempt callAndExtract(String model, Map<String, Object> requestBody) {
        Map<?, ?> response = callApiRaw(model, requestBody);
        return new Attempt(response, extractContent(response));
    }

    private Map<?, ?> callApiRaw(String model, Map<String, Object> requestBody) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw shapeFailure("null body");
            }
            return response;
        } catch (LlmCallException e) {
            throw e;
        } catch (RestClientException e) {
            // Includes HttpStatusCodeException; dispositionOf reads the status off the cause.
            throw new LlmCallException("OpenRouter HTTP error model=" + model, e, reasonOf(e));
        }
    }

    /**
     * Walks {@code choices[0].message.content} defensively, because every step of that walk is a
     * shape a provider can get wrong while still answering 200.
     *
     * <p>The previous version cast blindly. A null {@code message} NPE'd, a non-String
     * {@code content} threw {@code ClassCastException}, and a null {@code content} was returned as
     * null and NPE'd later inside the parser. None of those three is an
     * {@code IllegalArgumentException}, so none reached a handler that knew what had happened —
     * each surfaced as the catch-all 500 "Błąd serwera" for what is plainly a bad provider response.
     * All of them are now {@link ResponseShapeException}, which {@link #dispositionOf} already
     * classifies FATAL, so the fallback walk is unchanged.
     */
    private String extractContent(Map<?, ?> response) {
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw shapeFailure("empty choices");
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            throw shapeFailure("choices[0] is not an object");
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            throw shapeFailure("choices[0].message is absent or not an object");
        }
        if (!(message.get("content") instanceof String content)) {
            throw shapeFailure("choices[0].message.content is absent or not a string");
        }
        if (content.isBlank()) {
            throw shapeFailure("choices[0].message.content is blank");
        }
        return content;
    }

    /** A 200 whose JSON cannot yield an analysis. Fatal, and named as such for the user. */
    private static LlmCallException shapeFailure(String detail) {
        return new LlmCallException("OpenRouter returned an unusable response: " + detail,
                new ResponseShapeException(detail),
                LlmCallException.Reason.UNUSABLE_PROVIDER_RESPONSE);
    }

    /**
     * 401/403. Single source for both the FATAL disposition and the user-facing cause, so the two
     * cannot drift apart when the list changes.
     */
    private static boolean isRejectedCredentials(HttpStatusCode status) {
        return status.value() == 401 || status.value() == 403;
    }

    private static LlmCallException.Reason reasonOf(RestClientException e) {
        if (e instanceof HttpStatusCodeException http && isRejectedCredentials(http.getStatusCode())) {
            return LlmCallException.Reason.REJECTED_CREDENTIALS;
        }
        return LlmCallException.Reason.UNCLASSIFIED;
    }

    private static Disposition dispositionOf(LlmCallException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ResponseShapeException) {
            return Disposition.FATAL;
        }
        // Checked before RestClientException: HttpStatusCodeException is a subtype.
        if (cause instanceof HttpStatusCodeException http) {
            HttpStatusCode status = http.getStatusCode();
            if (status.value() == 429 || status.is5xxServerError()) {
                return Disposition.RETRY;
            }
            if (isRejectedCredentials(status)) {
                // A rejected key rejects every model — fail loudly instead of masking it.
                return Disposition.FATAL;
            }
            // 404 = slug retired, 400 = request rejected. Neither improves on retry.
            return Disposition.NEXT_MODEL;
        }
        if (cause instanceof RestClientException || cause instanceof IOException) {
            return Disposition.RETRY;
        }
        return Disposition.FATAL;
    }

    /** Honours {@code Retry-After}, bounded by {@link #MAX_RETRY_WAIT} and the deadline. */
    private static Duration retryWait(LlmCallException failure, long deadlineAt) {
        Duration wait = DEFAULT_RETRY_WAIT;
        if (failure.getCause() instanceof HttpStatusCodeException http) {
            HttpHeaders headers = http.getResponseHeaders();
            String header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (header != null) {
                try {
                    long seconds = Long.parseLong(header.strip());
                    if (seconds > 0) {
                        wait = Duration.ofSeconds(seconds);
                    }
                } catch (NumberFormatException ignored) {
                    // Retry-After may also be an HTTP-date; the default wait covers that.
                }
            }
        }
        if (wait.compareTo(MAX_RETRY_WAIT) > 0) {
            wait = MAX_RETRY_WAIT;
        }
        Duration remaining = Duration.ofNanos(Math.max(0, deadlineAt - System.nanoTime()));
        return wait.compareTo(remaining) > 0 ? remaining : wait;
    }

    /** @return false if the thread was interrupted, in which case the caller must give up. */
    private static boolean sleepQuietly(Duration wait) {
        if (wait.isZero() || wait.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(wait.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int extractTokens(Map<?, ?> response, String key) {
        if (response == null) return -1;
        Object usage = response.get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Object val = usageMap.get(key);
            if (val instanceof Number n) return n.intValue();
        }
        return -1;
    }

    private Map<String, Object> buildRequestBody(String model, String listingText) {
        return Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.systemPrompt()),
                        Map.of("role", "user", "content", prompt.userMessage(listingText))
                ),
                "temperature", 0.2,
                "max_tokens", 8192
        );
    }
}
