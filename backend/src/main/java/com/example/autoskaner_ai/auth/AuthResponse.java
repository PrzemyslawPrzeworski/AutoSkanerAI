package com.example.autoskaner_ai.auth;

/**
 * What register, login and refresh all return.
 *
 * <p>{@code email} is included so the frontend can render "logged in as" after a refresh-only
 * bootstrap — on a page reload the in-memory access token is gone and only the stored refresh token
 * survives, so the response to that refresh is the only place the identity can come from without an
 * extra round trip.
 */
public record AuthResponse(
        String accessToken, String refreshToken, long expiresIn, String email) {

    static AuthResponse of(String email, AuthTokens tokens) {
        return new AuthResponse(
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresInSeconds(), email);
    }
}
