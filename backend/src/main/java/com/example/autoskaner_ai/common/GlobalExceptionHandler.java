package com.example.autoskaner_ai.common;

import com.example.autoskaner_ai.analysis.llm.LlmCallException;
import com.example.autoskaner_ai.analysis.llm.LlmResponseSchemaException;
import com.example.autoskaner_ai.auth.EmailAlreadyRegisteredException;
import com.example.autoskaner_ai.auth.InvalidCredentialsException;
import com.example.autoskaner_ai.saved.SavedAnalysisNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Stream;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = Stream.concat(
                ex.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage),
                ex.getBindingResult().getGlobalErrors().stream().map(ObjectError::getDefaultMessage)
        ).toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, "Błąd walidacji", messages));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "Nieprawidłowe dane wejściowe", List.of("Nieprawidłowy JSON")));
    }

    /**
     * Every failure to prove identity — unknown email, wrong password, rejected refresh token,
     * account deleted since the token was minted — answers with this one body. The distinction is
     * logged and never returned: an API that tells a caller which half was wrong is an account
     * enumeration endpoint. See {@code InvalidCredentialsException}.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.info("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(401, "Nieprawidłowe dane logowania",
                        List.of("Nieprawidłowy e-mail lub hasło.")));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailTaken(EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Konto już istnieje",
                        List.of("Konto z tym adresem e-mail jest już zarejestrowane.")));
    }

    /**
     * A saved analysis that is missing and one that belongs to somebody else answer identically,
     * because the lookup that raised this could not tell them apart — see
     * {@code SavedAnalysisNotFoundException}. Not logged at warn: a 404 here is an ordinary outcome
     * of a stale tab or a re-issued delete, not a fault.
     */
    @ExceptionHandler(SavedAnalysisNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSavedAnalysisNotFound(SavedAnalysisNotFoundException ex) {
        log.debug("Saved analysis lookup missed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "Nie znaleziono analizy",
                        List.of("Ta analiza nie istnieje lub nie należy do Ciebie.")));
    }

    @ExceptionHandler(LlmCallException.class)
    public ResponseEntity<ErrorResponse> handleLlmCall(LlmCallException ex) {
        log.warn("LLM call failed reason={}", ex.reason(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(502, errorFor(ex.reason()), List.of(adviceFor(ex.reason()))));
    }

    /**
     * The {@code error} headline per cause. Three distinct strings inside the locked four-field
     * envelope — a rejected key, an unusable provider response and an exhausted fallback chain are
     * three different situations for the user and used to render as one.
     */
    private static String errorFor(LlmCallException.Reason reason) {
        return switch (reason) {
            case REJECTED_CREDENTIALS -> "Usługa LLM odrzuciła dane dostępowe";
            case UNUSABLE_PROVIDER_RESPONSE -> "Usługa LLM zwróciła nieczytelną odpowiedź";
            case ALL_CANDIDATES_EXHAUSTED -> "Wszystkie modele LLM są niedostępne";
            case UNCLASSIFIED -> "Błąd usługi LLM";
        };
    }

    /** What the user can actually do — different per cause, because retrying only helps for two. */
    private static String adviceFor(LlmCallException.Reason reason) {
        return switch (reason) {
            case REJECTED_CREDENTIALS -> "Klucz dostępowy usługi LLM został odrzucony. "
                    + "Ponowna próba nie pomoże — powiadom administratora aplikacji.";
            case UNUSABLE_PROVIDER_RESPONSE -> "Model odpowiedział, ale jego odpowiedź nie zawierała "
                    + "analizy. Spróbuj ponownie za chwilę.";
            case ALL_CANDIDATES_EXHAUSTED -> "Żaden z dostępnych modeli nie odpowiedział — "
                    + "prawdopodobnie są chwilowo przeciążone. Spróbuj ponownie za kilka minut.";
            case UNCLASSIFIED -> "Wystąpił błąd usługi LLM. Spróbuj ponownie.";
        };
    }

    @ExceptionHandler(LlmResponseSchemaException.class)
    public ResponseEntity<ErrorResponse> handleLlmSchema(LlmResponseSchemaException ex) {
        log.warn("LLM schema violation at {}: {}", ex.getFieldPath(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(502, "Niepoprawny format odpowiedzi LLM",
                        List.of(ex.getFieldPath())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "Błąd serwera", List.of("Wystąpił nieoczekiwany błąd")));
    }
}
