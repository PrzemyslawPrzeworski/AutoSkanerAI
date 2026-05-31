package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("llm")
public class LlmAnalysisService implements AiAnalysisService {

    @Override
    public AnalysisResult analyze(String listingText) {
        // Stub — will be deleted and replaced by BedrockClaudeAnalysisService in Phase 3
        throw new UnsupportedOperationException("LLM integration not yet implemented");
    }
}
