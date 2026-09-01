package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

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

    // Every test above drives the session to throw, so until this one `lookup`'s success path
    // never ran here and `close()` was only ever asserted on the failure route — a session leaked
    // per successful lookup would have gone unnoticed. Uses a real parser, because a mocked one
    // makes the outcome whatever the stub says.
    //
    // What this test cannot catch: which HTTP call each payload came from. The session is mocked,
    // so swapping the two fetch calls themselves — asking timeline-data for the vehicle payload —
    // is invisible here. That coverage lives in CepikDamageReachesTheResponseTest, where
    // MockRestServiceServer's ordered expectations pin the request order at the network edge.
    // (Swapping the two arguments to parser.parse *is* caught here, but only incidentally: the two
    // captures have disjoint top-level keys, so a swap makes both unreadable and trips the
    // nothing-readable guard rather than being recognised as the wrong way round.)
    @Test
    void aSuccessfulLookupParsesTheDamageAndStillClosesTheSession() throws IOException {
        var withRealParser = new HistoriaPojazduService(mock(RestClient.Builder.class),
                new HistoriaPojazduParser()) {
            @Override
            HistoriaPojazduSession createSession() { return session; }
        };
        when(session.fetchVehicleData(any(), any(), any()))
                .thenReturn(fixture("vehicle-data-found.json"));
        when(session.fetchTimelineData(any(), any(), any()))
                .thenReturn(fixture("timeline-data-found.json"));

        var result = withRealParser.lookup("WX00000", "NMTBZ3BE40R000000", "2022-04-12");

        assertThat(result.status()).isEqualTo(CepikStatus.FOUND);
        assertThat(result.damageRecords()).hasSize(1);
        assertThat(result.damageRecords().getFirst().date()).isEqualTo("2023-02-07");
        assertThat(result.make()).isEqualTo("TOYOTA");
        verify(session).close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) throws IOException {
        try (InputStream in = HistoriaPojazduServiceTest.class.getResourceAsStream("/cepik/" + name)) {
            assertThat(in).as("missing fixture %s", name).isNotNull();
            return new ObjectMapper().readValue(in, Map.class);
        }
    }
}
