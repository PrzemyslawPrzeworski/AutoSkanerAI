package com.example.autoskaner_ai.cepik;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HistoriaPojazduConfig {

    @Bean("historiaPojazduBuilder")
    public RestClient.Builder historiaPojazduBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);

        return RestClient.builder()
                .baseUrl("https://moj.gov.pl")
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json, */*")
                .defaultHeader("Content-Type", "application/json");
    }
}
