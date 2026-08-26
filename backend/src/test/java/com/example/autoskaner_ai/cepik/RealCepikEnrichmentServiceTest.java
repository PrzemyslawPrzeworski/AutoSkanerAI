package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealCepikEnrichmentServiceTest {

    private static final String VALID_VIN = "WBAAM31060GE12345";

    private final HistoriaPojazduService historiaPojazduService = mock(HistoriaPojazduService.class);
    private final RealCepikEnrichmentService service = new RealCepikEnrichmentService(historiaPojazduService);

    private static ExtractedData extracted(String vin, String plate, String firstRegDate) {
        return new ExtractedData(
                "BMW", "320i", 2018, BigDecimal.valueOf(60_000), "PLN", 120_000,
                null, null, null, null, null, null, null,
                vin, plate, firstRegDate);
    }

    @Test
    void looksUpHistoriaPojazduWhenAllThreeInputsPresent() {
        when(historiaPojazduService.lookup(any(), any(), any())).thenReturn(
                new CepikResult(CepikStatus.FOUND, VALID_VIN, "2018-03-15", null, "POLSKA",
                        2, List.of(), List.of(), "https://historiapojazdu.gov.pl", Instant.now()));

        var result = service.enrich(extracted(VALID_VIN, "WA12345", "2018-03-15"));

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        verify(historiaPojazduService).lookup("WA12345", VALID_VIN, "2018-03-15");
    }

    // The public CEPiK API cannot supply a missing first-registration date (it exposes no VIN
    // field), so a blank date short-circuits to MISSING_INPUTS with no outbound call at all.
    @Test
    void missingFirstRegistrationDateShortCircuitsWithoutAnyLookup() {
        var result = service.enrich(extracted(VALID_VIN, "WA12345", null));

        assertThat(result.status()).isEqualTo(CepikStatus.MISSING_INPUTS);
        assertThat(result.vin()).isEqualTo(VALID_VIN);
        verifyNoInteractions(historiaPojazduService);
    }

    // historiapojazdu's nfv_regex validator 400s on anything but yyyy-MM-dd, and the prompt
    // asks the LLM for the date "w formacie z ogłoszenia" — so dd.MM.yyyy is the common case,
    // not the edge case. Before normalisation every real lookup failed with LOOKUP_FAILED.
    @ParameterizedTest
    @CsvSource({
            "12.05.2016, 2016-05-12",
            "12-05-2016, 2016-05-12",
            "12/05/2016, 2016-05-12",
            "2016-05-12, 2016-05-12",
            "  12.05.2016  , 2016-05-12",
            // Verbatim from a live Otomoto listing (toyota-corolla-ID6HG6ZH, 2026-08-26): the
            // page shows the date as Polish prose, so this is the common case for URL fetches,
            // not an exotic one.
            "12 kwietnia 2022, 2022-04-12",
            "12 Kwietnia 2022, 2022-04-12",
            "1 września 2019, 2019-09-01",
            "12 kwiecień 2022, 2022-04-12"
    })
    void normalisesListingDateFormatsToIsoBeforeLookup(String raw, String expectedIso) {
        when(historiaPojazduService.lookup(any(), any(), any())).thenReturn(
                new CepikResult(CepikStatus.NOT_FOUND, VALID_VIN, null, null, null,
                        null, null, null, "https://historiapojazdu.gov.pl", Instant.now()));

        service.enrich(extracted(VALID_VIN, "WA12345", raw));

        verify(historiaPojazduService).lookup("WA12345", VALID_VIN, expectedIso);
    }

    // A doomed request would come back as LOOKUP_FAILED, which the UI words as "registry
    // temporarily unavailable" — blaming the registry for a value we could see was wrong.
    @ParameterizedTest
    // "kwiecień 2022" has no day, so there is no date to send. Guessing the 1st would put a
    // fabricated value in front of the registry and blame it for the 400 that came back.
    @ValueSource(strings = {"31.02.2016", "maj 2016", "2016", "05.2016", "not a date",
            "kwiecień 2022", "kwietnia 2022", "31 lutego 2016"})
    void unparseableDateAsksTheUserInsteadOfCallingTheRegistry(String raw) {
        var result = service.enrich(extracted(VALID_VIN, "WA12345", raw));

        assertThat(result.status()).isEqualTo(CepikStatus.MISSING_INPUTS);
        verifyNoInteractions(historiaPojazduService);
    }

    @Test
    void malformedPlateShortCircuits() {
        var result = service.enrich(extracted(VALID_VIN, "??", "2018-03-15"));

        assertThat(result.status()).isEqualTo(CepikStatus.MISSING_INPUTS);
        verifyNoInteractions(historiaPojazduService);
    }

    @Test
    void invalidVinShortCircuitsWithNullVin() {
        var result = service.enrich(extracted("NOT-A-VIN", "WA12345", "2018-03-15"));

        assertThat(result.status()).isEqualTo(CepikStatus.MISSING_INPUTS);
        assertThat(result.vin()).isNull();
        verifyNoInteractions(historiaPojazduService);
    }

    // An empty damage list renders as "no damage reported to insurers"; for a result where
    // nothing was ever checked that is the "unknown is not clean" violation.
    @Test
    void missingInputsCarriesNullListsNotEmptyOnes() {
        var result = service.enrich(extracted(VALID_VIN, "WA12345", null));

        assertThat(result.damageRecords()).isNull();
        assertThat(result.mileageStamps()).isNull();
    }
}
