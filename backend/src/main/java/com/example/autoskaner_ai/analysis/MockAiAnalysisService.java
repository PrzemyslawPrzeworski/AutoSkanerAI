package com.example.autoskaner_ai.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Profile("mock")
public class MockAiAnalysisService implements AiAnalysisService {

    @Override
    public List<RiskFlag> analyzeRisks(String listingText) {
        List<RiskFlag> flags = new ArrayList<>();

        String lower = listingText.toLowerCase();

        if (!lower.contains("wypadek") && !lower.contains("bezwypadkowy") && !lower.contains("historia")) {
            flags.add(new RiskFlag(
                    "NO_ACCIDENT_DECLARATION",
                    "HIGH",
                    "Brak deklaracji dotyczącej historii wypadków. Dane nieznane — nie można potwierdzić stanu pojazdu."
            ));
        }

        if (!lower.contains("vin") && !lower.contains("nr identyfikacyjny")) {
            flags.add(new RiskFlag(
                    "NO_VIN",
                    "HIGH",
                    "Brak numeru VIN w ogłoszeniu. Uniemożliwia weryfikację historii pojazdu."
            ));
        }

        if (!lower.contains("serwis") && !lower.contains("przegląd") && !lower.contains("olej")) {
            flags.add(new RiskFlag(
                    "NO_SERVICE_HISTORY",
                    "MEDIUM",
                    "Brak informacji o historii serwisowej pojazdu."
            ));
        }

        if (lower.contains("pilnie") || lower.contains("okazja") || lower.contains("wyprzedaż")) {
            flags.add(new RiskFlag(
                    "URGENCY_PRESSURE",
                    "MEDIUM",
                    "Ogłoszenie zawiera język presji sprzedaży ('pilnie', 'okazja'). Może sugerować ukryte wady."
            ));
        }

        return flags;
    }
}
