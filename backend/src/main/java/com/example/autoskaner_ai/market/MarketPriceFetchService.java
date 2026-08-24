package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Profile("!mock")
public class MarketPriceFetchService implements MarketPriceEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceFetchService.class);

    private static final String JINA_PREFIX = "https://r.jina.ai/";
    private static final String OTOMOTO_BASE = "https://www.otomoto.pl/osobowe";
    private static final Pattern PRICE_PATTERN = Pattern.compile("###\\s*([\\d\\s]+)\\r?\\nPLN");

    private final RestClient client;
    private final OtomotoSlugMapper slugMapper;

    public MarketPriceFetchService(
            @Qualifier("listingFetchBuilder") RestClient.Builder builder,
            OtomotoSlugMapper slugMapper) {
        this.client = builder.build();
        this.slugMapper = slugMapper;
    }

    @Override
    public MarketPriceContext enrich(ExtractedData extracted) {
        if (extracted == null || extracted.make() == null) {
            return missing();
        }

        Optional<String> makeSlugOpt = slugMapper.makeSlug(extracted.make());
        if (makeSlugOpt.isEmpty()) {
            log.info("Market price fetch skipped: unknown make={}", extracted.make());
            return missing();
        }

        String makeSlug = makeSlugOpt.get();
        String modelSlug = extracted.model() != null ? slugMapper.modelSlug(extracted.model()) : null;

        String queryUrl = buildUrl(makeSlug, modelSlug, extracted.year(), extracted.mileageKm());
        List<Integer> prices = fetchPrices(queryUrl);

        if (prices == null) {
            return failed(queryUrl);
        }

        // Retry without model slug if no results and model was used
        if (prices.isEmpty() && modelSlug != null && !modelSlug.isBlank()) {
            log.info("Market price retry without model slug make={}", makeSlug);
            queryUrl = buildUrl(makeSlug, null, extracted.year(), extracted.mileageKm());
            List<Integer> retryPrices = fetchPrices(queryUrl);
            if (retryPrices != null) {
                prices = retryPrices;
            }
        }

        if (prices.isEmpty()) {
            return new MarketPriceContext(MarketPriceStatus.INSUFFICIENT_DATA, null, null, null, null, queryUrl, Instant.now());
        }

        Collections.sort(prices);
        int min = prices.get(0);
        int max = prices.get(prices.size() - 1);
        int median = prices.get(prices.size() / 2);
        int sampleSize = prices.size();

        log.info("Market price fetch ok make={} model={} year={} sampleSize={} min={} median={} max={}",
                makeSlug, modelSlug, extracted.year(), sampleSize, min, median, max);

        return new MarketPriceContext(MarketPriceStatus.OK, min, median, max, sampleSize, queryUrl, Instant.now());
    }

    private String buildUrl(String makeSlug, String modelSlug, Integer year, Integer mileageKm) {
        StringBuilder sb = new StringBuilder(OTOMOTO_BASE)
                .append("/").append(makeSlug);

        if (modelSlug != null && !modelSlug.isBlank()) {
            sb.append("/").append(modelSlug);
        }

        boolean hasParam = false;
        if (year != null) {
            sb.append("?search[filter_float_year:from]=").append(year - 2)
              .append("&search[filter_float_year:to]=").append(year + 2);
            hasParam = true;
        }
        if (mileageKm != null) {
            sb.append(hasParam ? "&" : "?")
              .append("search[filter_float_mileage:to]=").append(mileageKm + 30_000);
        }

        return sb.toString();
    }

    /**
     * Returns null on network/fetch error, empty list if no prices found.
     */
    private List<Integer> fetchPrices(String otomotoUrl) {
        String jinaUrl = JINA_PREFIX + URLEncoder.encode(otomotoUrl, StandardCharsets.UTF_8);
        String body;
        try {
            body = client.get()
                    .uri(java.net.URI.create(jinaUrl))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("Market price fetch failed url={} cause={}", otomotoUrl, e.getMessage());
            return null;
        }

        if (body == null || body.isBlank()) {
            log.warn("Market price fetch returned empty body url={}", otomotoUrl);
            return null;
        }

        List<Integer> prices = new ArrayList<>();
        Matcher m = PRICE_PATTERN.matcher(body);
        while (m.find()) {
            String raw = m.group(1).replaceAll("\\s", "");
            try {
                long v = Long.parseLong(raw);
                if (v >= 1_000 && v <= 10_000_000) {
                    prices.add((int) v);
                } else {
                    log.warn("Market price token out of range url={} token={}", otomotoUrl, raw);
                }
            } catch (NumberFormatException e) {
                log.warn("Market price unparseable token url={} token={}", otomotoUrl, m.group(1));
            }
        }
        return prices;
    }

    private MarketPriceContext missing() {
        return new MarketPriceContext(MarketPriceStatus.MISSING_INPUTS, null, null, null, null, null, null);
    }

    private MarketPriceContext failed(String queryUrl) {
        return new MarketPriceContext(MarketPriceStatus.FETCH_FAILED, null, null, null, null, queryUrl, Instant.now());
    }
}
