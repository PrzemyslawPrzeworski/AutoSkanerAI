package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.market.MarketPriceSampleQuality;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two fields added to {@link MarketPriceContext} reach the wire under <em>the
 * application's own</em> Jackson configuration.
 *
 * <p>The sibling {@code MarketPriceFetchServiceTest} asserts against the record's accessors, which
 * says nothing about serialisation, and {@code AnalysisControllerTest} builds its converters through
 * {@code MockMvcBuilders.standaloneSetup} — that never reads {@code application.properties}. So a
 * later {@code spring.jackson.default-property-inclusion=non_null} would leave both of those green
 * while {@code sampleQuality} vanished from every response, and the frontend would fall back to
 * rendering an uncaveated range: precisely the failure this change exists to close, reintroduced
 * from a properties file. Same reasoning as {@link CepikResultSerialisationTest}, same shape.
 *
 * <p>Both cases assert the <b>raw body string</b> rather than a parsed tree. A caveat key that is
 * absent and a caveat key holding {@code null} are indistinguishable once parsed, and only one of
 * them is the contract.
 */
@SpringBootTest
@ActiveProfiles("mock")
class MarketPriceContextSerialisationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theSampleQualityAndDiscardedCountAreWrittenOnAnOkResult() throws Exception {
        var context = new MarketPriceContext(MarketPriceStatus.OK, 78_000, 82_900, 90_000, 7,
                "https://www.otomoto.pl/osobowe/toyota/corolla", Instant.now(),
                MarketPriceSampleQuality.DISPERSED, 3);

        String json = objectMapper.writeValueAsString(context);

        // Serialised by name, not ordinal: the frontend switches on the string, so renaming an enum
        // constant is a breaking change and is meant to look like one here.
        assertThat(json).contains("\"sampleQuality\":\"DISPERSED\"");
        assertThat(json).contains("\"discardedCount\":3");
    }

    // The mirror image, and the one that actually bites: on a non-OK status there is no sample to
    // judge, so both fields are null — and a null that serialises as an absent key is how the
    // deployed frontend ends up reading `undefined` where it expected a caveat.
    @Test
    void bothFieldsSerialiseAsExplicitNullsWhenThereIsNoSample() throws Exception {
        var context = new MarketPriceContext(MarketPriceStatus.FETCH_FAILED, null, null, null, null,
                "https://www.otomoto.pl/osobowe/toyota/corolla", Instant.now(), null, null);

        String json = objectMapper.writeValueAsString(context);

        assertThat(json)
                .as("an absent sampleQuality key is not the same contract as a null one")
                .contains("\"sampleQuality\":null");
        assertThat(json).contains("\"discardedCount\":null");
    }
}
