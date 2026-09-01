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
        return analysis(risk, overallFor(risk), verdict, accidentClaim);
    }

    /**
     * A model result whose {@code overall} is stated rather than derived. LLM output does not
     * guarantee its {@code overall} agrees with its own four categories, and that disagreement is
     * the only shape that reaches the never-raise guard.
     */
    private static AnalysisResult analysis(int risk, int overall, VerdictCode verdict,
                                           String accidentClaim) {
        var extracted = new ExtractedData("Toyota", "Corolla", 2022,
                BigDecimal.valueOf(82_900), "PLN", 26_320,
                "hybryda", null, null, null, Boolean.TRUE, accidentClaim, Boolean.TRUE,
                VIN, "WX00000", "2022-04-12");
        // completeness 90, equipment 75, value 60 — chosen so the recomputed overall is checkable.
        var scores = new CategoryScores(90, 75, risk, 60, overall);
        return new AnalysisResult(extracted, List.of(),
                List.of(new RiskFlag("MISSING_TRANSMISSION", RiskSeverity.LOW, "Nie podano skrzyni")),
                List.of("Istniejące pytanie"), scores,
                new Verdict(verdict, "warto sprawdzić"),
                new AnalysisMeta("openrouter", "some-model", 16_000L, Instant.now()));
    }

    /**
     * Hand-computed, deliberately not derived. The class under test owns the mean-of-four rule, so
     * a fixture that re-implements that formula cannot detect the rule changing — and while it did,
     * every input {@code overall} agreed with its categories by construction, which left the
     * never-raise guard in {@code capRisk} unreachable by all fourteen tests in this class.
     *
     * <p>The {@code default -> throw} is the point: it forces the next author to do the arithmetic
     * rather than reaching for the production formula.
     */
    private static int overallFor(int risk) {
        return switch (risk) {
            case 88 -> 78;   // (90 + 75 + 88 + 60) / 4
            case 60 -> 71;   // (90 + 75 + 60 + 60) / 4
            case 10 -> 58;   // (90 + 75 + 10 + 60) / 4
            default -> throw new IllegalArgumentException(
                    "compute the overall for risk " + risk + " by hand — do not copy the formula");
        };
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

    private static CepikResult withTheftMarker() {
        return found(List.of(), Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
    }

    private static CepikResult withOdometerRollback() {
        return found(List.of(), Boolean.FALSE, Boolean.TRUE, Boolean.TRUE);
    }

    private static CepikResult withoutOcPolicy() {
        return found(List.of(), Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }

    /** The risk the adjuster leaves on a listing the model scored 88. */
    private int riskAfter(CepikResult cepik, String accidentClaim) {
        return adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, accidentClaim), cepik)
                .scores().risk();
    }

    @Test
    void theCorollaThatScored88() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy"), withDamage());

        // Relative to what production returned, which is this test's only oracle. The ceiling's
        // magnitude is pinned once, in theCapMagnitudesAreChangeDetectionOnly below.
        assertThat(result.scores().risk())
                .as("a registered structural damage cannot leave risk at 88")
                .isLessThan(88);
        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .containsExactly("CEPIK_SIGNIFICANT_DAMAGE", "CEPIK_CONTRADICTS_LISTING",
                        "MISSING_TRANSMISSION");
    }

    // A properly repaired damage with a positive post-repair inspection can still be a fair
    // purchase at the right price, so the damage alone must ask for more information rather than
    // force a walk-away. It is the listing's false "bezwypadkowy" that makes it a walk-away. This
    // asymmetry follows from the product guardrail, not from reading the adjuster.
    @Test
    void damageAloneNeedsMoreInfoRatherThanSkip() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withDamage());

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
    void theftMarkerForcesSkip() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withTheftMarker());

        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.riskFlags()).extracting(RiskFlag::code).contains("CEPIK_VEHICLE_LOST");
    }

    @Test
    void odometerRollbackForcesSkip() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null),
                withOdometerRollback());

        assertThat(result.verdict().code()).isEqualTo(VerdictCode.HIGH_RISK_SKIP);
        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .contains("CEPIK_ODOMETER_ROLLBACK");
    }

    // The other documented asymmetry: an expired OC policy is the seller's paperwork problem, not
    // evidence about the car, so it lowers the ceiling and moves no verdict at all.
    @Test
    void missingOcPolicyIsMediumAndDoesNotChangeTheVerdict() {
        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, null), withoutOcPolicy());

        assertThat(result.verdict().code()).isEqualTo(VerdictCode.WORTH_CHECKING);
        assertThat(result.riskFlags()).extracting(RiskFlag::severity)
                .contains(RiskSeverity.MEDIUM);
    }

    // The guard at capRisk's `Math.min(scores.overall(), overall)`, which nothing reached before.
    // It fires when the model's own overall disagrees with its four categories — routine in LLM
    // output — and without it, capping risk downward can push overall *up*: the recomputed mean of
    // 65 below is 25 points better than the 40 the model actually returned. A car cannot come out
    // of a registry check looking better than the model judged it.
    @Test
    void aRecomputedOverallNeverRaisesTheModelsOwnJudgement() {
        var pessimisticOverall = analysis(88, 40, VerdictCode.WORTH_CHECKING, null);

        var result = adjuster.apply(pessimisticOverall, withDamage());

        // Hand arithmetic: risk is capped to the damage ceiling, so the mean of the four
        // categories is (90 + 75 + 35 + 60) / 4 = 65 — higher than the model's stated 40.
        assertThat(result.scores().overall())
                .as("the model's lower judgement stands; a cap may not improve the headline score")
                .isEqualTo(40);
    }

    // The magnitudes have no oracle outside the implementation, but the severity *ordering* they
    // encode does — it follows from the product's stated reasoning about each finding. So this
    // asserts the ordering and names no magnitude at all: shift all five caps by a constant and
    // this test still passes, which is deliberate and is what separates it from the pinned copy
    // below.
    @Test
    void theCapsEncodeTheDocumentedSeverityOrdering() {
        record Scenario(String name, String accidentClaim, CepikResult cepik) {}

        // Least severe last. Each is one registry finding, except the contradiction, which is a
        // damage the listing denies — ranked worse than the damage alone because being lied to
        // says something about every other claim in the advert.
        var scenarios = List.of(
                new Scenario("theft marker", null, withTheftMarker()),
                new Scenario("odometer rollback", null, withOdometerRollback()),
                new Scenario("damage contradicting a bezwypadkowy claim", "bezwypadkowy", withDamage()),
                new Scenario("registered szkoda istotna", null, withDamage()),
                new Scenario("no valid OC policy", null, withoutOcPolicy()));

        var risks = scenarios.stream()
                .map(s -> riskAfter(s.cepik(), s.accidentClaim()))
                .toList();

        for (int i = 1; i < risks.size(); i++) {
            assertThat(risks.get(i))
                    .as("%s must leave a higher risk ceiling than %s",
                            scenarios.get(i).name(), scenarios.get(i - 1).name())
                    .isGreaterThan(risks.get(i - 1));
        }
    }

    /**
     * Change detection only, and the single place these five integers appear in the test tree.
     *
     * <p><b>This test cannot tell a cap that is wrong by design from one that is right.</b> Nothing
     * in the product statement says a theft marker means 5 rather than 3, or that a szkoda istotna
     * means 35 rather than 30 — the numbers are a product judgement whose only written record is
     * {@code CepikRiskAdjuster} itself, so asserting them here just restates the implementation. If
     * it fails, the only question it answers is "was that deliberate?".
     *
     * <p>The property that <em>is</em> falsifiable lives in
     * {@link #theCapsEncodeTheDocumentedSeverityOrdering}. Read that one for the real guarantee.
     */
    @Test
    void theCapMagnitudesAreChangeDetectionOnly() {
        assertThat(riskAfter(withTheftMarker(), null)).isEqualTo(5);
        assertThat(riskAfter(withOdometerRollback(), null)).isEqualTo(20);
        assertThat(riskAfter(withDamage(), "bezwypadkowy")).isEqualTo(25);
        assertThat(riskAfter(withDamage(), null)).isEqualTo(35);
        assertThat(riskAfter(withoutOcPolicy(), null)).isEqualTo(70);
    }

    // Every other multi-fact test sets one flag, so the harsher-fact-wins rule was unverified for
    // findings that do not already co-occur. Asserted relative to the single-fact run rather than
    // against a magnitude, so it survives a cap being retuned.
    @Test
    void aLesserFindingNeverSoftensTheHarshestOne() {
        var theftAndDamage = found(List.of(SZKODA), Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
        assertThat(riskAfter(theftAndDamage, null))
                .as("a stolen car with a registered damage is at least as bad as a stolen car")
                .isEqualTo(riskAfter(withTheftMarker(), null));

        var rollbackAndNoOc = found(List.of(), Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);
        assertThat(riskAfter(rollbackAndNoOc, null))
                .isEqualTo(riskAfter(withOdometerRollback(), null));
    }

    // The ceiling comes from one finding, but the user must still see all of them. Note the
    // frontend collapses everything past the fourth flag, so the ordering here is load-bearing:
    // the one that gets cut is the least severe.
    @Test
    void everyFindingIsReportedEvenWhenOnlyOneSetsTheCeiling() {
        var everything = found(List.of(SZKODA), Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);

        var result = adjuster.apply(analysis(88, VerdictCode.WORTH_CHECKING, "bezwypadkowy"),
                everything);

        assertThat(result.riskFlags()).extracting(RiskFlag::code)
                .containsExactly("CEPIK_VEHICLE_LOST", "CEPIK_ODOMETER_ROLLBACK",
                        "CEPIK_SIGNIFICANT_DAMAGE", "CEPIK_CONTRADICTS_LISTING",
                        "CEPIK_NO_OC_POLICY", "MISSING_TRANSMISSION");
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
