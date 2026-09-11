package com.example.autoskaner_ai.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The wire contract of the three public auth endpoints: status codes, the response body, and which
 * bad input is a 400 rather than a 401.
 *
 * <p>{@code standaloneSetup} on purpose, matching this repository's other controller tests — there is
 * no filter chain here, so nothing in this class can accidentally depend on security configuration.
 * The lock on {@code /api/**} is asserted separately, and only, in {@link
 * ApiRequiresAuthenticationTest}.
 */
class AuthControllerTest {

    private static final AuthTokens TOKENS = new AuthTokens("access.jwt", "refresh.jwt", 900L);

    private final AuthService authService = mock(AuthService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registeringAnswers201WithTheTokenPair() throws Exception {
        when(authService.register("owner@example.pl", "correct-horse"))
                .thenReturn(AuthResponse.of("owner@example.pl", TOKENS));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.jwt"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.email").value("owner@example.pl"));
    }

    @Test
    void registeringWithAMalformedEmailIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.messages[0]").value("email: podaj poprawny adres e-mail"));

        verify(authService, never()).register(any(), any());
    }

    @Test
    void registeringWithAShortPasswordIs400() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]")
                        .value("password: hasło musi mieć od 8 do 72 znaków"));
    }

    /**
     * BCrypt hashes only the first 72 bytes of its input, so a longer password is refused rather than
     * silently truncated — otherwise two different long passwords would open the same account.
     */
    @Test
    void registeringWithAPasswordPastTheBcryptLimitIs400() throws Exception {
        String tooLong = "x".repeat(73);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"" + tooLong
                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]")
                        .value("password: hasło musi mieć od 8 do 72 znaków"));
    }

    @Test
    void registeringATakenEmailIs409() throws Exception {
        when(authService.register(any(), any()))
                .thenThrow(new EmailAlreadyRegisteredException("account already exists"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.pl\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Konto już istnieje"));
    }

    @Test
    void loggingInAnswers200WithTheTokenPair() throws Exception {
        when(authService.login("owner@example.pl", "correct-horse"))
                .thenReturn(AuthResponse.of("owner@example.pl", TOKENS));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.email").value("owner@example.pl"));
    }

    /**
     * A malformed address at login is a failed login, not a 400: two different statuses for the same
     * mistake would tell a caller which addresses are shaped like registered ones. Hence
     * {@code LoginRequest} carries {@code @NotBlank} and no {@code @Email}.
     */
    @Test
    void loggingInWithAMalformedEmailIsAFailedLoginAndNotAValidationError() throws Exception {
        when(authService.login(eq("not-an-email"), any()))
                .thenThrow(new InvalidCredentialsException("no account for the supplied email",
                        null));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Nieprawidłowe dane logowania"));
    }

    /** The 401 body says only that the pair was wrong — never which half. */
    @Test
    void aFailedLoginSaysNothingAboutWhichHalfWasWrong() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(new InvalidCredentialsException(
                        "wrong password for account 42", null));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.messages[0]").value("Nieprawidłowy e-mail lub hasło."))
                .andExpect(jsonPath("$.messages[0]", not(containsString("42"))));
    }

    @Test
    void loggingInWithAnEmptyPasswordIs400() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.pl\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).login(any(), any());
    }

    @Test
    void refreshingAnswers200WithARotatedPair() throws Exception {
        when(authService.refresh("refresh.jwt"))
                .thenReturn(AuthResponse.of("owner@example.pl",
                        new AuthTokens("new.access", "new.refresh", 900L)));

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access"))
                .andExpect(jsonPath("$.refreshToken").value("new.refresh"));
    }

    @Test
    void refreshingWithARejectedTokenIs401() throws Exception {
        when(authService.refresh(any()))
                .thenThrow(new InvalidCredentialsException("refresh token rejected", null));

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshingWithNoTokenAtAllIs400() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).refresh(any());
    }
}
