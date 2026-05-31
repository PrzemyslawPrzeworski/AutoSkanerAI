package com.example.autoskaner_ai.analysis;

import java.util.List;

public record AnalysisResult(
        ExtractedData extracted,
        List<EquipmentItem> equipment,
        List<RiskFlag> riskFlags,
        List<String> sellerQuestions,
        CategoryScores scores,
        Verdict verdict,
        AnalysisMeta meta
) {
}
