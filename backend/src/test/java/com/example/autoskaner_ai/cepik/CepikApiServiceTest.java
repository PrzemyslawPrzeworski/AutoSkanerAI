package com.example.autoskaner_ai.cepik;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class CepikApiServiceTest {

    private RestClient.Builder builder;
    private RestClient restClient;
    private CepikApiService service;

    @BeforeEach
    void setUp() {
        builder = mock(RestClient.Builder.class);
        restClient = mock(RestClient.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.defaultHeader(anyString(), anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        service = new CepikApiService(builder);
    }

    @Test
    void returnsDateWhenOneVoivodeshipHitsOnVin() {
        Map<String, Object> hit = Map.of(
                "data", List.of(Map.of(
                        "attributes", Map.of(
                                "data-pierwszej-rejestracjiwkraju", "20180315"
                        )
                ))
        );
        setupRestClientToReturn(hit);

        Optional<String> result = service.lookupFirstRegistrationDate("WBAAM31060GE12345");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("2018-03-15");
    }

    @Test
    void returnsEmptyWhenAllVoivodeshipsMissed() {
        Map<String, Object> empty = Map.of("data", List.of());
        setupRestClientToReturn(empty);

        Optional<String> result = service.lookupFirstRegistrationDate("WBAAM31060GE12345");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenAllVoivodeshipThrowExceptions() {
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenThrow(new RuntimeException("timeout"));

        Optional<String> result = service.lookupFirstRegistrationDate("WBAAM31060GE12345");

        assertThat(result).isEmpty();
    }

    private void setupRestClientToReturn(Map<String, Object> body) {
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(), any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(body);
    }
}
