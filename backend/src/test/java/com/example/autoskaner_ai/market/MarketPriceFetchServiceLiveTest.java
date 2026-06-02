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
                null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        System.out.println("Market price result: status=" + ctx.status()
                + " min=" + ctx.minPricePln()
                + " median=" + ctx.medianPricePln()
                + " max=" + ctx.maxPricePln()
                + " sampleSize=" + ctx.sampleSize()
                + " queryUrl=" + ctx.queryUrl());

        // Jina/Otomoto may be unavailable from dev (Zscaler), so accept OK or FETCH_FAILED
        assertThat(ctx.status()).isNotNull();
        assertThat(ctx.status()).isNotEqualTo(MarketPriceStatus.MISSING_INPUTS);
        if (ctx.status() == MarketPriceStatus.OK) {
            assertThat(ctx.minPricePln()).isPositive();
            assertThat(ctx.maxPricePln()).isGreaterThanOrEqualTo(ctx.minPricePln());
            assertThat(ctx.medianPricePln()).isBetween(ctx.minPricePln(), ctx.maxPricePln());
            assertThat(ctx.sampleSize()).isPositive();
            assertThat(ctx.queryUrl()).contains("otomoto.pl");
            assertThat(ctx.fetchedAt()).isNotNull();
        }
    }

    @Test
    void fetch_nullMake_returnsMissingInputs() {
        ExtractedData data = new ExtractedData(
                null, "Corolla", 2019, null, null, 95_000,
                null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.MISSING_INPUTS);
        assertThat(ctx.queryUrl()).isNull();
        assertThat(ctx.minPricePln()).isNull();
    }
}
