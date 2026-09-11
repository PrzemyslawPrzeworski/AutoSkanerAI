package com.example.autoskaner_ai.saved;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the saved list (FR-012). Built from the denormalised columns beside the payload, so
 * listing N analyses deserialises nothing.
 */
public record SavedAnalysisSummaryResponse(
        Long id,
        String title,
        String note,
        String sourceUrl,
        String make,
        String model,
        Integer productionYear,
        BigDecimal priceAmount,
        String priceCurrency,
        Integer mileageKm,
        String verdictCode,
        Integer overallScore,
        Instant createdAt,
        Instant updatedAt
) {

    static SavedAnalysisSummaryResponse of(SavedAnalysis saved) {
        return new SavedAnalysisSummaryResponse(
                saved.getId(),
                saved.getTitle(),
                saved.getNote(),
                saved.getSourceUrl(),
                saved.getMake(),
                saved.getModel(),
                saved.getProductionYear(),
                saved.getPriceAmount(),
                saved.getPriceCurrency(),
                saved.getMileageKm(),
                saved.getVerdictCode(),
                saved.getOverallScore(),
                saved.getCreatedAt(),
                saved.getUpdatedAt());
    }
}
