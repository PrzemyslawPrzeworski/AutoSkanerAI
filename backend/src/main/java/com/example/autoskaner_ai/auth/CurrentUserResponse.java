package com.example.autoskaner_ai.auth;

/** What {@code GET /api/auth/me} answers: who the presented token says you are. */
public record CurrentUserResponse(long userId, String email) {}
