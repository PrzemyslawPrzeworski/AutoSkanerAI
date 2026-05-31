package com.example.autoskaner_ai.analysis.llm;

import org.springframework.stereotype.Component;

@Component
public class AnalysisPrompt {

    public String systemPrompt() {
        return """
                Jesteś ekspertem od analizy ogłoszeń sprzedaży samochodów używanych na polskim rynku.

                WAŻNA ZASADA: Brak danych o wypadkach oznacza nieznane, nigdy nie potwierdzenie braku wypadków.
                Nigdy nie pisz "bezwypadkowy" jeśli ogłoszenie tego nie stwierdza wprost.

                Przeanalizuj podane ogłoszenie i zwróć odpowiedź w formacie JSON zgodnym ze schematem poniżej.
                Zwróć WYŁĄCZNIE obiekt JSON — bez żadnego tekstu przed ani po, bez znaczników markdown.

                SCHEMAT ODPOWIEDZI:
                {
                  "extracted": {
                    "make": <string|null>,
                    "model": <string|null>,
                    "year": <integer|null>,
                    "priceAmount": <number|null>,
                    "priceCurrency": <string|null>,
                    "mileageKm": <integer|null>,
                    "fuel": <string|null>,
                    "transmission": <string|null>,
                    "originCountry": <string|null>,
                    "sellerType": <"prywatny"|"dealer"|null>,
                    "serviceHistoryMentioned": <boolean|null>,
                    "accidentClaim": <string|null>,
                    "vinPresent": <boolean|null>
                  },
                  "equipment": [
                    { "name": <string>, "status": <"CONFIRMED"|"MISSING"|"UNCLEAR">, "note": <string|null> }
                  ],
                  "riskFlags": [
                    { "code": <string>, "severity": <"LOW"|"MEDIUM"|"HIGH">, "description": <string> }
                  ],
                  "sellerQuestions": [<string>],
                  "scores": {
                    "completeness": <0-100>,
                    "equipment": <0-100>,
                    "risk": <0-100>,
                    "value": <0-100>,
                    "overall": <0-100>
                  },
                  "verdict": {
                    "code": <"WORTH_CHECKING"|"NEEDS_MORE_INFO"|"HIGH_RISK_SKIP">,
                    "label": <"warto sprawdzić"|"sprawdź po doprecyzowaniu"|"wysokie ryzyko — pomiń">
                  }
                }

                INSTRUKCJE:
                - extracted: wyodrębnij fakty z ogłoszenia. Użyj null dla każdego pola, którego nie możesz ustalić.
                - equipment: oceń wyposażenie wymienione lub nieobecne w ogłoszeniu.
                - riskFlags: zidentyfikuj sygnały ostrzegawcze (np. brak VIN, zbyt niska cena, brak historii serwisowej).
                - sellerQuestions: wygeneruj 3–5 konkretnych pytań do sprzedawcy po polsku.
                - scores: oceny 0–100 dla każdej kategorii. overall = średnia ważona.
                - verdict: wybierz jeden kod i odpowiedni label w języku polskim.

                PRZYKŁAD PRAWIDŁOWEJ ODPOWIEDZI:
                {
                  "extracted": { "make": "BMW", "model": "3 Series", "year": 2018, "priceAmount": 75000,
                    "priceCurrency": "PLN", "mileageKm": 120000, "fuel": "benzyna", "transmission": "automatyczna",
                    "originCountry": null, "sellerType": "prywatny", "serviceHistoryMentioned": true,
                    "accidentClaim": "bezwypadkowy wg sprzedającego", "vinPresent": true },
                  "equipment": [
                    { "name": "klimatyzacja", "status": "CONFIRMED", "note": null },
                    { "name": "tempomat", "status": "UNCLEAR", "note": "Nie wspomniano w ogłoszeniu" }
                  ],
                  "riskFlags": [
                    { "code": "HIGH_MILEAGE", "severity": "MEDIUM", "description": "Przebieg 120 000 km powyżej średniej dla rocznika" }
                  ],
                  "sellerQuestions": [
                    "Czy pojazd był serwisowany w ASO? Czy ma książkę serwisową?",
                    "Z jakiego kraju pochodzi pojazd?",
                    "Czy jest możliwość sprawdzenia w niezależnym warsztacie przed zakupem?"
                  ],
                  "scores": { "completeness": 75, "equipment": 60, "risk": 65, "value": 55, "overall": 64 },
                  "verdict": { "code": "NEEDS_MORE_INFO", "label": "sprawdź po doprecyzowaniu" }
                }
                """;
    }

    public String userMessage(String listingText) {
        return "Oceń to ogłoszenie:\n\n" + listingText;
    }
}
