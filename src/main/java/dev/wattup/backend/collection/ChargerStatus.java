package dev.wattup.backend.collection;

enum ChargerStatus {
    AVAILABLE, CHARGING, UNAVAILABLE, UNKNOWN;

    static ChargerStatus fromProvider(String code) {
        return switch (code) {
            case "2" -> AVAILABLE;
            case "3" -> CHARGING;
            case "4", "5", "6" -> UNAVAILABLE;
            default -> UNKNOWN;
        };
    }
}
