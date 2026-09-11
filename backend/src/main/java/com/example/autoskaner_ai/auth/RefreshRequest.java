package com.example.autoskaner_ai.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "refreshToken: brak tokenu odświeżania") String refreshToken) {}
