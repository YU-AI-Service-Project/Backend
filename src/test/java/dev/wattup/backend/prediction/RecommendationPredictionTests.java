package dev.wattup.backend.prediction;

import com.sun.net.httpserver.HttpServer;
import dev.wattup.backend.station.ChargerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationPredictionTests {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ChargerProfile PROFILE = ChargerProfile.DC_COMBO;
    private static final Map<String, Long> INVENTORY = Map.of("1", 11L);
    private static final byte[] ROUTE = """
            {"routes":[{"result_code":0,"summary":{"duration":300,"distance":1200}}]}
            """.getBytes(StandardCharsets.UTF_8);
    private final EtaHistoryRepository history = mock(EtaHistoryRepository.class);
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 30})
    void preservesRoadEtaWhenInferenceFailsForEveryCandidate(int size) throws Exception {
        var ids = IntStream.range(0, size).mapToObj(i -> "KECO:" + i).toList();
        var features = new EtaHistoryRepository.Candidate(35.81, 128.71, NOW, BigDecimal.valueOf(100),
                INVENTORY, Map.of("totalCount", 1));
        stubHistory(ids, features);
        var routeCalls = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var maxBatch = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/route", exchange -> {
                try (exchange) {
                    routeCalls.incrementAndGet();
                    exchange.sendResponseHeaders(200, ROUTE.length);
                    exchange.getResponseBody().write(ROUTE);
                }
            });
            server.createContext("/ai", exchange -> {
                try (exchange) {
                    modelCalls.incrementAndGet();
                    int count = json.readTree(exchange.getRequestBody()).path("items").size();
                    maxBatch.accumulateAndGet(count, Math::max);
                    exchange.sendResponseHeaders(503, -1);
                }
            });
            server.start();
            try (var http = HttpClient.newHttpClient()) {
                var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                var routes = new KakaoRouteClient(http, json, uri.resolve("/route"), "test-key", Duration.ofSeconds(2), CLOCK);
                var ai = new EtaBatchClient(http, json, uri.resolve("/ai"), "", Duration.ofSeconds(2));
                var service = new PredictionService(history, routes, ai, CLOCK);
                var result = service.predictForRecommendation(ids, PROFILE, 35.8, 128.7, BigDecimal.valueOf(70),
                        ids.stream().collect(Collectors.toMap(Function.identity(), id -> INVENTORY)));
                assertThat(result.items()).extracting(PredictionService.StationPrediction::stationId)
                        .containsExactlyElementsOf(ids);
                assertThat(result.items()).allSatisfy(item -> {
                    assertThat(item.prediction().status()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(item.prediction().probability()).isNull();
                    assertThat(item.prediction().etaSeconds()).isEqualTo(300);
                    assertThat(item.prediction().roadDistanceM()).isEqualTo(1200);
                });
                assertThat(routeCalls.get()).isEqualTo(size);
                assertThat(modelCalls.get()).isEqualTo(2);
                assertThat(maxBatch.get()).isEqualTo(size);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void retainsRouteWithoutInferenceWhenFilteredOutputDoesNotMatchModelProfile() {
        var ids = List.of("KECO:ONE");
        stubHistory(ids, new EtaHistoryRepository.Candidate(35.81, 128.71, NOW, BigDecimal.valueOf(25),
                INVENTORY, Map.of("totalCount", 2)));
        var routes = mock(KakaoRouteClient.class);
        when(routes.find(35.8, 128.7, 35.81, 128.71))
                .thenReturn(Optional.of(new KakaoRouteClient.Route(300, 1200, NOW, NOW)));
        var ai = mock(EtaBatchClient.class);
        var result = new PredictionService(history, routes, ai, CLOCK)
                .predictForRecommendation(ids, PROFILE, 35.8, 128.7, BigDecimal.valueOf(70), Map.of("KECO:ONE", INVENTORY))
                .items().getFirst().prediction();
        assertThat(result.status()).isEqualTo("OUTPUT_FILTER_NOT_SUPPORTED");
        assertThat(result.probability()).isNull();
        assertThat(result.requestedArrivalAt()).isEqualTo(NOW.plusSeconds(300));
        verifyNoInteractions(ai);
    }

    @Test
    void doesNotAttachProbabilityToASearchSnapshotFromAnotherInventoryRun() {
        var ids = List.of("KECO:ONE");
        stubHistory(ids, new EtaHistoryRepository.Candidate(35.81, 128.71, NOW, BigDecimal.valueOf(100),
                INVENTORY, Map.of("totalCount", 1)));
        var routes = mock(KakaoRouteClient.class);
        when(routes.find(35.8, 128.7, 35.81, 128.71))
                .thenReturn(Optional.of(new KakaoRouteClient.Route(300, 1200, NOW, NOW)));
        var ai = mock(EtaBatchClient.class);
        var result = new PredictionService(history, routes, ai, CLOCK)
                .predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, Map.of("KECO:ONE", Map.of("1", 10L)))
                .items().getFirst().prediction();
        assertThat(result.status()).isEqualTo("INVENTORY_CHANGED");
        assertThat(result.probability()).isNull();
        assertThat(result.etaSeconds()).isEqualTo(300);
        verifyNoInteractions(ai);
    }

    @Test
    void routeDeadlineReturnsUnavailableWithoutSendingFabricatedEta() throws Exception {
        var ids = List.of("KECO:ONE");
        stubHistory(ids, new EtaHistoryRepository.Candidate(35.81, 128.71, NOW, BigDecimal.valueOf(100),
                INVENTORY, Map.of("totalCount", 1)));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/route", exchange -> {
            try (exchange) {
                try {
                    Thread.sleep(Duration.ofMillis(800));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/route");
            var routes = new KakaoRouteClient(http, json, uri, "test-key", Duration.ofMillis(150), CLOCK);
            var ai = mock(EtaBatchClient.class);
            var result = new PredictionService(history, routes, ai, CLOCK)
                    .predictForRecommendation(ids, PROFILE, 35.8, 128.7, null, Map.of("KECO:ONE", INVENTORY))
                    .items().getFirst().prediction();
            assertThat(result.status()).isEqualTo("ROUTE_UNAVAILABLE");
            assertThat(result.etaSeconds()).isNull();
            assertThat(result.probability()).isNull();
            verifyNoInteractions(ai);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sharedRouteBudgetKeepsCompletedRoutesAndCancelsSlowOnes() {
        var ids = List.of("KECO:FAST", "KECO:SLOW");
        var features = Map.<String, Number>of("totalCount", 1);
        when(history.load(eq(ids), eq(PROFILE), any())).thenReturn(Map.of(
                ids.getFirst(), new EtaHistoryRepository.Candidate(35.81, 128.71, NOW, null, INVENTORY, features),
                ids.getLast(), new EtaHistoryRepository.Candidate(35.82, 128.72, NOW, null, INVENTORY, features)));
        var routes = mock(KakaoRouteClient.class);
        when(routes.find(35.8, 128.7, 35.81, 128.71))
                .thenReturn(Optional.of(new KakaoRouteClient.Route(300, 1200, NOW, NOW)));
        when(routes.find(35.8, 128.7, 35.82, 128.72)).thenAnswer(call -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
                return Optional.of(new KakaoRouteClient.Route(300, 1200, NOW, NOW));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        });
        var ai = mock(EtaBatchClient.class);
        when(ai.predict(any(), eq(NOW), any())).thenReturn(Optional.empty());
        var service = new PredictionService(history, routes, ai, CLOCK, Duration.ofMillis(400));
        long started = System.nanoTime();
        var result = service.predictForRecommendation(ids, PROFILE, 35.8, 128.7, null,
                Map.of(ids.getFirst(), INVENTORY, ids.getLast(), INVENTORY));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(result.items().getFirst().prediction().roadDistanceM()).isEqualTo(1200);
        assertThat(result.items().getLast().prediction().status()).isEqualTo("ROUTE_UNAVAILABLE");
        assertThat(result.items().getLast().prediction().roadDistanceM()).isNull();
    }

    private void stubHistory(List<String> ids, EtaHistoryRepository.Candidate candidate) {
        when(history.load(eq(ids), eq(PROFILE), any())).thenReturn(ids.stream()
                .collect(Collectors.toMap(Function.identity(), id -> candidate)));
    }
}
