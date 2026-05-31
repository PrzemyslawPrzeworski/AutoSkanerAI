package com.example.autoskaner_ai.analysis.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("openrouter")
public class OpenRouterConfig {

    @Value("${llm.openrouter.base-url}")
    private String baseUrl;

    // No default — Spring will refuse to create the bean if OPENROUTER_API_KEY is unset
    @Value("${OPENROUTER_API_KEY}")
    private String apiKey;

    @Bean(name = "openRouterBuilder")
    public RestClient.Builder openRouterBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("HTTP-Referer", "https://autoskaner-ai.pages.dev")
                .defaultHeader("X-Title", "AutoSkanerAI");
    }
}
