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
        return new MarketPriceContext(
                MarketPriceStatus.OK,
                45_000, 55_000, 70_000, 12,
                "https://www.otomoto.pl/osobowe/toyota/corolla",
                Instant.now());
    }
}
