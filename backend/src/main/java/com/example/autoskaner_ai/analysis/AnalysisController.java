package com.example.autoskaner_ai.analysis;

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

    public AnalysisController(AiAnalysisService aiAnalysisService, ListingFetchService listingFetchService) {
        this.aiAnalysisService = aiAnalysisService;
        this.listingFetchService = listingFetchService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalysisRequest request) {
        if (request.url() != null && !request.url().isBlank()) {
            FetchResult fetch = listingFetchService.fetch(request.url());
            if (fetch.isOk()) {
                AnalysisResult result = aiAnalysisService.analyze(fetch.text());
                return ResponseEntity.ok(AnalysisResponse.ok(result));
            } else {
                return ResponseEntity.ok(AnalysisResponse.urlFailed(fetch.reason()));
            }
        }

        AnalysisResult result = aiAnalysisService.analyze(request.listingText());
        return ResponseEntity.ok(AnalysisResponse.text(result));
    }
}
