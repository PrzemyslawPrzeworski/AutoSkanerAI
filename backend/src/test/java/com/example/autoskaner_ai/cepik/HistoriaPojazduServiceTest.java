package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoriaPojazduServiceTest {

    private final HistoriaPojazduSession session = mock(HistoriaPojazduSession.class);
    private final HistoriaPojazduParser parser = mock(HistoriaPojazduParser.class);
    private final HistoriaPojazduService service = new HistoriaPojazduService(mock(RestClient.Builder.class), parser) {
        @Override
        HistoriaPojazduSession createSession() { return session; }
    };

    @Test
    void returnsLookupFailedWhenSessionThrows() {
        doThrow(new HistoriaPojazduSessionException("connection refused"))
                .when(session).open();

        var result = service.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.status()).isEqualTo(CepikStatus.LOOKUP_FAILED);
        assertThat(result.vin()).isEqualTo("WBAAM31060GE12345");
        assertThat(result.lookupUrl()).isEqualTo("https://historiapojazdu.gov.pl");
        assertThat(result.fetchedAt()).isNotNull();
        verify(session).close();
    }

    @Test
    void returnsLookupFailedWhenFetchVehicleDataThrows() {
        doNothing().when(session).open();
        doThrow(new HistoriaPojazduSessionException("vehicle-data error"))
                .when(session).fetchVehicleData(any(), any(), any());

        var result = service.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.status()).isEqualTo(CepikStatus.LOOKUP_FAILED);
        verify(session).close();
    }

    // The registry answers an unknown plate/VIN/date triple with a 404 carrying HIPO-0002.
    // That is a definitive "no such vehicle", and the UI words it differently from an outage.
    @Test
    void returnsNotFoundWhenRegistryReportsHipo0002() {
        doNothing().when(session).open();
        doThrow(new HistoriaPojazduSessionException(
                "vehicle-data failed: 404 Not Found: \"{\"VALIDATION_ERROR_MSG\":"
                        + "\"W bazie danych nie istnieje pojazd o podanych parametrach\","
                        + "\"VALIDATION_ERROR_CODE\":\"HIPO-0002\"}\""))
                .when(session).fetchVehicleData(any(), any(), any());

        var result = service.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.status()).isEqualTo(CepikStatus.NOT_FOUND);
        assertThat(result.vin()).isEqualTo("WBAAM31060GE12345");
        verify(session).close();
    }

    @Test
    void detectsHipo0002NestedInTheCauseChain() {
        doNothing().when(session).open();
        doThrow(new HistoriaPojazduSessionException("vehicle-data failed",
                new RuntimeException("404 Not Found: VALIDATION_ERROR_CODE HIPO-0002")))
                .when(session).fetchVehicleData(any(), any(), any());

        var result = service.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.status()).isEqualTo(CepikStatus.NOT_FOUND);
    }

    // Every non-FOUND status must leave the lists null. An empty damage list renders as
    // "no damage reported", which is the "unknown is not clean" trap.
    @Test
    void nonFoundResultsCarryNullListsNotEmptyOnes() {
        doThrow(new HistoriaPojazduSessionException("connection refused")).when(session).open();

        var result = service.lookup("WA12345", "WBAAM31060GE12345", "2018-03-15");

        assertThat(result.damageRecords()).isNull();
        assertThat(result.mileageStamps()).isNull();
    }
}
