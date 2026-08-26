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

    private String apiBase = "/nforms/api/HistoriaPojazdu/" + FALLBACK_API_VERSION + "/data";

    private final RestClient.Builder builder;

    private RestClient client;
    private final List<String> cookies = new ArrayList<>();
    private String xsrfToken;
    private String nfWid;

    public HistoriaPojazduSession(RestClient.Builder builder) {
        this.builder = builder;
        this.client = builder.build();
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

            client = builder
                    .defaultHeader(HttpHeaders.COOKIE, String.join("; ", cookies))
                    .build();

            ResponseEntity<String> nfResponse = client.post()
                    .uri(SESSION_PATH)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("NF_WID=" + nfWid)
                    .retrieve()
                    .toEntity(String.class);

            extractCookies(nfResponse.getHeaders());
            extractApiVersion(nfResponse.getBody());
            extractXsrfToken();

            client = builder
                    .defaultHeader(HttpHeaders.COOKIE, String.join("; ", cookies))
                    .build();

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
            if (cookie.startsWith("XSRF-TOKEN=")) {
                xsrfToken = cookie.substring("XSRF-TOKEN=".length());
                return;
            }
        }
    }
}
