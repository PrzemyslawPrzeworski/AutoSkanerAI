package com.example.autoskaner_ai.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ListingFetchServiceTest {

    private MockRestServiceServer mockServer;
    private ListingFetchService service;

    private static final String LISTING_TEXT = """
            BMW 3 E46 2002. Cena: 18500 zł. Przebieg: 180000 km. Benzyna, manual.
            VIN: WBAAM31060GE12345. Klimatyzacja, ABS, airbagi.
            Historia serwisowa dostępna. Sprzedający twierdzi bezwypadkowy.
            Opony zimowe w zestawie. Kontakt telefoniczny po 16:00.
            """;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new ListingFetchService(builder);
    }

    @Test
    void fetch_jinaReturnsContent_returnsOk() {
        // Verify that the actual HTTP call goes to Jina, not directly to otomoto.pl
        mockServer.expect(requestTo(ListingFetchService.JINA_PREFIX + "https://otomoto.pl/listing/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(LISTING_TEXT, MediaType.TEXT_PLAIN));

        FetchResult result = service.fetch("https://otomoto.pl/listing/123");

        assertThat(result.isOk()).isTrue();
        assertThat(result.text()).contains("BMW");
        mockServer.verify();
    }

    @Test
    void fetch_jina403_returnsBlocked() {
        mockServer.expect(requestTo(ListingFetchService.JINA_PREFIX + "https://otomoto.pl/listing/456"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        FetchResult result = service.fetch("https://otomoto.pl/listing/456");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isNotNull();
        mockServer.verify();
    }

    @Test
    void fetch_jinaServerError_returnsFailure() {
        mockServer.expect(requestTo(ListingFetchService.JINA_PREFIX + "https://olx.pl/listing/999"))
                .andRespond(withServerError());

        FetchResult result = service.fetch("https://olx.pl/listing/999");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isNotNull();
        mockServer.verify();
    }

    @Test
    void fetch_jinaEmptyResponse_returnsEmptyContent() {
        mockServer.expect(requestTo(ListingFetchService.JINA_PREFIX + "https://otomoto.pl/listing/empty"))
                .andRespond(withSuccess("short", MediaType.TEXT_PLAIN));

        FetchResult result = service.fetch("https://otomoto.pl/listing/empty");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isEqualTo("empty_content");
        mockServer.verify();
    }

    @Test
    void fetch_privateIpUrl_returnsSsrfBlocked() {
        // SSRF check on the user-supplied host fires before Jina call
        FetchResult result = service.fetch("http://192.168.1.1/test");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isEqualTo("ssrf_blocked");
    }

    @Test
    void fetch_ftpScheme_returnsInvalidScheme() {
        FetchResult result = service.fetch("ftp://example.com/file.txt");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isEqualTo("invalid_scheme");
    }
}
