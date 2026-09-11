package com.example.autoskaner_ai.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email: podaj adres e-mail")
                @Email(message = "email: podaj poprawny adres e-mail")
                @Size(max = 255, message = "email: adres jest za długi")
                String email,
        /*
         * The upper bound is not cosmetic. BCrypt hashes at most the first 72 bytes of its input,
         * so without it "<72 chars>a" and "<72 chars>b" would both open the same account — the user
         * would have set a password longer than the one that actually guards it.
         */
        @NotBlank(message = "password: podaj hasło")
                @Size(min = 8, max = 72, message = "password: hasło musi mieć od 8 do 72 znaków")
                String password) {}
