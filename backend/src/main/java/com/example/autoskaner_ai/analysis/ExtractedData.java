package com.example.autoskaner_ai.analysis;

import java.math.BigDecimal;

public record ExtractedData(
        String make,
        String model,
        Integer year,
        BigDecimal priceAmount,
        String priceCurrency,
        Integer mileageKm,
        String fuel,
        String transmission,
        String originCountry,
        String sellerType,
        Boolean serviceHistoryMentioned,
        String accidentClaim,
        Boolean vinPresent
) {
}
