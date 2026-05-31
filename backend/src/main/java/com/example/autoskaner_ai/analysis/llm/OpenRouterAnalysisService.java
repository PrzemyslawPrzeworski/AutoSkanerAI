package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Service
@Profile("openrouter")
public class OpenRouterAnalysisService implements AiAnalysisService {

    private final AnalysisPrompt prompt;
    private final AnalysisResponseParser parser;
    private final RestClient restClient;
    private final String model;

    public OpenRouterAnalysisService(
            AnalysisPrompt prompt,
            AnalysisResponseParser parser,
            @Qualifier("openRouterBuilder") RestClient.Builder openRouterBuilder,
            @Value("${llm.openrouter.model}") String model) {
        this.prompt = prompt;
        this.parser = parser;
        this.restClient = openRouterBuilder.build();
        this.model = model;
    }

    @Override
    public AnalysisResult analyze(String listingText) {
        long t0 = System.nanoTime();
        Map<String, Object> requestBody = buildRequestBody(listingText);

        String rawText;
        try {
            rawText = callApi(requestBody);
        } catch (LlmCallException e) {
            // transport failure — retry once
            try {
                rawText = callApi(requestBody);
            } catch (Exception retryEx) {
                throw new LlmCallException("OpenRouter call failed after retry", retryEx);
            }
        }

        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        return parser.parse(rawText, "openrouter", model, latencyMs);
    }

    private String callApi(Map<String, Object> requestBody) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new LlmCallException("OpenRouter returned null response", new RuntimeException("null body"));
            }

            @SuppressWarnings("unchecked")
            List<Map<?, ?>> choices = (List<Map<?, ?>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmCallException("OpenRouter returned empty choices", new RuntimeException("empty choices"));
            }

            @SuppressWarnings("unchecked")
            Map<?, ?> message = (Map<?, ?>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (LlmCallException e) {
            throw e;
        } catch (RestClientException e) {
            throw new LlmCallException("OpenRouter HTTP error", e);
        }
    }

    private Map<String, Object> buildRequestBody(String listingText) {
        return Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.systemPrompt()),
                        Map.of("role", "user", "content", prompt.userMessage(listingText))
                ),
                "temperature", 0.2,
                "max_tokens", 4096
        );
    }
}
