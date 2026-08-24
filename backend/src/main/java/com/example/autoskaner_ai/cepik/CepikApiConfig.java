package com.example.autoskaner_ai.cepik;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// Note: using JdkClientHttpRequestFactory instead of HttpComponentsClientHttpRequestFactory
// (httpclient5 5.3.x unavailable in dev env due to Zscaler); functionally equivalent.
// If api.cepik.gov.pl TLS handshake fails on Render, diagnose the specific cipher/cert issue
// and configure SSLParameters.setCipherSuites() or import the CA cert — do NOT restore trust-all.
@Configuration
@Profile("!mock")
public class CepikApiConfig {

    @Bean("cepikApiBuilder")
    public RestClient.Builder cepikApiBuilder() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));

        return RestClient.builder()
                .baseUrl("https://api.cepik.gov.pl")
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json");
    }
}
