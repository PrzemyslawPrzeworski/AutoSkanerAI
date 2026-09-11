package com.example.autoskaner_ai.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one call that turns a verified token into an owner id.
 *
 * <p>Tested directly rather than only through a decoded token, because the missing-subject case
 * cannot be reached that way: {@link TokenService} always sets a subject, so the only way to see what
 * happens without one is to hand it a {@link Jwt} that has none. The answer must be a refusal — S-03
 * will pass the return value into {@code findByIdAndUserId}, and a lenient parse there is somebody
 * else's saved analysis.
 */
class AuthenticatedUserTest {

    @Test
    void readsTheAccountIdFromTheSubject() {
        assertThat(AuthenticatedUser.requireId(jwtWithSubject("42"))).isEqualTo(42L);
    }

    @Test
    void refusesATokenWithNoSubject() {
        assertThatThrownBy(() -> AuthenticatedUser.requireId(jwtWithSubject(null)))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("no subject");
    }

    @Test
    void refusesASubjectThatIsNotAnAccountId() {
        // An email is the plausible wrong subject: it is the other identifier in the token, and a
        // service that used it here would work until it met a repository call.
        assertThatThrownBy(() -> AuthenticatedUser.requireId(jwtWithSubject("owner@example.pl")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("not an account id");
    }

    @Test
    void refusesASubjectThatOverflowsAnAccountId() {
        assertThatThrownBy(
                        () ->
                                AuthenticatedUser.requireId(
                                        jwtWithSubject("999999999999999999999999")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private static Jwt jwtWithSubject(String subject) {
        Instant now = Instant.now();
        Jwt.Builder builder =
                Jwt.withTokenValue("irrelevant")
                        .header("alg", "HS256")
                        .issuedAt(now)
                        .expiresAt(now.plus(Duration.ofMinutes(15)));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }
}
