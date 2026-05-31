package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("mock")
class AnalysisControllerTest {

    private MockMvc mockMvc;
    private AiAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        aiAnalysisService = mock(AiAnalysisService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(aiAnalysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AnalysisResult fullResult() {
        var extracted = new ExtractedData("BMW", "3 Series", 2018, null, null, 120000,
                "benzyna", null, null, null, Boolean.TRUE, "bezwypadkowy", Boolean.TRUE);
        var equipment = List.of(
                new EquipmentItem("klimatyzacja", EquipmentStatus.CONFIRMED, null),
                new EquipmentItem("tempomat", EquipmentStatus.UNCLEAR, "Nie wspomniano w ogłoszeniu")
        );
        var flags = List.of(
                new RiskFlag("NO_SERVICE_HISTORY", RiskSeverity.MEDIUM, "Brak historii serwisowej")
        );
        var questions = List.of("Czy pojazd był w wypadku?", "Jaki jest powód sprzedaży?");
        var scores = new CategoryScores(83, 50, 75, 60, 67);
        var verdict = new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić");
        var meta = new AnalysisMeta("mock", "mock-v1", 5L, Instant.now());
        return new AnalysisResult(extracted, equipment, flags, questions, scores, verdict, meta);
    }

    @Test
    void returnsFullAnalysisResult_whenValidInput() throws Exception {
        when(aiAnalysisService.analyze(anyString())).thenReturn(fullResult());

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"BMW 3 Series 2018, bezwypadkowy, VIN dostępny\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extracted").exists())
                .andExpect(jsonPath("$.equipment").isArray())
                .andExpect(jsonPath("$.riskFlags").isArray())
                .andExpect(jsonPath("$.sellerQuestions").isArray())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.verdict").exists())
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.provider").value("mock"))
                .andExpect(jsonPath("$.verdict.code").value("WORTH_CHECKING"));
    }

    @Test
    void returns400_whenListingTextIsBlank() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.messages[0]").value("listingText: nie może być pusty"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returns400_whenListingTextIsMissing() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returns400_whenBodyIsNotJson() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
