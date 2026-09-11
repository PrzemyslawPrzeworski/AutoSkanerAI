package com.example.autoskaner_ai.auth;

import com.example.autoskaner_ai.account.UserAccount;
import com.example.autoskaner_ai.account.UserAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * A hash of a value nobody knows, compared against when the email is unknown so that both
     * failures cost one BCrypt. Without it, "no such account" answers in microseconds and "wrong
     * password" in ~100 ms, which is a usable oracle for whether an address is registered — and it
     * would undo the single-message policy in {@link InvalidCredentialsException}. Computed once at
     * startup rather than committed, so no hash of a known string ships in a public repository.
     */
    private final String unusedHashForTimingParity;

    AuthService(
            UserAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.unusedHashForTimingParity = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(String email, String rawPassword) {
        String normalised = UserAccount.normaliseEmail(email);
        if (accounts.existsByEmail(normalised)) {
            throw new EmailAlreadyRegisteredException("account already exists");
        }
        UserAccount account =
                UserAccount.create(normalised, passwordEncoder.encode(rawPassword), Instant.now());
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            // The existsByEmail check above is not a lock: two simultaneous registrations of the
            // same address both pass it and the unique index refuses the second. Without this the
            // loser would get a 500 for a situation the API already has a word for.
            throw new EmailAlreadyRegisteredException("account already exists");
        }
        log.info("Registered account id={}", account.getId());
        return tokensFor(account);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String email, String rawPassword) {
        // Normalising on the way in is what makes Foo@Example.com the same account as
        // foo@example.com; the column is matched byte for byte. See UserAccount.normaliseEmail.
        UserAccount account =
                accounts.findByEmail(UserAccount.normaliseEmail(email)).orElse(null);
        if (account == null) {
            passwordEncoder.matches(rawPassword, unusedHashForTimingParity);
            throw new InvalidCredentialsException("no account for the supplied email", null);
        }
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            throw new InvalidCredentialsException("wrong password for account " + account.getId(),
                    null);
        }
        return tokensFor(account);
    }

    /**
     * The account is re-read rather than trusted from the token, so a refresh token outlives its
     * account by at most one refresh: a deleted account cannot keep minting access tokens for the
     * remaining fortnight of its refresh window.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        long userId = tokenService.userIdFromRefreshToken(refreshToken);
        UserAccount account =
                accounts.findById(userId)
                        .orElseThrow(
                                () ->
                                        new InvalidCredentialsException(
                                                "refresh token names account " + userId
                                                        + ", which no longer exists",
                                                null));
        return tokensFor(account);
    }

    /**
     * The one place a persisted account becomes a token pair. {@code TokenService} takes an id and an
     * email rather than the entity, so it stays testable without a database — this method is the
     * seam, and it is also the only place that can observe an unsaved account: {@code getId()} is
     * null until the insert, and {@code String.valueOf(null)} would mint a token whose subject is the
     * literal {@code "null"}.
     */
    private AuthResponse tokensFor(UserAccount account) {
        Long id = account.getId();
        if (id == null) {
            throw new IllegalStateException("cannot mint tokens for an unsaved account");
        }
        return AuthResponse.of(account.getEmail(), tokenService.mintFor(id, account.getEmail()));
    }
}
