package com.example.autoskaner_ai.saved;

import com.example.autoskaner_ai.analysis.AnalysisResponse;

/**
 * A single saved analysis, summary plus the stored analysis itself.
 *
 * <p>{@code analysis} is the payload parsed back into the shape {@code POST /api/analyses} returns,
 * not the raw column, so the frontend renders a saved analysis through the same components as a fresh
 * one instead of owning a second reader for a JSON string.
 */
public record SavedAnalysisDetailResponse(
        SavedAnalysisSummaryResponse summary,
        AnalysisResponse analysis
) {}
