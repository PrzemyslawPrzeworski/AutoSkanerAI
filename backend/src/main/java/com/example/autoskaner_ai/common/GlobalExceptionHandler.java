package com.example.autoskaner_ai.common;

import com.example.autoskaner_ai.analysis.llm.LlmCallException;
import com.example.autoskaner_ai.analysis.llm.LlmResponseSchemaException;
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

    @ExceptionHandler(LlmCallException.class)
    public ResponseEntity<ErrorResponse> handleLlmCall(LlmCallException ex) {
        String causeMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(502, "Błąd usługi LLM", List.of(causeMsg)));
    }

    @ExceptionHandler(LlmResponseSchemaException.class)
    public ResponseEntity<ErrorResponse> handleLlmSchema(LlmResponseSchemaException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(502, "Niepoprawny format odpowiedzi LLM",
                        List.of(ex.getFieldPath() + ": " + ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "Błąd serwera", List.of("Wystąpił nieoczekiwany błąd")));
    }
}
