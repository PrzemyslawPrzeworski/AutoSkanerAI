package com.example.autoskaner_ai.market;

/**
 * How much weight the reported min / median / max can carry.
 *
 * <p>This is a server-side judgement on purpose. The frontend used to caveat a range when
 * {@code sampleSize < 3}, which collided with {@code MarketPriceStatistics.MIN_SAMPLE_TO_KEEP = 3}:
 * a sample of exactly 3 is the most contaminated thing the pipeline can emit — it is what you get
 * when the median band found the prices so far apart that trimming would have left nothing, so the
 * untrimmed sample is reported instead — and it was the one size that slipped past the caveat. The
 * range showed up looking like every other range.
 *
 * <p>The pipeline knows why a sample looks the way it does; the panel only sees a number. So the
 * decision is made where the thresholds are, next to the code that applied them.
 */
public enum MarketPriceSampleQuality {

    /** Enough listings, and they agree well enough to describe one market. */
    SUFFICIENT,

    /** Too few listings to read as a distribution. The numbers are real but easily moved. */
    THIN,

    /**
     * The listings do not describe one market. Reported as found rather than trimmed into a
     * confident-looking range invented from whichever few values happened to agree.
     */
    DISPERSED
}
