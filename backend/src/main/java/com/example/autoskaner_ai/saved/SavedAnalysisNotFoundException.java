package com.example.autoskaner_ai.saved;

/**
 * Raised when a lookup keyed on {@code (id, userId)} found nothing.
 *
 * <p>It deliberately does not distinguish "no such analysis" from "not yours". Those are the same
 * answer on purpose: telling a caller that id 41 exists but belongs to somebody else turns a
 * sequential id into an oracle for how many analyses other people have saved.
 */
public class SavedAnalysisNotFoundException extends RuntimeException {

    public SavedAnalysisNotFoundException(long id) {
        super("no saved analysis " + id + " for this user");
    }
}
