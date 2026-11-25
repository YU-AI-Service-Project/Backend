package dev.wattup.backend.station;

import dev.wattup.backend.prediction.PredictionService;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
class StationAnalyticsController {
    private static final String TIME_ZONE = "Asia/Seoul";
    private static final String SUFFICIENT = "SUFFICIENT";
    private static final String INSUFFICIENT = "INSUFFICIENT_HISTORY";
    private static final String OBSERVATION_BASIS = "CURRENT_PUBLIC_COHORT_FULL_SNAPSHOTS";
    private static final double HIGH_PROBABILITY_THRESHOLD = 0.75;
    private static final String EVALUATION_BASIS = "FULL_SNAPSHOT_WITHIN_FORECAST_GRID";
    private final ChargingStationRepository stations;
    private final ChargingStationService nearby;
    private final StationAnalyticsRepository observations;
    private final PredictionService predictions;
    private final PredictionQualityRepository quality;

    StationAnalyticsController(ChargingStationRepository stations, ChargingStationService nearby,
                               StationAnalyticsRepository observations, PredictionService predictions,
                               PredictionQualityRepository quality) {
        this.stations = stations;
        this.nearby = nearby;
        this.observations = observations;
        this.predictions = predictions;
        this.quality = quality;
    }

    @GetMapping("/api/charging-stations/{stationId}/patterns")
    PatternResult patterns(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile,
                           @RequestParam(defaultValue = "30") int periodDays) {
        var request = historyRequest(stationId, profile, periodDays);
        var samples = observations.patterns(request.station, request.profile, request.from, request.to);
        var observed = samples.isEmpty() ? null : new Period(
                samples.stream().map(StationAnalyticsRepository.Bucket::from).min(Comparator.naturalOrder()).orElseThrow(),
                samples.stream().map(StationAnalyticsRepository.Bucket::to).max(Comparator.naturalOrder()).orElseThrow());
        var counts = samples.stream().map(bucket -> new SampleCount(bucket.weekday(), bucket.hour(), bucket.count())).toList();
        var buckets = samples.stream().filter(bucket -> bucket.count() >= 3)
                .map(bucket -> new HourBucket(bucket.weekday(), bucket.hour(), bucket.meanAvailableRatio())).toList();
        return new PatternResult(request.station, request.profile, periodDays, TIME_ZONE, OBSERVATION_BASIS,
                observed, buckets, counts, buckets.isEmpty() ? INSUFFICIENT : SUFFICIENT);
    }

    @GetMapping("/api/charging-stations/{stationId}/turnover")
    TurnoverResult turnover(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile,
                            @RequestParam(defaultValue = "30") int periodDays) {
        var request = historyRequest(stationId, profile, periodDays);
        var observed = observations.turnover(request.station, request.profile, request.from, request.to);
        boolean sufficient = observed.hours() >= 24 && observed.days() >= 2;
        return new TurnoverResult(request.station, request.profile, periodDays, OBSERVATION_BASIS,
                observed.from() == null ? null : new Period(observed.from(), observed.to()), observed.hours(),
                observed.chargerHours(), observed.days(), sufficient ? observed.changes() : null,
                sufficient ? observed.chargingToAvailable() : null,
                sufficient ? observed.changes() / observed.chargerHours() : null, sufficient ? SUFFICIENT : INSUFFICIENT);
    }

    @GetMapping("/api/charging-stations/{stationId}/prediction-quality")
    QualityResult quality(@PathVariable String stationId, @RequestParam(defaultValue = "ALL") String profile,
                          @RequestParam(defaultValue = "30") int periodDays) {
        var request = historyRequest(stationId, profile, periodDays);
        var result = quality.evaluate(request.station, request.profile, request.from, request.to);
        boolean sufficient = result.sampleCount() >= 30 && result.dayCount() >= 3;
        var buckets = sufficient ? result.buckets().stream().filter(bucket -> bucket.sampleCount() >= 5)
                .map(bucket -> new ProbabilityBucket(bucket.index() / 5.0, (bucket.index() + 1) / 5.0,
                        bucket.sampleCount(), bucket.observedFraction())).toList() : List.<ProbabilityBucket>of();
        String status = result.sampleCount() == 0 ? "UNVERIFIED_MODEL"
                : sufficient ? SUFFICIENT : "INSUFFICIENT_EVALUATION";
        return new QualityResult(request.station, request.profile, new Period(request.from, request.to),
                status, result.modelVersion(), result.featureVersion(), EVALUATION_BASIS,
                result.sampleCount(), result.dayCount(), buckets);
    }

    @GetMapping("/api/areas/charging-availability")
    AreaResult area(@RequestParam double latitude, @RequestParam double longitude,
                   @RequestParam(defaultValue = "3000") int radiusM, @RequestParam(defaultValue = "ALL") String profile) {
        var filter = ChargerFilter.forSearch(profile, List.of(), null);
        var search = StationSearch.of(latitude, longitude, radiusM, 30, false, "DISTANCE", filter);
        var results = nearby.recommendationCandidates(search);
        var candidates = results.items();
        var fresh = candidates.stream().filter(candidate -> candidate.status().current().unknownCount() == 0
                && candidate.status().freshness(results.freshAfter()) == StationViews.Freshness.FRESH).toList();
        long available = fresh.stream().filter(candidate -> candidate.freshAvailableCount() > 0).count();
        long total = candidates.isEmpty() ? 0 : candidates.getFirst().totalMatches();
        String sufficiency = candidates.isEmpty() ? "NO_CANDIDATES" : fresh.isEmpty() ? "INSUFFICIENT_CURRENT_DATA"
                : fresh.size() == candidates.size() ? SUFFICIENT : "PARTIAL_CURRENT_DATA";
        var forecasts = areaForecasts(search, candidates);
        int high = (int) forecasts.stream().filter(probability -> probability >= HIGH_PROBABILITY_THRESHOLD).count();
        String predictionSufficiency = forecasts.isEmpty() ? "NOT_EVALUATED"
                : forecasts.size() == candidates.size() ? SUFFICIENT : "PARTIAL_PREDICTION_DATA";
        return new AreaResult(latitude, longitude, radiusM, filter.profile(), total, candidates.size(), fresh.size(),
                fresh.isEmpty() ? null : available, forecasts.size(), forecasts.isEmpty() ? null : high,
                HIGH_PROBABILITY_THRESHOLD, predictionSufficiency, sufficiency);
    }

    private List<Double> areaForecasts(StationSearch search, List<ChargingStationRepository.Nearby> candidates) {
        var ids = candidates.stream().map(ChargingStationRepository.Nearby::stationId).toList();
        var inventories = candidates.stream().collect(Collectors.toMap(ChargingStationRepository.Nearby::stationId,
                ChargingStationRepository.Nearby::inventory));
        try {
            return predictions.predictForArea(ids, search.filter().profile(), search.latitude(), search.longitude(),
                            inventories).items().stream().map(PredictionService.StationPrediction::prediction)
                    .filter(prediction -> "OK".equals(prediction.status()) && prediction.probability() != null)
                    .map(PredictionService.Prediction::probability).toList();
        } catch (DataAccessException | TransactionException e) {
            return List.of();
        }
    }

    private HistoryRequest historyRequest(String stationId, String profile, int periodDays) {
        if (periodDays < 1 || periodDays > 90) {
            throw StationApiError.invalid("INVALID_REQUEST", "periodDays");
        }
        var filter = ChargerFilter.forStation(profile, null, null);
        if (!stationId.matches("KECO:[A-Za-z0-9_-]{1,80}")) {
            throw StationApiError.binding("INVALID_REQUEST", "stationId", "INVALID_FORMAT");
        }
        var station = stations.station(stationId).orElseThrow(StationApiError::notFound);
        Instant to = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new HistoryRequest(station.stationId(), filter.profile(), to.minus(periodDays, ChronoUnit.DAYS), to);
    }

    record HistoryRequest(String station, ChargerProfile profile, Instant from, Instant to) {}
    record Period(Instant from, Instant to) {}
    record HourBucket(int weekday, int hour, double meanAvailableRatio) {}
    record SampleCount(int weekday, int hour, int count) {}
    record PatternResult(String stationId, ChargerProfile profile, int periodDays, String timezone, String basis,
                         Period observedPeriod, List<HourBucket> weekdayHourBuckets, List<SampleCount> sampleCounts,
                         String dataSufficiency) {}
    record TurnoverResult(String stationId, ChargerProfile profile, int periodDays, String basis, Period observedPeriod,
                          double observedHours, double observedChargerHours, int observedDays, Long observedChanges,
                          Long observedChargingToAvailable, Double changesPerObservedChargerHour, String dataSufficiency) {}
    record QualityResult(String stationId, ChargerProfile profile, Period requestedPeriod, String dataSufficiency,
                         String modelVersion, String featureVersion, String observationBasis, long evaluationSampleCount,
                         long evaluationDayCount, List<ProbabilityBucket> probabilityBuckets) {}
    record ProbabilityBucket(double minimumProbability, double maximumProbability, long sampleCount, double observedFraction) {}
    record AreaResult(double latitude, double longitude, int radiusM, ChargerProfile profile, long candidateCount,
                      int evaluatedCandidateCount, int freshCandidateCount, Long freshAvailableCandidateCount,
                      int predictionProvidedCount, Integer highProbabilityCandidateCount, double highProbabilityThreshold,
                      String predictionDataSufficiency,
                      String dataSufficiency) {}
}
