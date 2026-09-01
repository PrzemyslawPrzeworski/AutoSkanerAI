package com.example.autoskaner_ai.cepik;

import com.example.autoskaner_ai.analysis.AnalysisMeta;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.CategoryScores;
import com.example.autoskaner_ai.analysis.CepikRiskAdjuster;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.RiskFlag;
import com.example.autoskaner_ai.analysis.Verdict;
import com.example.autoskaner_ai.analysis.VerdictCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The theft marker and the odometer-rollback flag are the two registry facts the committed capture
 * cannot supply — both are {@code false} in it. Everywhere else they exist only as a hand-set
 * {@code Boolean.TRUE} handed straight to {@link CepikRiskAdjuster}, which proves the adjuster
 * reacts to a boolean but never that the parser can produce that boolean from a registry payload.
 *
 * <p>So this drives them from the Phase 1 derived fixtures instead: the captured vehicle-data with
 * exactly one value flipped, through the real parser, into the real adjuster. Both fixtures pair
 * with the clean timeline so the fact under test is the only one firing, and the control below runs
 * the unmodified capture to show the flag comes from the edit rather than from the baseline.
 *
 * <p>See {@code src/test/resources/cepik/README.md} for the derived-fixture convention.
 */
class RegistryFactsReachTheScoreTest {

    private static final String VIN = "NMTBZ3BE40R000000";

    private final HistoriaPojazduParser parser = new HistoriaPojazduParser();
    private final CepikRiskAdjuster adjuster = new CepikRiskAdjuster();

    @Test
    void aRollbackFlippedInTheCaptureForcesSkip() throws IOException {
        var result = adjust("vehicle-data-rolled-back-derived.json");

        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .contains("CEPIK_ODOMETER_ROLLBACK");
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.scores().risk())
                .as("the registry's own rollback detection cannot leave risk where the model put it")
                .isLessThan(LLM_RISK);
    }

    @Test
    void aTheftMarkerFlippedInTheCaptureForcesSkip() throws IOException {
        var result = adjust("vehicle-data-lost-derived.json");

        assertThat(result.riskFlags()).extracting(RiskFlag::code).contains("CEPIK_VEHICLE_LOST");
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.scores().risk()).isLessThan(LLM_RISK);
    }

    // The control. Without it, both tests above would pass even if the flags fired unconditionally,
    // and the derived edit would be doing no work.
    @Test
    void theUnmodifiedCaptureRaisesNeitherFlag() throws IOException {
        var result = adjust("vehicle-data-found.json");

        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .doesNotContain("CEPIK_ODOMETER_ROLLBACK", "CEPIK_VEHICLE_LOST");
        assertThat(result.scores().risk()).isEqualTo(LLM_RISK);
    }

    /** Parses the given vehicle-data against the clean timeline and folds the result into a score. */
    private AnalysisResult adjust(String vehicleDataFixture) throws IOException {
        var cepik = parser.parse(fixture(vehicleDataFixture),
                fixture("timeline-data-clean-derived.json"), VIN);
        return adjuster.apply(llmResult(), cepik);
    }

    private static final int LLM_RISK = 88;

    /** No accident claim, so the contradiction rule stays out of the way. */
    private static AnalysisResult llmResult() {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022, null, null, 26_320,
                "hybryda", null, null, null, Boolean.TRUE, null, Boolean.TRUE,
                VIN, "WX00000", "2022-04-12");
        return new AnalysisResult(extracted, List.of(), List.of(), List.of(),
                new CategoryScores(90, 75, LLM_RISK, 60, 78),
                new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić"),
                new AnalysisMeta("openrouter", "some-model", 16_000L, Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) throws IOException {
        try (InputStream in = RegistryFactsReachTheScoreTest.class
                .getResourceAsStream("/cepik/" + name)) {
            assertThat(in).as("missing fixture %s", name).isNotNull();
            return new ObjectMapper().readValue(in, Map.class);
        }
    }
}
