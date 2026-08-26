package com.example.autoskaner_ai.analysis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Structured fields for the third input mode (FR-003): no URL, no pasted advert. Every field is
 * optional — a user who knows only "Corolla, 2022, 83 000 zł" should still get an analysis, with
 * the gaps showing up as low completeness rather than as a validation error.
 *
 * <p>The bounds exist to reject nonsense, not to enforce a schema: a 1899 car or a 3 000 000 km
 * odometer is a typo, and letting it through would put a fabricated fact in front of the user.
 */
public record ManualListing(
        @Size(max = 60, message = "manual.make: zbyt długa wartość")
        String make,

        @Size(max = 60, message = "manual.model: zbyt długa wartość")
        String model,

        @Min(value = 1900, message = "manual.year: rok poza zakresem")
        @Max(value = 2100, message = "manual.year: rok poza zakresem")
        Integer year,

        @Min(value = 0, message = "manual.priceAmount: cena nie może być ujemna")
        BigDecimal priceAmount,

        @Size(max = 8, message = "manual.priceCurrency: zbyt długa wartość")
        String priceCurrency,

        @Min(value = 0, message = "manual.mileageKm: przebieg nie może być ujemny")
        @Max(value = 2_000_000, message = "manual.mileageKm: przebieg poza zakresem")
        Integer mileageKm,

        @Size(max = 40, message = "manual.fuel: zbyt długa wartość")
        String fuel,

        @Size(max = 40, message = "manual.transmission: zbyt długa wartość")
        String transmission,

        @Size(max = 10000, message = "manual.notes: zbyt długi tekst (max 10 000 znaków)")
        String notes
) {

    /**
     * True when at least one field carries a value. An all-blank object is treated as absent, so a
     * frontend that always sends the manual block cannot turn an empty form into an analysis of
     * nothing.
     */
    public boolean hasAnyValue() {
        return notBlank(make) || notBlank(model) || year != null || priceAmount != null
                || mileageKm != null || notBlank(fuel) || notBlank(transmission) || notBlank(notes);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
