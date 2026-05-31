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

    public AnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResult> analyze(@Valid @RequestBody AnalysisRequest request) {
        return ResponseEntity.ok(aiAnalysisService.analyze(request.listingText()));
    }
}
