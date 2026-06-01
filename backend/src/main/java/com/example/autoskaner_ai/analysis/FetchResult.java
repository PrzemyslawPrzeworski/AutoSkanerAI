package com.example.autoskaner_ai.analysis;

public record FetchResult(String status, String reason, String text) {

    public static FetchResult ok(String text) {
        return new FetchResult("ok", null, text);
    }

    public static FetchResult failed(String reason) {
        return new FetchResult("url_failed", reason, null);
    }

    public boolean isOk() {
        return "ok".equals(status);
    }
}
