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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

        // Deadline-aware on purpose: a transient fault we cannot afford to wait out is not a
        // retryable fault, it is an unusable model. See dispositionOf(e, deadlineAt).
        if (dispositionOf(failure, deadlineAt) != Disposition.RETRY) {
            throw failure;
        }

        // The disposition above already established that the requested wait fits the budget; the cap
        // is the separate question of how long we will block the request thread honouring it.
        Duration requested = retryWait(failure);
        Duration wait = atMostMaxWait(requested);
        log.warn("LLM call retry provider=openrouter model={} waitMs={} requestedMs={} cause={}",
                model, wait.toMillis(), requested.toMillis(), failure.getMessage());
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
     * 401 and 403 (key rejected) and 402 (out of credits).
     *
     * <p>Grouped because they share the one property that makes them FATAL: the rejection is
     * account-level, so every candidate model rejects the request the same way and walking the
     * fallback chain only multiplies latency before the identical error. 402 previously fell into
     * the catch-all and was treated as permanent for <em>this model</em>, which sent the chain off
     * to spend another request per slug on an account that cannot pay for any of them.
     *
     * <p>Single source for both the disposition and the user-facing cause, so the two cannot drift
     * apart when the list changes.
     *
     * <p>Oracle: OpenRouter's published status semantics, verified 2026-09-03.
     */
    private static boolean rejectsEveryModel(HttpStatusCode status) {
        int code = status.value();
        return code == 401 || code == 402 || code == 403;
    }

    private static LlmCallException.Reason reasonOf(RestClientException e) {
        if (e instanceof HttpStatusCodeException http && rejectsEveryModel(http.getStatusCode())) {
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
            // 408 belongs here rather than in the catch-all: a request timeout is transient by
            // definition, and the same model on a second attempt is exactly the right response.
            if (status.value() == 429 || status.value() == 408 || status.is5xxServerError()) {
                return Disposition.RETRY;
            }
            if (rejectsEveryModel(status)) {
                // Fail loudly instead of masking it behind a walk through every other model.
                return Disposition.FATAL;
            }
            // Everything else is permanent for this model: 404 = slug retired, 400 = request
            // rejected. Neither improves on a retry, and an unrecognised 4xx is far likelier to be
            // one of those than a transient blip — so the catch-all deliberately walks on instead
            // of spending a wait first. Asserted by a test, so adding a status above is a choice
            // someone made rather than a default nobody noticed.
            return Disposition.NEXT_MODEL;
        }
        if (cause instanceof RestClientException || cause instanceof IOException) {
            return Disposition.RETRY;
        }
        return Disposition.FATAL;
    }

    /**
     * Disposition with the remaining request budget taken into account.
     *
     * <p>A transient fault whose requested wait does not fit before the deadline makes this model
     * unusable <em>now</em>, and that is the next-model axis, not the retry axis. The old code
     * clamped the wait down to whatever was left instead — which at the deadline edge is zero, so
     * it retried immediately into the same saturated pool. That is the 2026-08-26 regression, and
     * clamping is what reproduced it: OpenRouter answers a saturated free pool with
     * {@code Retry-After: 60} against a 70 s budget already partly spent.
     *
     * <p>The comparison is against the wait the provider <em>asked for</em>, not against a capped
     * one — see {@link #retryWait}. Capping first made this branch reachable only under
     * {@link #MAX_RETRY_WAIT} of remaining budget, which is not the rule.
     */
    private static Disposition dispositionOf(LlmCallException e, long deadlineAt) {
        Disposition base = dispositionOf(e);
        if (base != Disposition.RETRY) {
            return base;
        }
        Duration wait = retryWait(e);
        Duration remaining = remainingUntil(deadlineAt);
        if (wait.compareTo(remaining) > 0) {
            log.warn("LLM retry skipped, requested wait exceeds remaining budget: waitMs={} remainingMs={}",
                    wait.toMillis(), remaining.toMillis());
            return Disposition.NEXT_MODEL;
        }
        return Disposition.RETRY;
    }

    /**
     * The wait the provider asked for, unmodified.
     *
     * <p>Clamped to nothing — not to the remaining budget, and <b>not to {@link #MAX_RETRY_WAIT}</b>.
     * Whether the requested wait fits is a disposition question, answered by
     * {@link #dispositionOf(LlmCallException, long)}; how long we are willing to block the request
     * thread once we have decided to retry is a separate one, answered at the sleep site by
     * {@link #atMostMaxWait}. Applying the cap here collapsed the two: the fit test then compared a
     * value already floored at 6 s, so "the wait must fit before the deadline" silently degenerated
     * into "fewer than 6 s remain" and a {@code Retry-After: 3600} was indistinguishable from a
     * {@code Retry-After: 7}. A provider that says the pool is busy for a minute is telling us to go
     * elsewhere, and that signal has to survive as far as the disposition.
     */
    private static Duration retryWait(LlmCallException failure) {
        Duration wait = DEFAULT_RETRY_WAIT;
        if (failure.getCause() instanceof HttpStatusCodeException http) {
            HttpHeaders headers = http.getResponseHeaders();
            String header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            Duration requested = parseRetryAfter(header);
            if (requested != null) {
                wait = requested;
            }
        }
        return wait;
    }

    /**
     * The cap, applied only once a retry has been decided on — the case the requested wait
     * <em>did</em> fit the budget. This wait sits on the request thread, so honouring a provider's
     * request in full is not on offer however well it fits.
     *
     * <p>The magnitude of the cap is deliberately unpinned by any test: observing it requires
     * actually sleeping {@link #MAX_RETRY_WAIT} on a suite that otherwise runs in seconds. What is
     * pinned is that a fitting wait is waited out at all ({@code Retry-After: 1}) and that a
     * non-fitting one moves the chain on without sleeping.
     */
    private static Duration atMostMaxWait(Duration requested) {
        return requested.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : requested;
    }

    /**
     * {@code Retry-After} in either form the spec allows: delta-seconds, or an HTTP-date.
     *
     * <p>The date form is not exotic — a CDN or reverse proxy in front of the provider emits it
     * routinely. Falling back to {@link #DEFAULT_RETRY_WAIT} read a 60-second date as one second,
     * which retried far too early <em>and</em> made the wait look like it comfortably fitted the
     * budget, defeating the skip rule above.
     *
     * @return the requested wait — possibly {@link Duration#ZERO}, which is the provider saying
     *     "retry immediately" and which {@link #sleepQuietly} deliberately declines — or
     *     {@code null} when the header is absent or unparseable, in which case
     *     {@link #DEFAULT_RETRY_WAIT} applies
     */
    private static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notDeltaSeconds) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration until = Duration.between(ZonedDateTime.now(when.getZone()), when);
            // A date already in the past says the same thing as a delta of 0.
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException notAnHttpDate) {
            return null;
        }
    }

    private static Duration remainingUntil(long deadlineAt) {
        return Duration.ofNanos(Math.max(0, deadlineAt - System.nanoTime()));
    }

    /**
     * @return false if the wait was not actually taken — either a non-positive wait or an interrupt.
     *     The caller must give up on this model in both cases.
     *
     *     <p>A non-positive wait is the provider's {@code Retry-After: 0}, or an HTTP-date already
     *     in the past: "retry immediately". We deliberately do not. An immediate retry into a
     *     saturated free pool is exactly what turned single 429s into production 502s on
     *     2026-08-26, and a different model is the better use of the request. Returning true here
     *     would let that wait fall straight through into the retry it exists to prevent.
     */
    private static boolean sleepQuietly(Duration wait) {
        if (wait.isZero() || wait.isNegative()) {
            return false;
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
