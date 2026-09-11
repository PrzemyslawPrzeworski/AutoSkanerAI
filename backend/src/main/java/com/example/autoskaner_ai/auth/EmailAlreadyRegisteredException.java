package com.example.autoskaner_ai.auth;

/**
 * Registration only. Unlike {@link InvalidCredentialsException} this one does tell the caller that
 * an account exists — a registration form that answers "maybe" is unusable, and the address is
 * already known to whoever typed it in the overwhelming majority of cases.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String message) {
        super(message);
    }
}
