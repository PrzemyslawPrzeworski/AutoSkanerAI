package com.example.autoskaner_ai.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The token pair, minted and read back without a Spring context.
 *
 * <p>The tests that matter here are the ones asserting a token is <em>refused</em>. Both tokens are
 * signed with the same key, so the signature cannot tell them apart — everything separating a
 * fortnight-long refresh token from a credential that opens the API is the {@code typ} claim and the
 * per-decoder validator that checks it. See {@link TokenType}.
 */
class TokenServiceTest {

    private static final SecretKey KEY = key("test-signing-key-of-at-least-32-bytes-long");
    private static final SecretKey OTHER_KEY = key("a-different-signing-key-also-32-bytes-plus");

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    private final JwtDecoder accessDecoder = JwtConfig.decoderFor(KEY, TokenType.ACCESS);
    private final TokenService tokenService = serviceWith(KEY, ACCESS_TTL, REFRESH_TTL);

    @Test
    void mintsAnAccessTokenTheResourceServerDecoderAccepts() {
        AuthTokens tokens = tokenService.mintFor(42L, "owner@example.pl");

        Jwt jwt = accessDecoder.decode(tokens.accessToken());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString(TokenService.EMAIL_CLAIM)).isEqualTo("owner@example.pl");
        assertThat(jwt.getClaimAsString(TokenType.CLAIM)).isEqualTo("access");
        // Read as a string, not via getIssuer(): that accessor insists the claim parse as a URL and
        // throws on a bare name. JwtConfig's own issuer validator reads the claim the same way.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("autoskaner-ai");
    }

    @Test
    void reportsTheAccessTokenLifetimeSoTheClientCanRefreshBeforeItExpires() {
        AuthTokens tokens = tokenService.mintFor(1L, "owner@example.pl");

        assertThat(tokens.expiresInSeconds()).isEqualTo(ACCESS_TTL.toSeconds());
    }

    @Test
    void theTwoTokensAreDistinctValues() {
        AuthTokens tokens = tokenService.mintFor(1L, "owner@example.pl");

        assertThat(tokens.accessToken()).isNotEqualTo(tokens.refreshToken());
    }

    /**
     * The load-bearing assertion of the whole change. A refresh token lives fourteen days and is
     * stored in {@code localStorage}; if the resource server accepted it as a bearer token, that
     * storage decision would silently become "the API credential sits in localStorage for a
     * fortnight". Nothing but the {@code typ} claim prevents it — the signature is identical.
     */
    @Test
    void aRefreshTokenIsRefusedAsAnAccessToken() {
        AuthTokens tokens = tokenService.mintFor(42L, "owner@example.pl");

        assertThatThrownBy(() -> accessDecoder.decode(tokens.refreshToken()))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access");
    }

    /** The mirror image: the short-lived token must not be able to extend a session either. */
    @Test
    void anAccessTokenIsRefusedWhereARefreshTokenIsExpected() {
        AuthTokens tokens = tokenService.mintFor(42L, "owner@example.pl");

        assertThatThrownBy(() -> tokenService.userIdFromRefreshToken(tokens.accessToken()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void readsTheAccountIdBackFromAValidRefreshToken() {
        AuthTokens tokens = tokenService.mintFor(7L, "owner@example.pl");

        assertThat(tokenService.userIdFromRefreshToken(tokens.refreshToken())).isEqualTo(7L);
    }

    /**
     * Both expiry tests hand-build the timestamps rather than configuring a negative TTL, because
     * {@code NimbusJwtEncoder} asserts {@code expiresAt} is after {@code issuedAt} and so cannot mint
     * an already-dead token at all — a small guarantee worth knowing, and the reason a negative-TTL
     * service throws {@code IllegalArgumentException} instead of producing one.
     *
     * <p>The window closed five minutes ago, not thirty seconds ago: {@link
     * org.springframework.security.oauth2.jwt.JwtTimestampValidator} allows 60 seconds of clock skew
     * by default, so a token one minute stale still validates and this test would pass for the wrong
     * reason while an expiry check was in fact broken.
     */
    @Test
    void anExpiredAccessTokenIsRefused() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        String stale =
                mintRaw(
                        KEY,
                        JwtConfig.ISSUER,
                        TokenType.ACCESS,
                        "42",
                        tenMinutesAgo,
                        tenMinutesAgo.plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> accessDecoder.decode(stale)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredRefreshTokenCannotMintAnything() {
        Instant aMonthAgo = Instant.now().minus(Duration.ofDays(30));
        String stale =
                mintRaw(
                        KEY,
                        JwtConfig.ISSUER,
                        TokenType.REFRESH,
                        "42",
                        aMonthAgo,
                        aMonthAgo.plus(REFRESH_TTL));

        assertThatThrownBy(() -> tokenService.userIdFromRefreshToken(stale))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        AuthTokens forged = serviceWith(OTHER_KEY, ACCESS_TTL, REFRESH_TTL)
                .mintFor(42L, "attacker@example.pl");

        assertThatThrownBy(() -> accessDecoder.decode(forged.accessToken()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokenService.userIdFromRefreshToken(forged.refreshToken()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * The issuer check is not decoration: it is what stops a token minted by some other service
     * that happens to share this key — a copy-pasted secret in a sibling app, say — from being read
     * as an account here.
     */
    @Test
    void aCorrectlySignedTokenFromAForeignIssuerIsRefused() {
        String foreign = mintRaw(KEY, "somebody-else", TokenType.ACCESS, "42");

        assertThatThrownBy(() -> accessDecoder.decode(foreign))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("autoskaner-ai");
    }

    /** A token with no {@code typ} at all must fail closed, not default to "access". */
    @Test
    void aTokenWithNoTypeClaimIsRefusedByBothDecoders() {
        String untyped = mintRaw(KEY, JwtConfig.ISSUER, null, "42");

        assertThatThrownBy(() -> accessDecoder.decode(untyped)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokenService.userIdFromRefreshToken(untyped))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * {@code AuthenticatedUser.requireId} is what S-03 will use to scope a saved analysis to its
     * owner, so a subject that is not an account id has to be a rejection rather than a parse
     * exception escaping as a 500.
     */
    @Test
    void aRefreshTokenWhoseSubjectIsNotAnAccountIdIsRefused() {
        String named = mintRaw(KEY, JwtConfig.ISSUER, TokenType.REFRESH, "owner@example.pl");

        assertThatThrownBy(() -> tokenService.userIdFromRefreshToken(named))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("subject");
    }

    private static TokenService serviceWith(SecretKey key, Duration access, Duration refresh) {
        return new TokenService(encoderFor(key), key, access, refresh);
    }

    private static JwtEncoder encoderFor(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** Mints a token the production code would never produce, to test what happens when one shows up. */
    private static String mintRaw(SecretKey key, String issuer, TokenType type, String subject) {
        Instant now = Instant.now();
        return mintRaw(key, issuer, type, subject, now, now.plus(Duration.ofMinutes(15)));
    }

    private static String mintRaw(
            SecretKey key,
            String issuer,
            TokenType type,
            String subject,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet.Builder claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject(subject)
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt);
        if (type != null) {
            claims.claim(TokenType.CLAIM, type.claimValue());
        }
        return encoderFor(key)
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
