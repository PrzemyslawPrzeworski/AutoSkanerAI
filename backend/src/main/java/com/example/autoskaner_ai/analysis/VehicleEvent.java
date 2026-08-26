package com.example.autoskaner_ai.analysis;

import java.util.List;

/**
 * One entry from the historiapojazdu timeline, passed through largely as the registry returned
 * it. The registry keeps adding event types, so this is deliberately generic: {@code type} and
 * {@code details} are not interpreted here beyond what the parser needs for damage and mileage.
 *
 * <p>Passing the whole timeline through means a type this code has never seen still reaches the
 * user instead of being silently dropped — which is how the significant-damage event on
 * NMTBZ3BE40R000000 went missing while the app reported a clean history.
 */
public record VehicleEvent(
        String date,
        String type,
        String name,
        List<EventDetail> details
) {
    public record EventDetail(String name, String value) {
    }
}
