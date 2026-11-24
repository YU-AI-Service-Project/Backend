package dev.wattup.backend.collection;

import java.util.Objects;

record KecoCollectionSettings(String serviceKey, String region, int pageSize, int dailyLimit) {

    KecoCollectionSettings {
        if (Objects.requireNonNull(serviceKey).isBlank()) {
            throw new IllegalArgumentException("WATTUP_KECO_SERVICE_KEY is required");
        }
        if (!Objects.requireNonNull(region).matches("[0-9]{2}")) {
            throw new IllegalArgumentException("WATTUP_KECO_REGION must be a two-digit region code");
        }
        if (pageSize < 10 || pageSize > 9999 || dailyLimit < 1) {
            throw new IllegalArgumentException("Invalid KECO page size or daily request limit");
        }
    }

    @Override
    public String toString() {
        return "KecoCollectionSettings[region=%s, pageSize=%d, dailyLimit=%d]"
                .formatted(region, pageSize, dailyLimit);
    }
}
