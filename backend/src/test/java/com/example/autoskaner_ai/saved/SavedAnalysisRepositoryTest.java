package com.example.autoskaner_ai.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.autoskaner_ai.account.UserAccount;
import com.example.autoskaner_ai.account.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saved-analyses table against H2 in PostgreSQL compatibility mode.
 *
 * <p>The round trip is the point: {@code ddl-auto=validate} already fails startup when an entity and
 * the migration disagree on a column's existence or type, but only a write and a read back prove the
 * mapping carries the value — a {@code NUMERIC(12,2)} that silently truncates, or a timestamp that
 * loses its zone, passes validation and loses data.
 */
@SpringBootTest
@ActiveProfiles("mock")
@Transactional
class SavedAnalysisRepositoryTest {

    private static final Instant EARLIER = Instant.parse("2026-09-10T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-11T10:00:00Z");

    @Autowired
    private SavedAnalysisRepository repository;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private EntityManager entityManager;

    private Long owner(String email) {
        return users.saveAndFlush(UserAccount.create(email, "hash", EARLIER)).getId();
    }

    @Test
    void roundTripsEverySummaryColumnAndTheWholePayload() {
        Long userId = owner("round-trip@example.pl");
        String payload = "{\"fetchStatus\":\"text\",\"analysis\":{\"verdict\":{\"code\":\"WORTH_CHECKING\"}}}";
        var stored =
                SavedAnalysis.create(userId, "Toyota Corolla 2019", payload, LATER)
                        .describe(
                                new SavedAnalysis.Summary(
                                        "Toyota",
                                        "Corolla",
                                        2019,
                                        new BigDecimal("64900.00"),
                                        "PLN",
                                        118_500,
                                        "WORTH_CHECKING",
                                        78))
                        .from("https://www.otomoto.pl/oferta/toyota-corolla-ID6HG6ZH");

        Long id = repository.saveAndFlush(stored).getId();
        entityManager.clear();

        var read = repository.findById(id).orElseThrow();
        assertThat(read.getUserId()).isEqualTo(userId);
        assertThat(read.getTitle()).isEqualTo("Toyota Corolla 2019");
        assertThat(read.getNote()).isNull();
        assertThat(read.getSourceUrl())
                .isEqualTo("https://www.otomoto.pl/oferta/toyota-corolla-ID6HG6ZH");
        assertThat(read.getMake()).isEqualTo("Toyota");
        assertThat(read.getModel()).isEqualTo("Corolla");
        assertThat(read.getProductionYear()).isEqualTo(2019);
        assertThat(read.getPriceAmount()).isEqualByComparingTo("64900.00");
        assertThat(read.getPriceCurrency()).isEqualTo("PLN");
        assertThat(read.getMileageKm()).isEqualTo(118_500);
        assertThat(read.getVerdictCode()).isEqualTo("WORTH_CHECKING");
        assertThat(read.getOverallScore()).isEqualTo(78);
        assertThat(read.getPayload()).isEqualTo(payload);
        assertThat(read.getCreatedAt()).isEqualTo(LATER);
        assertThat(read.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void aPayloadLongerThanAVarcharSurvives() {
        // The column is TEXT, not VARCHAR(255) — a real AnalysisResponse with equipment, risk flags,
        // a CEPiK panel and a market range runs to several kilobytes.
        Long userId = owner("long-payload@example.pl");
        String payload = "{\"filler\":\"" + "x".repeat(40_000) + "\"}";

        Long id =
                repository
                        .saveAndFlush(SavedAnalysis.create(userId, "Duże", payload, LATER))
                        .getId();
        entityManager.clear();

        assertThat(repository.findById(id).orElseThrow().getPayload()).hasSize(payload.length());
    }

    @Test
    void listsOneUsersSavedAnalysesNewestFirst() {
        Long userId = owner("list@example.pl");
        repository.save(SavedAnalysis.create(userId, "Starszy", "{}", EARLIER));
        repository.save(SavedAnalysis.create(userId, "Nowszy", "{}", LATER));
        repository.flush();

        var listed = repository.findByUserIdOrderByCreatedAtDesc(userId);

        assertThat(listed).extracting(SavedAnalysis::getTitle).containsExactly("Nowszy", "Starszy");
    }

    @Test
    void listsNothingForAUserWhoHasSavedNothing() {
        assertThat(repository.findByUserIdOrderByCreatedAtDesc(owner("empty@example.pl"))).isEmpty();
    }

    @Test
    void doesNotHandAnAnalysisToAUserWhoDoesNotOwnIt() {
        // Ownership is part of the lookup, so a stranger's request is indistinguishable from a
        // request for a row that does not exist. This is the assertion that keeps FR-011 and FR-012
        // from becoming a way to read and delete other people's analyses by guessing an id.
        Long mine = owner("mine@example.pl");
        Long stranger = owner("stranger@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(mine, "Moje", "{}", LATER)).getId();

        assertThat(repository.findByIdAndUserId(id, mine)).isPresent();
        assertThat(repository.findByIdAndUserId(id, stranger)).isEmpty();
    }

    @Test
    void anEditIsPersisted() {
        Long userId = owner("edit@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(userId, "Przed", "{}", EARLIER)).getId();

        repository.findByIdAndUserId(id, userId).orElseThrow().edit("Po", "Notatka", LATER);
        repository.flush();
        entityManager.clear();

        var read = repository.findById(id).orElseThrow();
        assertThat(read.getTitle()).isEqualTo("Po");
        assertThat(read.getNote()).isEqualTo("Notatka");
        assertThat(read.getUpdatedAt()).isEqualTo(LATER);
        assertThat(read.getCreatedAt()).isEqualTo(EARLIER);
    }

    @Test
    void deletingASavedAnalysisLeavesTheAccountAlone() {
        Long userId = owner("delete@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(userId, "Do usunięcia", "{}", LATER)).getId();

        repository.delete(repository.findByIdAndUserId(id, userId).orElseThrow());
        repository.flush();

        assertThat(repository.findById(id)).isEmpty();
        assertThat(users.findById(userId)).isPresent();
    }

    @Test
    void theOwnerScopedDeleteRemovesTheRowAndSaysSo() {
        Long userId = owner("scoped-delete@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(userId, "Moje", "{}", LATER)).getId();

        assertThat(repository.deleteByIdAndUserId(id, userId)).isEqualTo(1L);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void theOwnerScopedDeleteRefusesAStrangersId() {
        // The counterpart to doesNotHandAnAnalysisToAUserWhoDoesNotOwnIt, for the one operation that
        // cannot be a read followed by a check: JPA's own deleteById takes an id with no owner, so
        // DELETE /api/saved-analyses/{id} would otherwise remove a stranger's row on a guessed number.
        Long mine = owner("keep-mine@example.pl");
        Long stranger = owner("not-yours@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(mine, "Moje", "{}", LATER)).getId();

        assertThat(repository.deleteByIdAndUserId(id, stranger)).isZero();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    void deletingAnAccountTakesItsSavedAnalysesWithIt() {
        // ON DELETE CASCADE in the schema, not an orphanRemoval Hibernate would have to be asked to
        // apply — so it also holds for a row deleted by hand or by a future admin path.
        Long userId = owner("cascade@example.pl");
        Long id = repository.saveAndFlush(SavedAnalysis.create(userId, "Znikające", "{}", LATER)).getId();

        users.deleteById(userId);
        users.flush();
        entityManager.clear();

        assertThat(repository.findById(id)).isEmpty();
    }
}
