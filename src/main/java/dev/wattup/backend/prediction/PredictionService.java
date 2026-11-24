package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class PredictionService {
    private static final String FEATURE_VERSION = EtaBatchClient.FEATURE_VERSION;
    private final EtaHistoryRepository history;
    private final KakaoRouteClient routes;
    private final EtaBatchClient ai;
    private final Clock clock;
    private final Duration routeBudget;
    private final Semaphore activeBatches = new Semaphore(4);
    private final Semaphore activeRoutes = new Semaphore(8, true);

    PredictionService(EtaHistoryRepository history, KakaoRouteClient routes, EtaBatchClient ai, Clock clock) {
        this(history, routes, ai, clock, Duration.ofSeconds(9));
    }

    PredictionService(EtaHistoryRepository history, KakaoRouteClient routes, EtaBatchClient ai, Clock clock,
                      Duration routeBudget) {
        this.history = history;
        this.routes = routes;
        this.ai = ai;
        this.clock = clock;
        this.routeBudget = routeBudget;
    }

    Batch predict(List<String> stationIds, ChargerProfile profile, PredictionController.Origin origin) {
        return predict(stationIds, profile, origin, null, null);
    }

    public Batch predictForRecommendation(List<String> stationIds, ChargerProfile profile, double latitude,
                                          double longitude, BigDecimal minOutputKw,
                                          Map<String, Map<String, Long>> inventories) {
        return predict(stationIds, profile, new PredictionController.Origin(latitude, longitude), minOutputKw,
                inventories);
    }

    private Batch predict(List<String> stationIds, ChargerProfile profile, PredictionController.Origin origin,
                          BigDecimal minOutputKw, Map<String, Map<String, Long>> inventories) {
        String requestId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        if (!activeBatches.tryAcquire()) {
            return new Batch(requestId, stationIds.stream().map(id -> new StationPrediction(id, profile, "ARRIVAL_ETA",
                    result("MODEL_UNAVAILABLE", null, startedAt, null, null))).toList());
        }
        try {
            Instant asOf = Instant.ofEpochSecond(Math.floorDiv(startedAt.getEpochSecond(), EtaFeatures.GRID_SECONDS)
                    * EtaFeatures.GRID_SECONDS);
            var candidates = history.load(stationIds, profile, asOf);
            var resolvedRoutes = loadRoutes(candidates, origin, asOf, inventories != null);
            Instant requestAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            var prepared = new HashMap<String, Prediction>();
            var items = new ArrayList<EtaBatchClient.Item>();
            for (String stationId : stationIds) {
                var candidate = candidates.get(stationId);
                var route = resolvedRoutes.get(stationId);
                if (route != null && Duration.between(route.generatedAt(), requestAt).compareTo(Duration.ofSeconds(30)) > 0) {
                    route = null;
                }
                String status = candidate != null && minOutputKw != null && !candidate.matchesOutput(minOutputKw)
                        ? "OUTPUT_FILTER_NOT_SUPPORTED" : unavailableReason(candidate, asOf);
                if (status == null && inventories != null && !candidate.inventory().equals(inventories.get(stationId))) {
                    status = "INVENTORY_CHANGED";
                }
                if (status == null && route == null) {
                    status = "ROUTE_UNAVAILABLE";
                }
                if (status != null) {
                    var timing = route == null ? null : ForecastTiming.calculate(asOf, requestAt, route.etaSeconds());
                    prepared.put(stationId, result(status, candidate == null ? null : asOf, requestAt, route, timing));
                    continue;
                }
                var timing = ForecastTiming.calculate(asOf, requestAt, route.etaSeconds());
                prepared.put(stationId, result(timing.status(), asOf, requestAt, route, timing));
                if ("OK".equals(timing.status())) {
                    items.add(new EtaBatchClient.Item(Integer.toString(items.size() + 1), stationId, profile,
                            asOf, route.etaSeconds(), route.generatedAt(), route.roadDistanceM(), candidate.features()));
                }
            }
            if (!items.isEmpty()) {
                var inferred = ai.predict(requestId, requestAt, items).orElseGet(Map::of);
                for (var item : items) {
                    var existing = prepared.get(item.stationId());
                    var result = inferred.get(item.requestItemId());
                    prepared.put(item.stationId(), existing.withInference(result));
                }
            }
            return new Batch(requestId, stationIds.stream().map(id ->
                    new StationPrediction(id, profile, "ARRIVAL_ETA", prepared.get(id))).toList());
        } finally {
            activeBatches.release();
        }
    }

    private static String unavailableReason(EtaHistoryRepository.Candidate candidate, Instant asOf) {
        if (candidate == null) {
            return "STATION_NOT_FOUND";
        }
        if (candidate.statusPollAt() != null && Duration.between(candidate.statusPollAt(), asOf)
                .compareTo(Duration.ofSeconds(450)) > 0) {
            return "STALE_FEATURES";
        }
        return candidate.features().isEmpty() ? "INPUT_INCOMPLETE" : null;
    }

    private Map<String, KakaoRouteClient.Route> loadRoutes(Map<String, EtaHistoryRepository.Candidate> candidates,
                                                          PredictionController.Origin origin, Instant asOf,
                                                          boolean includeRoutesWithoutFeatures) {
        var result = new HashMap<String, KakaoRouteClient.Route>();
        List<Callable<Map.Entry<String, Optional<KakaoRouteClient.Route>>>> lookups = new ArrayList<>();
        candidates.forEach((stationId, candidate) -> {
            if ((includeRoutesWithoutFeatures || unavailableReason(candidate, asOf) == null) && candidate.latitude() != null
                    && candidate.longitude() != null) {
                lookups.add(() -> Map.entry(stationId, findRoute(origin, candidate)));
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var task : executor.invokeAll(lookups, routeBudget.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    var route = task.get();
                    route.getValue().ifPresent(value -> result.put(route.getKey(), value));
                } catch (CancellationException ignored) {
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Route lookup failed to execute", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private Optional<KakaoRouteClient.Route> findRoute(PredictionController.Origin origin,
                                                       EtaHistoryRepository.Candidate candidate)
            throws InterruptedException {
        activeRoutes.acquire();
        try {
            return routes.find(origin.latitude(), origin.longitude(), candidate.latitude(), candidate.longitude());
        } finally {
            activeRoutes.release();
        }
    }

    private static Prediction result(String status, Instant asOf, Instant requestAt, KakaoRouteClient.Route route,
                                     ForecastTiming timing) {
        return new Prediction(status, null, asOf, requestAt, route == null ? null : route.requestedAt(),
                route == null ? null : route.generatedAt(), route == null ? null : route.etaSeconds(),
                route == null ? null : route.roadDistanceM(), route == null ? null : "KAKAO_DRIVING_DIRECTIONS",
                timing == null ? null : timing.requestedArrivalAt(), timing == null ? null : timing.forecastAt(),
                timing == null ? null : timing.horizonMinutes(), timing == null ? null : timing.forecastRoundingSeconds(),
                timing == null ? null : timing.featureAgeSeconds(), null, FEATURE_VERSION);
    }

    public record Batch(String requestId, List<StationPrediction> items) {
    }

    public record StationPrediction(String stationId, ChargerProfile profile, String mode, Prediction prediction) {
    }

    public record Prediction(String status, Double probability, Instant asOf, Instant requestAt, Instant routeRequestedAt,
                      Instant etaGeneratedAt, Integer etaSeconds, Integer roadDistanceM, String etaProvider,
                      Instant requestedArrivalAt, Instant forecastAt, Integer horizonMinutes, Double forecastRoundingSeconds,
                      Double featureAgeSeconds, String modelVersion, String featureVersion) {
        public static Prediction unavailable() {
            return result("MODEL_UNAVAILABLE", null, null, null, null);
        }

        Prediction withInference(EtaBatchClient.Result result) {
            return new Prediction(result == null ? "MODEL_UNAVAILABLE" : result.status(),
                    result == null ? null : result.probability(), asOf, requestAt, routeRequestedAt, etaGeneratedAt,
                    etaSeconds, roadDistanceM, etaProvider, requestedArrivalAt, forecastAt, horizonMinutes,
                    forecastRoundingSeconds, featureAgeSeconds, result == null ? null : result.modelVersion(), featureVersion);
        }
    }
}
