package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.market.MarketPriceSampleQuality;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import java.time.Instant;

/**
 * @param sampleQuality  how much weight the three numbers carry; null for every non-{@code OK}
 *                       status, because there is no sample to judge. Appended rather than inserted:
 *                       the deployed frontend deserialises this by field name, so adding fields is
 *                       safe and removing or renaming one is not.
 * @param discardedCount prices the trim dropped. Previously computed and thrown away at the
 *                       mapping, which left "too dispersed" with no observable outside a log line.
 */
public record MarketPriceContext(
        MarketPriceStatus status,
        Integer minPricePln,
        Integer medianPricePln,
        Integer maxPricePln,
        Integer sampleSize,
        String queryUrl,
        Instant fetchedAt,
        MarketPriceSampleQuality sampleQuality,
        Integer discardedCount
) {}
