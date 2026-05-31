package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Profile("mock")
public class MockAiAnalysisService implements AiAnalysisService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(199\\d|20[012]\\d)\\b");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d[\\d\\s]{2,})\\s*(zł|pln|eur|€|usd)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MILEAGE_PATTERN = Pattern.compile("(\\d[\\d\\s]*)\\s*(km|tys\\.?\\s*km)", Pattern.CASE_INSENSITIVE);

    private static final List<String> CANNED_QUESTIONS = List.of(
            "Czy pojazd był uczestnikiem wypadku lub kolizji?",
            "Czy dostępna jest pełna historia serwisowa?",
            "Czy numer VIN można zweryfikować w bazie CEPiK?",
            "Jaki jest powód sprzedaży pojazdu?",
            "Czy cena jest negocjowalna?"
    );

    private static final List<String> CANNED_EQUIPMENT = List.of(
            "klimatyzacja", "tempomat", "ABS", "poduszki powietrzne", "centralny zamek"
    );

    @Override
    public AnalysisResult analyze(String listingText) {
        long t0 = System.currentTimeMillis();
        String lower = listingText.toLowerCase();

        ExtractedData extracted = extractData(lower, listingText);
        List<RiskFlag> riskFlags = buildRiskFlags(lower);
        List<EquipmentItem> equipment = buildEquipment(lower);

        int highCount = (int) riskFlags.stream().filter(f -> f.severity() == RiskSeverity.HIGH).count();
        int medCount = (int) riskFlags.stream().filter(f -> f.severity() == RiskSeverity.MEDIUM).count();

        int riskScore = Math.max(0, 100 - highCount * 25 - medCount * 10);
        int completenessScore = computeCompletenessScore(extracted);
        int equipmentScore = equipment.isEmpty() ? 50 :
                (int) (equipment.stream().filter(e -> e.status() == EquipmentStatus.CONFIRMED).count() * 100 / equipment.size());
        int valueScore = 60;
        int overall = (riskScore + completenessScore + equipmentScore + valueScore) / 4;

        VerdictCode verdictCode = overall >= 70 ? VerdictCode.WORTH_CHECKING
                : overall >= 40 ? VerdictCode.NEEDS_MORE_INFO
                : VerdictCode.HIGH_RISK_SKIP;
        String verdictLabel = switch (verdictCode) {
            case WORTH_CHECKING -> "warto sprawdzić";
            case NEEDS_MORE_INFO -> "sprawdź po doprecyzowaniu";
            case HIGH_RISK_SKIP -> "wysokie ryzyko — pomiń";
        };

        long latencyMs = System.currentTimeMillis() - t0;
        AnalysisMeta meta = new AnalysisMeta("mock", "mock-v1", latencyMs, Instant.now());

        return new AnalysisResult(
                extracted,
                equipment,
                riskFlags,
                CANNED_QUESTIONS,
                new CategoryScores(completenessScore, equipmentScore, riskScore, valueScore, overall),
                new Verdict(verdictCode, verdictLabel),
                meta
        );
    }

    private ExtractedData extractData(String lower, String original) {
        Integer year = null;
        Matcher yearMatcher = YEAR_PATTERN.matcher(original);
        if (yearMatcher.find()) {
            year = Integer.parseInt(yearMatcher.group(1));
        }

        BigDecimal price = null;
        String currency = null;
        Matcher priceMatcher = PRICE_PATTERN.matcher(lower);
        if (priceMatcher.find()) {
            String digits = priceMatcher.group(1).replaceAll("\\s", "");
            try {
                price = new BigDecimal(digits);
                currency = priceMatcher.group(2).toLowerCase().contains("eur") || priceMatcher.group(2).contains("€") ? "EUR" : "PLN";
            } catch (NumberFormatException ignored) {
            }
        }

        Integer mileage = null;
        Matcher mileageMatcher = MILEAGE_PATTERN.matcher(lower);
        if (mileageMatcher.find()) {
            String digits = mileageMatcher.group(1).replaceAll("\\s", "");
            try {
                int raw = Integer.parseInt(digits);
                mileage = mileageMatcher.group(2).toLowerCase().startsWith("tys") ? raw * 1000 : raw;
            } catch (NumberFormatException ignored) {
            }
        }

        String accidentClaim = null;
        if (lower.contains("bezwypadkowy")) {
            accidentClaim = "bezwypadkowy";
        } else if (lower.contains("wypadek") || lower.contains("kolizja")) {
            accidentClaim = "historia wypadków wspomniana";
        }

        Boolean vinPresent = lower.contains("vin") || lower.contains("nr identyfikacyjny") ? Boolean.TRUE : null;
        Boolean serviceHistory = lower.contains("serwis") || lower.contains("przegląd") || lower.contains("olej") ? Boolean.TRUE : null;

        return new ExtractedData(
                null, null, year, price, currency, mileage,
                null, null, null, null,
                serviceHistory, accidentClaim, vinPresent
        );
    }

    private List<RiskFlag> buildRiskFlags(String lower) {
        List<RiskFlag> flags = new ArrayList<>();

        if (!lower.contains("wypadek") && !lower.contains("bezwypadkowy") && !lower.contains("historia")) {
            flags.add(new RiskFlag(
                    "NO_ACCIDENT_DECLARATION",
                    RiskSeverity.HIGH,
                    "Brak deklaracji dotyczącej historii wypadków. Dane nieznane — nie można potwierdzić stanu pojazdu."
            ));
        }

        if (!lower.contains("vin") && !lower.contains("nr identyfikacyjny")) {
            flags.add(new RiskFlag(
                    "NO_VIN",
                    RiskSeverity.HIGH,
                    "Brak numeru VIN w ogłoszeniu. Uniemożliwia weryfikację historii pojazdu."
            ));
        }

        if (!lower.contains("serwis") && !lower.contains("przegląd") && !lower.contains("olej")) {
            flags.add(new RiskFlag(
                    "NO_SERVICE_HISTORY",
                    RiskSeverity.MEDIUM,
                    "Brak informacji o historii serwisowej pojazdu."
            ));
        }

        if (lower.contains("pilnie") || lower.contains("okazja") || lower.contains("wyprzedaż")) {
            flags.add(new RiskFlag(
                    "URGENCY_PRESSURE",
                    RiskSeverity.MEDIUM,
                    "Ogłoszenie zawiera język presji sprzedaży ('pilnie', 'okazja'). Może sugerować ukryte wady."
            ));
        }

        return flags;
    }

    private List<EquipmentItem> buildEquipment(String lower) {
        List<EquipmentItem> items = new ArrayList<>();
        for (String item : CANNED_EQUIPMENT) {
            EquipmentStatus status = lower.contains(item) ? EquipmentStatus.CONFIRMED : EquipmentStatus.UNCLEAR;
            String note = status == EquipmentStatus.UNCLEAR ? "Nie wspomniano w ogłoszeniu" : null;
            items.add(new EquipmentItem(item, status, note));
        }
        return items;
    }

    private int computeCompletenessScore(ExtractedData d) {
        int total = 6;
        int present = 0;
        if (d.year() != null) present++;
        if (d.priceAmount() != null) present++;
        if (d.mileageKm() != null) present++;
        if (d.vinPresent() != null) present++;
        if (d.accidentClaim() != null) present++;
        if (d.serviceHistoryMentioned() != null) present++;
        return present * 100 / total;
    }
}
