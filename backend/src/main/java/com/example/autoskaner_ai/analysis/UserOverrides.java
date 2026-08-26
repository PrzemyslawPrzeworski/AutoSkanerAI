package com.example.autoskaner_ai.analysis;

import java.math.BigDecimal;

/**
 * Applies what the user typed over what the LLM extracted.
 *
 * <p>Two rules, and they are the whole class:
 *
 * <ol>
 *   <li><b>A typed value wins.</b> The user is looking at the car (or its papers); the model is
 *       reading an advert. Where they disagree the user is right, and the displayed table must
 *       show the user's value rather than quietly analysing a different car.
 *   <li><b>A blank field never overwrites a good extraction with null.</b> The form is prefilled
 *       from the extraction, so an untouched field means "no opinion", not "clear this".
 * </ol>
 *
 * <p>This runs before enrichment, so the registry lookup and the market-price query both see the
 * corrected values. That is the point of the whole slice: Otomoto hides the VIN from logged-out
 * fetches, so a URL-only analysis can never produce a CEPiK result on its own.
 */
final class UserOverrides {

    private UserOverrides() {
    }

    static ExtractedData apply(ExtractedData extracted, AnalysisRequest request) {
        if (extracted == null || request == null) {
            return extracted;
        }
        ManualListing manual = request.hasManualEntry() ? request.manual() : null;

        // VIN and plate are upper-case identifiers, so a typed value is canonicalised before it
        // reaches the extracted table the user reads back. The date is left exactly as typed:
        // RealCepikEnrichmentService owns date normalisation, and doing it twice in two places is
        // how the two copies drift apart.
        String vin = upperIfTyped(request.vin(), extracted.vin());
        // The user typing a VIN is itself the evidence that one exists — leaving vinPresent false
        // while showing the VIN would contradict the panel next to it.
        Boolean vinPresent = notBlank(request.vin()) ? Boolean.TRUE : extracted.vinPresent();

        return new ExtractedData(
                manual == null ? extracted.make() : firstPresent(manual.make(), extracted.make()),
                manual == null ? extracted.model() : firstPresent(manual.model(), extracted.model()),
                manual == null ? extracted.year() : firstPresent(manual.year(), extracted.year()),
                manual == null ? extracted.priceAmount()
                        : firstPresent(manual.priceAmount(), extracted.priceAmount()),
                manual == null ? extracted.priceCurrency()
                        : firstPresent(manual.priceCurrency(), extracted.priceCurrency()),
                manual == null ? extracted.mileageKm()
                        : firstPresent(manual.mileageKm(), extracted.mileageKm()),
                manual == null ? extracted.fuel() : firstPresent(manual.fuel(), extracted.fuel()),
                manual == null ? extracted.transmission()
                        : firstPresent(manual.transmission(), extracted.transmission()),
                extracted.originCountry(),
                extracted.sellerType(),
                extracted.serviceHistoryMentioned(),
                // Never overridden. An accident claim is a claim *the listing makes*, and the
                // CEPiK contradiction check compares it against the registry — letting the user
                // edit it would let a correction erase the contradiction it should surface.
                extracted.accidentClaim(),
                vinPresent,
                vin,
                upperIfTyped(request.registrationPlate(), extracted.registrationPlate()),
                firstPresent(request.firstRegistrationDate(), extracted.firstRegistrationDate())
        );
    }

    private static String firstPresent(String typed, String extracted) {
        return notBlank(typed) ? typed.strip() : extracted;
    }

    private static String upperIfTyped(String typed, String extracted) {
        return notBlank(typed) ? typed.strip().toUpperCase() : extracted;
    }

    private static Integer firstPresent(Integer typed, Integer extracted) {
        return typed != null ? typed : extracted;
    }

    private static BigDecimal firstPresent(BigDecimal typed, BigDecimal extracted) {
        return typed != null ? typed : extracted;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
