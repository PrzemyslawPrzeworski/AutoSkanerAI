package com.example.autoskaner_ai.analysis.llm;

/**
 * An LLM call that did not produce an analysis.
 *
 * <p>Carries a {@link Reason} because a user who waited out an analysis and got a 502 needs to know
 * <em>which</em> failure it was: a rejected key never recovers on its own, a saturated model pool
 * usually does within minutes, and an unusable provider response is neither. All three previously
 * rendered the single string "Błąd usługi LLM", which made the three cases indistinguishable at the
 * only boundary a user can observe.
 */
public class LlmCallException extends RuntimeException {

    /** What the caller should be told, and roughly what they can do about it. */
    public enum Reason {
        /**
         * The provider rejected our credentials (401/403). Every model rejects the same key, so
         * retrying and walking the fallback chain both only add latency before the same error.
         */
        REJECTED_CREDENTIALS,
        /**
         * The provider answered with a well-formed HTTP response whose JSON shape cannot yield an
         * analysis — no choices, no message, a non-string or blank content.
         */
        UNUSABLE_PROVIDER_RESPONSE,
        /** Every candidate model in the fallback chain failed or was skipped. */
        ALL_CANDIDATES_EXHAUSTED,
        /** Anything else. Keeps the pre-existing generic message as the default. */
        UNCLASSIFIED
    }

    private final Reason reason;

    public LlmCallException(String message, Throwable cause) {
        this(message, cause, Reason.UNCLASSIFIED);
    }

    public LlmCallException(String message, Throwable cause, Reason reason) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
