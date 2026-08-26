package com.example.autoskaner_ai.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers in {@link #theCorollaThatScored88} are the ones production actually returned on
 * 2026-08-26 for a vehicle carrying a registered szkoda istotna: risk 88, verdict WORTH_CHECKING.
 * That response is the reason this class exists, so it is the first test.
 */
class CepikRiskAdjusterTest {

    private final CepikRiskAdjuster adjuster = new CepikRiskAdjuster();

    private static final String VIN = "NMTBZ3BE40R000000";

    private static AnalysisResult analysis(int risk, VerdictCode verdict, String accidentClaim) {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022,
                BigDecimal.valueOf(82_900), "PLN", 26_320,
                "hybryda", null, null, null, Boolean.TRUE, accidentClaim, Boolean.TRUE,
                VIN, "WX00000", "2022-04-12");
        // completeness 90, equipment 75, value 60 — chosen so the recomputed overall is checkable.
        var scores = new CategoryScores(90, 75, risk, 60, (90 + 75 + risk + 60) / 4);
        return new AnalysisResult(extracted, List.of(),
                List.of(new RiskFlag("MISSING_TRANSMISSION", RiskSeverity.LOW, "Nie podano skrzyni")),
                List.of("Istniejące pytanie"), scores,
                new Verdict(verdict, "warto sprawdzić"),
                new AnalysisMeta("openrouter", "some-model", 16_000L, Instant.now()));
    }

    private static CepikResult found(List<DamageRecord> damages, Boolean lost, Boolean rolledBack,
                                     Boolean ocValid) {
        return new CepikResult(CepikStatus.FOUND, VIN, "2022-04-12", null, null, 2,
                List.of(new MileageStamp("2025-04-14", 26_320)), damages,
                "https://historiapojazdu.gov.pl", Instant.now(),
                "TOYOTA", "TOYOTA COROLLA", "SAMOCHÓD OSOBOWY", 2022,
                "Zarejestrowany", "aktualne", ocValid, lost, rolledBack, "mazowieckie",
                List.of());
    }

    private static CepikResult clean() {
        return found(List.of(), Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
    }

    private static final DamageRecord SZKODA = new DamageRecord("2023-02-07",
            "Powstanie szkody istotnej", "PZU", List.of("Uszkodzenie elementów układu nośnego"));

    private static CepikResult withDamage() {
        return found(List.of(SZKODA), Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
    }

    @Test
    void theCorollaThatScored88() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy"), withDamage());

        assertThat(result.scores().risk())
                .as("a registered structural damage cannot leave risk at 88")
                .isEqualTo(25);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .containsExactly("CEPIK_SIGNIFICANT_DAMAGE", "CEPIK_CONTRADICTS_LISTING",
                        "MISSING_TRANSMISSION");
    }

    // The damage alone caps at 35 and asks for more information; it is the listing's false
    // "bezwypadkowy" that turns it into a walk-away.
    @Test
    void damageAloneCapsAt35AndNeedsMoreInfo() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withDamage());

        assertThat(result.scores().risk()).isEqualTo(35);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.NEEDS_MORE_INFO);
        assertThat(result.verdict().label()).isEqualTo("sprawdź po doprecyzowaniu");
        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .doesNotContain("CEPIK_CONTRADICTS_LISTING");
    }

    @Test
    void damageFlagCarriesDateInsurerAndCategories() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withDamage());

        var flag = result.riskFlags().getFirst();
        assertThat(flag.code()).isEqualTo("CEPIK_SIGNIFICANT_DAMAGE");
        assertThat(flag.severity()).isEqualTo(RiskSeverity.HIGH);
        assertThat(flag.description())
                .contains("2023-02-07")
                .contains("Uszkodzenie elementów układu nośnego")
                .contains("PZU");
    }

    @Test
    void overallIsRecomputedFromTheCappedRisk() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withDamage());

        // (90 + 75 + 35 + 60) / 4
        assertThat(result.scores().overall()).isEqualTo(65);
        assertThat(result.scores().completeness()).isEqualTo(90);
        assertThat(result.scores().equipment()).isEqualTo(75);
        assertThat(result.scores().value()).isEqualTo(60);
    }

    @Test
    void theftMarkerIsTheHarshestCap() {
        var cepik = found(List.of(), Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);

        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), cepik);

        assertThat(result.scores().risk()).isEqualTo(5);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.riskFlags()).extracting(RiskFlag::code).contains("CEPIK_VEHICLE_LOST");
    }

    @Test
    void odometerRollbackCapsAt20AndSkips() {
        var cepik = found(List.of(), Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);

        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), cepik);

        assertThat(result.scores().risk()).isEqualTo(20);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
    }

    @Test
    void missingOcPolicyIsMediumAndDoesNotChangeTheVerdict() {
        var cepik = found(List.of(), Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);

        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), cepik);

        assertThat(result.scores().risk()).isEqualTo(70);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.WORTH_CHECKING);
        assertThat(result.riskFlags()).extracting(RiskFlag::severity)
                .contains(RiskSeverity.MEDIUM);
    }

    // The caps are ceilings. A listing the model already distrusted keeps its own lower score,
    // and nothing here may ever make a car look better than the model judged it.
    @Test
    void capsNeverRaiseAScoreOrSoftenAVerdict() {
        var result = adjuster.apply(analysis(10, VerdictCode.HIGH_RISK_SKIP, null), withDamage());

        assertThat(result.scores().risk()).isEqualTo(10);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
    }

    @Test
    void aCleanRegistryReportLeavesTheAnalysisUntouched() {
        var original = analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy");

        var result = adjuster.apply(original, clean());

        assertThat(result).isSameAs(original);
    }

    // "We did not check" must not move the score in either direction — the same rule that keeps
    // damageRecords null rather than empty for non-FOUND results.
    @Test
    void nonFoundStatusesAreIgnoredEntirely() {
        var original = analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy");

        for (CepikStatus status : List.of(CepikStatus.NOT_FOUND, CepikStatus.LOOKUP_FAILED,
                CepikStatus.MISSING_INPUTS)) {
            var withoutData = CepikResult.withoutData(status, VIN, "https://historiapojazdu.gov.pl");
            assertThat(adjuster.apply(original, withoutData)).as("status %s", status).isSameAs(original);
        }
    }

    @Test
    void nullCepikResultIsIgnored() {
        var original = analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy");

        assertThat(adjuster.apply(original, null)).isSameAs(original);
    }

    // A FOUND result whose timeline could not be read carries null lists, not empty ones. Treating
    // null as "no damage" is the inversion this whole change exists to prevent.
    @Test
    void nullDamageListIsNotTreatedAsNoDamage() {
        var cepik = found(null, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);

        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), cepik);

        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .doesNotContain("CEPIK_SIGNIFICANT_DAMAGE");
        assertThat(result.scores().risk())
                .as("an unread timeline is not evidence of damage either — no cap applies")
                .isEqualTo(88);
    }

    @Test
    void accidentFreeClaimIsMatchedAcrossWordingAndCase() {
        for (String claim : List.of("bezwypadkowy", "Bezwypadkowy wg sprzedającego", "BEZSZKODOWY",
                "auto bezwypadkowe", "brak szkód")) {
            var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, claim), withDamage());
            assertThat(result.riskFlags()).extracting(RiskFlag::code)
                    .as("claim %s", claim).contains("CEPIK_CONTRADICTS_LISTING");
        }
    }

    @Test
    void anHonestListingThatAdmitsTheDamageIsNotAccusedOfLying() {
        var result = adjuster.apply(
                analysis(60, VerdictCode.NEEDS_MORE_INFO, "szkoda naprawiona w ASO"), withDamage());

        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .doesNotContain("CEPIK_CONTRADICTS_LISTING");
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.NEEDS_MORE_INFO);
    }
}
