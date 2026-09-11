package com.example.autoskaner_ai.saved;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The update operation's whole surface (FR-011): the two columns that are the user's own.
 *
 * <p>Everything else on a saved row records what the model and the registry said at a point in time.
 * See {@code SavedAnalysis.edit} — a saved verdict that can be rewritten is no longer evidence.
 */
public record EditSavedAnalysisRequest(
        @NotBlank(message = "title: jest wymagany")
        @Size(max = 200, message = "title: maksymalnie 200 znaków")
        String title,

        @Size(max = 2000, message = "note: maksymalnie 2000 znaków")
        String note
) {}
