package com.example.autoskaner_ai.analysis;

import java.time.Instant;

public record AnalysisMeta(String provider, String model, long latencyMs, Instant generatedAt) {
}
