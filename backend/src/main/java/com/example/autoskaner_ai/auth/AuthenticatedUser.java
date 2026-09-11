package com.example.autoskaner_ai.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified token into the account id a repository query needs.
 *
 * <p>This exists so that S-03's rule — <em>{@code userId} comes from the authenticated principal,
 * never from the request body or a query parameter</em> — is one call rather than a convention. See
 * {@code context/foundation/test-plan.md} §2 risk #8: {@code SavedAnalysisRepository} makes
 * ownership part of the lookup, and that guarantee is worth nothing if the caller supplies the
 * owner.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {}

    /**
     * @throws InvalidCredentialsException if the subject is missing or not an account id. A token
     *     this service minted always carries a numeric subject, so reaching here means a token
     *     signed with the right key but built by something else — refused rather than parsed
     *     leniently.
     */
    public static long requireId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new InvalidCredentialsException("token has no subject", null);
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new InvalidCredentialsException("token subject is not an account id", e);
        }
    }
}
