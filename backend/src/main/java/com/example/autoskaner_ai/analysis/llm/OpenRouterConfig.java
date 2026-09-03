package com.example.autoskaner_ai.analysis.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@Profile("openrouter")
public class OpenRouterConfig {

    /**
     * Named rather than inlined: one LLM attempt can occupy {@code CONNECT + READ} of the
     * request-time budget, and the fallback chain's ceiling is derived from it in
     * {@code RequestTimeoutBudgetTest}.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Value("${llm.openrouter.base-url}")
    private String baseUrl;

    // No default — Spring will refuse to create the bean if OPENROUTER_API_KEY is unset
    @Value("${OPENROUTER_API_KEY}")
    private String apiKey;

    @Bean(name = "openRouterBuilder")
    public RestClient.Builder openRouterBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("HTTP-Referer", "https://autoskaner-ai.pages.dev")
                .defaultHeader("X-Title", "AutoSkanerAI");
    }
}
