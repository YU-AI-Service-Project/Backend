package dev.wattup.backend.prediction;

import com.sun.net.httpserver.HttpServer;
import dev.wattup.backend.station.ChargerProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EtaBatchClientTests {
    private final JsonMapper json = new JsonMapper();

    @Test
    void retriesTransientFailureAndMatchesResultsByRequestItemId() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/test-arrivals", exchange -> {
            try (exchange) {
                var body = json.readTree(exchange.getRequestBody());
                if (!exchange.getRequestMethod().equals("POST")
                        || !"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !body.path("featureVersion").asText().equals("daegu-eta-v4")
                        || !body.path("requestAt").isString()
                        || body.path("items").size() != 3) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                if (calls.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] result = """
                        {"requestId":"batch","items":[
                          {"requestItemId":"2","stationId":"KECO:B","profile":"ALL","status":"INPUT_INCOMPLETE","probability":null},
                          {"requestItemId":"1","stationId":"KECO:A","profile":"ALL","status":"OK","probability":0.0,
                           "modelVersion":"test-model","featureVersion":"daegu-eta-v4",
                           "asOf":"2026-11-12T10:00:00+09:00","requestAt":"2026-11-12T10:00:00+09:00",
                           "etaSeconds":600,"roadDistanceM":3000,"requestedArrivalAt":"2026-11-12T10:10:00+09:00",
                           "forecastAt":"2026-11-12T10:10:00+09:00","horizonMinutes":10,
                           "featureAgeSeconds":0,"forecastRoundingSeconds":0},
                          {"requestItemId":"3","stationId":"KECO:C","profile":"ALL","status":"OK","probability":0.7,
                           "modelVersion":"test-model","featureVersion":"daegu-eta-v4",
                           "asOf":"2026-11-12T10:00:00+09:00","requestAt":"2026-11-12T10:00:00+09:00",
                           "etaSeconds":600,"roadDistanceM":3000,"requestedArrivalAt":"2026-11-12T10:10:00+09:00",
                           "forecastAt":"2026-11-12T10:15:00+09:00","horizonMinutes":15,
                           "featureAgeSeconds":0,"forecastRoundingSeconds":300}
                        ]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/test-arrivals");
            var client = new EtaBatchClient(HttpClient.newHttpClient(), json, endpoint, "test-token", Duration.ofSeconds(2));
            var results = client.predict("batch", Instant.parse("2026-11-12T01:00:00Z"), List.of(
                    item("1", "KECO:A"), item("2", "KECO:B"), item("3", "KECO:C"))).orElseThrow();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(results).containsOnlyKeys("1", "2");
            assertThat(results.get("1").probability()).isZero();
            assertThat(results.get("2").probability()).isNull();
            assertThat(results.get("2").status()).isEqualTo("INPUT_INCOMPLETE");
        } finally {
            server.stop(0);
        }
    }

    private static EtaBatchClient.Item item(String key, String station) {
        Instant asOf = Instant.parse("2026-11-12T01:00:00Z");
        return new EtaBatchClient.Item(key, station, ChargerProfile.ALL, asOf, 600, asOf, 3000,
                Map.of("totalCount", 1));
    }
}
