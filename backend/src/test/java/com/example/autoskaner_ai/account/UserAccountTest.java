package com.example.autoskaner_ai.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The email normalisation, without a database. The unique index in {@code V1__init.sql} is on the
 * stored bytes, so this method is the whole of the case-insensitivity guarantee — see
 * {@link UserAccountRepositoryTest} for the constraint it cooperates with.
 */
class UserAccountTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
        "Foo@Example.COM, foo@example.com",
        "'  spaced@example.com  ', spaced@example.com",
        "ALREADY@LOWER.PL, already@lower.pl",
        "already@lower.pl, already@lower.pl",
    })
    void loweringAndTrimmingIsWhatMakesTwoSpellingsOneAccount(String typed, String stored) {
        assertThat(UserAccount.create(typed, "hash", NOW).getEmail()).isEqualTo(stored);
    }

    @Test
    void aNullEmailStaysNullRatherThanThrowing() {
        // The NOT NULL column is what rejects it, and it does so with a message naming the column.
        // Throwing here would move that failure to a place the registration endpoint has to
        // translate for no gain.
        assertThat(UserAccount.normaliseEmail(null)).isNull();
    }

    @Test
    void thePasswordHashIsStoredVerbatim() {
        // Only the email is normalised. A hash is bytes; trimming or lower-casing one silently
        // breaks every subsequent login.
        String bcrypt = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

        assertThat(UserAccount.create("a@b.pl", bcrypt, NOW).getPasswordHash()).isEqualTo(bcrypt);
    }
}
