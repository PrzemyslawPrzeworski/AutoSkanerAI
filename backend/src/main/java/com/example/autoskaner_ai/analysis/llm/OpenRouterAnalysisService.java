package com.example.autoskaner_ai.analysis.llm;

import com.example.autoskaner_ai.analysis.AiAnalysisService;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAnalysisService.class);

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
        Map<?, ?> fullResponse = null;
        try {
            fullResponse = callApiRaw(requestBody);
            rawText = extractContent(fullResponse);
        } catch (LlmCallException e) {
            log.warn("LLM call retry provider={} model={} cause={}", "openrouter", model, e.getMessage());
            try {
                fullResponse = callApiRaw(requestBody);
                rawText = extractContent(fullResponse);
            } catch (Exception retryEx) {
                log.error("LLM call failed provider={} model={} cause={}", "openrouter", model, retryEx.getMessage());
                throw new LlmCallException("OpenRouter call failed after retry", retryEx);
            }
        }

        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        int inputTokens = extractTokens(fullResponse, "prompt_tokens");
        int outputTokens = extractTokens(fullResponse, "completion_tokens");
        log.info("LLM call provider={} model={} latencyMs={} inputTokens={} outputTokens={} listingChars={}",
                "openrouter", model, latencyMs, inputTokens, outputTokens, listingText.length());

        return parser.parse(rawText, "openrouter", model, latencyMs);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> callApiRaw(Map<String, Object> requestBody) {
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
            return response;
        } catch (LlmCallException e) {
            throw e;
        } catch (RestClientException e) {
            throw new LlmCallException("OpenRouter HTTP error", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        List<Map<?, ?>> choices = (List<Map<?, ?>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmCallException("OpenRouter returned empty choices", new RuntimeException("empty choices"));
        }
        Map<?, ?> message = (Map<?, ?>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    private int extractTokens(Map<?, ?> response, String key) {
        if (response == null) return -1;
        Object usage = response.get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Object val = usageMap.get(key);
            if (val instanceof Number n) return n.intValue();
        }
        return -1;
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
