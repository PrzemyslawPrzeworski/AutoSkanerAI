package com.example.autoskaner_ai.auth;

/**
 * The two kinds of token this service mints, distinguished by the {@code typ} claim.
 *
 * <p>They are signed with the same key, so without this claim a refresh token presented as
 * {@code Authorization: Bearer …} would authenticate every request in the app — a 14-day
 * credential doing the job of a 15-minute one. Each decoder is built to accept exactly one type
 * (see {@link JwtConfig#decoderFor}), so neither token works where the other belongs.
 */
enum TokenType {
    ACCESS("access"),
    REFRESH("refresh");

    static final String CLAIM = "typ";

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    String claimValue() {
        return claimValue;
    }
}
