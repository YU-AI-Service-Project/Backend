package dev.wattup.backend.prediction;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class KakaoRouteClient {
    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final String key;
    private final Duration timeout;
    private final Clock clock;

    KakaoRouteClient(HttpClient http, JsonMapper json, URI endpoint, String key, Duration timeout, Clock clock) {
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.key = key;
        this.timeout = timeout;
        this.clock = clock;
    }

    Optional<Route> find(double originLatitude, double originLongitude,
                         double destinationLatitude, double destinationLongitude) {
        if (key.isBlank()) {
            return Optional.empty();
        }
        String url = endpoint + "?origin=" + originLongitude + "," + originLatitude
                + "&destination=" + destinationLongitude + "," + destinationLatitude
                + "&priority=RECOMMEND&summary=true&alternatives=false";
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Authorization", "KakaoAK " + key).header("Accept", "application/json").GET().build();
        Instant requestedAt = clock.instant();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode route = json.readTree(response.body()).path("routes").path(0);
            JsonNode duration = route.path("summary").path("duration");
            JsonNode distance = route.path("summary").path("distance");
            if (!route.path("result_code").isIntegralNumber() || route.path("result_code").asInt(-1) != 0
                    || !nonnegativeInteger(duration) || !nonnegativeInteger(distance)) {
                return Optional.empty();
            }
            return Optional.of(new Route(duration.asInt(), distance.asInt(), requestedAt, clock.instant()));
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (TimeoutException e) {
            pending.cancel(true);
            return Optional.empty();
        } catch (ExecutionException | JacksonException e) {
            return Optional.empty();
        }
    }

    private static boolean nonnegativeInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
    }

    record Route(int etaSeconds, int roadDistanceM, Instant requestedAt, Instant generatedAt) {
    }
}
