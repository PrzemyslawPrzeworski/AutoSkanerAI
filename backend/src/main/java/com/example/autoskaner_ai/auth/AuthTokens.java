package com.example.autoskaner_ai.auth;

/** A freshly minted pair. {@code expiresInSeconds} describes the access token only. */
public record AuthTokens(String accessToken, String refreshToken, long expiresInSeconds) {}
