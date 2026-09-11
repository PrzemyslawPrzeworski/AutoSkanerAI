package com.example.autoskaner_ai.auth;

import com.example.autoskaner_ai.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps 401 and 403 inside the API's one error envelope.
 *
 * <p>Both are produced by the security filter chain, which sits in front of the dispatcher — so
 * {@code GlobalExceptionHandler} never sees them and the {@code @RestControllerAdvice} that owns
 * every other error shape cannot help here. Without this class the two statuses a client meets most
 * often would be the only two answering in a different format: Spring's bearer-token entry point
 * sends an empty body with a {@code WWW-Authenticate} header, and the access-denied default sends a
 * servlet container error page.
 *
 * <p>One class implements both interfaces so the shape is defined once; it is injected under both
 * types.
 */
@Component
class AuthErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    AuthErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Wymagane uwierzytelnienie",
                "Zaloguj się, aby korzystać z tej funkcji.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        write(
                response,
                HttpStatus.FORBIDDEN,
                "Brak uprawnień",
                "Twoje konto nie ma dostępu do tego zasobu.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(status.value(), error, List.of(message)));
    }
}
