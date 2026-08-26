package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketPriceFetchServiceTest {

    private MockRestServiceServer mockServer;
    private MarketPriceFetchService service;

    // Simulates the Jina markdown response format with 5 price blocks
    private static final String JINA_MARKDOWN_5_PRICES = """
            # Oferty Toyota Corolla

            ## Oferta 1
            ### 52 300
            PLN
            ## Oferta 2
            ### 48 000
            PLN
            ## Oferta 3
            ### 65 500
            PLN
            ## Oferta 4
            ### 59 900
            PLN
            ## Oferta 5
            ### 71 200
            PLN
            """;

    // Otomoto renders a financing instalment in the same "### <n> PLN" block as an asking price,
    // and 1 299 clears the 1 000 floor. Before the trim this became the reported minimum.
    private static final String JINA_MARKDOWN_WITH_INSTALMENT = """
            # Oferty Toyota Corolla

            ## Oferta 1
            ### 78 000
            PLN
            ### 1 299
            PLN
            ## Oferta 2
            ### 80 000
            PLN
            ## Oferta 3
            ### 82 000
            PLN
            ## Oferta 4
            ### 84 000
            PLN
            ## Oferta 5
            ### 86 000
            PLN
            ## Oferta 6
            ### 88 000
            PLN
            ## Oferta 7
            ### 90 000
            PLN
            """;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new MarketPriceFetchService(builder, new OtomotoSlugMapper());
    }

    @Test
    void fetch_validMarkdownWith5Prices_returnsCorrectStats() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("r.jina.ai")))
                .andExpect(requestTo(containsString("toyota%2Fcorolla")))
                .andRespond(withSuccess(JINA_MARKDOWN_5_PRICES, MediaType.TEXT_PLAIN));

        ExtractedData data = toyotaCorolla2019();
        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.OK);
        assertThat(ctx.minPricePln()).isEqualTo(48_000);
        assertThat(ctx.maxPricePln()).isEqualTo(71_200);
        // sorted: 48000, 52300, 59900, 65500, 71200 → median at index 2 = 59900
        assertThat(ctx.medianPricePln()).isEqualTo(59_900);
        assertThat(ctx.sampleSize()).isEqualTo(5);
        assertThat(ctx.queryUrl()).isNotNull();
        assertThat(ctx.fetchedAt()).isNotNull();
        mockServer.verify();
    }

    @Test
    void fetch_markdownWithAnInstalment_reportsTheAskingPriceRange() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("r.jina.ai")))
                .andRespond(withSuccess(JINA_MARKDOWN_WITH_INSTALMENT, MediaType.TEXT_PLAIN));

        MarketPriceContext ctx = service.enrich(toyotaCorolla2019());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.OK);
        assertThat(ctx.minPricePln()).isEqualTo(78_000);
        assertThat(ctx.maxPricePln()).isEqualTo(90_000);
        // sampleSize describes the listings the range came from, so the instalment is gone here too.
        assertThat(ctx.sampleSize()).isEqualTo(7);
        mockServer.verify();
    }

    @Test
    void fetch_emptyBody_returnsFetchFailed() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("r.jina.ai")))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        MarketPriceContext ctx = service.enrich(toyotaCorolla2019());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.FETCH_FAILED);
        mockServer.verify();
    }

    @Test
    void fetch_serverError_returnsFetchFailed() {
        mockServer.expect(method(HttpMethod.GET))
                .andRespond(withServerError());

        MarketPriceContext ctx = service.enrich(toyotaCorolla2019());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.FETCH_FAILED);
        mockServer.verify();
    }

    @Test
    void fetch_nullMake_returnsMissingInputs() {
        ExtractedData data = new ExtractedData(null, "Corolla", 2019, null, null, 95_000,
                null, null, null, null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.MISSING_INPUTS);
        assertThat(ctx.queryUrl()).isNull();
    }

    @Test
    void fetch_unknownMake_returnsMissingInputs() {
        ExtractedData data = new ExtractedData("Trabant", "601", 1980, null, null, 50_000,
                null, null, null, null, null, null, null, null, null, null);

        MarketPriceContext ctx = service.enrich(data);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.MISSING_INPUTS);
    }

    @Test
    void fetch_noPricesInMarkdown_retryWithoutModel_thenInsufficient() {
        // Both calls return body with no price blocks → INSUFFICIENT_DATA after retry
        String noPrices = "No listings found for this search.";
        mockServer.expect(method(HttpMethod.GET)).andRespond(withSuccess(noPrices, MediaType.TEXT_PLAIN));
        mockServer.expect(method(HttpMethod.GET)).andRespond(withSuccess(noPrices, MediaType.TEXT_PLAIN));

        MarketPriceContext ctx = service.enrich(toyotaCorolla2019());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.INSUFFICIENT_DATA);
        assertThat(ctx.sampleSize()).isNull();
        mockServer.verify();
    }

    @Test
    void fetch_nullExtractedData_returnsMissingInputs() {
        MarketPriceContext ctx = service.enrich(null);

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.MISSING_INPUTS);
    }

    private ExtractedData toyotaCorolla2019() {
        return new ExtractedData("Toyota", "Corolla", 2019, BigDecimal.valueOf(55_000), "PLN",
                95_000, "benzyna", "automat", null, null, null, null, null, null, null, null);
    }
}
