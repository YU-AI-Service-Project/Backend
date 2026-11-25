package dev.wattup.backend.station;

import dev.wattup.backend.prediction.PredictionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StationAnalyticsTests {
    private static final String STATION = "KECO:ANALYTICS";
    private static final String BASE = "/api/charging-stations/" + STATION;
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private final ChargingStationRepository stations = mock(ChargingStationRepository.class);
    private final ChargingStationService nearby = mock(ChargingStationService.class);
    private final StationAnalyticsRepository observations = mock(StationAnalyticsRepository.class);
    private final PredictionService predictions = mock(PredictionService.class);
    private final PredictionQualityRepository quality = mock(PredictionQualityRepository.class);
    private final org.springframework.test.web.servlet.MockMvc api = MockMvcBuilders.standaloneSetup(
            new StationAnalyticsController(stations, nearby, observations, predictions, quality))
            .setControllerAdvice(new StationApiAdvice()).build();

    @Test
    void masksThinBucketsAndReturnsOnlyQualifiedObservedRates() throws Exception {
        stationExists();
        when(observations.patterns(eq(STATION), eq(ChargerProfile.ALL), any(), any())).thenReturn(List.of(
                new StationAnalyticsRepository.Bucket(2, 18, 2, 0.1, NOW.minusSeconds(3600), NOW),
                new StationAnalyticsRepository.Bucket(2, 19, 3, 0.4, NOW.minusSeconds(3600), NOW)));
        when(observations.turnover(eq(STATION), eq(ChargerProfile.ALL), any(), any())).thenReturn(
                new StationAnalyticsRepository.Turnover(25.0, 50.0, 2, 6, 1, NOW.minusSeconds(90000), NOW));

        api.perform(get(BASE + "/patterns")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSufficiency").value("SUFFICIENT"))
                .andExpect(jsonPath("$.sampleCounts[0].count").value(2))
                .andExpect(jsonPath("$.weekdayHourBuckets.length()").value(1))
                .andExpect(jsonPath("$.weekdayHourBuckets[0].hour").value(19))
                .andExpect(jsonPath("$.weekdayHourBuckets[0].meanAvailableRatio").value(0.4));
        api.perform(get(BASE + "/turnover")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observedChanges").value(6))
                .andExpect(jsonPath("$.observedChargingToAvailable").value(1))
                .andExpect(jsonPath("$.changesPerObservedChargerHour").value(0.12));
    }

    @Test
    void doesNotTurnMissingObservationOrValidationDataIntoZeroScores() throws Exception {
        stationExists();
        when(observations.patterns(eq(STATION), eq(ChargerProfile.ALL), any(), any())).thenReturn(List.of());
        when(observations.turnover(eq(STATION), eq(ChargerProfile.ALL), any(), any())).thenReturn(
                new StationAnalyticsRepository.Turnover(2, 2, 1, 0, 0, NOW.minusSeconds(7200), NOW));
        when(quality.evaluate(eq(STATION), eq(ChargerProfile.ALL), any(), any()))
                .thenReturn(new PredictionQualityRepository.Evaluation(null, null, 0, 0, List.of()));

        api.perform(get(BASE + "/patterns")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observedPeriod").isEmpty())
                .andExpect(jsonPath("$.dataSufficiency").value("INSUFFICIENT_HISTORY"));
        api.perform(get(BASE + "/turnover")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observedHours").value(2.0)).andExpect(jsonPath("$.observedChanges").isEmpty())
                .andExpect(jsonPath("$.dataSufficiency").value("INSUFFICIENT_HISTORY"));
        api.perform(get(BASE + "/prediction-quality")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSufficiency").value("UNVERIFIED_MODEL"))
                .andExpect(jsonPath("$.evaluationSampleCount").value(0))
                .andExpect(jsonPath("$.probabilityBuckets").isEmpty());
    }

    @Test
    void reportsOnlySufficientProbabilityBucketsAndIdentifiesTheModelAndObservationBasis() throws Exception {
        stationExists();
        var buckets = List.of(new PredictionQualityRepository.Bucket(0, 4, 0.25),
                new PredictionQualityRepository.Bucket(4, 26, 0.77));
        when(quality.evaluate(eq(STATION), eq(ChargerProfile.ALL), any(), any()))
                .thenReturn(new PredictionQualityRepository.Evaluation("v4", "eta", 30, 2, buckets),
                        new PredictionQualityRepository.Evaluation("v4", "eta", 30, 3, buckets));

        api.perform(get(BASE + "/prediction-quality")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSufficiency").value("INSUFFICIENT_EVALUATION"))
                .andExpect(jsonPath("$.evaluationSampleCount").value(30))
                .andExpect(jsonPath("$.probabilityBuckets").isEmpty());
        api.perform(get(BASE + "/prediction-quality")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSufficiency").value("SUFFICIENT"))
                .andExpect(jsonPath("$.modelVersion").value("v4"))
                .andExpect(jsonPath("$.observationBasis").value("FULL_SNAPSHOT_WITHIN_FORECAST_GRID"))
                .andExpect(jsonPath("$.evaluationDayCount").value(3))
                .andExpect(jsonPath("$.probabilityBuckets.length()").value(1))
                .andExpect(jsonPath("$.probabilityBuckets[0].minimumProbability").value(0.8))
                .andExpect(jsonPath("$.probabilityBuckets[0].sampleCount").value(26))
                .andExpect(jsonPath("$.probabilityBuckets[0].observedFraction").value(0.77));
    }

    @Test
    void validatesStationAndPeriodAndReturnsRegionalCurrentObservationsSeparatelyFromForecasts() throws Exception {
        api.perform(get(BASE + "/patterns").param("periodDays", "91")).andExpect(status().isBadRequest());
        api.perform(get(BASE + "/turnover")).andExpect(status().isNotFound());
        verifyNoInteractions(observations);

        var current = new StationViews.Current(ChargerProfile.ALL, 3, 1, 1, 1, 0);
        var summary = new StationViews.StatusSummary(current, 3, NOW);
        var fresh = new ChargingStationRepository.Nearby(STATION, "station", null, null, null, 0,
                summary, 41, null, 1, Map.of("inventory", 3L));
        when(nearby.recommendationCandidates(any())).thenReturn(
                new ChargingStationService.RecommendationCandidates(List.of(fresh), NOW.minusSeconds(600)));
        var inventories = Map.of(STATION, Map.of("inventory", 3L));
        var forecast = new PredictionService.Prediction("OK", 0.8, NOW, NOW, NOW, NOW, 900, 1000, "KAKAO",
                NOW, NOW, 15, 0.0, 0.0, "eta", "v4");
        when(predictions.predictForArea(List.of(STATION), ChargerProfile.ALL, 35.8, 128.7, inventories))
                .thenReturn(new PredictionService.Batch(null, List.of()),
                        new PredictionService.Batch("batch", List.of(new PredictionService.StationPrediction(
                                STATION, ChargerProfile.ALL, "ARRIVAL_ETA", forecast))));
        api.perform(get("/api/areas/charging-availability").param("latitude", "35.8").param("longitude", "128.7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(41))
                .andExpect(jsonPath("$.evaluatedCandidateCount").value(1))
                .andExpect(jsonPath("$.freshAvailableCandidateCount").value(1))
                .andExpect(jsonPath("$.predictionProvidedCount").value(0))
                .andExpect(jsonPath("$.highProbabilityCandidateCount").isEmpty());
        api.perform(get("/api/areas/charging-availability").param("latitude", "35.8").param("longitude", "128.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictionProvidedCount").value(1))
                .andExpect(jsonPath("$.highProbabilityCandidateCount").value(1))
                .andExpect(jsonPath("$.highProbabilityThreshold").value(0.75));
    }

    private void stationExists() {
        when(stations.station(STATION)).thenReturn(Optional.of(new ChargingStationRepository.Station(
                STATION, "station", null, null, null, null, null, null, null, null, null)));
    }
}
