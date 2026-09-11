package com.example.autoskaner_ai.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Callers must pass an address already through {@link UserAccount#normaliseEmail}; the column is
     * matched byte for byte.
     */
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
