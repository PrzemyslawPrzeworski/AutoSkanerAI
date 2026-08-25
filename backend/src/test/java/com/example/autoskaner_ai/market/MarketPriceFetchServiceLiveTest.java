package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.ListingFetchConfig;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// Needs outbound access to r.jina.ai. Deliberately NOT a @SpringBootTest: the real bean is
// @Profile("!mock"), and the only non-mock profiles wire an LLM client, so a context-loading
// test would demand an API key it never uses. Constructing the service by hand keeps this
// runnable anywhere with a network — including a CI runner with no secrets configured.
//
// The builder comes from the production @Configuration class rather than a hand-rolled
// RestClient.builder(), so the 30 s Jina read timeout and headers under test are the real ones.
//
// Behind a TLS-intercepting proxy add -DargLine="-Djavax.net.ssl.trustStoreType=Windows-ROOT";
// a proxy that blocks r.jina.ai outright (e.g. an "AI tools" category rule) makes this fail
// with a 403 interstitial. That is local network policy, not a product defect — but it is not
// something the test should paper over, so FETCH_FAILED is treated as a failure here.
@Tag("live-llm")
class MarketPriceFetchServiceLiveTest {

    private final MarketPriceFetchService service = new MarketPriceFetchService(
            new ListingFetchConfig().listingFetchBuilder(), new OtomotoSlugMapper());

    @Test
    void fetch_toyotaCorolla2019_returnsOkWithPriceRange() {
        ExtractedData data = new ExtractedData(
                "Toyota", "Corolla", 2019,
                BigDecimal.valueOf(55_000), "PLN",
                95_000,
                null, null, null, null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status())
                .as("FETCH_FAILED means Jina/Otomoto was unreachable; INSUFFICIENT_DATA means the "
                        + "page rendered but PRICE_PATTERN matched nothing — i.e. Otomoto changed "
                        + "its price markup and the market-price range is silently dead. Check the "
                        + "logged cause before assuming a network problem.")
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
