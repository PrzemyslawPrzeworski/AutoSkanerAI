package com.example.autoskaner_ai.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accounts table against a real engine — H2 in PostgreSQL compatibility mode, the same
 * datasource {@code ./mvnw test} and {@code spring-boot:run} use.
 *
 * <p>{@code @SpringBootTest} rather than {@code @DataJpaTest} deliberately: this repository's other
 * context tests use it, and a slice annotation would also replace the configured datasource URL with
 * a generated one — dropping {@code MODE=PostgreSQL} and so testing the migration against a dialect
 * nothing runs.
 */
@SpringBootTest
@ActiveProfiles("mock")
@Transactional
class UserAccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    @Autowired
    private UserAccountRepository repository;

    @Test
    void storesAnAccountAndFindsItByEmail() {
        repository.save(UserAccount.create("owner@example.pl", "hash", NOW));

        var found = repository.findByEmail("owner@example.pl");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void aSecondAccountWithTheSameEmailIsRejectedByTheDatabase() {
        // The uniqueness lives in ux_users_email, not in a lookup the registration endpoint runs
        // first: two simultaneous registrations both see "no such user" and only the constraint
        // stops the second one.
        repository.saveAndFlush(UserAccount.create("dup@example.pl", "hash", NOW));

        assertThatThrownBy(
                        () ->
                                repository.saveAndFlush(
                                        UserAccount.create("dup@example.pl", "other-hash", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDifferentlyCasedEmailCollidesWithTheStoredOne() {
        // Normalisation and the constraint have to agree, and only a round trip shows that they do:
        // if UserAccount.create stopped lower-casing, this would quietly become a second account.
        repository.saveAndFlush(UserAccount.create("case@example.pl", "hash", NOW));

        assertThatThrownBy(
                        () ->
                                repository.saveAndFlush(
                                        UserAccount.create("Case@Example.PL", "hash", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByEmailDistinguishesAStoredAccountFromAnUnknownOne() {
        repository.save(UserAccount.create("known@example.pl", "hash", NOW));

        assertThat(repository.existsByEmail("known@example.pl")).isTrue();
        assertThat(repository.existsByEmail("stranger@example.pl")).isFalse();
    }
}
