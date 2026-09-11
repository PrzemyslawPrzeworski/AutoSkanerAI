package com.example.autoskaner_ai.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.autoskaner_ai.analysis.AnalysisResponse;
import com.example.autoskaner_ai.analysis.AnalysisResult;
import com.example.autoskaner_ai.analysis.CategoryScores;
import com.example.autoskaner_ai.analysis.ExtractedData;
import com.example.autoskaner_ai.analysis.Verdict;
import com.example.autoskaner_ai.analysis.VerdictCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The four operations, against a mocked repository.
 *
 * <p>Two of these tests are about ownership rather than behaviour — {@link
 * #deletingSomebodyElsesAnalysisRemovesNothingAndIs404} and {@link
 * #deleteNeverReachesTheOwnerlessJpaDelete} — because the interesting failure of this class is not a
 * wrong field, it is a request that succeeds for the wrong user. See
 * {@code context/foundation/test-plan.md} §2 risk #8.
 */
class SavedAnalysisServiceTest {

    private static final long OWNER = 7L;
    private static final long INTRUDER = 8L;

    private final SavedAnalysisRepository repository = mock(SavedAnalysisRepository.class);
    private final SavedAnalysisService service =
            new SavedAnalysisService(repository, new ObjectMapper());

    private static AnalysisResponse analysis() {
        ExtractedData extracted = new ExtractedData(
                "Toyota", "Corolla", 2019, new BigDecimal("64900.00"), "PLN", 118_500,
                "benzyna", "manualna", "PL", "private", true, null, true, null, null, null);
        AnalysisResult result = new AnalysisResult(
                extracted,
                List.of(),
                List.of(),
                List.of("Czy jest komplet kluczy?"),
                new CategoryScores(70, 65, 60, 75, 68),
                new Verdict(VerdictCode.WORTH_CHECKING, "warto sprawdzić"),
                null);
        return new AnalysisResponse("text", null, result, null, null);
    }

    private static SaveAnalysisRequest request(String title, String note) {
        return new SaveAnalysisRequest(title, note, "https://example.pl/oferta", analysis());
    }

    private void repositoryEchoesSaves() {
        when(repository.save(any(SavedAnalysis.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void savingCopiesTheSummaryColumnsOutOfTheAnalysis() {
        repositoryEchoesSaves();

        SavedAnalysisDetailResponse detail = service.save(OWNER, request("Corolla z OLX", null));

        ArgumentCaptor<SavedAnalysis> stored = ArgumentCaptor.forClass(SavedAnalysis.class);
        verify(repository).save(stored.capture());
        SavedAnalysis saved = stored.getValue();
        assertThat(saved.getUserId()).isEqualTo(OWNER);
        assertThat(saved.getTitle()).isEqualTo("Corolla z OLX");
        assertThat(saved.getSourceUrl()).isEqualTo("https://example.pl/oferta");
        assertThat(saved.getMake()).isEqualTo("Toyota");
        assertThat(saved.getModel()).isEqualTo("Corolla");
        assertThat(saved.getProductionYear()).isEqualTo(2019);
        assertThat(saved.getPriceAmount()).isEqualByComparingTo("64900.00");
        assertThat(saved.getPriceCurrency()).isEqualTo("PLN");
        assertThat(saved.getMileageKm()).isEqualTo(118_500);
        assertThat(saved.getVerdictCode()).isEqualTo("WORTH_CHECKING");
        assertThat(saved.getOverallScore()).isEqualTo(68);
        assertThat(detail.summary().title()).isEqualTo("Corolla z OLX");
    }

    /**
     * The payload is written and read by the same mapper, so this is the test that fails if a new
     * field on the analysis is serialisable but not deserialisable — which would break every GET of
     * an already-saved row rather than the save that introduced it.
     */
    @Test
    void theStoredPayloadRoundTripsBackIntoAnAnalysis() {
        repositoryEchoesSaves();

        SavedAnalysisDetailResponse detail = service.save(OWNER, request("Round trip", null));

        assertThat(detail.analysis().fetchStatus()).isEqualTo("text");
        assertThat(detail.analysis().analysis().extracted().make()).isEqualTo("Toyota");
        assertThat(detail.analysis().analysis().verdict().code()).isEqualTo(VerdictCode.WORTH_CHECKING);
        assertThat(detail.analysis().analysis().scores().overall()).isEqualTo(68);
        assertThat(detail.analysis().analysis().sellerQuestions())
                .containsExactly("Czy jest komplet kluczy?");
    }

    @Test
    void aNoteSuppliedAtSaveTimeIsStored() {
        repositoryEchoesSaves();

        SavedAnalysisDetailResponse detail =
                service.save(OWNER, request("Z notatką", "Dzwonić po 18:00"));

        assertThat(detail.summary().note()).isEqualTo("Dzwonić po 18:00");
        assertThat(detail.summary().title()).isEqualTo("Z notatką");
    }

    @Test
    void listingReadsOnlyTheCallersOwnRows() {
        when(repository.findByUserIdOrderByCreatedAtDesc(OWNER))
                .thenReturn(List.of(row("Nowsza"), row("Starsza")));

        List<SavedAnalysisSummaryResponse> list = service.list(OWNER);

        assertThat(list).extracting(SavedAnalysisSummaryResponse::title)
                .containsExactly("Nowsza", "Starsza");
        verify(repository).findByUserIdOrderByCreatedAtDesc(OWNER);
    }

    @Test
    void readingSomebodyElsesAnalysisIs404() {
        when(repository.findByIdAndUserId(41L, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(INTRUDER, 41L))
                .isInstanceOf(SavedAnalysisNotFoundException.class);
    }

    @Test
    void editingRewritesOnlyTitleAndNote() {
        SavedAnalysis existing = row("Stary tytuł");
        when(repository.findByIdAndUserId(41L, OWNER)).thenReturn(Optional.of(existing));
        repositoryEchoesSaves();

        SavedAnalysisDetailResponse detail = service.edit(
                OWNER, 41L, new EditSavedAnalysisRequest("Nowy tytuł", "Sprawdzone"));

        assertThat(detail.summary().title()).isEqualTo("Nowy tytuł");
        assertThat(detail.summary().note()).isEqualTo("Sprawdzone");
        assertThat(existing.getMake()).isEqualTo("Toyota");
        assertThat(existing.getVerdictCode()).isEqualTo("WORTH_CHECKING");
        assertThat(existing.getOverallScore()).isEqualTo(68);
    }

    @Test
    void editingSomebodyElsesAnalysisIs404AndWritesNothing() {
        when(repository.findByIdAndUserId(41L, INTRUDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.edit(INTRUDER, 41L, new EditSavedAnalysisRequest("Moje teraz", null)))
                .isInstanceOf(SavedAnalysisNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void deletingYourOwnAnalysisRemovesIt() {
        when(repository.deleteByIdAndUserId(41L, OWNER)).thenReturn(1L);

        service.delete(OWNER, 41L);

        verify(repository).deleteByIdAndUserId(41L, OWNER);
    }

    @Test
    void deletingSomebodyElsesAnalysisRemovesNothingAndIs404() {
        when(repository.deleteByIdAndUserId(41L, INTRUDER)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(INTRUDER, 41L))
                .isInstanceOf(SavedAnalysisNotFoundException.class);
    }

    /**
     * {@code deleteById} takes an id with no owner, so reaching for it is how this endpoint would
     * delete a stranger's row on a guessed number. Pinned as a call that must not happen, because a
     * future simplification of {@link SavedAnalysisService#delete} would otherwise stay green.
     */
    @Test
    void deleteNeverReachesTheOwnerlessJpaDelete() {
        when(repository.deleteByIdAndUserId(anyLong(), anyLong())).thenReturn(1L);

        service.delete(OWNER, 41L);

        verify(repository, never()).deleteById(anyLong());
        verify(repository, never()).delete(any());
    }

    private SavedAnalysis row(String title) {
        return SavedAnalysis
                .create(OWNER, title, new ObjectMapper().writeValueAsString(analysis()),
                        Instant.parse("2026-09-11T10:00:00Z"))
                .describe(new SavedAnalysis.Summary(
                        "Toyota", "Corolla", 2019, new BigDecimal("64900.00"), "PLN", 118_500,
                        "WORTH_CHECKING", 68));
    }
}
