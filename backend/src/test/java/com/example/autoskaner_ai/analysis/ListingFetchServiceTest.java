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

    private static final String LISTING_HTML = """
            <html><head><title>BMW 3 E46 2002</title></head>
            <body>
            <h1>BMW 3 E46 2002</h1>
            <p>Cena: 18500 zł. Przebieg: 180000 km. Benzyna, manual. VIN: WBAAM31060GE12345.
            Klimatyzacja, ABS, airbagi. Historia serwisowa dostępna. Sprzedający twierdzi bezwypadkowy.
            Opony zimowe w zestawie. Możliwa zamiana. Kontakt telefoniczny po 16:00.</p>
            </body></html>
            """;

    private static final String CLOUDFLARE_HTML = """
            <html><head><title>Just a moment...</title></head>
            <body><div id="cf-browser-verification">Checking your browser...</div></body></html>
            """;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new ListingFetchService(builder);
    }

    @Test
    void fetch_http200WithContent_returnsOk() {
        mockServer.expect(requestTo("https://otomoto.pl/listing/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(LISTING_HTML, MediaType.TEXT_HTML));

        FetchResult result = service.fetch("https://otomoto.pl/listing/123");

        assertThat(result.isOk()).isTrue();
        assertThat(result.text()).isNotBlank();
        assertThat(result.text()).contains("BMW");
        mockServer.verify();
    }

    @Test
    void fetch_http403_returnsBlocked() {
        mockServer.expect(requestTo("https://otomoto.pl/listing/456"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        FetchResult result = service.fetch("https://otomoto.pl/listing/456");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isNotNull();
        mockServer.verify();
    }

    @Test
    void fetch_cloudflareChallengeHtml_returnsBlocked() {
        mockServer.expect(requestTo("https://otomoto.pl/listing/789"))
                .andRespond(withSuccess(CLOUDFLARE_HTML, MediaType.TEXT_HTML));

        FetchResult result = service.fetch("https://otomoto.pl/listing/789");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked");
        mockServer.verify();
    }

    @Test
    void fetch_serverError_returnsFailure() {
        mockServer.expect(requestTo("https://olx.pl/listing/999"))
                .andRespond(withServerError());

        FetchResult result = service.fetch("https://olx.pl/listing/999");

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).isNotNull();
        mockServer.verify();
    }

    @Test
    void fetch_privateIpUrl_returnsSsrfBlocked() {
        // SSRF check fires before any HTTP call — no mock expectation needed
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
