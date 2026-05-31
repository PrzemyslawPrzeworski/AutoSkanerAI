package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class StubController {
        @GetMapping("/test-llm-call")
        String throwLlmCall() {
            throw new LlmCallException("transport error", new RuntimeException("connection refused"));
        }

        @GetMapping("/test-llm-schema")
        String throwLlmSchema() {
            throw new LlmResponseSchemaException("Wartość poza zakresem", "scores.completeness");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void llmCallException_returns502WithErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test-llm-call"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Błąd usługi LLM"))
                .andExpect(jsonPath("$.messages[0]").value("connection refused"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void llmResponseSchemaException_returns502WithFieldPath() throws Exception {
        mockMvc.perform(get("/test-llm-schema"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Niepoprawny format odpowiedzi LLM"))
                .andExpect(jsonPath("$.messages[0]").value("scores.completeness: Wartość poza zakresem"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
