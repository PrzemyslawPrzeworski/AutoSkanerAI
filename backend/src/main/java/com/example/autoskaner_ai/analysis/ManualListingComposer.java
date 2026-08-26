package com.example.autoskaner_ai.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns manually entered fields into the Polish advert-style text the analysis prompt expects, so
 * the manual mode reuses the whole S-01 pipeline instead of needing a second prompt and a second
 * output schema.
 *
 * <p>This lives on the server on purpose: how fields become a prompt is a backend concern, and a
 * prompt change must not require a frontend release.
 *
 * <p>The composer never invents a value. Blank fields are omitted rather than filled with "brak
 * danych", because the model reads "brak informacji o historii serwisowej" as a stated fact about
 * the car and flags it, when the truth is only that the user did not type it in.
 */
final class ManualListingComposer {

    private ManualListingComposer() {
    }

    static String compose(ManualListing manual, String extraText) {
        List<String> lines = new ArrayList<>();
        // Named as such so the model knows it is reading a form, not a seller's own words —
        // the absence of sales language here is not a signal about the seller.
        lines.add("Dane pojazdu wprowadzone ręcznie przez użytkownika:");

        addIfPresent(lines, "Marka", manual.make());
        addIfPresent(lines, "Model", manual.model());
        addIfPresent(lines, "Rok produkcji", manual.year());
        addPrice(lines, manual.priceAmount(), manual.priceCurrency());
        addMileage(lines, manual.mileageKm());
        addIfPresent(lines, "Paliwo", manual.fuel());
        addIfPresent(lines, "Skrzynia biegów", manual.transmission());

        if (notBlank(manual.notes())) {
            lines.add("");
            lines.add("Dodatkowe informacje od użytkownika:");
            lines.add(manual.notes().strip());
        }

        if (notBlank(extraText)) {
            lines.add("");
            lines.add("Treść ogłoszenia:");
            lines.add(extraText.strip());
        }

        return String.join("\n", lines);
    }

    private static void addIfPresent(List<String> lines, String label, Object value) {
        if (value instanceof String text) {
            if (notBlank(text)) {
                lines.add(label + ": " + text.strip());
            }
            return;
        }
        if (value != null) {
            lines.add(label + ": " + value);
        }
    }

    private static void addPrice(List<String> lines, BigDecimal amount, String currency) {
        if (amount == null) {
            return;
        }
        // Default to PLN rather than leaving the amount bare: an unlabelled 82900 alongside a
        // Polish advert is PLN, and a currency-less price makes the value score meaningless.
        String unit = notBlank(currency) ? currency.strip() : "PLN";
        lines.add("Cena: " + amount.stripTrailingZeros().toPlainString() + " " + unit);
    }

    private static void addMileage(List<String> lines, Integer mileageKm) {
        if (mileageKm != null) {
            lines.add("Przebieg: " + mileageKm + " km");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
