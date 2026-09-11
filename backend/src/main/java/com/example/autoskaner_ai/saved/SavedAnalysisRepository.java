package com.example.autoskaner_ai.saved;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedAnalysisRepository extends JpaRepository<SavedAnalysis, Long> {

    List<SavedAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Every read of a single saved analysis goes through this, never {@code findById}. The owner is
     * part of the lookup rather than a check after it, so a request for somebody else's id is
     * indistinguishable from a request for an id that does not exist — which is what it should look
     * like, and it cannot be forgotten at one of three call sites.
     */
    Optional<SavedAnalysis> findByIdAndUserId(Long id, Long userId);

    /**
     * The same rule as {@link #findByIdAndUserId}, for the one operation that cannot be expressed as
     * a read followed by a check: JPA's inherited {@code deleteById} takes an id alone, so a
     * controller reaching for it would delete somebody else's row on a guessed id. The return is the
     * number of rows removed — 0 means "not yours or not there", which is the same answer for the
     * same reason.
     */
    long deleteByIdAndUserId(Long id, Long userId);
}
