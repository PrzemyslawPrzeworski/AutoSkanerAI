package com.example.autoskaner_ai.saved;

import com.example.autoskaner_ai.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create, read, update and delete a saved analysis (FR-010 … FR-012).
 *
 * <p>Under {@code /api/**}, so every method here already required a valid access token before it ran
 * — {@code SecurityConfig} makes the prefix {@code authenticated()}. The token is also the only
 * source of the owner: each method's first act is {@link AuthenticatedUser#requireId}, and no path,
 * query parameter or body field on this controller carries a user id.
 */
@RestController
@RequestMapping("/api/saved-analyses")
public class SavedAnalysisController {

    private final SavedAnalysisService service;

    SavedAnalysisController(SavedAnalysisService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SavedAnalysisDetailResponse> save(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveAnalysisRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.save(AuthenticatedUser.requireId(jwt), request));
    }

    @GetMapping
    public List<SavedAnalysisSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(AuthenticatedUser.requireId(jwt));
    }

    @GetMapping("/{id}")
    public SavedAnalysisDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(AuthenticatedUser.requireId(jwt), id);
    }

    @PatchMapping("/{id}")
    public SavedAnalysisDetailResponse edit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody EditSavedAnalysisRequest request) {
        return service.edit(AuthenticatedUser.requireId(jwt), id, request);
    }

    /** 204, and 404 when the row is missing or somebody else's — never a silent success. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(AuthenticatedUser.requireId(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
