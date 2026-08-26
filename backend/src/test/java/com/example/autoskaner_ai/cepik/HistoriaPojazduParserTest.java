package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Driven by payloads captured verbatim from historiapojazdu.gov.pl on 2026-08-26 for
 * NMTBZ3BE40R000000 (see src/test/resources/cepik/). This matters: the previous version of this
 * test hand-wrote fixtures to match field names the parser invented, so all three tests passed
 * against a shape the registry has never returned, and the app reported an empty damage list for
 * a vehicle carrying a szkoda istotna. Fixtures here must stay copies of real responses — if a
 * new case is needed, capture it rather than composing it.
 *
 * <p>One deliberate edit to the captures: the VIN is replaced with a synthetic
 * NMTBZ3BE40R000000. This repo is public and the rest of the payload is a real seller's vehicle
 * record; stripped of the VIN it is just "a 2022 Corolla". Nothing the parser does depends on the
 * value, so the shape under test is unaffected. Do not paste a real VIN back in.
 */
class HistoriaPojazduParserTest {

    private static final String VIN = "NMTBZ3BE40R000000";

    private final HistoriaPojazduParser parser = new HistoriaPojazduParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> vehicleData;
    private Map<String, Object> timelineData;

    @BeforeEach
    void loadFixtures() throws IOException {
        vehicleData = fixture("vehicle-data-found.json");
        timelineData = fixture("timeline-data-found.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/cepik/" + name)) {
            assertThat(in).as("missing fixture %s", name).isNotNull();
            return mapper.readValue(in, Map.class);
        }
    }

    // The whole point of the rewrite: this vehicle HAS a significant damage, and the old parser
    // returned an empty list, which the UI renders as "no damage reported to insurers".
    @Test
    void readsTheSignificantDamageWithInsurerAndCategory() {
        var result = parser.parse(vehicleData, timelineData, VIN);

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        assertThat(result.damageRecords()).hasSize(1);

        var damage = result.damageRecords().getFirst();
        assertThat(damage.date()).isEqualTo("2023-02-07");
        assertThat(damage.description()).isEqualTo("Powstanie szkody istotnej");
        assertThat(damage.insurer()).isEqualTo("PZU");
        assertThat(damage.categories()).containsExactly("Uszkodzenie elementów układu nośnego");
    }

    // Dated readings come from the inspection events, not from the registry's undated
    // odometerReadings list.
    @Test
    void buildsDatedMileageHistoryFromInspectionEvents() {
        var result = parser.parse(vehicleData, timelineData, VIN);

        assertThat(result.mileageStamps())
                .extracting(s -> s.date() + "=" + s.mileageKm())
                .containsExactly("2024-01-03=12330", "2025-04-14=26320", "2025-04-14=26320");
    }

    @Test
    void readsRegistryIdentityAndStatusFlags() {
        var result = parser.parse(vehicleData, timelineData, VIN);

        assertThat(result.make()).isEqualTo("TOYOTA");
        assertThat(result.model()).isEqualTo("TOYOTA COROLLA");
        assertThat(result.vehicleType()).isEqualTo("SAMOCHÓD OSOBOWY");
        assertThat(result.yearOfManufacture()).isEqualTo(2022);
        assertThat(result.registrationStatus()).isEqualTo("Zarejestrowany");
        assertThat(result.technicalInspectionStatus()).isEqualTo("aktualne");
        assertThat(result.ocInsuranceValid()).isTrue();
        assertThat(result.vehicleLost()).isFalse();
        assertThat(result.odometerRolledBack()).isFalse();
        assertThat(result.registrationProvince()).isEqualTo("mazowieckie");
        assertThat(result.ownerCount()).isEqualTo(2);
        assertThat(result.firstRegistrationDatePl()).isEqualTo("2022-04-12");
    }

    // The timeline is passed through whole so an event type this code does not model still
    // reaches the user rather than being dropped.
    @Test
    void passesTheWholeTimelineThrough() {
        var result = parser.parse(vehicleData, timelineData, VIN);

        assertThat(result.events()).hasSize(14);
        assertThat(result.events()).extracting(com.example.autoskaner_ai.analysis.VehicleEvent::type)
                .contains("pierwsza-rejestracja-w-polsce", "szkoda-istotna", "zmiana-wlasciciela",
                        "badanie-techniczne-dodatkowe", "badanie-techniczne-okresowe");
    }

    // An unreadable timeline must not look like a clean history. This is the invariant the old
    // parser broke, so it is asserted directly rather than inferred from the happy path.
    @Test
    void missingTimelineYieldsNullListsNotEmptyOnes() {
        var result = parser.parse(vehicleData, Map.of(), VIN);

        assertThat(result.damageRecords())
                .as("an empty damage list claims the registry reported nothing — it did not answer")
                .isNull();
        assertThat(result.mileageStamps()).isNull();
        // Vehicle data still parsed, so identity is present even though the timeline is not.
        assertThat(result.make()).isEqualTo("TOYOTA");
    }

    @Test
    void nullPayloadsDoNotThrowAndCarryNoData() {
        var result = parser.parse(null, null, VIN);

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        assertThat(result.vin()).isEqualTo(VIN);
        assertThat(result.damageRecords()).isNull();
        assertThat(result.mileageStamps()).isNull();
        assertThat(result.events()).isNull();
        assertThat(result.make()).isNull();
    }

    // A timeline that parsed and genuinely holds no damage event is the one case where an empty
    // list is the truth, and it must stay distinguishable from the null above.
    @Test
    void timelineWithoutDamageEventsYieldsEmptyListNotNull() {
        Map<String, Object> noDamage = Map.of("timelineData", Map.of(
                "events", List.of(Map.of(
                        "eventDate", "2022-04-12",
                        "eventType", "pierwsza-rejestracja-w-polsce",
                        "eventName", "Pierwsza rejestracja w Polsce",
                        "eventDetails", List.of()))));

        var result = parser.parse(vehicleData, noDamage, VIN);

        assertThat(result.damageRecords()).isNotNull().isEmpty();
    }
}
