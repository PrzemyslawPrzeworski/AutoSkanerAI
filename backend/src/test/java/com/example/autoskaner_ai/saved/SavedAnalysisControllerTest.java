package com.example.autoskaner_ai.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The wire contract of the five saved-analysis endpoints.
 *
 * <p>{@code standaloneSetup} matching this repository's other controller tests, so nothing here
 * depends on security configuration — the lock on {@code /api/**} is asserted only in
 * {@code ApiRequiresAuthenticationTest}. What this class does assert about identity is narrower and
 * is the whole point of the controller: the owner comes from the token's subject, and the request
 * bodies have nowhere to put one.
 */
class SavedAnalysisControllerTest {

    private static final long OWNER = 7L;
    private static final String VALID_BODY = """
            {"title":"Corolla z OLX","analysis":{"fetchStatus":"text","analysis":{"verdict":\
            {"code":"WORTH_CHECKING","label":"warto sprawdzić"}}}}""";

    private final SavedAnalysisService service = mock(SavedAnalysisService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SavedAnalysisController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        Jwt jwt = Jwt.withTokenValue("access.jwt")
                .header("alg", "HS256")
                .subject(String.valueOf(OWNER))
                .claim("typ", "access")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static SavedAnalysisDetailResponse detail(long id, String title, String note) {
        return new SavedAnalysisDetailResponse(
                new SavedAnalysisSummaryResponse(
                        id, title, note, "https://example.pl/oferta", "Toyota", "Corolla", 2019,
                        new BigDecimal("64900.00"), "PLN", 118_500, "WORTH_CHECKING", 68,
                        Instant.parse("2026-09-11T10:00:00Z"), Instant.parse("2026-09-11T10:00:00Z")),
                null);
    }

    @Test
    void savingAnswers201AndTakesTheOwnerFromTheToken() throws Exception {
        when(service.save(eq(OWNER), any())).thenReturn(detail(41L, "Corolla z OLX", null));

        mockMvc.perform(post("/api/saved-analyses").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.id").value(41))
                .andExpect(jsonPath("$.summary.title").value("Corolla z OLX"));

        verify(service).save(eq(OWNER), any());
    }

    /**
     * The structural half of "{@code userId} never comes from the caller": neither request record has
     * anywhere to put one. A reflection check rather than a rejected field, because the guarantee
     * worth keeping is that the field does not exist — a test that posts {@code userId} and expects it
     * to be ignored passes just as well when someone adds the field and wires it up.
     */
    @Test
    void neitherRequestBodyHasAUserIdToOverrideTheTokenWith() {
        for (Class<?> body : List.of(SaveAnalysisRequest.class, EditSavedAnalysisRequest.class)) {
            assertThat(Arrays.stream(body.getRecordComponents()).map(RecordComponent::getName))
                    .as("%s must not accept an owner from the caller", body.getSimpleName())
                    .noneMatch(name -> name.toLowerCase().contains("user"));
        }
    }

    @Test
    void savingWithABlankTitleIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/saved-analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"analysis\":{\"fetchStatus\":\"text\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Błąd walidacji"));

        verify(service, never()).save(anyLong(), any());
    }

    /** A {@code url_failed} response has no analysis in it; saving one would list as an empty card. */
    @Test
    void savingAResponseWithNoAnalysisIs400() throws Exception {
        mockMvc.perform(post("/api/saved-analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nieudane\",\"analysis\":{\"fetchStatus\":\"url_failed\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("analysis: nie zawiera wyniku analizy"));

        verify(service, never()).save(anyLong(), any());
    }

    @Test
    void listingAnswersTheCallersRows() throws Exception {
        when(service.list(OWNER)).thenReturn(List.of(detail(41L, "Corolla", null).summary()));

        mockMvc.perform(get("/api/saved-analyses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(41))
                .andExpect(jsonPath("$[0].title").value("Corolla"))
                .andExpect(jsonPath("$[0].verdictCode").value("WORTH_CHECKING"));

        verify(service).list(OWNER);
    }

    @Test
    void readingOneAnswersTheDetail() throws Exception {
        when(service.get(OWNER, 41L)).thenReturn(detail(41L, "Corolla", "Do obejrzenia"));

        mockMvc.perform(get("/api/saved-analyses/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.note").value("Do obejrzenia"));
    }

    @Test
    void aMissingOrForeignAnalysisIs404InTheLockedErrorShape() throws Exception {
        when(service.get(OWNER, 999L)).thenThrow(new SavedAnalysisNotFoundException(999L));

        mockMvc.perform(get("/api/saved-analyses/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Nie znaleziono analizy"))
                .andExpect(jsonPath("$.messages[0]")
                        .value("Ta analiza nie istnieje lub nie należy do Ciebie."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void editingAnswersTheUpdatedRow() throws Exception {
        when(service.edit(eq(OWNER), eq(41L), any()))
                .thenReturn(detail(41L, "Nowy tytuł", "Sprawdzone"));

        mockMvc.perform(patch("/api/saved-analyses/41").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nowy tytuł\",\"note\":\"Sprawdzone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.title").value("Nowy tytuł"))
                .andExpect(jsonPath("$.summary.note").value("Sprawdzone"));

        verify(service).edit(eq(OWNER), eq(41L), any());
    }

    @Test
    void editingWithABlankTitleIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(patch("/api/saved-analyses/41").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"note\":\"cokolwiek\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).edit(anyLong(), anyLong(), any());
    }

    @Test
    void deletingAnswers204() throws Exception {
        mockMvc.perform(delete("/api/saved-analyses/41"))
                .andExpect(status().isNoContent());

        verify(service).delete(OWNER, 41L);
    }

    @Test
    void deletingSomethingThatIsNotYoursIs404() throws Exception {
        org.mockito.Mockito.doThrow(new SavedAnalysisNotFoundException(999L))
                .when(service).delete(OWNER, 999L);

        mockMvc.perform(delete("/api/saved-analyses/999"))
                .andExpect(status().isNotFound());
    }
}
