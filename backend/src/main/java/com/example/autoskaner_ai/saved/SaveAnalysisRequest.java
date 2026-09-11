package com.example.autoskaner_ai.saved;

import com.example.autoskaner_ai.analysis.AnalysisResponse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the client sends to keep an analysis (FR-010).
 *
 * <p>The whole {@link AnalysisResponse} it already holds comes back to the server rather than an id,
 * because the analysis is never persisted on the way out — {@code POST /api/analyses} computes it and
 * returns it, and there is no server-side copy to refer to. That also fixes what a saved row means:
 * the bytes the user was looking at when they pressed save.
 *
 * <p>There is deliberately no {@code userId} field. It comes from the bearer token via
 * {@code AuthenticatedUser.requireId}; accepting it here would hand the ownership guarantee to the
 * caller. See {@code context/foundation/test-plan.md} §2 risk #8.
 */
public record SaveAnalysisRequest(
        @NotBlank(message = "title: jest wymagany")
        @Size(max = 200, message = "title: maksymalnie 200 znaków")
        String title,

        @Size(max = 2000, message = "note: maksymalnie 2000 znaków")
        String note,

        @Size(max = 2048, message = "sourceUrl: maksymalnie 2048 znaków")
        String sourceUrl,

        @NotNull(message = "analysis: jest wymagana")
        AnalysisResponse analysis
) {

    /**
     * A {@code url_failed} response carries {@code analysis: null} — there is nothing in it to save,
     * and a row whose payload holds only a failure reason would list as a car with no make, no
     * verdict and no score. Rejected at the edge rather than stored and rendered as an empty card.
     */
    @AssertTrue(message = "analysis: nie zawiera wyniku analizy")
    public boolean isAnalysisPresent() {
        return analysis != null && analysis.analysis() != null;
    }
}
