package com.example.autoskaner_ai.analysis;

import java.util.Optional;
import java.util.regex.Pattern;

public class VinValidator {

    private static final Pattern VALID = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    public static Optional<String> normalise(String raw) {
        if (raw == null) return Optional.empty();
        String normalised = raw.trim().toUpperCase().replaceAll("[\\s\\-]", "");
        if (VALID.matcher(normalised).matches()) {
            return Optional.of(normalised);
        }
        return Optional.empty();
    }
}
