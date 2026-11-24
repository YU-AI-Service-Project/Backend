package dev.wattup.backend.station;

import dev.wattup.backend.prediction.PredictionService;
import dev.wattup.backend.prediction.PredictionService.Prediction;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final ChargerProfile PROFILE = ChargerProfile.DC_COMBO;
    private static final StationSearch SEARCH = StationSearch.of(35.8, 128.7, 3000, 10,
            false, "DISTANCE", ChargerFilter.forRecommendation("DC_COMBO", null));
    private final ChargingStationService stations = mock(ChargingStationService.class);
    private final PredictionService predictions = mock(PredictionService.class);
    private final RecommendationService service = new RecommendationService(stations, predictions);

    @Test
    void respectsPredictionAndReturnsOnlyTopThreeWithRouteEvidence() {
        var closest = station("A", 100, 0, 0);
        var higherForecast = station("B", 600, 0, 0);
        var further = station("D", 900, 1, 0);
        var sameScore = station("C", 900, 1, 0);
        when(stations.recommendationCandidates(SEARCH)).thenReturn(candidates(closest, higherForecast, further, sameScore));
        var ids = List.of("A", "B", "D", "C");
        when(predictions.predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, inventories(ids)))
                .thenReturn(new PredictionService.Batch("request", List.of(prediction("A", 0.0), prediction("B", 0.9),
                        prediction("D", 0.5), prediction("C", 0.5))));

        var result = service.recommend(SEARCH, 3);
        assertThat(result.items()).extracting(RecommendationService.Item::stationId).containsExactly("B", "C", "D");
        assertThat(result.items().getFirst().prediction().probability()).isEqualTo(0.9);
        assertThat(result.items().getFirst().etaSeconds()).isEqualTo(300);
        assertThat(result.items().getFirst().roadDistanceM()).isEqualTo(1200);
        assertThat(result.rankingBasis()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT_PREDICTION");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.totalCandidates()).isEqualTo(12);
        assertThat(result.evaluatedCandidates()).isEqualTo(4);
        verify(predictions).predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, inventories(ids));
    }

    @Test
    void usesOneRankingBasisForPartialPredictionsAndKeepsKnownProbabilities() {
        var first = station("B", 100, 1, 1);
        var second = station("C", 100, 1, 1);
        when(stations.recommendationCandidates(SEARCH)).thenReturn(candidates(second, first));
        var ids = List.of("C", "B");
        when(predictions.predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, inventories(ids)))
                .thenReturn(new PredictionService.Batch("request", List.of(prediction("B", 0.2))));
        var result = service.recommend(SEARCH, 3);
        assertThat(result.items()).extracting(RecommendationService.Item::stationId).containsExactly("B", "C");
        assertThat(result.items().getFirst().score()).isEqualTo(result.items().getLast().score());
        assertThat(result.items().getFirst().prediction().probability()).isEqualTo(0.2);
        assertThat(result.items().getLast().prediction().probability()).isNull();
        assertThat(result.rankingBasis()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT");
        assertThat(result.warnings()).containsExactly("PREDICTION_PARTIAL", "ROUTE_RESULTS_INCOMPLETE");
    }

    @Test
    void keepsFreshAvailabilityAheadOfStaleLastKnownAvailabilityWhenHistoryTimesOut() {
        var stale = station("A", 100, 2, 0);
        var fresh = station("B", 600, 1, 1);
        when(stations.recommendationCandidates(SEARCH)).thenReturn(candidates(stale, fresh));
        var ids = List.of("A", "B");
        when(predictions.predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, inventories(ids)))
                .thenThrow(new QueryTimeoutException("synthetic timeout"));

        var result = service.recommend(SEARCH, 3);
        assertThat(result.items()).extracting(RecommendationService.Item::stationId).containsExactly("B", "A");
        assertThat(result.items().getLast().current().availableCount()).isEqualTo(2);
        assertThat(result.items().getLast().current().freshAvailableCount()).isZero();
        assertThat(result.items().getLast().prediction().status()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(result.items().getLast().prediction().probability()).isNull();
        assertThat(result.rankingBasis()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT");
    }

    private static ChargingStationService.RecommendationCandidates candidates(ChargingStationRepository.Nearby... items) {
        return new ChargingStationService.RecommendationCandidates(List.of(items), NOW.minusSeconds(600));
    }

    private static ChargingStationRepository.Nearby station(String id, int distance, int available, int freshAvailable) {
        var current = new StationViews.Current(PROFILE, 2, available, 2 - available, 0, 0);
        var status = new StationViews.StatusSummary(current, 2, id.equals("A") ? NOW.minusSeconds(900) : NOW);
        return new ChargingStationRepository.Nearby(id, id, null, null, null, distance, status, 12,
                BigDecimal.valueOf(100), freshAvailable, Map.of(id, 1L));
    }

    private static Map<String, Map<String, Long>> inventories(List<String> stationIds) {
        return stationIds.stream().collect(Collectors.toMap(id -> id, id -> Map.of(id, 1L)));
    }

    private static PredictionService.StationPrediction prediction(String station, double probability) {
        var prediction = new Prediction("OK", probability, NOW, NOW, NOW, NOW, 300, 1200, "KAKAO_DRIVING_DIRECTIONS",
                NOW.plusSeconds(300), NOW.plusSeconds(300), 5, 0.0, 0.0, "test-v4", "daegu-eta-v4");
        return new PredictionService.StationPrediction(station, PROFILE, "ARRIVAL_ETA", prediction);
    }
}
