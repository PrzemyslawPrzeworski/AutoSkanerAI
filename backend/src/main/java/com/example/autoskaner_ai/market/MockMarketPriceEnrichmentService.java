package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@Profile("mock")
public class MockMarketPriceEnrichmentService implements MarketPriceEnrichmentService {

    @Override
    public MarketPriceContext enrich(ExtractedData extracted) {
        // Twelve listings, none of them discarded: the mock stands in for the healthy case, so it
        // reports SUFFICIENT rather than exercising a caveat the real service decides.
        return new MarketPriceContext(
                MarketPriceStatus.OK,
                45_000, 55_000, 70_000, 12,
                "https://www.otomoto.pl/osobowe/toyota/corolla",
                Instant.now(),
                MarketPriceSampleQuality.SUFFICIENT, 0);
    }
}
