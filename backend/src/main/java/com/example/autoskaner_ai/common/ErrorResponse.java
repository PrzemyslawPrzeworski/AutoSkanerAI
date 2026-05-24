package com.example.autoskaner_ai.common;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(int status, String error, List<String> messages, Instant timestamp) {

    public static ErrorResponse of(int status, String error, List<String> messages) {
        return new ErrorResponse(status, error, messages, Instant.now());
    }
}
