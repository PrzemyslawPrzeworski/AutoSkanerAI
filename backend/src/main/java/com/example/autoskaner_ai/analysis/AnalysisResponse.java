package com.example.autoskaner_ai.analysis;

public record AnalysisResponse(
        String fetchStatus,
        String fetchFailureReason,
        AnalysisResult analysis,
        CepikResult cepikResult,
        MarketPriceContext marketPriceContext
) {

    public static AnalysisResponse ok(AnalysisResult result) {
        return new AnalysisResponse("ok", null, result, null, null);
    }

    public static AnalysisResponse text(AnalysisResult result) {
        return new AnalysisResponse("text", null, result, null, null);
    }

    public static AnalysisResponse urlFailed(String reason) {
        return new AnalysisResponse("url_failed", reason, null, null, null);
    }
}
