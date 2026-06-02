package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final AiAnalysisService aiAnalysisService;
    private final ListingFetchService listingFetchService;
    private final MarketPriceEnrichmentService marketPriceEnrichmentService;

    public AnalysisController(AiAnalysisService aiAnalysisService,
                              ListingFetchService listingFetchService,
                              MarketPriceEnrichmentService marketPriceEnrichmentService) {
        this.aiAnalysisService = aiAnalysisService;
        this.listingFetchService = listingFetchService;
        this.marketPriceEnrichmentService = marketPriceEnrichmentService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalysisRequest request) {
        if (request.url() != null && !request.url().isBlank()) {
            FetchResult fetch = listingFetchService.fetch(request.url());
            if (fetch.isOk()) {
                AnalysisResult result = aiAnalysisService.analyze(fetch.text());
                var marketPriceContext = marketPriceEnrichmentService.enrich(result.extracted());
                return ResponseEntity.ok(new AnalysisResponse("ok", null, result, null, marketPriceContext));
            } else {
                return ResponseEntity.ok(AnalysisResponse.urlFailed(fetch.reason()));
            }
        }

        AnalysisResult result = aiAnalysisService.analyze(request.listingText());
        var marketPriceContext = marketPriceEnrichmentService.enrich(result.extracted());
        return ResponseEntity.ok(new AnalysisResponse("text", null, result, null, marketPriceContext));
    }
}
