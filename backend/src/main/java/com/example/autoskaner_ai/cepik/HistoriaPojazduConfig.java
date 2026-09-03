package com.example.autoskaner_ai.cepik;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@Profile("!mock")
public class HistoriaPojazduConfig {

    /**
     * Named rather than inlined: one lookup spends five calls on this builder (session open is two,
     * then vehicle-data, timeline-data, close), so both numbers are multiplied by five in the
     * request-time budget {@code RequestTimeoutBudgetTest} adds up.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean("historiaPojazduBuilder")
    public RestClient.Builder historiaPojazduBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        return RestClient.builder()
                .baseUrl("https://moj.gov.pl")
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json, */*")
                .defaultHeader("Content-Type", "application/json");
    }
}
