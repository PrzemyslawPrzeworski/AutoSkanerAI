package com.example.autoskaner_ai.analysis;

public record AnalysisResponse(String fetchStatus, String fetchFailureReason, AnalysisResult analysis) {

    public static AnalysisResponse ok(AnalysisResult result) {
        return new AnalysisResponse("ok", null, result);
    }

    public static AnalysisResponse text(AnalysisResult result) {
        return new AnalysisResponse("text", null, result);
    }

    public static AnalysisResponse urlFailed(String reason) {
        return new AnalysisResponse("url_failed", reason, null);
    }
}
