package dev.wattup.backend.collection;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

record KecoReading(
        String stationId,
        String chargerId,
        String rawStatus,
        LocalDateTime providerUpdatedAt,
        LocalDateTime chargeStartedAt,
        JsonNode attributes) {

    ChargerStatus status() {
        return ChargerStatus.fromProvider(rawStatus);
    }

    String internalStationId() {
        return "KECO:" + stationId;
    }
}
