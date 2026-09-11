package com.example.autoskaner_ai.auth;

import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/** Mints both tokens, and is the only place that reads a refresh token back. */
@Service
public class TokenService {

    static final String EMAIL_CLAIM = "email";

    private final JwtEncoder encoder;
    private final JwtDecoder refreshTokenDecoder;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    TokenService(
            JwtEncoder encoder,
            SecretKey jwtSigningKey,
            @Value("${auth.jwt.access-token-ttl}") Duration accessTokenTtl,
            @Value("${auth.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.encoder = encoder;
        this.refreshTokenDecoder = JwtConfig.decoderFor(jwtSigningKey, TokenType.REFRESH);
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * The subject is the account id, not the email: the email is mutable in principle and the id is
     * what {@code analyses.user_id} references, so a controller reading the principal gets the
     * value it needs for a lookup without a second query. The email rides along as a claim purely
     * so the frontend can render "logged in as" without a round trip.
     */
    public AuthTokens mintFor(long userId, String email) {
        return new AuthTokens(
                mint(userId, email, TokenType.ACCESS, accessTokenTtl),
                mint(userId, email, TokenType.REFRESH, refreshTokenTtl),
                accessTokenTtl.toSeconds());
    }

    /**
     * @return the account id carried by a valid, unexpired refresh token
     * @throws InvalidCredentialsException for anything else — a wrong signature, an expired token,
     *     a foreign issuer, or an <em>access</em> token offered in place of a refresh one. The
     *     caller cannot tell these apart on purpose; none of them is actionable by the user beyond
     *     logging in again.
     */
    public long userIdFromRefreshToken(String refreshToken) {
        Jwt jwt;
        try {
            jwt = refreshTokenDecoder.decode(refreshToken);
        } catch (JwtException e) {
            throw new InvalidCredentialsException("refresh token rejected: " + e.getMessage(), e);
        }
        return AuthenticatedUser.requireId(jwt);
    }

    private String mint(long userId, String email, TokenType type, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(JwtConfig.ISSUER)
                        .subject(String.valueOf(userId))
                        .issuedAt(now)
                        .expiresAt(now.plus(ttl))
                        .claim(TokenType.CLAIM, type.claimValue())
                        .claim(EMAIL_CLAIM, email)
                        .build();
        // The header is explicit because NimbusJwtEncoder defaults to RS256, which an
        // ImmutableSecret cannot sign with — the failure is a runtime error, not a compile one.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
