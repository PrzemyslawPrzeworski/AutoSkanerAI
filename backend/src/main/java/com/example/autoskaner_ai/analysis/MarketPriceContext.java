package com.example.autoskaner_ai.analysis;

import java.time.Instant;

public record MarketPriceContext(
        Integer minPricePln,
        Integer medianPricePln,
        Integer maxPricePln,
        Integer sampleSize,
        String queryUrl,
        Instant fetchedAt
) {}
