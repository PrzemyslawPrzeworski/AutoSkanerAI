package com.example.autoskaner_ai.market;

import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.MarketPriceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketPriceFetchServiceTest {

    private MockRestServiceServer mockServer;
    private MarketPriceFetchService service;

    /**
     * Jina serves UTF-8, and the capture holds Polish place names. Stated rather than left to
     * {@code StringHttpMessageConverter}'s ISO-8859-1 default, so the bytes the service parses are
     * the bytes on disk.
     */
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private static final String CAPTURE_LF = "otomoto-search-results.md";
    private static final String CAPTURE_CRLF = "otomoto-search-results-crlf-derived.md";

    // Facts about the committed capture's bytes, counted off the file itself (see
    // src/test/resources/market/README.md for its provenance). Not recomputed here: a test that
    // re-runs PRICE_PATTERN to work out what PRICE_PATTERN should find proves nothing.
    private static final int PRICES_IN_CAPTURE = 40;
    private static final int LOWEST_IN_CAPTURE = 21_800;
    private static final int SECOND_LOWEST_IN_CAPTURE = 40_900;
    private static final int HIGHEST_IN_CAPTURE = 124_900;

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

    // ---------------------------------------------------------------------------------------------
    // The captured payload. Everything above this line feeds the regex markdown we wrote ourselves;
    // these cases feed it the bytes Jina actually returned, which is the layer the 2026-08-26 `\n`
    // bug lived in and the one no test had ever exercised.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theCapturedOtomotoPageIsReadForEveryPriceOnIt() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("r.jina.ai")))
                .andRespond(withSuccess(capture(CAPTURE_LF), UTF8_TEXT));

        MarketPriceContext ctx = service.enrich(toyotaCorolla2022());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.OK);

        // The regex's yield, stated as an invariant rather than as a min/max: kept plus discarded is
        // every price the pattern found, whatever the trim then decides. This is the assertion that
        // would have caught the `\n` bug — a pattern that matches nothing gives INSUFFICIENT_DATA,
        // and one that matches the wrong blocks gives the wrong total.
        assertThat(ctx.sampleSize() + ctx.discardedCount()).isEqualTo(PRICES_IN_CAPTURE);

        // Hand arithmetic over the sorted capture. The 20th and 21st of the 40 prices are both
        // 79 900, so the median is 79 900 whichever way an even sample's tie is rounded. The band
        // floor is 79 900 / 3 = 26 633, and exactly one price in the file sits below it: 21 800,
        // on a 2022 petrol Corolla at 54 900 km. Whatever that figure is — an instalment, a
        // net-of-VAT price, a damaged car — it is not what this car asks, and the next lowest price
        // in the file is 40 900.
        assertThat(ctx.discardedCount()).isEqualTo(1);
        assertThat(ctx.minPricePln())
                .as("21 800 is out of band and must not be reported as the market floor")
                .isEqualTo(SECOND_LOWEST_IN_CAPTURE)
                .isNotEqualTo(LOWEST_IN_CAPTURE);
        assertThat(ctx.maxPricePln()).isEqualTo(HIGHEST_IN_CAPTURE);

        // 39 prices survive the band, and the interquartile fence drops none of them; the 20th of
        // those 39 is 79 900.
        assertThat(ctx.medianPricePln()).isEqualTo(79_900);
        assertThat(ctx.sampleSize()).isEqualTo(39);
        assertThat(ctx.sampleQuality()).isEqualTo(MarketPriceSampleQuality.SUFFICIENT);

        // The URL the capture was actually taken from, recorded in the fixture README. Asserted here
        // because it is the one thing tying the fixture to this test's inputs: if buildUrl stops
        // reproducing it, the payload above is no longer the answer to the question being asked.
        assertThat(ctx.queryUrl()).isEqualTo("https://www.otomoto.pl/osobowe/toyota/corolla"
                + "?search[filter_float_year:from]=2020&search[filter_float_year:to]=2024"
                + "&search[filter_float_mileage:to]=56320");
        mockServer.verify();
    }

    /**
     * The same page with Windows line endings must read identically.
     *
     * <p>Jina normalises to LF server-side — the capture carries 606 LF and not one CR, which
     * settles the open question {@code market-price-context/reviews/impl-review.md:29} left behind.
     * So the {@code \r?} in {@code PRICE_PATTERN} guards a shape that has never arrived, and it is
     * additionally <b>redundant</b>: {@code [\d\s]+} already admits {@code \r}, so deleting
     * {@code \r?} changes the match on neither fixture. Verified 2026-09-03 against both files.
     *
     * <p>That makes this test a guarantee rather than a mechanism check — the price scrape is line
     * ending agnostic, however that ends up being spelled. The mutation that kills it is narrowing
     * the character class to {@code [\d \n]+}: the LF fixture still parses and this one stops.
     */
    @Test
    void theSameCapturedPageWithWindowsLineEndingsIsReadTheSameWay() {
        mockServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(containsString("r.jina.ai")))
                .andRespond(withSuccess(capture(CAPTURE_CRLF), UTF8_TEXT));

        MarketPriceContext ctx = service.enrich(toyotaCorolla2022());

        assertThat(ctx.status()).isEqualTo(MarketPriceStatus.OK);
        assertThat(ctx.sampleSize() + ctx.discardedCount()).isEqualTo(PRICES_IN_CAPTURE);
        assertThat(ctx.minPricePln()).isEqualTo(SECOND_LOWEST_IN_CAPTURE);
        assertThat(ctx.maxPricePln()).isEqualTo(HIGHEST_IN_CAPTURE);
        assertThat(ctx.medianPricePln()).isEqualTo(79_900);
        mockServer.verify();
    }

    /**
     * Guards the pair itself, because the test above is worthless if both files hold the same bytes.
     *
     * <p>{@code core.autocrlf=true} is the default on a fresh Windows clone and on plenty of CI
     * images. Under it, git would rewrite both fixtures to one style on checkout and the CRLF case
     * would quietly test LF a second time — green, and covering nothing. {@code .gitattributes}
     * pins them {@code -text}; this test is what makes the pin observable rather than a comment in a
     * config file nobody runs.
     */
    @Test
    void theDerivedFixtureDiffersFromItsParentOnlyInLineEndings() {
        String lf = capture(CAPTURE_LF);
        String crlf = capture(CAPTURE_CRLF);

        assertThat(lf).as("the verbatim capture is LF-only, as Jina served it").doesNotContain("\r");
        assertThat(crlf).as("the derived fixture must actually carry CRLF").contains("\r\n");
        assertThat(crlf.replace("\r\n", "\n"))
                .as("one delete-nothing, change-nothing edit apart: line endings only")
                .isEqualTo(lf);
    }

    /** Byte-exact: {@code readAllBytes}, never a reader that would translate line endings. */
    private static String capture(String name) {
        try (InputStream in = new ClassPathResource("market/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing market fixture: " + name, e);
        }
    }

    private ExtractedData toyotaCorolla2019() {
        return new ExtractedData("Toyota", "Corolla", 2019, BigDecimal.valueOf(55_000), "PLN",
                95_000, "benzyna", "automat", null, null, null, null, null, null, null, null);
    }

    /**
     * Chosen so {@code buildUrl} reproduces the capture's own search URL: year 2022 widens to
     * 2020–2024, and 26 320 km widens to a 56 320 ceiling.
     */
    private ExtractedData toyotaCorolla2022() {
        return new ExtractedData("Toyota", "Corolla", 2022, BigDecimal.valueOf(82_900), "PLN",
                26_320, "hybryda", "automat", null, null, null, null, null, null, null, null);
    }
}
