package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

final class EtaBatchClient {
    static final String FEATURE_VERSION = "daegu-eta-v4";
    static final int MAX_ITEMS = 30;
    private static final Set<String> FAILURE_STATUSES = Set.of("INPUT_INCOMPLETE", "STALE_FEATURES",
            "HORIZON_UNSUPPORTED", "INVALID_ETA_SECONDS", "FEATURE_TIME_AFTER_REQUEST", "ROUTE_MISSING",
            "ROUTE_UNAVAILABLE", "MODEL_UNAVAILABLE");

    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final Semaphore concurrentCalls = new Semaphore(4);

    EtaBatchClient(HttpClient http, JsonMapper json, URI endpoint, String token, Duration timeout) {
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.token = token;
        this.timeout = timeout;
    }

    Optional<Map<String, Result>> predict(String requestId, Instant requestAt, List<Item> items) {
        if (endpoint == null || items.isEmpty() || items.size() > MAX_ITEMS || !concurrentCalls.tryAcquire()) {
            return Optional.empty();
        }
        try {
            var body = new Batch(requestId, requestAt, FEATURE_VERSION, items);
            var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .header("Accept", "application/json").timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            var response = send(request.build());
            if (response == null || response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = json.readTree(response.body());
            if (root == null || !requestId.equals(root.path("requestId").asText()) || !root.path("items").isArray()
                    || root.path("items").size() > MAX_ITEMS) {
                return Optional.empty();
            }
            return Optional.of(parseItems(root.path("items"), items, requestAt));
        } catch (JacksonException e) {
            return Optional.empty();
        } finally {
            concurrentCalls.release();
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (attempt == 1 || !Set.of(502, 503, 504).contains(response.statusCode())) {
                    return response;
                }
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | TimeoutException e) {
                pending.cancel(true);
            }
        }
        return null;
    }

    private static Map<String, Result> parseItems(JsonNode response, List<Item> items, Instant requestAt) {
        var expected = items.stream().collect(Collectors.toMap(Item::requestItemId, Function.identity()));
        var results = new HashMap<String, Result>();
        var seen = new HashSet<String>();
        for (JsonNode node : response) {
            String id = node.path("requestItemId").asText();
            Item item = expected.get(id);
            if (!seen.add(id)) {
                results.remove(id);
                continue;
            }
            if (item == null || !item.stationId.equals(node.path("stationId").asText())
                    || !item.profile.name().equals(node.path("profile").asText())) {
                continue;
            }
            String status = node.path("status").asText();
            if (FAILURE_STATUSES.contains(status)) {
                results.put(id, new Result(status, null, null));
            } else if ("OK".equals(status) && FEATURE_VERSION.equals(node.path("featureVersion").asText())) {
                JsonNode probability = node.path("probability");
                String model = node.path("modelVersion").asText("");
                if (probability.isNumber() && probability.asDouble() >= 0 && probability.asDouble() <= 1
                        && !model.isBlank() && model.length() <= 100 && matchingForecast(node, item, requestAt)) {
                    results.put(id, new Result(status, probability.asDouble(), model));
                }
            }
        }
        return Map.copyOf(results);
    }

    private static boolean matchingForecast(JsonNode node, Item item, Instant requestAt) {
        var timing = ForecastTiming.calculate(item.asOf, requestAt, item.etaSeconds);
        return "OK".equals(timing.status())
                && matchesInstant(node.path("asOf"), item.asOf)
                && matchesInstant(node.path("requestAt"), requestAt)
                && matchesInstant(node.path("requestedArrivalAt"), timing.requestedArrivalAt())
                && matchesInstant(node.path("forecastAt"), timing.forecastAt())
                && matchesNumber(node.path("etaSeconds"), item.etaSeconds)
                && matchesNumber(node.path("roadDistanceM"), item.roadDistanceM)
                && matchesNumber(node.path("horizonMinutes"), timing.horizonMinutes())
                && matchesNumber(node.path("featureAgeSeconds"), timing.featureAgeSeconds())
                && matchesNumber(node.path("forecastRoundingSeconds"), timing.forecastRoundingSeconds());
    }

    private static boolean matchesInstant(JsonNode value, Instant expected) {
        if (!value.isString()) {
            return false;
        }
        try {
            return OffsetDateTime.parse(value.asText()).toInstant().equals(expected);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean matchesNumber(JsonNode value, double expected) {
        return value.isNumber() && Math.abs(value.asDouble() - expected) <= 0.000001;
    }

    record Item(String requestItemId, String stationId, ChargerProfile profile, Instant asOf, int etaSeconds,
                Instant etaGeneratedAt, int roadDistanceM, Map<String, Number> features) {
    }

    record Result(String status, Double probability, String modelVersion) {
    }

    private record Batch(String requestId, Instant requestAt, String featureVersion, List<Item> items) {
    }
}
