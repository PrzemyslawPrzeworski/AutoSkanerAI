package com.example.autoskaner_ai.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * A registered account. Named {@code UserAccount} rather than {@code User} because F-03 introduces
 * Spring Security, whose own {@code User} is imported in the same files.
 *
 * <p>The table is {@code users}: {@code user} is a reserved word in PostgreSQL.
 */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserAccount() {
        // for JPA
    }

    /**
     * The only way to build an account, so that no caller can skip the email normalisation. The
     * unique index is on the stored bytes, so {@code Foo@Example.com} and {@code foo@example.com}
     * would otherwise be two accounts that a user believes are one — and the login for the second
     * would fail against the password of the first.
     */
    public static UserAccount create(String email, String passwordHash, Instant createdAt) {
        UserAccount account = new UserAccount();
        account.email = normaliseEmail(email);
        account.passwordHash = passwordHash;
        account.createdAt = createdAt;
        return account;
    }

    /** Lower-cases and trims. Callers looking an account up must normalise the same way. */
    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
