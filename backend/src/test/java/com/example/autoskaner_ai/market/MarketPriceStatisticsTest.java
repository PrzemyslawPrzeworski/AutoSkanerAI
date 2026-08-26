package com.example.autoskaner_ai.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These numbers reach the user as "cars like this cost X to Y". A contaminated min reads as a
 * bargain that does not exist, so every case here is about what must not survive into the range.
 */
class MarketPriceStatisticsTest {

    @Test
    void anEvenSampleAveragesTheTwoMiddleElements() {
        var stats = MarketPriceStatistics.of(List.of(60_000, 70_000, 80_000, 90_000));

        // The old code returned 80 000 here: the upper-middle element, one place off.
        assertThat(stats.medianPln()).isEqualTo(75_000);
        assertThat(stats.minPln()).isEqualTo(60_000);
        assertThat(stats.maxPln()).isEqualTo(90_000);
        assertThat(stats.sampleSize()).isEqualTo(4);
        assertThat(stats.discardedCount()).isZero();
    }

    @Test
    void anOddSampleTakesTheMiddleElement() {
        var stats = MarketPriceStatistics.of(List.of(90_000, 60_000, 75_000));

        assertThat(stats.medianPln()).isEqualTo(75_000);
    }

    @Test
    void anExactHalfRoundsUp() {
        var stats = MarketPriceStatistics.of(List.of(60_000, 60_001));

        assertThat(stats.medianPln()).isEqualTo(60_001);
    }

    @Test
    void inputOrderDoesNotMatterAndTheCallersListIsNotMutated() {
        List<Integer> input = new ArrayList<>(List.of(90_000, 60_000, 75_000, 80_000));

        var stats = MarketPriceStatistics.of(input);

        assertThat(stats.minPln()).isEqualTo(60_000);
        assertThat(stats.maxPln()).isEqualTo(90_000);
        assertThat(input).containsExactly(90_000, 60_000, 75_000, 80_000);
    }

    // The one that motivated the whole class: Otomoto renders financing instalments in the same
    // "### <n> PLN" block as asking prices, and 1 299 clears the service's 1 000 floor.
    @Test
    void aMonthlyInstalmentIsNotAPrice() {
        List<Integer> prices = new ArrayList<>(
                List.of(1_299, 78_000, 80_000, 82_000, 84_000, 86_000, 88_000, 90_000));

        var stats = MarketPriceStatistics.of(prices);

        assertThat(stats.minPln()).isEqualTo(78_000);
        assertThat(stats.discardedCount()).isEqualTo(1);
        assertThat(stats.sampleSize()).isEqualTo(7);
    }

    // Small samples get no interquartile fence, but the median band still applies — an instalment
    // is junk whether there are five listings or fifty.
    @Test
    void aMonthlyInstalmentIsDroppedFromASmallSampleToo() {
        var stats = MarketPriceStatistics.of(List.of(1_299, 78_000, 82_000, 90_000));

        assertThat(stats.minPln()).isEqualTo(78_000);
        assertThat(stats.medianPln()).isEqualTo(82_000);
        assertThat(stats.sampleSize()).isEqualTo(3);
    }

    // A salvage-title or wrong-variant listing is the right order of magnitude, so only the fence
    // catches it. This is the live case: min=39 900 reported against median=82 900.
    @Test
    void aFarBelowMarketOutlierIsFencedOut() {
        List<Integer> prices = new ArrayList<>(List.of(
                39_900, 78_000, 80_000, 81_000, 82_900, 84_000, 85_000, 86_000, 88_000, 90_000));

        var stats = MarketPriceStatistics.of(prices);

        assertThat(stats.minPln()).isEqualTo(78_000);
        assertThat(stats.maxPln()).isEqualTo(90_000);
        assertThat(stats.discardedCount()).isEqualTo(1);
    }

    @Test
    void aFarAboveMarketOutlierIsFencedOut() {
        List<Integer> prices = new ArrayList<>(List.of(
                78_000, 80_000, 81_000, 82_900, 84_000, 85_000, 86_000, 88_000, 90_000, 189_000));

        var stats = MarketPriceStatistics.of(prices);

        assertThat(stats.maxPln()).isEqualTo(90_000);
        assertThat(stats.discardedCount()).isEqualTo(1);
    }

    // A wide but genuine market — a base 2017 against a loaded 2021 — is not outliers. Trimming it
    // would report a confident range narrower than the thing it describes.
    @Test
    void aGenuinelyWideSpreadSurvives() {
        List<Integer> prices = new ArrayList<>(List.of(
                55_000, 62_000, 68_000, 74_000, 80_000, 86_000, 92_000, 98_000, 104_000, 110_000));

        var stats = MarketPriceStatistics.of(prices);

        assertThat(stats.minPln()).isEqualTo(55_000);
        assertThat(stats.maxPln()).isEqualTo(110_000);
        assertThat(stats.discardedCount()).isZero();
    }

    // Zero interquartile range would make every fence collapse onto one value. Twenty dealers
    // asking exactly 60 000 does not make the twenty-first at 64 000 an outlier.
    @Test
    void anIdenticalPricedSampleDiscardsNothing() {
        List<Integer> prices = new ArrayList<>(
                List.of(60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 64_000));

        var stats = MarketPriceStatistics.of(prices);

        assertThat(stats.minPln()).isEqualTo(60_000);
        assertThat(stats.maxPln()).isEqualTo(64_000);
        assertThat(stats.medianPln()).isEqualTo(60_000);
        assertThat(stats.discardedCount()).isZero();
    }

    @Test
    void aSingletonIsItsOwnMinMedianAndMax() {
        var stats = MarketPriceStatistics.of(List.of(82_900));

        assertThat(stats.minPln()).isEqualTo(82_900);
        assertThat(stats.medianPln()).isEqualTo(82_900);
        assertThat(stats.maxPln()).isEqualTo(82_900);
        assertThat(stats.sampleSize()).isEqualTo(1);
    }

    // Nothing agrees with anything, so there is no market to describe. Reporting the sample as
    // found beats inventing a tight range from whichever two values happened to be close.
    @Test
    void aSampleWithNothingLeftAfterTheBandIsReportedAsFound() {
        var stats = MarketPriceStatistics.of(List.of(2_000, 60_000, 900_000));

        assertThat(stats.minPln()).isEqualTo(2_000);
        assertThat(stats.maxPln()).isEqualTo(900_000);
        assertThat(stats.sampleSize()).isEqualTo(3);
        assertThat(stats.discardedCount()).isZero();
    }
}
