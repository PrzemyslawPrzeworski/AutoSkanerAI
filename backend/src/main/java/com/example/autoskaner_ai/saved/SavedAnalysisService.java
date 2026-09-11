package com.example.autoskaner_ai.saved;

import com.example.autoskaner_ai.analysis.AnalysisResponse;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.ExtractedData;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The four persisted operations on a saved analysis (FR-010 … FR-012).
 *
 * <p>Every method takes {@code userId} as its first parameter and every repository call is keyed on
 * it. There is no method here that can be called without an owner, which is the point: ownership is
 * part of each lookup rather than a check a caller might skip. See
 * {@code context/foundation/test-plan.md} §2 risk #8.
 */
@Service
public class SavedAnalysisService {

    private final SavedAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    SavedAnalysisService(SavedAnalysisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SavedAnalysisDetailResponse save(long userId, SaveAnalysisRequest request) {
        Instant now = Instant.now();
        SavedAnalysis saved = SavedAnalysis
                .create(userId, request.title(), objectMapper.writeValueAsString(request.analysis()), now)
                .describe(summaryOf(request.analysis()))
                .from(request.sourceUrl());
        // `edit` is the entity's only mutator for the two user-owned columns, so a note supplied at
        // save time routes through it rather than opening a second write path into `note`.
        if (request.note() != null) {
            saved.edit(request.title(), request.note(), now);
        }
        return detailOf(repository.save(saved));
    }

    @Transactional(readOnly = true)
    public List<SavedAnalysisSummaryResponse> list(long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(SavedAnalysisSummaryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public SavedAnalysisDetailResponse get(long userId, long id) {
        return detailOf(require(userId, id));
    }

    @Transactional
    public SavedAnalysisDetailResponse edit(long userId, long id, EditSavedAnalysisRequest request) {
        SavedAnalysis saved = require(userId, id);
        saved.edit(request.title(), request.note(), Instant.now());
        return detailOf(repository.save(saved));
    }

    /**
     * @throws SavedAnalysisNotFoundException when nothing was removed, so a caller cannot tell a
     *     missing id from another user's id — and so a DELETE that deleted nothing does not answer
     *     204.
     */
    @Transactional
    public void delete(long userId, long id) {
        if (repository.deleteByIdAndUserId(id, userId) == 0) {
            throw new SavedAnalysisNotFoundException(id);
        }
    }

    private SavedAnalysis require(long userId, long id) {
        return repository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new SavedAnalysisNotFoundException(id));
    }

    private SavedAnalysisDetailResponse detailOf(SavedAnalysis saved) {
        return new SavedAnalysisDetailResponse(
                SavedAnalysisSummaryResponse.of(saved),
                objectMapper.readValue(saved.getPayload(), AnalysisResponse.class));
    }

    /**
     * The columns the list view reads. Every one of them is legitimately absent — extraction may find
     * no make, no price, no mileage — so this copies nulls through rather than substituting defaults;
     * a price of 0 for "not stated" would sort and compare as a real number.
     */
    private static SavedAnalysis.Summary summaryOf(AnalysisResponse response) {
        AnalysisResult result = response.analysis();
        ExtractedData extracted = result.extracted();
        return new SavedAnalysis.Summary(
                extracted == null ? null : extracted.make(),
                extracted == null ? null : extracted.model(),
                extracted == null ? null : extracted.year(),
                extracted == null ? null : extracted.priceAmount(),
                extracted == null ? null : extracted.priceCurrency(),
                extracted == null ? null : extracted.mileageKm(),
                result.verdict() == null ? null : result.verdict().code().name(),
                result.scores() == null ? null : result.scores().overall());
    }
}
