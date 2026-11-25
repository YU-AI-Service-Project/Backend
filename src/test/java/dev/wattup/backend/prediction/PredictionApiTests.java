package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import dev.wattup.backend.station.PredictionQualityCapture;
import dev.wattup.backend.station.StationApiAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PredictionApiTests {
    private static final Instant NOW = Instant.parse("2026-11-12T01:03:00Z");
    private static final String STATION = "KECO:DEMO";
    private final EtaHistoryRepository history = mock(EtaHistoryRepository.class);
    private final KakaoRouteClient routes = mock(KakaoRouteClient.class);
    private final EtaBatchClient ai = mock(EtaBatchClient.class);
    private final PredictionService service = new PredictionService(history, routes, ai, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MockMvc http = MockMvcBuilders.standaloneSetup(
            new PredictionController(service)).setControllerAdvice(new StationApiAdvice()).build();

    @Test
    void doesNotCaptureAResultReceivedAfterTheRequestedArrival() {
        stationWithHistory();
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW, NOW.plusSeconds(601));
        when(ai.predict(any(), eq(NOW), any())).thenReturn(Optional.of(
                Map.of("1", new EtaBatchClient.Result("OK", 0.48, "replay-v4"))));
        var capture = mock(PredictionQualityCapture.class);
        var serviceWithCapture = new PredictionService(history, routes, ai, clock, Duration.ofSeconds(2), capture);

        var result = serviceWithCapture.predict(List.of(STATION), ChargerProfile.AC_SLOW,
                new PredictionController.Origin(35.2, 128.3));
        org.assertj.core.api.Assertions.assertThat(result.items().getFirst().prediction().probability()).isEqualTo(0.48);
        verifyNoInteractions(capture);
    }

    @Test
    void singlePredictionReturnsActualArrivalAndForecastTime() throws Exception {
        stationWithHistory();
        when(ai.predict(any(), eq(NOW), any())).thenReturn(Optional.of(
                Map.of("1", new EtaBatchClient.Result("OK", 0.48, "replay-v4"))));
        http.perform(get("/api/charging-stations/{id}/prediction", STATION).param("profile", "AC_SLOW")
                        .param("originLatitude", "35.2").param("originLongitude", "128.3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("ARRIVAL_ETA"))
                .andExpect(jsonPath("$.prediction.probability").value(0.48))
                .andExpect(jsonPath("$.prediction.requestedArrivalAt").value("2026-11-12T01:13:00Z"))
                .andExpect(jsonPath("$.prediction.forecastAt").value("2026-11-12T01:15:00Z"));
    }

    @Test
    void batchPreservesInputOrderAndAllowsPartialFailure() throws Exception {
        stationWithHistory();
        when(ai.predict(any(), eq(NOW), any())).thenReturn(Optional.empty());
        http.perform(post("/api/predictions/batch").contentType("application/json").content("""
                    {"profile":"AC_SLOW","stationIds":["KECO:MISSING","KECO:DEMO"],
                     "origin":{"latitude":35.2,"longitude":128.3}}
                    """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].stationId").value("KECO:MISSING"))
                .andExpect(jsonPath("$.items[0].prediction.status").value("STATION_NOT_FOUND"))
                .andExpect(jsonPath("$.items[1].prediction.status").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.items[1].prediction.probability").isEmpty());
    }

    @Test
    void allEligibleItemsFailingInferenceReturns503() throws Exception {
        stationWithHistory();
        when(ai.predict(any(), eq(NOW), any())).thenReturn(Optional.empty());
        http.perform(post("/api/predictions/batch").contentType("application/json").content("""
                    {"profile":"AC_SLOW","stationIds":["KECO:DEMO"],
                     "origin":{"latitude":35.2,"longitude":128.3}}
                    """))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void inputErrorsAndIncompleteHistoryDoNotReachProviders() throws Exception {
        http.perform(post("/api/predictions/batch").contentType("application/json").content("""
                    {"profile":"AC_SLOW","stationIds":["KECO:DEMO","KECO:DEMO"],
                     "origin":{"latitude":35.2,"longitude":128.3}}
                    """)).andExpect(status().isBadRequest());
        http.perform(get("/api/charging-stations/{id}/prediction", STATION).param("profile", "WRONG"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("UNSUPPORTED_PROFILE"));
        when(history.load(eq(List.of(STATION)), eq(ChargerProfile.AC_SLOW), any()))
                .thenReturn(Map.of(STATION, new EtaHistoryRepository.Candidate(35.1, 128.2, NOW, null, Map.of(), Map.of())));
        http.perform(get("/api/charging-stations/{id}/prediction", STATION).param("profile", "AC_SLOW")
                        .param("originLatitude", "35.2").param("originLongitude", "128.3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.prediction.status").value("INPUT_INCOMPLETE"))
                .andExpect(jsonPath("$.prediction.probability").isEmpty());
        verify(routes, never()).find(35.2, 128.3, 35.1, 128.2);
        verify(ai, never()).predict(any(), any(), any());
    }

    @Test
    void distinguishesStaleCollectionFromMissingHistory() throws Exception {
        when(history.load(eq(List.of(STATION)), eq(ChargerProfile.AC_SLOW), any()))
                .thenReturn(Map.of(STATION, new EtaHistoryRepository.Candidate(
                        35.1, 128.2, NOW.minusSeconds(631), null, Map.of(), Map.of())));
        http.perform(get("/api/charging-stations/{id}/prediction", STATION).param("profile", "AC_SLOW")
                        .param("originLatitude", "35.2").param("originLongitude", "128.3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.prediction.status").value("STALE_FEATURES"))
                .andExpect(jsonPath("$.prediction.probability").isEmpty());
        verify(ai, never()).predict(any(), any(), any());
    }

    @Test
    void batchesThirtyStationsInOneInferenceCallAndRejectsThirtyOne() throws Exception {
        var ids = IntStream.range(0, 31).mapToObj(i -> "KECO:TEST" + i).toList();
        var firstThirty = ids.subList(0, 30);
        var json = new JsonMapper();
        var origin = Map.of("latitude", 35.2, "longitude", 128.3);
        var candidate = new EtaHistoryRepository.Candidate(
                35.1, 128.2, NOW.minusSeconds(180), null, Map.of(), Map.of("totalCount", 1));
        when(history.load(eq(firstThirty), eq(ChargerProfile.AC_SLOW), any()))
                .thenReturn(firstThirty.stream().collect(Collectors.toMap(Function.identity(), id -> candidate)));
        when(routes.find(35.2, 128.3, 35.1, 128.2))
                .thenReturn(Optional.of(new KakaoRouteClient.Route(600, 4000, NOW, NOW)));
        when(ai.predict(any(), eq(NOW), any())).thenAnswer(call -> {
            List<EtaBatchClient.Item> items = call.getArgument(2);
            org.assertj.core.api.Assertions.assertThat(items).hasSize(30);
            return Optional.of(items.stream().collect(Collectors.toMap(EtaBatchClient.Item::requestItemId,
                    item -> new EtaBatchClient.Result("OK", 0.975582926408748, "wattup-eta-v4-20261112"))));
        });
        http.perform(post("/api/predictions/batch").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("profile", "AC_SLOW", "stationIds", firstThirty,
                                "origin", origin))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.items[29].stationId").value("KECO:TEST29"))
                .andExpect(jsonPath("$.items[29].prediction.probability").value(0.975582926408748));
        http.perform(post("/api/predictions/batch").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("profile", "AC_SLOW", "stationIds", ids,
                                "origin", origin))))
                .andExpect(status().isBadRequest());
        verify(ai).predict(any(), eq(NOW), any());
    }

    private void stationWithHistory() {
        when(history.load(any(), eq(ChargerProfile.AC_SLOW), eq(NOW.minusSeconds(180))))
                .thenReturn(Map.of(STATION, new EtaHistoryRepository.Candidate(
                        35.1, 128.2, NOW.minusSeconds(180), null, Map.of(), Map.of("totalCount", 1))));
        when(routes.find(35.2, 128.3, 35.1, 128.2))
                .thenReturn(Optional.of(new KakaoRouteClient.Route(600, 4000, NOW, NOW)));
    }
}
