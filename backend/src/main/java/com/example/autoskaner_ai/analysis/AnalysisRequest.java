package com.example.autoskaner_ai.analysis;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AnalysisRequest(
        @Pattern(regexp = "https?://.+", message = "url: nieprawidłowy format URL")
        @Size(max = 2000, message = "url: zbyt długi URL")
        String url,

        @Size(max = 20000, message = "listingText: zbyt długi tekst (max 20 000 znaków)")
        String listingText
) {
    @AssertTrue(message = "Wymagane jest podanie url lub listingText")
    public boolean isInputPresent() {
        return (url != null && !url.isBlank()) || (listingText != null && !listingText.isBlank());
    }
}
