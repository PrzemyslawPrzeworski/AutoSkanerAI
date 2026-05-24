package com.example.autoskaner_ai.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RiskAnalysisRequest(
        @NotBlank(message = "listingText: nie może być pusty")
        @Size(max = 20000, message = "listingText: zbyt długi tekst (max 20 000 znaków)")
        String listingText
) {
}
