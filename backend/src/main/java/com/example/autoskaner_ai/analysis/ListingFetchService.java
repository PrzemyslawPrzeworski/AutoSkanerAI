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

        // SSRF protection — DNS lookup capped at 5 s
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

        // Fetch the URL
        String html;
        try {
            html = client.get()
                    .uri(rawUrl)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("read timed out")) {
                return FetchResult.failed("timeout");
            }
            return FetchResult.failed("blocked");
        }

        if (html == null || html.isBlank()) {
            return FetchResult.failed("empty_content");
        }

        // Detect Cloudflare challenge page before parsing
        if (html.contains("cf-browser-verification") || html.contains("Just a moment...")) {
            return FetchResult.failed("blocked");
        }

        String text = Jsoup.parse(html).text();
        if (text.length() < 100) {
            return FetchResult.failed("empty_content");
        }

        return FetchResult.ok(text);
    }
}
