package com.example.autoskaner_ai.analysis;

import java.util.List;

/**
 * A "szkoda istotna" entry from CEPiK — damage serious enough that the vehicle needed an
 * additional technical inspection before returning to the road.
 *
 * <p>{@code insurer} and {@code categories} come from the event's detail rows, e.g. PZU and
 * "Uszkodzenie elementów układu nośnego". The categories are what distinguish structural
 * damage from a cosmetic claim, so they must reach the UI rather than being flattened away.
 */
public record DamageRecord(
        String date,
        String description,
        String insurer,
        List<String> categories
) {
}
