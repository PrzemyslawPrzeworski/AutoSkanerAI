package com.example.autoskaner_ai.analysis.llm;

public class LlmResponseSchemaException extends RuntimeException {

    private final String fieldPath;

    public LlmResponseSchemaException(String message, String fieldPath) {
        super(message);
        this.fieldPath = fieldPath;
    }

    public String getFieldPath() {
        return fieldPath;
    }
}
