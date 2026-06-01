package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ListingFetchConfig {

    @Bean(name = "listingFetchBuilder")
    public RestClient.Builder listingFetchBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // Jina Reader renders JavaScript before responding — needs up to 30 s
        factory.setReadTimeout(30_000);

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "text/plain,text/html,*/*;q=0.8")
                .defaultHeader("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .defaultHeader("X-No-Cache", "true");
    }
}
