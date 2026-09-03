package com.example.autoskaner_ai.cepik;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerList;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The registry handshake: version discovery, the cookie jar, and the XSRF token.
 *
 * <h2>Version discovery</h2>
 *
 * The API path is versioned and moj.gov.pl bumps it without notice: the literal in this class was
 * {@code 1.0.17} when it was written and {@code 1.1.0} by 2026-08-26, and every lookup in between
 * failed as {@code LOOKUP_FAILED} — which the UI words as "registry temporarily unavailable".
 * Discovery from the bootstrap page replaced the pinned literal. Both branches are asserted through
 * the URL the next call goes to, because that is the only externally visible consequence of reading
 * the version.
 *
 * <h2>Cookies and XSRF</h2>
 *
 * <p>{@code CepikDamageReachesTheResponseTest} asserts the XSRF hop ({@code :333}) and the registry's
 * request-body key names ({@code :335-338}) — <b>and nothing about the {@code Cookie} header</b>. An
 * earlier version of this Javadoc claimed otherwise; the cookie merge was in fact asserted nowhere
 * in the suite, which is the gap the cases below close. A broken merge is invisible from the
 * outside: the data call 403s, {@code HistoriaPojazduService} maps that to {@code LOOKUP_FAILED},
 * {@code CepikRiskAdjuster} only acts on {@code FOUND}, and the 2026-08-26 production failure —
 * {@code risk: 88} on a vehicle carrying a registered szkoda istotna — comes back.
 *
 * <h2>Oracles</h2>
 *
 * <p>The {@code Cookie} header expectations come from RFC 6265 §5.4, not from reading
 * {@code extractCookies}: a client sends <b>one</b> {@code Cookie} header, holding {@code name=value}
 * pairs joined by {@code "; "}, with no {@code Set-Cookie} attributes echoed back and no header at
 * all when the jar is empty. The cross-lookup case comes from the defect itself — one shared mutable
 * builder bean, mutated per session.
 *
 * <h2>A note on the seam</h2>
 *
 * <p>A failed {@code MockRestRequestMatchers} matcher throws {@code AssertionError}, which is an
 * {@code Error} and so escapes {@code HistoriaPojazduSession.open()}'s {@code catch (Exception e)}.
 * These assertions therefore surface as test failures rather than being swallowed into
 * {@code HistoriaPojazduSessionException}. That is load-bearing: if the catch were ever widened to
 * {@code Throwable}, every header assertion in this file would go quiet.
 */
class HistoriaPojazduSessionTest {

    private static final String BASE = "https://moj.gov.pl";
    private static final String SESSION_URL =
            BASE + "/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu";

    private static final String PLATE = "WX00000";
    /** Synthetic, and it stays synthetic: this repo is public. */
    private static final String VIN = "NMTBZ3BE40R000000";
    private static final String FIRST_REG_DATE = "2022-04-12";

    private static final String SESSION_COOKIE = "JSESSIONID=stub-session; Path=/";
    private static final String XSRF_COOKIE = "XSRF-TOKEN=stub-xsrf-token; Path=/";
    private static final String BOTH_COOKIES = "JSESSIONID=stub-session; XSRF-TOKEN=stub-xsrf-token";

    /**
     * A bootstrap page that names the version, so the version-fallback WARN stays out of the way of
     * tests that assert on the log.
     */
    private static final String VERSIONED_BOOTSTRAP =
            "<html><head><script src=\"/nforms/api/HistoriaPojazdu/1.1.0/assets/main.js\"></script>"
                    + "</head><body></body></html>";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();

        logs = new ListAppender<>();
        logs.start();
        sessionLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        sessionLogger().detachAppender(logs);
        logs.stop();
    }

    // ===========================================================================================
    // Version discovery
    // ===========================================================================================

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

    /**
     * A bootstrap POST that answers 200 with no body at all — the guard clause in
     * {@code extractApiVersion} that stops a null from reaching the regex. Distinct from the case
     * above: there the body is present and names no version, here there is no body to search.
     * A page served as an error interstitial, or a truncated response, arrives this way.
     */
    @Test
    void aBootstrapPostWithNoBodyFallsBackInsteadOfThrowing() {
        expectGet(SESSION_URL).andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, SESSION_COOKIE));
        expectPost(SESSION_URL).andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));
        expectVehicleData("/nforms/api/HistoriaPojazdu/1.1.0/data/vehicle-data");

        openAndFetch();

        server.verify();
    }

    // ===========================================================================================
    // The cookie jar — RFC 6265 §5.4
    // ===========================================================================================

    /**
     * Two cookies from two responses arrive as one {@code Cookie} header on the data call, and the
     * bootstrap POST in between already carries the first one.
     *
     * <p>{@code headerList} with {@code contains} rather than {@code header}: the point includes
     * that there is exactly <b>one</b> header value. {@code header(name, value)} would pass on a
     * request carrying the right value plus a stale second one, which is precisely what appending
     * instead of replacing the default header produces.
     */
    @Test
    void bothCookiesGoOutAsASingleCookieHeader() {
        expectGet(SESSION_URL)
                // Nothing has been received yet, so there is nothing to send.
                .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
                .andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, SESSION_COOKIE));
        expectPost(SESSION_URL)
                .andExpect(headerList(HttpHeaders.COOKIE, contains("JSESSIONID=stub-session")))
                .andRespond(bootstrap().header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));
        expectDataCall("/vehicle-data")
                .andExpect(headerList(HttpHeaders.COOKIE, contains(BOTH_COOKIES)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        openAndFetch();

        server.verify();
    }

    /**
     * The handshake re-issues {@code JSESSIONID} on the bootstrap POST. The new value replaces the
     * old one; sending both would leave the server to guess which session it is talking to.
     *
     * <p>The replace-not-append semantics are the observed behaviour of the merge, documented here
     * so a change to it has to be deliberate.
     */
    @Test
    void aReissuedCookieIsReplacedRatherThanSentTwice() {
        expectGet(SESSION_URL)
                .andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, "JSESSIONID=first; Path=/"));
        expectPost(SESSION_URL)
                .andRespond(bootstrap().header(HttpHeaders.SET_COOKIE,
                        "JSESSIONID=second; Path=/", XSRF_COOKIE));
        expectDataCall("/vehicle-data")
                .andExpect(headerList(HttpHeaders.COOKIE,
                        contains("JSESSIONID=second; XSRF-TOKEN=stub-xsrf-token")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        openAndFetch();

        server.verify();
    }

    /**
     * {@code Path}, {@code HttpOnly}, {@code Secure}, {@code SameSite} and {@code Max-Age} are
     * directives addressed to a browser. A client echoes back {@code name=value} and nothing else.
     */
    @Test
    void cookieAttributesAreNotEchoedBack() {
        expectGet(SESSION_URL).andRespond(withSuccess().header(HttpHeaders.SET_COOKIE,
                "JSESSIONID=stub-session; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=3600"));
        expectPost(SESSION_URL)
                .andExpect(headerList(HttpHeaders.COOKIE, contains("JSESSIONID=stub-session")))
                .andRespond(bootstrap().header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));

        newSession().open();

        server.verify();
    }

    /**
     * No {@code Set-Cookie} anywhere in the handshake means no {@code Cookie} header, not an empty
     * one. {@code String.join} over an empty jar is {@code ""}, and {@code Cookie:} followed by
     * nothing is a malformed header rather than a statement that the client holds no cookies.
     */
    @Test
    void aHandshakeThatSetsNoCookiesSendsNoCookieHeaderAtAll() {
        expectGet(SESSION_URL).andRespond(withSuccess());
        expectPost(SESSION_URL)
                .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
                .andRespond(bootstrap());

        newSession().open();

        server.verify();
    }

    /**
     * The regression test for the per-session builder clone.
     *
     * <p>{@code HistoriaPojazduConfig} exposes one {@code RestClient.Builder} bean. {@code open()}
     * installs the session's cookies on its builder as a default header, so before the clone, lookup
     * one's {@code JSESSIONID} was still on the shared bean when lookup two built its client — the
     * second vehicle's bootstrap GET went out authenticated as the first vehicle's session, and two
     * concurrent analyses shared one jar. Both enrichments run on the request thread, so that is
     * ordinary load.
     *
     * <p>Two sessions from the same builder, and the assertion is on the second one's very first
     * request: it must be as clean as the first session's was.
     */
    @Test
    void noCookieFromOneLookupReachesTheNextLookupsBootstrapGet() {
        expectGet(SESSION_URL).andRespond(withSuccess()
                .header(HttpHeaders.SET_COOKIE, "JSESSIONID=lookup-one; Path=/"));
        expectPost(SESSION_URL).andRespond(bootstrap().header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));

        expectGet(SESSION_URL)
                .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
                .andRespond(withSuccess()
                        .header(HttpHeaders.SET_COOKIE, "JSESSIONID=lookup-two; Path=/"));
        expectPost(SESSION_URL)
                .andExpect(headerList(HttpHeaders.COOKIE, contains("JSESSIONID=lookup-two")))
                .andRespond(bootstrap().header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));

        newSession().open();
        newSession().open();

        server.verify();
    }

    // ===========================================================================================
    // XSRF
    // ===========================================================================================

    /**
     * The token is never handed to the session directly — it is read out of a {@code Set-Cookie} and
     * sent back as a header. Asserted here as well as in
     * {@code CepikDamageReachesTheResponseTest:333}, because this is the class that owns the hop and
     * that test could be rewritten around a different concern without anyone noticing the loss.
     */
    @Test
    void theXsrfTokenIsReadFromTheCookieAndSentAsAHeader() {
        expectSessionOpen("<html><body></body></html>");
        expectDataCall("/vehicle-data")
                .andExpect(header("X-Xsrf-Token", "stub-xsrf-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        openAndFetch();

        server.verify();
    }

    /**
     * A handshake that issues no {@code XSRF-TOKEN} cookie must say so at WARN.
     *
     * <p>Without the log this degrades invisibly: the data calls go out unauthenticated, the
     * registry rejects them, {@code HistoriaPojazduService} maps the rejection to
     * {@code LOOKUP_FAILED}, and the UI tells the user the registry is temporarily unavailable.
     * "Temporarily" would be wrong forever, and nothing in the logs would contradict it.
     *
     * <p>The line names the cookies it <em>did</em> receive, by name only. A session cookie value is
     * a credential, and this asserts it is not in the log.
     *
     * <p>Also pins the seam caveat about what a null token actually looks like on the next request —
     * see the comment on the data-call expectation.
     */
    @Test
    void aHandshakeWithNoXsrfCookieWarnsInsteadOfDegradingSilently() {
        expectGet(SESSION_URL).andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, SESSION_COOKIE));
        // Versioned, so the only WARN this handshake can produce is the one under test — the
        // version-fallback WARN would otherwise make `singleElement` a statement about ordering.
        expectPost(SESSION_URL).andRespond(bootstrap(VERSIONED_BOOTSTRAP));

        // Seam caveat, established by running it: with no token the header is *present* at the mock
        // seam holding a literal `null` value — `headerDoesNotExist` fails here with
        // "it exists with values: [null]". Production's real request factory coerces that to an
        // empty string on the wire. So this asserts the seam's truth, not the wire's; either way the
        // registry gets no token, which is what the WARN below exists to make findable.
        expectDataCall("/vehicle-data")
                .andExpect(headerList("X-Xsrf-Token", contains((String) null)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        openAndFetch();

        assertThat(warnings())
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("XSRF-TOKEN")
                .contains("JSESSIONID")
                .doesNotContain("stub-session");
        server.verify();
    }

    // ===========================================================================================
    // Plumbing
    // ===========================================================================================

    private HistoriaPojazduSession newSession() {
        return new HistoriaPojazduSession(builder);
    }

    private void openAndFetch() {
        var session = newSession();
        session.open();
        session.fetchVehicleData(PLATE, VIN, FIRST_REG_DATE);
    }

    /** The happy-path handshake: a session cookie, then a bootstrap page and the XSRF cookie. */
    private void expectSessionOpen(String bootstrapHtml) {
        expectGet(SESSION_URL).andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, SESSION_COOKIE));
        expectPost(SESSION_URL).andRespond(bootstrap(bootstrapHtml)
                .header(HttpHeaders.SET_COOKIE, XSRF_COOKIE));
    }

    private ResponseActions expectGet(String url) {
        return server.expect(requestTo(url)).andExpect(method(HttpMethod.GET));
    }

    private ResponseActions expectPost(String url) {
        return server.expect(requestTo(url)).andExpect(method(HttpMethod.POST));
    }

    private ResponseActions expectDataCall(String path) {
        return expectPost(BASE + "/nforms/api/HistoriaPojazdu/1.1.0/data" + path);
    }

    private void expectVehicleData(String expectedPath) {
        expectPost(BASE + expectedPath).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    private static DefaultResponseCreator bootstrap() {
        return bootstrap("<html><body></body></html>");
    }

    private static DefaultResponseCreator bootstrap(String html) {
        return withSuccess(html, MediaType.TEXT_HTML);
    }

    private static ch.qos.logback.classic.Logger sessionLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HistoriaPojazduSession.class);
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
