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
        // Hand arithmetic: the two middle elements are the whole sample, and 60 000 + 60 001 is
        // 120 001, half of which is 60 000.5. Rounded up that is 60 001; a plain integer average
        // truncates to 60 000. Which way a 50 gr tie falls does not matter to a buyer — that two
        // call sites might answer differently does, so the rule is pinned rather than inferred.
        var stats = MarketPriceStatistics.of(List.of(60_000, 60_001));

        assertThat(stats.medianPln()).isEqualTo(60_001);
    }

    @Test
    void anEvenSampleWithAnOddMiddleSumRoundsTheMedianUp() {
        // The case above is two elements wide, so it also passes for anything that happens to
        // return the larger of a pair. This one is six wide: sorted, the middle two are the 3rd and
        // 4th, 74 500 and 79 901. 74 500 + 79 901 = 154 401, half is 77 200.5, rounded up 77 201.
        // A plain average would report 77 200.
        var stats = MarketPriceStatistics.of(
                List.of(90_000, 60_900, 79_901, 70_000, 85_000, 74_500));

        assertThat(stats.medianPln()).isEqualTo(77_201);
        assertThat(stats.discardedCount()).isZero();
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
        // The trim worked, so the seven that remain are worth reporting plainly. Discarding a lot is
        // not itself a reason to caveat — the pass exists to be used.
        assertThat(stats.quality()).isEqualTo(MarketPriceSampleQuality.SUFFICIENT);
    }

    // Small samples get no interquartile fence, but the median band still applies — an instalment
    // is junk whether there are five listings or fifty.
    @Test
    void aMonthlyInstalmentIsDroppedFromASmallSampleToo() {
        var stats = MarketPriceStatistics.of(List.of(1_299, 78_000, 82_000, 90_000));

        assertThat(stats.minPln()).isEqualTo(78_000);
        assertThat(stats.medianPln()).isEqualTo(82_000);
        assertThat(stats.sampleSize()).isEqualTo(3);
        // Hand arithmetic: median of the four raw prices is (78 000 + 82 000 + 1) / 2 = 80 000, so
        // the band runs 26 666 to 240 000 and only 1 299 falls out. Three prices survive, which is
        // exactly MIN_SAMPLE_TO_KEEP — the band did not collapse, it trimmed. Contrast
        // theUntrimmableSampleOfThreeIsCalledDispersed below: same sampleSize, different reason,
        // and the reason is the whole point of reporting a quality at all.
        assertThat(stats.quality()).isEqualTo(MarketPriceSampleQuality.THIN);
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
        // Hand arithmetic on Tukey's hinges: the sample is eight wide, so the lower half is the
        // first four (all 60 000) and the upper half the last four (60 000, 60 000, 60 000, 64 000).
        // q1 = (60 000 + 60 000 + 1) / 2 = 60 000 and q3 the same, so the interquartile range is 0.
        // Any fence built on it would collapse onto a single value and call 64 000 an outlier.
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

    // ---------------------------------------------------------------------------------------------
    // Sample quality. Every expected value below is arithmetic written out from the composed input —
    // the list is a shape we own, so composing it is allowed; re-deriving the answer with the
    // production formula is not.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theUntrimmableSampleOfThreeIsCalledDispersedNotSufficient() {
        // The hole this field exists to close. Hand arithmetic: median of three is the middle,
        // 60 000, so the band runs 60 000 / 3 = 20 000 to 60 000 × 3 = 180 000. 2 000 is below it and
        // 900 000 above, leaving one price — fewer than MIN_SAMPLE_TO_KEEP — so the untrimmed sample
        // is reported instead and sampleSize lands on 3.
        //
        // Three is precisely the size the frontend's old `sampleSize < 3` caveat did not fire on. So
        // the most contaminated output the pipeline can produce was the one that reached the user
        // wearing the same face as a clean range.
        var stats = MarketPriceStatistics.of(List.of(2_000, 60_000, 900_000));

        assertThat(stats.sampleSize()).isEqualTo(3);
        assertThat(stats.quality())
                // ASCII only: an AssertJ description is read off a console, and the Windows codepage
                // turns an em dash into a replacement character mid-sentence.
                .as("reported untrimmed because nothing agreed, not merely a small sample")
                .isEqualTo(MarketPriceSampleQuality.DISPERSED);
    }

    @Test
    void dispersionOutranksSizeSoALargeSampleThatCollapsesIsStillDispersed() {
        // Ten prices spanning four orders of magnitude. Hand arithmetic: sorted, the middle two are
        // the 5th and 6th, 4 000 and 60 000, so the median is (4 000 + 60 000 + 1) / 2 = 32 000 and
        // the band runs 10 666 to 96 000. Only 60 000 is inside it. Ten listings, and still not one
        // market — which is why the quality is decided by whether the band collapsed and only then
        // by the count.
        var stats = MarketPriceStatistics.of(List.of(
                1_000, 2_000, 3_000, 4_000, 60_000, 900_000, 1_000_000, 2_000_000, 3_000_000,
                4_000_000));

        assertThat(stats.sampleSize()).isEqualTo(10);
        assertThat(stats.quality()).isEqualTo(MarketPriceSampleQuality.DISPERSED);
    }

    @Test
    void aSampleTooSmallToTrimIsThinRatherThanDispersed() {
        // The collapse check is gated on the input size, and this is what the gate is for. One price
        // does not disagree with itself, and no band can leave three out of two — without the gate
        // every sample below MIN_SAMPLE_TO_KEEP would be labelled DISPERSED, which is reporting
        // arithmetic as a finding about the market.
        assertThat(MarketPriceStatistics.of(List.of(82_900)).quality())
                .isEqualTo(MarketPriceSampleQuality.THIN);
        // Hand arithmetic for the pair: median is (78 000 + 90 000 + 1) / 2 = 84 000, band 28 000 to
        // 252 000, both prices inside it. Nothing was dropped; the sample is just small.
        assertThat(MarketPriceStatistics.of(List.of(78_000, 90_000)).quality())
                .isEqualTo(MarketPriceSampleQuality.THIN);
    }

    @Test
    void theFifthAgreeingPriceIsWhatClearsTheCaveat() {
        // MIN_SAMPLE_FOR_CONFIDENCE = 5, a stated product guardrail rather than a derived number.
        // These two samples differ by one price, so the boundary is the only thing being asserted.
        // Hand arithmetic: four prices give a median of (80 000 + 82 000 + 1) / 2 = 81 000, band
        // 27 000 to 243 000; five give a median of 82 000, band 27 333 to 246 000. Nothing falls out
        // of either, so the count is the whole difference.
        var four = MarketPriceStatistics.of(List.of(78_000, 80_000, 82_000, 84_000));
        var five = MarketPriceStatistics.of(List.of(78_000, 80_000, 82_000, 84_000, 86_000));

        assertThat(four.discardedCount()).isZero();
        assertThat(five.discardedCount()).isZero();
        assertThat(four.quality()).isEqualTo(MarketPriceSampleQuality.THIN);
        assertThat(five.quality()).isEqualTo(MarketPriceSampleQuality.SUFFICIENT);
    }

    @Test
    void theInterquartileFenceIsGatedOnTheSizeLeftAfterTheBandNotBeforeIt() {
        // Eight raw prices, which is MIN_SAMPLE_FOR_IQR exactly — so a fence gated on the *input*
        // size would run. It must not: hand arithmetic gives a median of
        // (78 000 + 80 000 + 1) / 2 = 79 000 and a band floor of 26 333, which drops all three
        // instalment-shaped figures and leaves five. Five is below MIN_SAMPLE_FOR_IQR, and a fence
        // over five genuine asking prices spanning 78 000 to 86 000 would start eating the market.
        var stats = MarketPriceStatistics.of(List.of(
                1_299, 1_400, 1_500, 78_000, 80_000, 82_000, 84_000, 86_000));

        assertThat(stats.minPln()).isEqualTo(78_000);
        assertThat(stats.maxPln()).isEqualTo(86_000);
        assertThat(stats.sampleSize()).isEqualTo(5);
        assertThat(stats.discardedCount())
                .as("three instalments dropped by the band, none by a fence that never ran")
                .isEqualTo(3);
        assertThat(stats.quality()).isEqualTo(MarketPriceSampleQuality.SUFFICIENT);
    }
}
