package com.example.autoskaner_ai.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The precedence rule is the only place this slice can go wrong in a way the user would not
 * notice: a silently ignored VIN looks exactly like a car with no registry record, and a blank
 * field that nulls a good extraction looks exactly like a listing that omitted the value.
 */
class UserOverridesTest {

    private static final String VIN = "NMTBZ3BE40R000000";

    private static ExtractedData extracted(String vin, String plate, String date) {
        return new ExtractedData("Toyota", "Corolla", 2022, BigDecimal.valueOf(82_900), "PLN",
                26_320, "hybryda", "automatyczna", "Polska", "prywatny", Boolean.TRUE,
                "bezwypadkowy", vin != null, vin, plate, date);
    }

    private static AnalysisRequest request(String vin, String plate, String date) {
        return new AnalysisRequest("https://www.otomoto.pl/x", null, null, vin, plate, date);
    }

    @Test
    void aTypedVinWinsOverAnEmptyExtraction() {
        var result = UserOverrides.apply(extracted(null, "WX00000", "2022-04-12"),
                request(VIN, null, null));

        assertThat(result.vin()).isEqualTo(VIN);
        assertThat(result.registrationPlate()).isEqualTo("WX00000");
        assertThat(result.firstRegistrationDate()).isEqualTo("2022-04-12");
    }

    // The listing said one plate, the user read another off the car. The user is looking at it.
    @Test
    void aTypedValueWinsOverANonEmptyExtraction() {
        var result = UserOverrides.apply(extracted(VIN, "WX00000", "2022-04-12"),
                request(null, "GD11111", "12.04.2022"));

        assertThat(result.registrationPlate()).isEqualTo("GD11111");
        assertThat(result.firstRegistrationDate()).isEqualTo("12.04.2022");
        assertThat(result.vin()).isEqualTo(VIN);
    }

    // The form is prefilled from the extraction, so an untouched field must mean "no opinion".
    // Treating it as "clear this" would lose a VIN the model had correctly read.
    @Test
    void blankAndNullFieldsNeverEraseAnExtraction() {
        var result = UserOverrides.apply(extracted(VIN, "WX00000", "2022-04-12"),
                request("   ", "", null));

        assertThat(result.vin()).isEqualTo(VIN);
        assertThat(result.registrationPlate()).isEqualTo("WX00000");
        assertThat(result.firstRegistrationDate()).isEqualTo("2022-04-12");
    }

    @Test
    void typedIdentifiersAreCanonicalisedToUpperCase() {
        var result = UserOverrides.apply(extracted(null, null, null),
                request(" nmtbz3be40r000000 ", " wx00000 ", " 12 kwietnia 2022 "));

        // The user reads these back out of the extracted table, and a plate is written upper case.
        assertThat(result.vin()).isEqualTo(VIN);
        assertThat(result.registrationPlate()).isEqualTo("WX00000");
        // The date is left exactly as typed: date normalisation belongs to
        // RealCepikEnrichmentService, and doing it twice is how the two copies drift apart.
        assertThat(result.firstRegistrationDate()).isEqualTo("12 kwietnia 2022");
    }

    @Test
    void aTypedVinSetsVinPresent() {
        var result = UserOverrides.apply(extracted(null, null, null), request(VIN, null, null));

        assertThat(result.vinPresent()).isTrue();
    }

    @Test
    void manualFieldsOverrideTheExtractedTable() {
        var manual = new ManualListing("Toyota", "Corolla TS", 2021, BigDecimal.valueOf(79_000),
                null, 31_000, null, "manualna", "hak holowniczy");
        var request = new AnalysisRequest(null, null, manual, null, null, null);

        var result = UserOverrides.apply(extracted(VIN, "WX00000", "2022-04-12"), request);

        assertThat(result.model()).isEqualTo("Corolla TS");
        assertThat(result.year()).isEqualTo(2021);
        assertThat(result.priceAmount()).isEqualByComparingTo("79000");
        assertThat(result.mileageKm()).isEqualTo(31_000);
        assertThat(result.transmission()).isEqualTo("manualna");
        // Untouched by the form, so the extraction stands.
        assertThat(result.priceCurrency()).isEqualTo("PLN");
        assertThat(result.fuel()).isEqualTo("hybryda");
    }

    @Test
    void anAllBlankManualBlockChangesNothing() {
        var request = new AnalysisRequest(null, "tekst", new ManualListing(null, null, null, null,
                null, null, null, null, null), null, null, null);

        var result = UserOverrides.apply(extracted(VIN, "WX00000", "2022-04-12"), request);

        assertThat(result).isEqualTo(extracted(VIN, "WX00000", "2022-04-12"));
    }

    // The contradiction check compares this against the registry. If a user could edit it, a
    // "correction" would delete the CEPIK_CONTRADICTS_LISTING finding it exists to raise.
    @Test
    void theAccidentClaimIsNotUserEditable() {
        var manual = new ManualListing("Toyota", "Corolla", null, null, null, null, null, null,
                "bezwypadkowy, jak nowy");
        var request = new AnalysisRequest(null, null, manual, null, null, null);

        var result = UserOverrides.apply(extracted(VIN, "WX00000", "2022-04-12"), request);

        assertThat(result.accidentClaim()).isEqualTo("bezwypadkowy");
    }

    @Test
    void nullInputsAreHandled() {
        assertThat(UserOverrides.apply(null, request(VIN, null, null))).isNull();
        var original = extracted(VIN, "WX00000", "2022-04-12");
        assertThat(UserOverrides.apply(original, null)).isSameAs(original);
    }
}
