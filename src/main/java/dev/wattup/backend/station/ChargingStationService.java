package dev.wattup.backend.station;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.wattup.backend.station.StationViews.*;

@Service
@Transactional(readOnly = true, timeout = 4)
class ChargingStationService {
    private static final Duration STATUS_MAX_AGE = Duration.ofMinutes(10);
    private final ChargingStationRepository stations;

    ChargingStationService(ChargingStationRepository stations) {
        this.stations = stations;
    }

    SearchResult search(StationSearch search) {
        Instant freshAfter = freshAfter();
        var rows = stations.search(search, freshAfter, false);
        var items = rows.stream().map(row -> new StationSummary(row.stationId(), row.name(), row.address(),
                row.latitude(), row.longitude(), Math.round(row.distanceM()), row.status().current(),
                row.status().freshness(freshAfter), row.status().asOf())).toList();
        var parameters = new SearchParameters(search.latitude(), search.longitude(), search.radiusM(),
                search.filter().profile(), "STRAIGHT_LINE");
        return new SearchResult(parameters, rows.isEmpty() ? 0 : rows.getFirst().totalMatches(), items);
    }

    RecommendationCandidates recommendationCandidates(StationSearch search) {
        Instant freshAfter = freshAfter();
        return new RecommendationCandidates(stations.search(search, freshAfter, true), freshAfter);
    }

    record RecommendationCandidates(List<ChargingStationRepository.Nearby> items, Instant freshAfter) {
    }

    StationDetails details(String id, ChargerFilter filter) {
        var station = requireStation(id);
        var chargers = stations.chargers(station.stationId(), filter);
        var status = summarize(filter.profile(), chargers);
        return new StationDetails(station.stationId(), station.name(), station.address(),
                new Location(station.latitude(), station.longitude()),
                new Operator(station.operatorName(), station.operatorPhone()), station.operatingHours(), station.feeInfo(),
                parking(station.parkingFreeCode()), station.usageRestrictions(), status.current(),
                status.freshness(freshAfter()), status.asOf(), chargers);
    }

    ChargerList chargers(String id, ChargerFilter filter) {
        String stationId = requireStation(id).stationId();
        var items = stations.chargers(stationId, filter);
        return new ChargerList(stationId, items, items.size());
    }

    StationStatus status(String id, ChargerFilter filter) {
        String stationId = requireStation(id).stationId();
        var status = summarize(filter.profile(), stations.chargers(stationId, filter));
        var freshness = status.freshness(freshAfter());
        String warning = switch (freshness) {
            case FRESH -> null;
            case STALE -> "정보 갱신이 지연되어 현재 상태를 확인할 필요가 있습니다.";
            case UNKNOWN -> "일부 또는 전체 충전기 상태를 확인할 수 없습니다.";
        };
        return new StationStatus(stationId, filter.profile(), status.current(), freshness, status.asOf(), warning);
    }

    private ChargingStationRepository.Station requireStation(String id) {
        if (!id.matches("KECO:[A-Za-z0-9_-]{1,80}")) {
            throw StationApiError.binding("INVALID_REQUEST", "stationId", "INVALID_FORMAT");
        }
        return stations.station(id).orElseThrow(StationApiError::notFound);
    }

    private static StatusSummary summarize(ChargerProfile profile, List<Charger> chargers) {
        int available = 0;
        int charging = 0;
        int other = 0;
        int observed = 0;
        Instant oldest = null;
        for (Charger charger : chargers) {
            switch (charger.status()) {
                case "AVAILABLE" -> available++;
                case "CHARGING" -> charging++;
                case "UNAVAILABLE" -> other++;
            }
            if (charger.asOf() != null) {
                observed++;
                if (oldest == null || charger.asOf().isBefore(oldest)) {
                    oldest = charger.asOf();
                }
            }
        }
        int total = chargers.size();
        return new StatusSummary(new Current(profile, total, available, charging, other,
                total - available - charging - other), observed, oldest);
    }

    private static ParkingInfo parking(String code) {
        if ("Y".equals(code)) {
            return new ParkingInfo(true);
        }
        return "N".equals(code) ? new ParkingInfo(false) : null;
    }

    private static Instant freshAfter() {
        return Instant.now().minus(STATUS_MAX_AGE);
    }
}
