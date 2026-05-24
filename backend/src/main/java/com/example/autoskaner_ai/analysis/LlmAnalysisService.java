package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile("llm")
public class LlmAnalysisService implements AiAnalysisService {

    @Override
    public List<RiskFlag> analyzeRisks(String listingText) {
        // TODO: implement real LLM call (Claude / OpenAI) in a future iteration
        throw new UnsupportedOperationException("LLM integration not yet implemented");
    }
}
