package dev.wattup.backend.collection;

final class KecoException extends RuntimeException {
    private final boolean retryable;

    KecoException(String code, boolean retryable) {
        super(code);
        this.retryable = retryable;
    }

    String code() {
        return getMessage();
    }

    boolean retryable() {
        return retryable;
    }
}
