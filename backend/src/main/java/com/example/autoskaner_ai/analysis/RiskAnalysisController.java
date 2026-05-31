package com.example.autoskaner_ai.analysis;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @deprecated Use POST /api/analyses instead. Will be removed once S-01 lands.
 */
@Deprecated
@RestController
@RequestMapping("/api/analysis")
public class RiskAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public RiskAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/risk")
    public ResponseEntity<RiskAnalysisResponse> analyzeRisk(@Valid @RequestBody RiskAnalysisRequest request) {
        var result = aiAnalysisService.analyze(request.listingText());
        return ResponseEntity.ok(new RiskAnalysisResponse(result.riskFlags()));
    }
}
