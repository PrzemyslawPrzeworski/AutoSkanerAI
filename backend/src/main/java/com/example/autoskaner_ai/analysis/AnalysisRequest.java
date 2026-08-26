package com.example.autoskaner_ai.analysis;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AnalysisRequest(
        @Pattern(regexp = "https?://.+", message = "url: nieprawidłowy format URL")
        @Size(max = 2000, message = "url: zbyt długi URL")
        String url,

        @Size(max = 20000, message = "listingText: zbyt długi tekst (max 20 000 znaków)")
        String listingText,

        @Valid
        ManualListing manual,

        // The three registry inputs, supplied by the user rather than the LLM. Deliberately not
        // format-validated here: a mistyped VIN must not 400 away an otherwise useful analysis.
        // RealCepikEnrichmentService already validates and normalises all three, and reports
        // MISSING_INPUTS for anything it cannot use. The bounds below only stop abuse.
        @Size(max = 32, message = "vin: zbyt długa wartość")
        String vin,

        @Size(max = 16, message = "registrationPlate: zbyt długa wartość")
        String registrationPlate,

        @Size(max = 32, message = "firstRegistrationDate: zbyt długa wartość")
        String firstRegistrationDate
) {
    @AssertTrue(message = "Wymagane jest podanie url, listingText lub danych pojazdu")
    public boolean isInputPresent() {
        return (url != null && !url.isBlank())
                || (listingText != null && !listingText.isBlank())
                || (manual != null && manual.hasAnyValue());
    }

    public boolean hasManualEntry() {
        return manual != null && manual.hasAnyValue();
    }
}
