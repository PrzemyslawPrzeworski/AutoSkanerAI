package com.example.autoskaner_ai.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Everything that touches the signing key. Symmetric HS256, one secret, because there is one
 * service both minting and verifying — an asymmetric pair would add a key-distribution problem
 * that nothing here has.
 */
@Configuration
public class JwtConfig {

    /** HS256 requires a key of at least 256 bits; Nimbus otherwise throws about key length. */
    static final int MINIMUM_SECRET_BYTES = 32;

    static final String ISSUER = "autoskaner-ai";

    /**
     * No default, deliberately — an unset {@code AUTH_JWT_SECRET} must fail startup rather than
     * fall back to something. This repository is public, so a committed default would be a key
     * anyone could use to mint a token for any account. {@code application-mock.properties}
     * overrides it for the offline profile; production activates {@code openrouter,postgres}.
     */
    @Bean
    SecretKey jwtSigningKey(@Value("${auth.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for HS256; got " + bytes.length
                            + ". Set AUTH_JWT_SECRET to a longer random value.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * The decoder the resource server uses, and it accepts <strong>access tokens only</strong>.
     * The refresh decoder is not a bean: exposing two {@code JwtDecoder}s would make the resource
     * server's choice ambiguous, and the one thing that must never happen is the request path
     * picking up the decoder that accepts refresh tokens. {@link TokenService} builds that one
     * privately from the same key.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return decoderFor(jwtSigningKey, TokenType.ACCESS);
    }

    static NimbusJwtDecoder decoderFor(SecretKey key, TokenType required) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                        new JwtIssuerEqualsValidator(), new TokenTypeValidator(required)));
        return decoder;
    }

    /**
     * {@code JwtValidators.createDefaultWithIssuer} would also do this, but it builds an
     * {@code iss}-as-URL validator; the issuer here is a bare name, so the comparison is explicit.
     */
    private static final class JwtIssuerEqualsValidator implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            Object issuer = token.getClaim(JwtClaimNames.ISS);
            return ISSUER.equals(issuer == null ? null : issuer.toString())
                    ? OAuth2TokenValidatorResult.success()
                    : failure("token was not issued by " + ISSUER);
        }
    }

    /** See {@link TokenType} for why this is the load-bearing check and not a formality. */
    private static final class TokenTypeValidator implements OAuth2TokenValidator<Jwt> {
        private final TokenType required;

        private TokenTypeValidator(TokenType required) {
            this.required = required;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            return required.claimValue().equals(token.getClaimAsString(TokenType.CLAIM))
                    ? OAuth2TokenValidatorResult.success()
                    : failure("expected a " + required.claimValue() + " token");
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
