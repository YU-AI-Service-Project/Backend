package dev.wattup.backend.station;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

final class StationViews {
    private StationViews() {
    }

    enum Freshness { FRESH, STALE, UNKNOWN }

    record Current(ChargerProfile profile, int totalCount, int availableCount, int chargingCount,
                   int otherCount, int unknownCount) {
    }

    record StatusSummary(Current current, int observedCount, Instant asOf) {
        Freshness freshness(Instant freshAfter) {
            if (asOf != null && !asOf.isAfter(freshAfter)) {
                return Freshness.STALE;
            }
            return asOf != null && observedCount == current.totalCount() ? Freshness.FRESH : Freshness.UNKNOWN;
        }
    }

    record SearchParameters(double latitude, double longitude, int radiusM, ChargerProfile profile,
                            String distanceType) {
    }

    record SearchResult(SearchParameters search, long total, List<StationSummary> items) {
    }

    record StationSummary(String stationId, String name, String address, BigDecimal latitude, BigDecimal longitude,
                          long straightDistanceM, Current current, Freshness freshness, Instant asOf) {
    }

    record Location(BigDecimal latitude, BigDecimal longitude) {
    }

    record Operator(String name, String phone) {
    }

    record ParkingInfo(boolean free) {
    }

    record Charger(String chargerId, String typeCode, String connector, BigDecimal outputKw,
                   String status, String rawStatusCode, Instant asOf) {
    }

    record ChargerList(String stationId, List<Charger> items, int total) {
    }

    record StationDetails(String stationId, String name, String address, Location location, Operator operator,
                          String operatingHours, String feeInfo, ParkingInfo parkingInfo, String usageRestrictions,
                          Current current, Freshness freshness, Instant asOf, List<Charger> chargers) {
    }

    record StationStatus(String stationId, ChargerProfile profile, Current current, Freshness freshness,
                         Instant asOf, String warning) {
    }

    static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
