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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

final class EtaBatchClient {
    static final String FEATURE_VERSION = "daegu-eta-v4";
    static final String EVENT_FEATURE_VERSION = "daegu-eta-v9-events-37";
    static final int MAX_ITEMS = 30;
    private static final Set<String> FAILURE_STATUSES = Set.of("INPUT_INCOMPLETE", "STALE_FEATURES",
            "HORIZON_UNSUPPORTED", "INVALID_ETA_SECONDS", "FEATURE_TIME_AFTER_REQUEST", "ROUTE_MISSING",
            "ROUTE_UNAVAILABLE", "MODEL_UNAVAILABLE");

    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final String featureVersion;
    private final Semaphore concurrentCalls = new Semaphore(4);
    private final AtomicReference<Activity> activity = new AtomicReference<>(new Activity(null, "NOT_ATTEMPTED", null, null, null));

    EtaBatchClient(HttpClient http, JsonMapper json, URI endpoint, String token, Duration timeout) {
        this(http, json, endpoint, token, timeout, FEATURE_VERSION);
    }

    EtaBatchClient(HttpClient http, JsonMapper json, URI endpoint, String token, Duration timeout,
                   String featureVersion) {
        if (!Set.of(FEATURE_VERSION, EVENT_FEATURE_VERSION).contains(featureVersion)) {
            throw new IllegalArgumentException("Unsupported prediction feature version");
        }
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.token = token;
        this.timeout = timeout;
        this.featureVersion = featureVersion;
    }

    Optional<Map<String, Result>> predict(String requestId, Instant requestAt, List<Item> items) {
        if (endpoint == null || items.isEmpty() || items.size() > MAX_ITEMS || !concurrentCalls.tryAcquire()) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        String model = null;
        String state = "FAILED";
        try {
            boolean eventVersion = EVENT_FEATURE_VERSION.equals(featureVersion);
            int batchSize = eventVersion ? 10 : MAX_ITEMS;
            List<Item> wireItems = eventVersion ? items : items.stream().map(Item::withoutEventFeatures).toList();
            var results = new HashMap<String, Result>();
            boolean responded = false;
            for (int offset = 0; offset < wireItems.size() && !Thread.currentThread().isInterrupted(); offset += batchSize) {
                String batchId = wireItems.size() <= batchSize ? requestId : requestId + "-" + offset;
                var batch = new Batch(batchId, requestAt, featureVersion,
                        wireItems.subList(offset, Math.min(wireItems.size(), offset + batchSize)));
                var result = requestBatch(batch);
                if (result.isPresent()) {
                    responded = true;
                    results.putAll(result.get());
                }
            }
            if (!responded) {
                return Optional.empty();
            }
            model = results.values().stream().filter(result -> "OK".equals(result.status))
                    .map(Result::modelVersion).sorted().findFirst().orElse(null);
            state = results.isEmpty() ? "FAILED" : model == null ? "NO_RESULT" : "SUCCEEDED";
            return Optional.of(Map.copyOf(results));
        } finally {
            recordActivity(state, model, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            concurrentCalls.release();
        }
    }

    String featureVersion() {
        return featureVersion;
    }

    private Optional<Map<String, Result>> requestBatch(Batch batch) {
        try {
            var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .header("Accept", "application/json").timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(batch)));
            if ("http".equals(endpoint.getScheme())) {
                // The local ASGI server accepts HTTP/1.1 rather than a cleartext HTTP/2 upgrade.
                request.version(HttpClient.Version.HTTP_1_1);
            }
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            var response = send(request.build());
            if (response == null || response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = json.readTree(response.body());
            if (root == null || !batch.requestId.equals(root.path("requestId").asText())
                    || !root.path("items").isArray() || root.path("items").size() > batch.items.size()) {
                return Optional.empty();
            }
            return Optional.of(parseItems(root.path("items"), batch.items, batch.requestAt));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    boolean configured() {
        return endpoint != null;
    }

    Activity activity() {
        return activity.get();
    }

    private void recordActivity(String state, String model, long latency) {
        Instant finishedAt = Instant.now();
        activity.updateAndGet(previous -> {
            if (previous.lastAttemptAt != null && previous.lastAttemptAt.isAfter(finishedAt)) {
                return previous;
            }
            return new Activity(finishedAt, state, model == null ? previous.lastSuccessAt : finishedAt,
                    model == null ? previous.modelVersion : model, model == null ? previous.latencyMs : Long.valueOf(latency));
        });
    }

    private HttpResponse<String> send(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 1024 * 1024));
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

    private Map<String, Result> parseItems(JsonNode response, List<Item> items, Instant requestAt) {
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
            } else if ("OK".equals(status) && featureVersion.equals(node.path("featureVersion").asText())) {
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
        Item withoutEventFeatures() {
            var core = new HashMap<>(features);
            core.keySet().removeAll(EtaFeatures.EVENT_KEYS);
            return new Item(requestItemId, stationId, profile, asOf, etaSeconds, etaGeneratedAt, roadDistanceM, core);
        }
    }

    record Result(String status, Double probability, String modelVersion) {
    }

    record Activity(Instant lastAttemptAt, String lastRequestStatus, Instant lastSuccessAt, String modelVersion,
                    Long latencyMs) {
    }

    private record Batch(String requestId, Instant requestAt, String featureVersion, List<Item> items) {
    }
}
