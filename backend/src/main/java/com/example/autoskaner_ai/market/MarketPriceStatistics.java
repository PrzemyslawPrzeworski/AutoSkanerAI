package com.example.autoskaner_ai.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a page of scraped prices into a min / median / max a buyer can act on.
 *
 * <p>Two things were wrong with doing this inline. {@code PRICE_PATTERN} cannot tell an asking
 * price from a monthly financing instalment or a salvage-title listing, so the extremes were
 * contaminated — a live run reported {@code min=39900} against {@code median=82900}, and the range
 * a user reads as "what this car costs" started 50% below the market. And
 * {@code prices.get(size / 2)} is the upper-middle element, not a median, for even samples.
 *
 * <p>The contamination comes in two flavours, so it takes two passes:
 *
 * <ol>
 *   <li><b>Order-of-magnitude junk</b> — a monthly instalment is a thirtieth of a price, and it
 *       clears the service's {@code 1_000..10_000_000} guard easily. An interquartile fence cannot
 *       catch this reliably, because enough junk drags the quartiles down with it. A band around
 *       the median can: the median is what junk cannot move.
 *   <li><b>Right order of magnitude, wrong car</b> — a damaged listing or a different trim inside
 *       the same search. That is what Tukey's 1.5×IQR fence is for.
 * </ol>
 *
 * <p>Everything reported describes the kept sample, {@code sampleSize} included — otherwise the
 * "small sample" caveat in the UI would be counting listings the numbers do not come from.
 */
final class MarketPriceStatistics {

    /** Tukey's canonical fence. Not tuned to any particular market. */
    private static final double IQR_FENCE = 1.5;

    /**
     * A price this far from the median is not the same product. Three-fold either way is wide on
     * purpose: the job is to drop instalments and per-month figures, not to narrow a market that
     * genuinely spans a 2017 base model and a 2021 loaded one.
     */
    private static final int MEDIAN_BAND_FACTOR = 3;

    /** Below this an interquartile range describes the noise, so nothing is discarded by it. */
    private static final int MIN_SAMPLE_FOR_IQR = 8;

    /** Never trim a sample down past this — a two-price "range" is not worth the precision. */
    private static final int MIN_SAMPLE_TO_KEEP = 3;

    /**
     * Below this many kept listings the range is reported as {@code THIN}.
     *
     * <p><b>A stated product guardrail, not a derived number.</b> Nothing in the data says 5 rather
     * than 4 or 6; what says it is the claim the panel makes. Three numbers off four adverts are not
     * a distribution — the median of an even sample is already interpolated between two of them, so
     * at four, every figure shown is moved by any single atypical advert. Five is the smallest
     * sample where the middle is a value somebody actually asked and the extremes have a neighbour.
     *
     * <p>Deliberately larger than {@link #MIN_SAMPLE_TO_KEEP}. That one is about when trimming stops
     * being safe; this one is about when reporting starts being meaningful, and conflating the two is
     * what let a sample of exactly 3 through uncaveated.
     */
    private static final int MIN_SAMPLE_FOR_CONFIDENCE = 5;

    private MarketPriceStatistics() {
    }

    record Stats(int minPln, int medianPln, int maxPln, int sampleSize, int discardedCount,
                 MarketPriceSampleQuality quality) {
    }

    /** @param rawPrices non-empty; the caller has already handled the no-results case. */
    static Stats of(List<Integer> rawPrices) {
        List<Integer> sorted = new ArrayList<>(rawPrices);
        Collections.sort(sorted);

        List<Integer> banded = withinMedianBand(sorted);
        // If the band leaves almost nothing, the sample is too strange to reason about and the
        // honest move is to report it as found rather than to invent a tight range from three
        // survivors that happened to agree — but to say so, which is what `dispersed` is for.
        //
        // Guarded on the input size because below MIN_SAMPLE_TO_KEEP the band *cannot* leave enough
        // however well the prices agree. One price does not disagree with itself, and calling a
        // singleton dispersed would be reporting arithmetic as a finding.
        boolean bandCollapsed = sorted.size() >= MIN_SAMPLE_TO_KEEP
                && banded.size() < MIN_SAMPLE_TO_KEEP;

        List<Integer> kept = bandCollapsed ? sorted : banded;
        // Not `kept.size() >= MIN_SAMPLE_FOR_IQR` alone: on a collapsed band `kept` is the whole
        // untrimmed sample, and fencing it would narrow the very range we just decided to report
        // in full. Reporting a visibly wide range is the point of DISPERSED; letting the fence
        // tighten it afterwards would put a confident-looking min/max under the honest label.
        if (!bandCollapsed && kept.size() >= MIN_SAMPLE_FOR_IQR) {
            List<Integer> fenced = withoutIqrOutliers(kept);
            if (fenced.size() >= MIN_SAMPLE_TO_KEEP) {
                kept = fenced;
            }
        }

        return new Stats(kept.get(0), median(kept), kept.get(kept.size() - 1), kept.size(),
                sorted.size() - kept.size(), qualityOf(kept.size(), bandCollapsed));
    }

    /**
     * Dispersion outranks size: a sample the band could not trim is not describing one market, and
     * that stays true whether it holds 3 listings or 30. Only when the prices did agree does the
     * count decide.
     *
     * <p>There is deliberately no "many were discarded, so call it dispersed" rule. The trim working
     * as designed is not a defect in the sample, and any threshold on the discarded count would be a
     * second invented number doing the first one's job.
     */
    private static MarketPriceSampleQuality qualityOf(int keptSize, boolean bandCollapsed) {
        if (bandCollapsed) {
            return MarketPriceSampleQuality.DISPERSED;
        }
        return keptSize < MIN_SAMPLE_FOR_CONFIDENCE
                ? MarketPriceSampleQuality.THIN
                : MarketPriceSampleQuality.SUFFICIENT;
    }

    /** The band alone, with no fallback — {@link #of} decides what a collapse means. */
    private static List<Integer> withinMedianBand(List<Integer> sorted) {
        long reference = median(sorted);
        return sorted.stream()
                .filter(price -> (long) price * MEDIAN_BAND_FACTOR >= reference
                        && price <= reference * MEDIAN_BAND_FACTOR)
                .toList();
    }

    private static List<Integer> withoutIqrOutliers(List<Integer> sorted) {
        int half = sorted.size() / 2;
        // Tukey's hinges: on an odd sample the median belongs to both halves.
        long q1 = median(sorted.subList(0, sorted.size() % 2 == 0 ? half : half + 1));
        long q3 = median(sorted.subList(half, sorted.size()));
        long iqr = q3 - q1;
        // Half the sample is a single price, so any fence would be a knife: a market where 20 cars
        // ask exactly 60 000 does not make the 21st at 64 000 an outlier.
        if (iqr == 0) {
            return sorted;
        }
        long low = q1 - Math.round(IQR_FENCE * iqr);
        long high = q3 + Math.round(IQR_FENCE * iqr);
        return sorted.stream().filter(price -> price >= low && price <= high).toList();
    }

    /** @param sorted non-empty and ascending. */
    private static int median(List<Integer> sorted) {
        int size = sorted.size();
        int mid = size / 2;
        if (size % 2 != 0) {
            return sorted.get(mid);
        }
        // Rounded up on an exact half. Prices are whole złoty and nobody cares which way a 50 gr
        // tie falls, but leaving it unstated is how two call sites end up disagreeing.
        return (int) (((long) sorted.get(mid - 1) + sorted.get(mid) + 1) / 2);
    }
}
