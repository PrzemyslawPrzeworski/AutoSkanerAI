package com.example.autoskaner_ai.analysis;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ListingFetchService {

    // Jina Reader renders JavaScript and bypasses Cloudflare for free.
    // Production (Render) calls this directly. Dev machines behind Zscaler will
    // get a timeout/block — that's a corporate proxy issue, not a code issue.
    static final String JINA_PREFIX = "https://r.jina.ai/";

    private final RestClient client;

    public ListingFetchService(@Qualifier("listingFetchBuilder") RestClient.Builder builder) {
        this.client = builder.build();
    }

    public FetchResult fetch(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            return FetchResult.failed("invalid_scheme");
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            return FetchResult.failed("invalid_scheme");
        }

        String host = uri.getHost();
        if (host == null) {
            return FetchResult.failed("invalid_scheme");
        }

        // SSRF protection — DNS lookup on the user-supplied host, capped at 5 s
        try {
            InetAddress[] addresses = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return InetAddress.getAllByName(host);
                        } catch (UnknownHostException e) {
                            throw new RuntimeException("unknown_host", e);
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                    return FetchResult.failed("ssrf_blocked");
                }
            }
        } catch (TimeoutException e) {
            return FetchResult.failed("timeout");
        } catch (ExecutionException e) {
            if (e.getCause() != null && "unknown_host".equals(e.getCause().getMessage())) {
                return FetchResult.failed("unknown_host");
            }
            return FetchResult.failed("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failed("timeout");
        }

        // Route through Jina Reader which renders JS and handles Cloudflare.
        // Build the Jina URL as a string — do NOT pass through URI constructor,
        // which collapses "https://" to "https:/" in the concatenated result.
        String jinaUrl = JINA_PREFIX + rawUrl;
        String content;
        try {
            // Use URI.create only for the base Jina host; pass the full encoded URL
            // via uriBuilder to prevent RestClient from normalizing the double-slash.
            content = client.get()
                    .uri(java.net.URI.create(jinaUrl))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("read timed out")) {
                return FetchResult.failed("timeout");
            }
            return FetchResult.failed("blocked");
        }

        if (content == null || content.isBlank()) {
            return FetchResult.failed("empty_content");
        }

        // Jina returns plain text — no HTML stripping needed, but run through
        // Jsoup anyway in case it returns HTML for non-standard pages
        String text = content.contains("<html") ? Jsoup.parse(content).text() : content;

        if (text.length() < 100) {
            return FetchResult.failed("empty_content");
        }

        return FetchResult.ok(text);
    }
}
