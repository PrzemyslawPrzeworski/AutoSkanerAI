package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskAnalysisControllerTest {

    private MockMvc mockMvc;
    private AiAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        aiAnalysisService = mock(AiAnalysisService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RiskAnalysisController(aiAnalysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsRiskFlags_whenValidInput() throws Exception {
        when(aiAnalysisService.analyzeRisks(anyString())).thenReturn(List.of(
                new RiskFlag("NO_VIN", "HIGH", "Brak numeru VIN")
        ));

        mockMvc.perform(post("/api/analysis/risk")
                        .contentType("application/json")
                        .content("{\"listingText\":\"Sprzedam auto, rok 2018, benzyna.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskFlags[0].code").value("NO_VIN"))
                .andExpect(jsonPath("$.riskFlags[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.riskFlags[0].description").value("Brak numeru VIN"));
    }

    @Test
    void returnsEmptyFlags_whenServiceReturnsNone() throws Exception {
        when(aiAnalysisService.analyzeRisks(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/api/analysis/risk")
                        .contentType("application/json")
                        .content("{\"listingText\":\"Świetne auto, bez uwag.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskFlags").isArray())
                .andExpect(jsonPath("$.riskFlags").isEmpty());
    }

    @Test
    void returns400_whenListingTextIsBlank() throws Exception {
        mockMvc.perform(post("/api/analysis/risk")
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
        mockMvc.perform(post("/api/analysis/risk")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Błąd walidacji"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returns400_whenBodyIsNotJson() throws Exception {
        mockMvc.perform(post("/api/analysis/risk")
                        .contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
