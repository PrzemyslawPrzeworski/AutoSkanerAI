package com.example.autoskaner_ai.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * No {@code @Email} and no length bounds, unlike {@link RegisterRequest}: a login attempt with a
 * malformed address is a failed login, not a validation error. Answering 400 here would let a caller
 * distinguish "this address could not exist" from "this address has no account", and would report
 * two different statuses for the same user mistake.
 */
public record LoginRequest(
        @NotBlank(message = "email: podaj adres e-mail") String email,
        @NotBlank(message = "password: podaj hasło") String password) {}
