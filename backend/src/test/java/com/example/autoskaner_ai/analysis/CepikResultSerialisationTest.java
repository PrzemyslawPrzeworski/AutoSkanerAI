package com.example.autoskaner_ai.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the null/empty/populated tri-state against <em>the application's own</em> Jackson
 * configuration.
 *
 * <p>This exists because the equivalent assertion in {@code CepikDamageReachesTheResponseTest}
 * cannot cover it. {@code MockMvcBuilders.standaloneSetup} builds its own message converters and
 * never reads {@code application.properties}, so adding
 * {@code spring.jackson.default-property-inclusion=non_null} leaves that test green while every
 * "unknown" on the real wire silently becomes an absent key — and the frontend reads an absent
 * {@code damageRecords} the same way it reads {@code []}: as "the registry reported no damage".
 * That is the 2026-08-26 false-clean failure reachable without touching a line of CEPiK code.
 *
 * <p>So this test injects the configured mapper from a booted context, which is the only place the
 * property is in force. Kept deliberately tiny: one context, no HTTP, no CEPiK collaborators.
 */
@SpringBootTest
@ActiveProfiles("mock")
class CepikResultSerialisationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aNonFoundResultSerialisesItsListsAsExplicitNulls() throws Exception {
        var result = CepikResult.withoutData(CepikStatus.LOOKUP_FAILED, "NMTBZ3BE40R000000",
                "https://historiapojazdu.gov.pl");

        String json = objectMapper.writeValueAsString(result);

        assertThat(json)
                .as("an absent damageRecords key reads as \"nothing reported\"; this is \"unknown\"")
                .contains("\"damageRecords\":null");
        assertThat(json).contains("\"mileageStamps\":null");
        assertThat(json).contains("\"events\":null");
    }

    // The other side of the same distinction: an empty list is a positive claim that the registry
    // was read and reported nothing, and it must survive serialisation as an empty array.
    @Test
    void aCleanFoundResultSerialisesAnEmptyArrayNotANull() throws Exception {
        var result = new CepikResult(CepikStatus.FOUND, "NMTBZ3BE40R000000", "2022-04-12",
                null, null, 2, java.util.List.of(), java.util.List.of(),
                "https://historiapojazdu.gov.pl", java.time.Instant.now(),
                "TOYOTA", "TOYOTA COROLLA", "SAMOCHÓD OSOBOWY", 2022,
                "Zarejestrowany", "aktualne", Boolean.TRUE, Boolean.FALSE, Boolean.FALSE,
                "mazowieckie", java.util.List.of());

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"damageRecords\":[]");
    }
}
