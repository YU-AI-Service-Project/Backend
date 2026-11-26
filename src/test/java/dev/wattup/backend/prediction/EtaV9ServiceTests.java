package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_AI_V9_URL", matches = ".+")
class EtaV9ServiceTests {
    @Test
    void sendsEventFeaturesAndBearerTokenToRealV9AndCombinesTwoBatches() throws Exception {
        var json = new JsonMapper();
        var fixturePath = Path.of(System.getenv("WATTUP_TEST_AI_V9_REQUEST"));
        var fixture = json.readTree(Files.readString(fixturePath));
        var item = fixture.path("items").get(0);
        var requestAt = Instant.parse(fixture.path("requestAt").asText());
        var asOf = Instant.parse(item.path("asOf").asText());
        var features = json.convertValue(item.path("features"), new TypeReference<Map<String, Number>>() {});
        assertThat(features).containsKeys(EtaFeatures.EVENT_KEYS.toArray(String[]::new));
        try (var http = HttpClient.newHttpClient()) {
            var client = new EtaBatchClient(http, json, URI.create(System.getenv("WATTUP_TEST_AI_V9_URL")),
                    System.getenv("WATTUP_TEST_AI_V9_TOKEN"), Duration.ofSeconds(3), EtaBatchClient.EVENT_FEATURE_VERSION);
            String station = item.path("stationId").asText();
            if (!station.startsWith("KECO:")) {
                station = "KECO:" + station;
            }
            String stationId = station;
            var items = IntStream.range(0, 12).mapToObj(id -> new EtaBatchClient.Item(Integer.toString(id), stationId,
                    ChargerProfile.valueOf(item.path("profile").asText()), asOf, item.path("etaSeconds").asInt(),
                    requestAt, item.path("roadDistanceM").asInt(), features)).toList();
            var result = client.predict("backend-v9-live", requestAt, items).orElseThrow();
            assertThat(result).hasSize(12).allSatisfy((id, prediction) -> {
                assertThat(prediction.status()).isEqualTo("OK");
                assertThat(prediction.probability()).isBetween(0., 1.);
                assertThat(prediction.modelVersion()).isEqualTo("wattup-eta-v9-20261126");
            });
            assertThat(client.activity().lastRequestStatus()).isEqualTo("SUCCEEDED");
        }
    }
}
