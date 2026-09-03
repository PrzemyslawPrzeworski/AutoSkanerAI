package com.example.autoskaner_ai.analysis;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ListingFetchService {

    private static final Logger log = LoggerFactory.getLogger(ListingFetchService.class);

    // Jina Reader renders JavaScript and bypasses Cloudflare for free.
    // Production (Render) calls this directly. Dev machines behind Zscaler will
    // get a timeout/block — that's a corporate proxy issue, not a code issue.
    static final String JINA_PREFIX = "https://r.jina.ai/";

    /**
     * Cap on the SSRF DNS pre-check. Named because it is the one stage of the request-time budget
     * that is not a socket timeout on a {@code RestClient} builder, and
     * {@code RequestTimeoutBudgetTest} would otherwise have to restate the literal.
     */
    static final int DNS_TIMEOUT_SECONDS = 5;

    private final RestClient client;

    public ListingFetchService(@Qualifier("listingFetchBuilder") RestClient.Builder builder) {
        this.client = builder.build();
    }

    public FetchResult fetch(String rawUrl) {
        long startNanos = System.nanoTime();
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            log.warn("Listing fetch failed reason=invalid_scheme rawUrl={}", rawUrl);
            return FetchResult.failed("invalid_scheme");
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            log.warn("Listing fetch failed reason=invalid_scheme scheme={}", scheme);
            return FetchResult.failed("invalid_scheme");
        }

        String host = uri.getHost();
        if (host == null) {
            log.warn("Listing fetch failed reason=invalid_scheme host=null");
            return FetchResult.failed("invalid_scheme");
        }

        log.info("Listing fetch start host={} scheme={}", host, scheme);

        // SSRF protection — DNS lookup on the user-supplied host, capped at DNS_TIMEOUT_SECONDS
        try {
            InetAddress[] addresses = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return InetAddress.getAllByName(host);
                        } catch (UnknownHostException e) {
                            throw new RuntimeException("unknown_host", e);
                        }
                    })
                    .get(DNS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                    log.warn("Listing fetch failed reason=ssrf_blocked host={} address={}", host, addr.getHostAddress());
                    return FetchResult.failed("ssrf_blocked");
                }
            }
        } catch (TimeoutException e) {
            log.warn("Listing fetch failed reason=timeout stage=dns host={}", host);
            return FetchResult.failed("timeout");
        } catch (ExecutionException e) {
            if (e.getCause() != null && "unknown_host".equals(e.getCause().getMessage())) {
                log.warn("Listing fetch failed reason=unknown_host host={}", host);
                return FetchResult.failed("unknown_host");
            }
            log.warn("Listing fetch failed reason=timeout stage=dns host={} cause={}", host, e.getMessage());
            return FetchResult.failed("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Listing fetch failed reason=timeout stage=dns host={} cause=interrupted", host);
            return FetchResult.failed("timeout");
        }

        // Route through Jina Reader which renders JS and handles Cloudflare.
        // Percent-encode the embedded URL so query strings (?utm_source=…&id=42)
        // and fragments are preserved as part of the target URL rather than being
        // attributed to the Jina URL by the URI parser.
        String jinaUrl = JINA_PREFIX + URLEncoder.encode(rawUrl, StandardCharsets.UTF_8);
        String content;
        try {
            content = client.get()
                    .uri(java.net.URI.create(jinaUrl))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("read timed out")) {
                log.warn("Listing fetch failed reason=timeout stage=jina host={}", host);
                return FetchResult.failed("timeout");
            }
            log.warn("Listing fetch failed reason=blocked stage=jina host={} cause={}", host, e.getMessage());
            return FetchResult.failed("blocked");
        }

        if (content == null || content.isBlank()) {
            log.warn("Listing fetch failed reason=empty_content host={}", host);
            return FetchResult.failed("empty_content");
        }

        // Jina returns plain text — no HTML stripping needed, but run through
        // Jsoup anyway in case it returns HTML for non-standard pages
        String text = content.contains("<html") ? Jsoup.parse(content).text() : content;

        if (text.length() < 100) {
            log.warn("Listing fetch failed reason=empty_content host={} chars={}", host, text.length());
            return FetchResult.failed("empty_content");
        }

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("Listing fetch ok host={} latencyMs={} chars={}", host, latencyMs, text.length());
        return FetchResult.ok(text);
    }
}
