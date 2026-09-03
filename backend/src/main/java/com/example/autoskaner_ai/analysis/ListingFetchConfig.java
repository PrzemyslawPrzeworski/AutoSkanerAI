package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ListingFetchConfig {

    /**
     * Named rather than inlined because these two numbers are part of the request-time budget the
     * PRD's 30 s NFR bounds, and {@code RequestTimeoutBudgetTest} does the arithmetic over them.
     * Bump one and that test fails, which is the point.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Jina Reader renders JavaScript before responding — needs up to 30 s. */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean(name = "listingFetchBuilder")
    public RestClient.Builder listingFetchBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "text/plain,text/html,*/*;q=0.8")
                .defaultHeader("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .defaultHeader("X-No-Cache", "true");
    }
}
