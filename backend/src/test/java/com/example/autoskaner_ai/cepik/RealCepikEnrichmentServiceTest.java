package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikResult;
import com.example.autoskaner_ai.analysis.CepikStatus;
import com.example.autoskaner_ai.analysis.ExtractedData;
import org.junit.jupiter.api.Test;

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
