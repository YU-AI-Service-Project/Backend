package dev.wattup.backend.station;

import dev.wattup.backend.prediction.PredictionService;
import dev.wattup.backend.prediction.PredictionService.Prediction;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class RecommendationService {
    private static final String CURRENT_BASIS = "DISTANCE_CURRENT_STATUS_OUTPUT";
    private static final double PREDICTION_WEIGHT = 0.50;
    private static final double CURRENT_WEIGHT = 0.25;
    private static final double DISTANCE_WEIGHT = 0.20;
    private static final double OUTPUT_WEIGHT = 0.05;
    private static final double MAX_SCORED_OUTPUT_KW = 350;
    private final ChargingStationService stations;
    private final PredictionService predictions;

    RecommendationService(ChargingStationService stations, PredictionService predictions) {
        this.stations = stations;
        this.predictions = predictions;
    }

    Result recommend(StationSearch search, int limit) {
        var candidates = stations.recommendationCandidates(search);
        var profile = search.filter().profile();
        if (candidates.items().isEmpty()) {
            return new Result("ARRIVAL_ETA", profile, Instant.now(), CURRENT_BASIS, List.of(), 0, 0, List.of());
        }

        var forecastByStation = forecasts(candidates.items(), search);
        boolean useForecasts = candidates.items().stream().allMatch(candidate -> {
            var prediction = forecastByStation.get(candidate.stationId());
            return prediction != null && prediction.probability() != null;
        });
        var ranked = new ArrayList<Scored>();
        int withForecast = 0;
        boolean routeMissing = false;
        for (var candidate : candidates.items()) {
            var prediction = forecastByStation.getOrDefault(candidate.stationId(), Prediction.unavailable());
            if (prediction.probability() != null) {
                withForecast++;
            }
            routeMissing |= prediction.etaSeconds() == null;
            ranked.add(new Scored(candidate, prediction, score(candidate, useForecasts ? prediction.probability() : null,
                    search.radiusM())));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingDouble(entry -> entry.station.distanceM()).thenComparing(entry -> entry.station.stationId()));

        var items = new ArrayList<Item>();
        for (var entry : ranked.stream().limit(limit).toList()) {
            var station = entry.station;
            var prediction = entry.prediction;
            var counts = station.status().current();
            var current = new Current(counts.totalCount(), counts.availableCount(), station.freshAvailableCount(),
                    counts.chargingCount(), counts.otherCount(), counts.unknownCount(),
                    station.status().freshness(candidates.freshAfter()), station.status().asOf());
            items.add(new Item(items.size() + 1, station.stationId(), station.name(), Math.round(station.distanceM()),
                    prediction.roadDistanceM(), prediction.etaSeconds(), station.outputKw(), current, prediction,
                    entry.score, reason(station, useForecasts)));
        }
        var warnings = new ArrayList<String>();
        if (withForecast < ranked.size()) {
            warnings.add(withForecast == 0 ? "PREDICTION_UNAVAILABLE" : "PREDICTION_PARTIAL");
        }
        if (routeMissing) {
            warnings.add("ROUTE_RESULTS_INCOMPLETE");
        }
        String rankingBasis = useForecasts ? CURRENT_BASIS + "_PREDICTION" : CURRENT_BASIS;
        return new Result("ARRIVAL_ETA", profile, Instant.now(), rankingBasis, items,
                candidates.items().getFirst().totalMatches(), ranked.size(), warnings);
    }

    private Map<String, Prediction> forecasts(List<ChargingStationRepository.Nearby> candidates, StationSearch search) {
        var ids = candidates.stream().map(ChargingStationRepository.Nearby::stationId).toList();
        var inventories = candidates.stream().collect(Collectors.toMap(ChargingStationRepository.Nearby::stationId,
                ChargingStationRepository.Nearby::inventory));
        try {
            var result = predictions.predictForRecommendation(ids, search.filter().profile(), search.latitude(),
                    search.longitude(), search.filter().minOutputKw(), inventories);
            return result.items().stream().collect(Collectors.toMap(PredictionService.StationPrediction::stationId,
                    PredictionService.StationPrediction::prediction));
        } catch (DataAccessException | TransactionException e) {
            return Map.of();
        }
    }

    private static double score(ChargingStationRepository.Nearby station, Double probability, int radiusM) {
        double current = station.freshAvailableCount() / (double) station.status().current().totalCount();
        double distance = Math.max(0, 1 - station.distanceM() / radiusM);
        double output = station.outputKw() == null ? 0
                : Math.min(1, station.outputKw().doubleValue() / MAX_SCORED_OUTPUT_KW);
        double base = CURRENT_WEIGHT * current + DISTANCE_WEIGHT * distance + OUTPUT_WEIGHT * output;
        return probability == null ? base / (CURRENT_WEIGHT + DISTANCE_WEIGHT + OUTPUT_WEIGHT)
                : base + probability * PREDICTION_WEIGHT;
    }

    private static String reason(ChargingStationRepository.Nearby station, boolean useForecasts) {
        if (useForecasts) {
            return "도착 예측 확률과 최근 확인된 가용 대수, 직선거리, 충전 출력에 따라 추천했습니다.";
        }
        return station.freshAvailableCount() > 0
                ? "최근 가용 상태가 확인된 충전기와 직선거리, 충전 출력에 따라 추천했습니다."
                : "최근 가용이 확인되지 않아 직선거리와 충전 출력을 기준으로 추천했습니다.";
    }

    record Result(String mode, ChargerProfile profile, Instant generatedAt, String rankingBasis, List<Item> items,
                  long totalCandidates, int evaluatedCandidates, List<String> warnings) {
    }

    record Item(int rank, String stationId, String name, long straightDistanceM, Integer roadDistanceM,
                Integer etaSeconds, BigDecimal outputKw, Current current, Prediction prediction, double score,
                String reason) {
    }

    record Current(int totalCount, int availableCount, int freshAvailableCount, int chargingCount, int otherCount,
                   int unknownCount, StationViews.Freshness freshness, Instant asOf) {
    }

    private record Scored(ChargingStationRepository.Nearby station, Prediction prediction, double score) {
    }
}
