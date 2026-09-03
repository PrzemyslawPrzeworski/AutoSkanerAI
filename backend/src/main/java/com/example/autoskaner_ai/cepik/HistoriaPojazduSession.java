package com.example.autoskaner_ai.cepik;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HistoriaPojazduSession {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduSession.class);
    private static final String SESSION_PATH = "/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu";

    // The API path is versioned and moj.gov.pl bumps it without notice — it was 1.0.17 when this
    // was written and 1.1.0 by 2026-08-26. The bootstrap HTML names the current version in its
    // asset URLs, so read it there rather than pinning a literal that silently rots.
    private static final Pattern API_VERSION =
            Pattern.compile("/nforms/api/HistoriaPojazdu/([0-9]+(?:\\.[0-9]+)*)/");
    private static final String FALLBACK_API_VERSION = "1.1.0";

    /** The handshake hands the XSRF token over as a cookie; every data call sends it as a header. */
    private static final String XSRF_COOKIE = "XSRF-TOKEN=";

    private String apiBase = "/nforms/api/HistoriaPojazdu/" + FALLBACK_API_VERSION + "/data";

    private final RestClient.Builder builder;

    private RestClient client;
    private final List<String> cookies = new ArrayList<>();
    private String xsrfToken;
    private String nfWid;

    /**
     * @param builder the shared {@code historiaPojazduBuilder} bean, which this constructor
     *                <em>clones</em> and never mutates.
     *
     * <p>The clone is the fix for a production defect, not tidiness. {@code open()} installs the
     * session's cookies as a default header on its builder; done to the shared singleton, that
     * leaves lookup <i>N</i>'s cookies on the builder that lookup <i>N+1</i>'s bootstrap GET is
     * built from, and gives two concurrent analyses one cookie jar between them. Both enrichments
     * run on the request thread ({@code CLAUDE.md} § "Enrichment services"), so concurrency here is
     * ordinary load, not a stress test.
     *
     * <p>{@code MockRestServiceServer.bindTo} works by calling {@code requestFactory(...)} on the
     * builder, and {@code clone()} copies the request factory, so a clone taken after {@code bindTo}
     * still routes to the mock. {@code CepikDamageReachesTheResponseTest} passing unchanged is the
     * check on that.
     */
    public HistoriaPojazduSession(RestClient.Builder builder) {
        this.builder = builder.clone();
        this.client = this.builder.build();
    }

    public void open() {
        cookies.clear();
        xsrfToken = null;
        nfWid = "HistoriaPojazdu:" + System.currentTimeMillis();

        try {
            ResponseEntity<Void> initResponse = client.get()
                    .uri(SESSION_PATH)
                    .retrieve()
                    .toBodilessEntity();

            extractCookies(initResponse.getHeaders());

            client = clientWithCookies();

            ResponseEntity<String> nfResponse = client.post()
                    .uri(SESSION_PATH)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("NF_WID=" + nfWid)
                    .retrieve()
                    .toEntity(String.class);

            extractCookies(nfResponse.getHeaders());
            extractApiVersion(nfResponse.getBody());
            extractXsrfToken();

            client = clientWithCookies();

        } catch (Exception e) {
            throw new HistoriaPojazduSessionException("Failed to open session: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchVehicleData(String plate, String vin, String firstRegDate) {
        try {
            return client.post()
                    .uri(apiBase + "/vehicle-data")
                    .header("X-Xsrf-Token", xsrfToken)
                    .header("Nf_wid", nfWid)
                    .body(Map.of("registrationNumber", plate, "VINNumber", vin, "firstRegistrationDate", firstRegDate))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new HistoriaPojazduSessionException("vehicle-data failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchTimelineData(String plate, String vin, String firstRegDate) {
        try {
            return client.post()
                    .uri(apiBase + "/timeline-data")
                    .header("X-Xsrf-Token", xsrfToken)
                    .header("Nf_wid", nfWid)
                    .body(Map.of("registrationNumber", plate, "VINNumber", vin, "firstRegistrationDate", firstRegDate))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new HistoriaPojazduSessionException("timeline-data failed: " + e.getMessage(), e);
        }
    }

    // Keeps the fallback if the markup ever stops naming the version, so a layout change
    // degrades to a possibly-stale path rather than an outright broken one.
    private void extractApiVersion(String bootstrapHtml) {
        if (bootstrapHtml == null) {
            return;
        }
        Matcher matcher = API_VERSION.matcher(bootstrapHtml);
        if (matcher.find()) {
            apiBase = "/nforms/api/HistoriaPojazdu/" + matcher.group(1) + "/data";
        } else {
            log.warn("Could not read the historiapojazdu API version from the bootstrap page; "
                    + "falling back to {}", FALLBACK_API_VERSION);
        }
    }

    public void close() {
        try {
            client.get().uri(apiBase + "/close").retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.debug("Session close failed (non-critical): {}", e.getMessage());
        }
    }

    /**
     * Rebuilds the client so the next request carries the cookies collected so far, as one
     * {@code Cookie} header.
     *
     * <p>{@code set} rather than {@code add}: this is called twice per {@code open()}, and appending
     * would put two {@code Cookie} header values on the wire where the protocol wants one.
     *
     * <p>No cookies means <b>no header at all</b>, not an empty one. {@code String.join} over an
     * empty list is {@code ""}, and {@code Cookie:} with nothing after it is not "I hold no
     * cookies" — it is a malformed header a server is entitled to reject.
     */
    private RestClient clientWithCookies() {
        return builder
                .defaultHeaders(headers -> {
                    if (cookies.isEmpty()) {
                        headers.remove(HttpHeaders.COOKIE);
                    } else {
                        headers.set(HttpHeaders.COOKIE, String.join("; ", cookies));
                    }
                })
                .build();
    }

    /**
     * Merges one response's {@code Set-Cookie} headers into the session jar.
     *
     * <p>Only {@code name=value} survives. Everything after the first {@code ;} is a directive
     * addressed to a browser ({@code Path}, {@code HttpOnly}, {@code SameSite}, {@code Max-Age});
     * echoing any of it back in a {@code Cookie} header is not what a client is supposed to send.
     *
     * <p>The {@code removeIf} is a replace, not a de-duplicate: the handshake re-issues
     * {@code JSESSIONID} on the bootstrap POST, and sending both the old and the new value leaves
     * the server to pick one.
     */
    private void extractCookies(HttpHeaders headers) {
        List<String> setCookieHeaders = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookieHeaders != null) {
            for (String header : setCookieHeaders) {
                String cookiePair = header.split(";")[0].strip();
                cookies.removeIf(c -> c.startsWith(cookiePair.split("=")[0] + "="));
                cookies.add(cookiePair);
            }
        }
    }

    private void extractXsrfToken() {
        for (String cookie : cookies) {
            if (cookie.startsWith(XSRF_COOKIE)) {
                xsrfToken = cookie.substring(XSRF_COOKIE.length());
                return;
            }
        }
        // Every data call sends this token. Without it the registry rejects the call, the lookup
        // surfaces as LOOKUP_FAILED, and the UI words that as "registry temporarily unavailable" —
        // so a handshake that stops issuing the cookie is a permanent breakage wearing an outage's
        // clothes. Names only, never values: a session cookie is a credential.
        log.warn("historiapojazdu handshake issued no {} cookie — data calls will go out without an "
                + "XSRF token and be rejected. Cookies received: {}", XSRF_COOKIE, cookieNames());
    }

    private List<String> cookieNames() {
        return cookies.stream().map(c -> c.split("=")[0]).toList();
    }
}
