package com.example.autoskaner_ai.cepik;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HistoriaPojazduSession {

    private static final Logger log = LoggerFactory.getLogger(HistoriaPojazduSession.class);
    private static final String SESSION_PATH = "/uslugi/engine/ng/index?xFormsAppName=HistoriaPojazdu";
    private static final String API_BASE = "/nforms/api/HistoriaPojazdu/1.0.17/data";

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

            ResponseEntity<Void> nfResponse = client.post()
                    .uri(SESSION_PATH)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("NF_WID=" + nfWid)
                    .retrieve()
                    .toBodilessEntity();

            extractCookies(nfResponse.getHeaders());
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
                    .uri(API_BASE + "/vehicle-data")
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
                    .uri(API_BASE + "/timeline-data")
                    .header("X-Xsrf-Token", xsrfToken)
                    .header("Nf_wid", nfWid)
                    .body(Map.of("registrationNumber", plate, "VINNumber", vin, "firstRegistrationDate", firstRegDate))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new HistoriaPojazduSessionException("timeline-data failed: " + e.getMessage(), e);
        }
    }

    public void close() {
        try {
            client.get().uri(API_BASE + "/close").retrieve().toBodilessEntity();
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
