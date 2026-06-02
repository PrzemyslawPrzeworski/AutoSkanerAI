package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.CepikStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoriaPojazduServiceTest {

    private final HistoriaPojazduSession session = mock(HistoriaPojazduSession.class);
    private final HistoriaPojazduParser parser = mock(HistoriaPojazduParser.class);
    private final HistoriaPojazduService service = new HistoriaPojazduService(session, parser);

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
}
