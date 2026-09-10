package com.example.autoskaner_ai.analysis;

import com.example.autoskaner_ai.common.GlobalExceptionHandler;
import com.example.autoskaner_ai.market.MarketPriceEnrichmentService;
import com.example.autoskaner_ai.market.MarketPriceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A risk flag must not outlive the input it describes.
 *
 * <h2>The observation</h2>
 *
 * <p>A request carrying a well-formed VIN + plate + date came back with {@code cepikResult.status:
 * FOUND} — the registry lookup keyed on the very VIN that was supplied — <em>and</em> with
 * {@code NO_VIN} at {@code HIGH} reading "Brak numeru VIN — nie można zweryfikować pojazdu". One
 * response, two contradictory claims: {@code extracted.vinPresent} was {@code true} in its own table
 * and the flag beside it said the VIN was missing.
 *
 * <h2>Why it happened, and why it was not a mock artefact</h2>
 *
 * <p>{@code NO_VIN} is derived from the advert, which never contained the VIN — Otomoto hides it from
 * logged-out fetches, which is the reason the manual field exists at all. Under {@code mock} the flag
 * comes from {@code MockAiAnalysisService}'s keyword scan; on both network paths it comes from the
 * model, which also only ever saw the advert ({@code AnalysisPrompt} asks for the code by name). The
 * user's values arrive later, in {@code AnalysisController.buildResponse}, and the analysis was
 * already in hand by then.
 *
 * <p>The sharp part is that the same method already got it right for the seller questions: they are
 * re-derived from the post-override {@code ExtractedData}, so "Proszę podać numer VIN pojazdu" was
 * correctly suppressed while the flag survived. The fix shares one predicate between the two, so the
 * question and the flag cannot disagree again.
 *
 * <h2>Validity, not presence — case 2 is the load-bearing one</h2>
 *
 * <p>{@code UserOverrides} sets {@code vinPresent} to {@code TRUE} for any non-blank typed value. A
 * user who types {@code ABC} therefore gets {@code vinPresent: true} and a registry lookup that
 * cannot run, and for that request {@code NO_VIN}'s text is <em>true</em>. So the rule is keyed on
 * {@code VinValidator}, not on non-blankness, and {@link #aMalformedTypedVinKeepsTheFlag()} is what
 * stops a future simplification from collapsing the two.
 *
 * <h2>What is real here and what is stubbed</h2>
 *
 * <p>The subject is {@code buildResponse}, so the LLM is a stub returning a fixed three-flag result
 * and both enrichments are stubs. {@code AnalysisController}, {@code UserOverrides},
 * {@code VinValidator} and the real {@code CepikRiskAdjuster} are production classes — the adjuster
 * for real because it runs between the overrides and this rule, and a reconciliation placed after it
 * has to survive whatever it adds.
 */
class SuppliedVinClearsTheNoVinFlagTest {

    /** The committed synthetic VIN. This repository is public; never a real vehicle's. */
    private static final String VALID_VIN = "NMTBZ3BE40R000000";

    private static final RiskFlag NO_VIN = new RiskFlag("NO_VIN", RiskSeverity.HIGH,
            "Brak numeru VIN w ogłoszeniu. Uniemożliwia weryfikację historii pojazdu.");
    private static final RiskFlag URGENCY = new RiskFlag("URGENCY_PRESSURE", RiskSeverity.LOW,
            "Ogłoszenie wywiera presję czasową.");
    private static final RiskFlag NO_ACCIDENT_DECLARATION =
            new RiskFlag("NO_ACCIDENT_DECLARATION", RiskSeverity.MEDIUM,
                    "Ogłoszenie nie zawiera informacji o historii wypadkowej. Status nieznany.");

    private MockMvc mockMvc;

    // ---------------------------------------------------------------------------------------
    // The rule, in both directions
    // ---------------------------------------------------------------------------------------

    /**
     * The reported case. The advert had no VIN, the user typed one, the registry answered — so the
     * flag is gone and the question is not asked.
     *
     * <p>The two other flags are asserted by position, not merely by presence: a filter that
     * reordered or dropped its way to a passing {@code NO_VIN} assertion would fail here.
     */
    @Test
    void aWellFormedTypedVinClearsTheFlagAndTheQuestion() throws Exception {
        perform(VALID_VIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.riskFlags.length()").value(2))
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("URGENCY_PRESSURE"))
                .andExpect(jsonPath("$.analysis.riskFlags[1].code").value("NO_ACCIDENT_DECLARATION"))
                // The one claim the code, not the model, has the last word on. Dropping a flag must
                // never disturb it: absence of accident data means unknown, not clean.
                .andExpect(jsonPath("$.analysis.riskFlags[1].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.analysis.extracted.vinPresent").value(true))
                .andExpect(jsonPath("$.analysis.extracted.vin").value(VALID_VIN))
                .andExpect(jsonPath("$.analysis.sellerQuestions",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                "Proszę podać numer VIN pojazdu"))));
    }

    /**
     * Typed, non-blank, and useless. {@code UserOverrides} still reports {@code vinPresent: true}, so
     * a rule keyed on presence would clear the flag here — and the flag is <em>correct</em> here,
     * because no registry can be asked about {@code ABC}. The question is still asked, for the same
     * reason.
     */
    @Test
    void aMalformedTypedVinKeepsTheFlag() throws Exception {
        perform("ABC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.riskFlags.length()").value(3))
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("NO_VIN"))
                .andExpect(jsonPath("$.analysis.sellerQuestions",
                        org.hamcrest.Matchers.hasItem("Proszę podać numer VIN pojazdu")));
    }

    /** Nothing typed and nothing in the advert: the flag is the truth, and it stays. */
    @Test
    void noVinAnywhereKeepsTheFlag() throws Exception {
        perform(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.riskFlags.length()").value(3))
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("NO_VIN"))
                .andExpect(jsonPath("$.analysis.sellerQuestions",
                        org.hamcrest.Matchers.hasItem("Proszę podać numer VIN pojazdu")));
    }

    /**
     * No override at all — the VIN was in the extraction. The rule is about the state the response
     * ships, not about who supplied it, so a model that reports a VIN <em>and</em> {@code NO_VIN} in
     * the same payload is reconciled too. This is the case that makes the rule a property of the
     * response rather than a patch on the override path.
     */
    @Test
    void aVinTheListingItselfSuppliedAlsoClearsTheFlag() throws Exception {
        buildWith(analysisCarrying(VALID_VIN));

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"listingText\":\"Toyota Corolla 2022, VIN w ogłoszeniu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.riskFlags.length()").value(2))
                .andExpect(jsonPath("$.analysis.riskFlags[0].code").value("URGENCY_PRESSURE"))
                .andExpect(jsonPath("$.analysis.sellerQuestions",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                                "Proszę podać numer VIN pojazdu"))));
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        // The advert case: the model read a listing with no VIN in it, which is every Otomoto fetch.
        buildWith(analysisCarrying(null));
    }

    /** A request whose only registry input is the VIN under test. */
    private ResultActions perform(String typedVin) throws Exception {
        String vinField = typedVin == null ? "" : ",\"vin\":\"" + typedVin + "\"";
        return mockMvc.perform(post("/api/analyses")
                .contentType("application/json")
                .content("{\"listingText\":\"Toyota Corolla 2022, przebieg 26 320 km\""
                        + vinField + "}"));
    }

    private void buildWith(AnalysisResult result) {
        var ai = mock(AiAnalysisService.class);
        when(ai.analyze(any())).thenReturn(result);

        // MISSING_INPUTS rather than FOUND: this test's subject is the flag list, and a FOUND result
        // would let CepikRiskAdjuster add flags of its own to the very list being counted.
        var cepik = mock(com.example.autoskaner_ai.cepik.CepikEnrichmentService.class);
        when(cepik.enrich(any()))
                .thenReturn(CepikResult.withoutData(CepikStatus.MISSING_INPUTS, null, null));

        var market = mock(MarketPriceEnrichmentService.class);
        when(market.enrich(any())).thenReturn(new MarketPriceContext(MarketPriceStatus.FETCH_FAILED,
                null, null, null, null, null, Instant.now(), null, null));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalysisController(ai, mock(ListingFetchService.class),
                        cepik, market, new CepikRiskAdjuster()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * A three-flag analysis with {@code NO_VIN} first and {@code NO_ACCIDENT_DECLARATION} last —
     * the order the real parser produces, since it appends the accident flag after the model's own.
     */
    private static AnalysisResult analysisCarrying(String extractedVin) {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022, BigDecimal.valueOf(82_900),
                "PLN", 26_320, "hybryda", "automatyczna", "Polska", "prywatny", Boolean.TRUE,
                null, extractedVin != null, extractedVin, null, null);
        return new AnalysisResult(extracted, List.of(),
                List.of(NO_VIN, URGENCY, NO_ACCIDENT_DECLARATION),
                List.of("Proszę pokazać książkę serwisową"),
                new CategoryScores(40, 55, 30, 60, 46),
                new Verdict(VerdictCode.NEEDS_MORE_INFO, "Wymaga weryfikacji"),
                new AnalysisMeta("mock", "mock", 12L, Instant.now()));
    }
}
