package com.example.autoskaner_ai.cepik;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The API path is versioned and moj.gov.pl bumps it without notice: the literal in this class was
 * {@code 1.0.17} when it was written and {@code 1.1.0} by 2026-08-26, and every lookup in between
 * failed as {@code LOOKUP_FAILED} — which the UI words as "registry temporarily unavailable".
 * Discovery from the bootstrap page replaced the pinned literal, and the class had no test at all.
 *
 * <p>Both branches are asserted through the URL the next call goes to, because that is the only
 * externally visible consequence of reading the version. Cookie merging, XSRF extraction and the
 * request body keys are asserted in {@link CepikDamageReachesTheResponseTest}.
 */
class HistoriaPojazduSessionTest {

    private static final String BASE = "https://moj.gov.pl";
    private static final String SESSION_URL =
            BASE + "/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu";

    private static final String PLATE = "WX00000";
    private static final String VIN = "NMTBZ3BE40R000000";
    private static final String FIRST_REG_DATE = "2022-04-12";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void theApiVersionIsReadFromTheBootstrapPage() {
        expectSessionOpen("""
                <html><head>
                <script src="/nforms/api/HistoriaPojazdu/1.2.3/assets/main.js"></script>
                </head><body></body></html>
                """);
        // 1.2.3 is not the fallback, so reaching this URL can only mean the version was read.
        expectVehicleData("/nforms/api/HistoriaPojazdu/1.2.3/data/vehicle-data");

        openAndFetch();

        server.verify();
    }

    @Test
    void aBootstrapPageNamingNoVersionFallsBackToThePinnedPath() {
        // A layout change that stops naming the version must degrade to a possibly-stale path
        // rather than an outright broken one. This is the branch no test had ever executed.
        expectSessionOpen("<html><body>Strona bez adresów API</body></html>");
        // 1.1.0 duplicates HistoriaPojazduSession.FALLBACK_API_VERSION on purpose: the field is
        // private, and pinning the path the fallback produces is the entire assertion. If the
        // fallback is deliberately bumped, this literal moves with it.
        expectVehicleData("/nforms/api/HistoriaPojazdu/1.1.0/data/vehicle-data");

        openAndFetch();

        server.verify();
    }

    private void openAndFetch() {
        var session = new HistoriaPojazduSession(builder);
        session.open();
        session.fetchVehicleData(PLATE, VIN, FIRST_REG_DATE);
    }

    private void expectSessionOpen(String bootstrapHtml) {
        server.expect(requestTo(SESSION_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess()
                        .header(HttpHeaders.SET_COOKIE, "JSESSIONID=stub-session; Path=/"));

        server.expect(requestTo(SESSION_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(bootstrapHtml, MediaType.TEXT_HTML)
                        .header(HttpHeaders.SET_COOKIE, "XSRF-TOKEN=stub-xsrf-token; Path=/"));
    }

    private void expectVehicleData(String expectedPath) {
        server.expect(requestTo(BASE + expectedPath))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }
}
