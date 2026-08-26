package com.example.autoskaner_ai.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ManualListingComposerTest {

    @Test
    void composesAnAdvertLikeBlockFromTheFilledFields() {
        var manual = new ManualListing("Toyota", "Corolla", 2022, BigDecimal.valueOf(82_900),
                "PLN", 26_320, "hybryda", "automatyczna", "Serwisowany w ASO, jeden właściciel");

        var text = ManualListingComposer.compose(manual, null);

        assertThat(text).isEqualTo("""
                Dane pojazdu wprowadzone ręcznie przez użytkownika:
                Marka: Toyota
                Model: Corolla
                Rok produkcji: 2022
                Cena: 82900 PLN
                Przebieg: 26320 km
                Paliwo: hybryda
                Skrzynia biegów: automatyczna

                Dodatkowe informacje od użytkownika:
                Serwisowany w ASO, jeden właściciel""");
    }

    // A blank field must be absent, not rendered as "brak danych" — the model reads a stated
    // "brak historii serwisowej" as a fact about the car and flags it, when all that happened is
    // that the user left the box empty.
    @Test
    void blankFieldsAreOmittedRatherThanFilledIn() {
        var manual = new ManualListing("Toyota", "  ", null, null, null, 26_320, null, null, null);

        var text = ManualListingComposer.compose(manual, null);

        assertThat(text).isEqualTo("""
                Dane pojazdu wprowadzone ręcznie przez użytkownika:
                Marka: Toyota
                Przebieg: 26320 km""");
        assertThat(text).doesNotContain("Model").doesNotContain("brak");
    }

    @Test
    void aPriceWithoutACurrencyIsAssumedToBeZloty() {
        var manual = new ManualListing(null, null, null, BigDecimal.valueOf(82_900.00), null,
                null, null, null, null);

        assertThat(ManualListingComposer.compose(manual, null)).contains("Cena: 82900 PLN");
    }

    @Test
    void anExplicitCurrencyIsKept() {
        var manual = new ManualListing(null, null, null, BigDecimal.valueOf(19_500), "EUR",
                null, null, null, null);

        assertThat(ManualListingComposer.compose(manual, null)).contains("Cena: 19500 EUR");
    }

    @Test
    void listingTextIsAppendedBelowTheFields() {
        var manual = new ManualListing("Toyota", null, null, null, null, null, null, null, null);

        var text = ManualListingComposer.compose(manual, "  Sprzedam Corollę, stan idealny.  ");

        assertThat(text).isEqualTo("""
                Dane pojazdu wprowadzone ręcznie przez użytkownika:
                Marka: Toyota

                Treść ogłoszenia:
                Sprzedam Corollę, stan idealny.""");
    }
}
