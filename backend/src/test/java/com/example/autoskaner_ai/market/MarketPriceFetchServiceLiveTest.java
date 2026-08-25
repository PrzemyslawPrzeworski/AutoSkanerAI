package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// Needs outbound access to r.jina.ai. Behind a TLS-intercepting proxy add
// -DargLine="-Djavax.net.ssl.trustStoreType=Windows-ROOT"; a proxy that blocks r.jina.ai
// outright (e.g. an "AI tools" category rule) will make this fail with a 403 interstitial —
// that is a local network policy, not a product defect, but it is not something the test
// should paper over, so FETCH_FAILED is treated as a failure here.
@Tag("live-llm")
@SpringBootTest
@ActiveProfiles("openrouter")
class MarketPriceFetchServiceLiveTest {

    @Autowired
    private MarketPriceFetchService service;

    @Test
    void fetch_toyotaCorolla2019_returnsOkWithPriceRange() {
        ExtractedData data = new ExtractedData(
                "Toyota", "Corolla", 2019,
                BigDecimal.valueOf(55_000), "PLN",
                95_000,
                null, null, null, null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status())
                .as("FETCH_FAILED means Jina/Otomoto was unreachable or the regex stopped "
                        + "matching; INSUFFICIENT_DATA means the page rendered but no prices parsed. "
                        + "Both are real signals — check the logged cause.")
                .isEqualTo(MarketPriceStatus.OK);
        assertThat(ctx.queryUrl()).contains("otomoto.pl");
        assertThat(ctx.sampleSize()).isPositive();
        assertThat(ctx.minPricePln()).isPositive();
        assertThat(ctx.maxPricePln()).isGreaterThanOrEqualTo(ctx.minPricePln());
        assertThat(ctx.medianPricePln()).isBetween(ctx.minPricePln(), ctx.maxPricePln());
        assertThat(ctx.fetchedAt()).isNotNull();
    }

    @Test
    void fetch_nullMake_returnsMissingInputs() {
        ExtractedData data = new ExtractedData(
                null, "Corolla", 2019, null, null, 95_000,
                null, null, null, null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.MISSING_INPUTS);
        assertThat(ctx.queryUrl()).isNull();
        assertThat(ctx.minPricePln()).isNull();
    }
}
