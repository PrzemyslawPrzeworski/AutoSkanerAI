package com.example.autoskaner_ai.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Folds registry findings into the risk score and verdict.
 *
 * <p>This exists because enrichment runs <em>after</em> the LLM has already scored the listing, so
 * the model never sees the CEPiK payload. Without this step a vehicle with a registered szkoda
 * istotna came back {@code risk: 88, verdict: WORTH_CHECKING} while the panel above it showed the
 * damage — the data was on screen but absent from the judgement.
 *
 * <p>The adjustment is deliberately deterministic rather than a second LLM call. A registered
 * structural damage cannot be allowed to score 88 because a model weighed it mildly; for facts
 * this load-bearing a rule is the right mechanism, and it costs no latency or tokens. The caps
 * below are ceilings, never raises: a listing the model already distrusted keeps its lower score.
 */
@Component
public class CepikRiskAdjuster {

    private static final Logger log = LoggerFactory.getLogger(CepikRiskAdjuster.class);

    // Ceilings, not settings. Each is the highest risk score a listing may still hold once the
    // registry has reported the corresponding fact.
    private static final int CAP_VEHICLE_LOST = 5;
    private static final int CAP_ODOMETER_ROLLBACK = 20;
    private static final int CAP_CONTRADICTED_CLAIM = 25;
    private static final int CAP_SIGNIFICANT_DAMAGE = 35;
    private static final int CAP_NO_OC_POLICY = 70;

    /**
     * Phrases sellers use to claim the car has never been damaged. Matched against the LLM's
     * {@code accidentClaim}, which is its summary of what the listing asserts.
     */
    private static final List<String> ACCIDENT_FREE_CLAIMS =
            List.of("bezwypadkow", "bezszkodow", "nie uczestniczy", "brak szkód", "brak szkod");

    /**
     * The phrases above whose meaning a preceding negation inverts. {@code "nie uczestniczy"} is
     * absent on purpose — its own "nie" is part of the claim, so stripping a leading negation from
     * it would delete the finding rather than correct it. {@code "brak szkód"} is absent because
     * negating a noun phrase that way ("nie brak szkód") is not Polish anyone writes.
     */
    private static final Set<String> NEGATABLE_CLAIMS = Set.of("bezwypadkow", "bezszkodow");

    /**
     * A negation attached to the phrase that follows it: a "nie" separated from it by nothing, or by
     * a copula or hedge that carries the negation forward ("nie jest", "nie do końca").
     *
     * <p>Anchored at the end on purpose, and matched only against the text <em>preceding</em> one
     * occurrence. A check that merely asked whether "nie" appeared anywhere in the claim would be a
     * bypass the seller can type — the advert is their text, so "nie mam nic do ukrycia, auto
     * bezwypadkowe" would clear the contradiction. Unaccented spellings are accepted alongside the
     * accented ones because the claim reaches us as free text.
     */
    private static final Pattern NEGATION_ATTACHED_TO_PHRASE = Pattern.compile(
            "\\bnie(?:\\s+(?:jest|s[ąa]|by[łl][aoy]?|do\\s+ko[ńn]ca|ca[łl]kiem|w\\s+pe[łl]ni))*\\s+$");

    /** {@code accidentClaim} is unbounded free text; a log line is not. */
    private static final int LOGGED_CLAIM_LIMIT = 160;

    public AnalysisResult apply(AnalysisResult result, CepikResult cepik) {
        // Only a FOUND result carries registry facts. For every other status the lists are null by
        // construction, and "we did not check" must never move the score in either direction.
        if (result == null || cepik == null || cepik.status() != CepikStatus.FOUND) {
            return result;
        }

        List<RiskFlag> added = new ArrayList<>();
        int riskCap = 100;
        VerdictCode verdictFloor = null;

        if (Boolean.TRUE.equals(cepik.vehicleLost())) {
            added.add(new RiskFlag("CEPIK_VEHICLE_LOST", RiskSeverity.HIGH,
                    "CEPiK oznacza pojazd jako utracony (kradzież). Nie kupuj bez wyjaśnienia."));
            riskCap = Math.min(riskCap, CAP_VEHICLE_LOST);
            verdictFloor = moreSevere(verdictFloor, VerdictCode.HIGH_RISK_SKIP);
        }

        if (Boolean.TRUE.equals(cepik.odometerRolledBack())) {
            added.add(new RiskFlag("CEPIK_ODOMETER_ROLLBACK", RiskSeverity.HIGH,
                    "Rejestr wykrył cofnięcie drogomierza — faktyczny przebieg jest nieznany."));
            riskCap = Math.min(riskCap, CAP_ODOMETER_ROLLBACK);
            verdictFloor = moreSevere(verdictFloor, VerdictCode.HIGH_RISK_SKIP);
        }

        List<DamageRecord> damages = cepik.damageRecords();
        if (damages != null && !damages.isEmpty()) {
            added.add(new RiskFlag("CEPIK_SIGNIFICANT_DAMAGE", RiskSeverity.HIGH,
                    describeDamage(damages)));
            riskCap = Math.min(riskCap, CAP_SIGNIFICANT_DAMAGE);
            // NEEDS_MORE_INFO, not HIGH_RISK_SKIP: a properly repaired damage with a positive
            // post-repair inspection can still be a reasonable purchase at the right price. What
            // it can never be is an unqualified "warto sprawdzić".
            verdictFloor = moreSevere(verdictFloor, VerdictCode.NEEDS_MORE_INFO);

            // Being lied to is a separate fact from the damage, and a worse one — it says
            // something about every other claim in the listing.
            if (claimsAccidentFree(result.extracted())) {
                added.add(new RiskFlag("CEPIK_CONTRADICTS_LISTING", RiskSeverity.HIGH,
                        "Ogłoszenie podaje, że pojazd jest bezwypadkowy, a rejestr zawiera "
                                + "zgłoszoną szkodę istotną. Sprzedający podaje nieprawdę."));
                riskCap = Math.min(riskCap, CAP_CONTRADICTED_CLAIM);
                verdictFloor = moreSevere(verdictFloor, VerdictCode.HIGH_RISK_SKIP);
            }
        }

        if (Boolean.FALSE.equals(cepik.ocInsuranceValid())) {
            added.add(new RiskFlag("CEPIK_NO_OC_POLICY", RiskSeverity.MEDIUM,
                    "Rejestr nie wykazuje aktualnej polisy OC."));
            riskCap = Math.min(riskCap, CAP_NO_OC_POLICY);
        }

        if (added.isEmpty()) {
            return result;
        }

        // Registry findings go first: the frontend shows only the first four flags before
        // collapsing the rest, and these are the ones that must survive that cut.
        List<RiskFlag> flags = new ArrayList<>(added);
        flags.addAll(result.riskFlags());

        CategoryScores scores = capRisk(result.scores(), riskCap);
        Verdict verdict = applyFloor(result.verdict(), verdictFloor);

        return new AnalysisResult(result.extracted(), result.equipment(), flags,
                result.sellerQuestions(), scores, verdict, result.meta());
    }

    private static String describeDamage(List<DamageRecord> damages) {
        StringBuilder text = new StringBuilder("Rejestr zawiera zgłoszoną szkodę istotną (")
                .append(damages.size()).append(damages.size() == 1 ? " zdarzenie)" : " zdarzenia)");
        for (DamageRecord damage : damages) {
            text.append(": ").append(damage.date() == null ? "data nieznana" : damage.date());
            if (damage.categories() != null && !damage.categories().isEmpty()) {
                text.append(" — ").append(String.join(", ", damage.categories()));
            }
            if (damage.insurer() != null && !damage.insurer().isBlank()) {
                text.append(" (").append(damage.insurer()).append(')');
            }
        }
        text.append(". Szkoda istotna oznacza uszkodzenie elementu wpływającego na bezpieczeństwo.");
        return text.toString();
    }

    /**
     * Whether the listing asserts the vehicle has never been damaged.
     *
     * <p>Substring matching alone read a denial as the claim: {@code "nie jest bezwypadkowy"} — a
     * seller disclosing the damage — contains {@code "bezwypadkow"}, so the honest listing earned
     * {@code CEPIK_CONTRADICTS_LISTING} and a forced walk-away while saying nothing at all earned
     * only {@code NEEDS_MORE_INFO}. So each occurrence is now checked against the text attached to
     * it, and one un-negated assertion anywhere is still an assertion.
     */
    private static boolean claimsAccidentFree(ExtractedData extracted) {
        if (extracted == null || extracted.accidentClaim() == null) {
            return false;
        }
        String claim = extracted.accidentClaim().toLowerCase(Locale.forLanguageTag("pl"));
        boolean denied = false;
        for (String phrase : ACCIDENT_FREE_CLAIMS) {
            for (int at = claim.indexOf(phrase); at >= 0;
                    at = claim.indexOf(phrase, at + phrase.length())) {
                if (isDenied(claim, phrase, at)) {
                    denied = true;
                } else {
                    return true;
                }
            }
        }
        if (denied) {
            // Logged, and logged at INFO, because this is the branch that used to accuse an honest
            // seller of lying and left no record of having done so. A judgement this consequential
            // being unobservable is what let the defect sit in a test-file comment instead of a
            // bug report.
            log.info("Accident-free wording present but denied, so no contradiction is raised: {}",
                    forLog(claim));
        }
        return false;
    }

    /** Whether the occurrence of {@code phrase} at {@code at} is negated by the text before it. */
    private static boolean isDenied(String claim, String phrase, int at) {
        return NEGATABLE_CLAIMS.contains(phrase)
                && NEGATION_ATTACHED_TO_PHRASE.matcher(claim.substring(0, at)).find();
    }

    /**
     * The advert is seller-supplied, so anything from it reaches the log as a payload rather than as
     * data. Control characters go first — a newline inside a log line is a forged log line — and the
     * length is bounded so one advert cannot flood the log it is evidence in.
     */
    private static String forLog(String claim) {
        String flattened = claim.replaceAll("\\p{Cntrl}", " ");
        return flattened.length() <= LOGGED_CLAIM_LIMIT
                ? flattened
                : flattened.substring(0, LOGGED_CLAIM_LIMIT) + "…";
    }

    /** Caps risk and recomputes overall the same way the scorers do — as the mean of the four. */
    private static CategoryScores capRisk(CategoryScores scores, int cap) {
        if (scores == null || scores.risk() <= cap) {
            return scores;
        }
        int risk = cap;
        int overall = (risk + scores.completeness() + scores.equipment() + scores.value()) / 4;
        // Never raise overall: if the model already scored it lower, that judgement stands.
        return new CategoryScores(scores.completeness(), scores.equipment(), risk, scores.value(),
                Math.min(scores.overall(), overall));
    }

    private static Verdict applyFloor(Verdict verdict, VerdictCode floor) {
        if (floor == null) {
            return verdict;
        }
        VerdictCode code = verdict == null ? floor : moreSevere(verdict.code(), floor);
        if (verdict != null && code == verdict.code()) {
            return verdict;
        }
        return new Verdict(code, labelFor(code));
    }

    /** Explicit ranks rather than {@code ordinal()}, so reordering the enum cannot invert this. */
    private static int rank(VerdictCode code) {
        return switch (code) {
            case WORTH_CHECKING -> 0;
            case NEEDS_MORE_INFO -> 1;
            case HIGH_RISK_SKIP -> 2;
        };
    }

    private static VerdictCode moreSevere(VerdictCode a, VerdictCode b) {
        if (a == null) return b;
        if (b == null) return a;
        return rank(a) >= rank(b) ? a : b;
    }

    // Kept in step with MockAiAnalysisService and AnalysisPrompt's examples.
    private static String labelFor(VerdictCode code) {
        return switch (code) {
            case WORTH_CHECKING -> "warto sprawdzić";
            case NEEDS_MORE_INFO -> "sprawdź po doprecyzowaniu";
            case HIGH_RISK_SKIP -> "wysokie ryzyko — pomiń";
        };
    }
}
