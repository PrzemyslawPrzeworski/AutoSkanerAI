package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;

public interface MarketPriceEnrichmentService {
    MarketPriceContext enrich(ExtractedData extracted);
}
