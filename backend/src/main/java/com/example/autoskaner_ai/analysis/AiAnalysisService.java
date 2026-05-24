package com.example.autoskaner_ai.analysis;

import java.util.List;

public interface AiAnalysisService {
    List<RiskFlag> analyzeRisks(String listingText);
}
