package com.example.autoskaner_ai.auth;

/**
 * One exception for every way a caller failed to prove who they are — an unknown email, a wrong
 * password, a rejected refresh token, an account deleted since the token was issued.
 *
 * <p>They are deliberately indistinguishable to the client. {@code GlobalExceptionHandler} maps all
 * of them to the same 401 with the same body, so the API cannot be used to find out which addresses
 * have accounts. The specific reason goes to the log, where it is useful and not readable by the
 * caller.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
