package com.example.autoskaner_ai.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The entity's own rules, without a database. */
class SavedAnalysisTest {

    private static final Instant SAVED_AT = Instant.parse("2026-09-11T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-12T08:30:00Z");

    private static SavedAnalysis saved() {
        return SavedAnalysis.create(7L, "Corolla 1.8 Hybrid", "{\"analysis\":null}", SAVED_AT);
    }

    @Test
    void aNewSavedAnalysisIsCreatedAndUpdatedAtTheSameInstant() {
        var analysis = saved();

        assertThat(analysis.getCreatedAt()).isEqualTo(SAVED_AT);
        assertThat(analysis.getUpdatedAt()).isEqualTo(SAVED_AT);
    }

    @Test
    void editingRenamesAnnotatesAndMovesUpdatedAtOnly() {
        var analysis = saved();

        analysis.edit("Corolla — do oglądania w sobotę", "Sprzedawca nie podał VIN.", LATER);

        assertThat(analysis.getTitle()).isEqualTo("Corolla — do oglądania w sobotę");
        assertThat(analysis.getNote()).isEqualTo("Sprzedawca nie podał VIN.");
        assertThat(analysis.getUpdatedAt()).isEqualTo(LATER);
        assertThat(analysis.getCreatedAt()).isEqualTo(SAVED_AT);
    }

    @Test
    void editingDoesNotTouchTheStoredAnalysis() {
        // The point of the note column: a saved verdict is evidence of what the model said, so the
        // user annotates it rather than correcting it.
        var analysis = saved();

        analysis.edit("Nowa nazwa", null, LATER);

        assertThat(analysis.getPayload()).isEqualTo("{\"analysis\":null}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aBlankTitleIsRejectedRatherThanReachingTheNotNullColumn(String blank) {
        var analysis = saved();

        assertThatThrownBy(() -> analysis.edit(blank, null, LATER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(analysis.getTitle()).isEqualTo("Corolla 1.8 Hybrid");
    }

    @Test
    void aNullTitleIsRejectedAtCreation() {
        assertThatThrownBy(() -> SavedAnalysis.create(7L, null, "{}", SAVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptySummaryIsLegal() {
        // Every summary column is nullable because extraction genuinely fails to find any of them —
        // a listing with no price is not a listing that cannot be saved.
        var analysis =
                saved().describe(
                                new SavedAnalysis.Summary(
                                        null, null, null, null, null, null, null, null));

        assertThat(analysis.getMake()).isNull();
        assertThat(analysis.getPriceAmount()).isNull();
        assertThat(analysis.getVerdictCode()).isNull();
    }

    @Test
    void describeCopiesEverySummaryFieldOntoTheRow() {
        var analysis =
                saved().describe(
                                new SavedAnalysis.Summary(
                                        "Toyota",
                                        "Corolla",
                                        2019,
                                        new BigDecimal("64900.00"),
                                        "PLN",
                                        118_500,
                                        "WORTH_CHECKING",
                                        78));

        assertThat(analysis.getMake()).isEqualTo("Toyota");
        assertThat(analysis.getModel()).isEqualTo("Corolla");
        assertThat(analysis.getProductionYear()).isEqualTo(2019);
        assertThat(analysis.getPriceAmount()).isEqualByComparingTo("64900.00");
        assertThat(analysis.getPriceCurrency()).isEqualTo("PLN");
        assertThat(analysis.getMileageKm()).isEqualTo(118_500);
        assertThat(analysis.getVerdictCode()).isEqualTo("WORTH_CHECKING");
        assertThat(analysis.getOverallScore()).isEqualTo(78);
    }
}
