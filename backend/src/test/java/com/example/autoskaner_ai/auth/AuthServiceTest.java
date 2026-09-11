package com.example.autoskaner_ai.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.autoskaner_ai.account.UserAccount;
import com.example.autoskaner_ai.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and refresh against the real datasource — H2 in PostgreSQL compatibility mode,
 * the same one {@code ./mvnw test} and {@code spring-boot:run} use.
 *
 * <p>{@code @SpringBootTest} rather than a mocked repository because two of the guarantees here are
 * the database's, not the service's: the unique index is what actually stops a duplicate account, and
 * the case-insensitivity of a login is a property of normalisation meeting stored bytes.
 */
@SpringBootTest
@ActiveProfiles("mock")
@Transactional
class AuthServiceTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private JwtDecoder accessTokenDecoder;

    @Test
    void registeringReturnsAUsableTokenPairForTheNewAccount() {
        AuthResponse response = authService.register("owner@example.pl", PASSWORD);

        assertThat(response.email()).isEqualTo("owner@example.pl");
        long id = accounts.findByEmail("owner@example.pl").orElseThrow().getId();
        Jwt access = accessTokenDecoder.decode(response.accessToken());
        assertThat(access.getSubject()).isEqualTo(String.valueOf(id));
        assertThat(access.getClaimAsString(TokenService.EMAIL_CLAIM))
                .isEqualTo("owner@example.pl");
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.expiresIn()).isPositive();
    }

    @Test
    void registeringStoresAHashAndNotThePassword() {
        authService.register("hash@example.pl", PASSWORD);

        String stored = accounts.findByEmail("hash@example.pl").orElseThrow().getPasswordHash();

        assertThat(stored).doesNotContain(PASSWORD);
        // The prefix is what makes the delegating encoder able to verify old hashes after the
        // algorithm changes; a bare hash would be unupgradeable.
        assertThat(stored).startsWith("{bcrypt}$2a$");
    }

    @Test
    void registeringNormalisesTheEmailSoTheAccountIsFoundLater() {
        AuthResponse response = authService.register("  Owner@Example.PL ", PASSWORD);

        assertThat(response.email()).isEqualTo("owner@example.pl");
        assertThat(accounts.existsByEmail("owner@example.pl")).isTrue();
    }

    @Test
    void asecondRegistrationOfTheSameEmailIsRefused() {
        authService.register("taken@example.pl", PASSWORD);

        assertThatThrownBy(() -> authService.register("taken@example.pl", "another-password"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void aDifferentlyCasedRegistrationHitsTheSameAccount() {
        authService.register("case@example.pl", PASSWORD);

        assertThatThrownBy(() -> authService.register("CASE@Example.pl", PASSWORD))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void loggingInWithTheRightPasswordReturnsTokens() {
        authService.register("login@example.pl", PASSWORD);

        AuthResponse response = authService.login("login@example.pl", PASSWORD);

        assertThat(response.email()).isEqualTo("login@example.pl");
        assertThat(accessTokenDecoder.decode(response.accessToken()).getSubject()).isNotBlank();
    }

    @Test
    void loggingInIsCaseInsensitiveInTheEmail() {
        authService.register("mixed@example.pl", PASSWORD);

        AuthResponse response = authService.login("Mixed@EXAMPLE.pl", PASSWORD);

        assertThat(response.email()).isEqualTo("mixed@example.pl");
    }

    @Test
    void loggingInWithTheWrongPasswordIsRefused() {
        authService.register("wrong@example.pl", PASSWORD);

        assertThatThrownBy(() -> authService.login("wrong@example.pl", "not-the-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * Same exception type as a wrong password, deliberately — {@code GlobalExceptionHandler} maps
     * both to one 401 body, so the API cannot be used to find out which addresses are registered.
     */
    @Test
    void loggingInWithAnUnknownEmailIsRefusedTheSameWay() {
        assertThatThrownBy(() -> authService.login("stranger@example.pl", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aPasswordIsNotInterchangeableBetweenAccounts() {
        authService.register("first@example.pl", PASSWORD);
        authService.register("second@example.pl", "a-different-password");

        assertThatThrownBy(() -> authService.login("second@example.pl", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshingWithARefreshTokenReturnsANewUsableAccessToken() {
        AuthResponse registered = authService.register("refresh@example.pl", PASSWORD);

        AuthResponse refreshed = authService.refresh(registered.refreshToken());

        assertThat(refreshed.email()).isEqualTo("refresh@example.pl");
        Jwt access = accessTokenDecoder.decode(refreshed.accessToken());
        assertThat(access.getSubject())
                .isEqualTo(accessTokenDecoder.decode(registered.accessToken()).getSubject());
        // A rotated pair: the client replaces both, so a refresh that returned no refresh token
        // would silently pin the session to the original fortnight.
        assertThat(refreshed.refreshToken()).isNotBlank();
    }

    @Test
    void refreshingWithAnAccessTokenIsRefused() {
        AuthResponse registered = authService.register("swap@example.pl", PASSWORD);

        assertThatThrownBy(() -> authService.refresh(registered.accessToken()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshingWithNonsenseIsRefused() {
        assertThatThrownBy(() -> authService.refresh("not.a.token"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * The account is re-read on every refresh rather than trusted from the token, so a deleted
     * account cannot keep minting access tokens for the remaining fortnight of its refresh window.
     */
    @Test
    void aRefreshTokenForADeletedAccountIsRefused() {
        AuthResponse registered = authService.register("gone@example.pl", PASSWORD);
        UserAccount account = accounts.findByEmail("gone@example.pl").orElseThrow();
        accounts.delete(account);
        accounts.flush();

        assertThatThrownBy(() -> authService.refresh(registered.refreshToken()))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
