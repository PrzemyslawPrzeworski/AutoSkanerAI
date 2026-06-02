package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.market.MarketPriceStatus;
import java.time.Instant;

public record MarketPriceContext(
        MarketPriceStatus status,
        Integer minPricePln,
        Integer medianPricePln,
        Integer maxPricePln,
        Integer sampleSize,
        String queryUrl,
        Instant fetchedAt
) {}
