package com.example.autoskaner_ai.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security filter chain, exercised over HTTP rather than read.
 *
 * <p>Every other test in this package can pass with an API that is wide open: they call services
 * directly. This one is the only place the {@code /api/**} lock itself is asserted, and it must run
 * through {@code @AutoConfigureMockMvc} — the repository's other controller tests use
 * {@code MockMvcBuilders.standaloneSetup}, which builds a dispatcher with no filter chain at all and
 * so would report 200 for an unauthenticated request no matter what {@code SecurityConfig} said.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class ApiRequiresAuthenticationTest {

    private static final String LISTING = "{\"listingText\":\"Toyota Corolla 2019, 90 000 km\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Test
    void theAnalysisEndpointRefusesAnAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 401 and 403 are produced by the filter chain, in front of the dispatcher, so
     * {@code GlobalExceptionHandler} cannot shape them. Without {@code AuthErrorResponder} the two
     * statuses a client meets most often would be the only two answering outside the locked
     * four-field envelope — Spring's bearer entry point sends an empty body.
     */
    @Test
    void anUnauthenticatedRefusalUsesTheApiErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Wymagane uwierzytelnienie"))
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages[0]").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void theAnalysisEndpointAnswersAnAuthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearerAccessToken())
                        .content(LISTING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").exists());
    }

    @Test
    void theCurrentUserEndpointRefusesAnAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCurrentUserEndpointReportsTheAccountBehindTheAccessToken() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearerAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(99))
                .andExpect(jsonPath("$.email").value("owner@example.pl"));
    }

    /**
     * The containment assertion, at the boundary that matters. {@link TokenServiceTest} proves the
     * decoder refuses a refresh token; this proves the <em>resource server</em> is wired to that
     * decoder and not to one that would accept either. Both tokens are signed with the same key, so
     * a single misconfigured bean here turns a fourteen-day token in {@code localStorage} into the
     * API credential.
     */
    @Test
    void aRefreshTokenDoesNotOpenTheApi() throws Exception {
        String refresh = tokenService.mintFor(99L, "owner@example.pl").refreshToken();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + refresh)
                        .content(LISTING))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbageBearerTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The three endpoints that cannot require a token, because they are how a client gets one.
     * Asserting "not 401" rather than a success status keeps the test about reachability: an empty
     * body is a 400 from validation, which is the endpoint answering.
     */
    @Test
    void theLoginEndpointIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.pl\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Nieprawidłowe dane logowania"));
    }

    @Test
    void theRegisterEndpointIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theRefreshEndpointIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not.a.token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Nieprawidłowe dane logowania"));
    }

    /** Render's platform health check has no credential to offer, and a 401 there is a dead deploy. */
    @Test
    void theHealthEndpointStaysOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /**
     * A preflight carries no {@code Authorization} header — the browser strips it — so if
     * {@code OPTIONS} required authentication the frontend would report an opaque CORS error for what
     * is really an authorization decision. This is also why the CORS rules moved into
     * {@code SecurityConfig}: a {@code WebMvcConfigurer} mapping is consulted after the filter chain
     * has already judged the request.
     */
    @Test
    void aPreflightFromTheFrontendOriginIsAnsweredWithoutAToken() throws Exception {
        mockMvc.perform(options("/api/analyses")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
    }

    @Test
    void aPreflightFromAnUnknownOriginIsNotGrantedAccess() throws Exception {
        mockMvc.perform(options("/api/analyses")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private String bearerAccessToken() {
        return "Bearer " + tokenService.mintFor(99L, "owner@example.pl").accessToken();
    }
}
