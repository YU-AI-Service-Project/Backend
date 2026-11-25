package dev.wattup.backend.location;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
final class KakaoLocalClient {
    private static final URI KAKAO = URI.create("https://dapi.kakao.com");
    private final JsonMapper json;
    private final String key;
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    private final Semaphore calls = new Semaphore(8);

    @Autowired
    KakaoLocalClient(JsonMapper json, @Value("${wattup.location.kakao-rest-api-key:}") String key) {
        this(json, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                KAKAO, Duration.ofSeconds(2));
    }

    KakaoLocalClient(JsonMapper json, String key, HttpClient http, URI endpoint, Duration timeout) {
        this.json = json;
        this.key = key;
        this.http = http;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    List<Place> search(String query, int limit) {
        var places = new ArrayList<Place>();
        for (JsonNode document : searchDocuments("keyword", query, limit)) {
            String address = first(text(document, "road_address_name"), text(document, "address_name"));
            addPlace(places, document, text(document, "place_name"), address, limit, false);
        }
        if (places.size() < limit) {
            try {
                for (JsonNode document : searchDocuments("address", query, limit)) {
                    String address = text(document, "address_name");
                    addPlace(places, document, address, address, limit, true);
                }
            } catch (Failure failure) {
                if (places.isEmpty()) {
                    throw failure;
                }
            }
        }
        return List.copyOf(places);
    }

    Address reverse(double latitude, double longitude) {
        URI uri = UriComponentsBuilder.fromUri(endpoint.resolve("/v2/local/geo/coord2address.json"))
                .queryParam("x", "{longitude}").queryParam("y", "{latitude}").queryParam("input_coord", "WGS84")
                .encode().buildAndExpand(longitude, latitude).toUri();
        try {
            JsonNode document = documents(uri).path(0);
            return new Address(text(document.path("road_address"), "address_name"),
                    text(document.path("address"), "address_name"));
        } catch (Failure failure) {
            return new Address(null, null);
        }
    }

    private JsonNode searchDocuments(String kind, String query, int limit) {
        URI uri = UriComponentsBuilder.fromUri(endpoint.resolve("/v2/local/search/" + kind + ".json"))
                .queryParam("query", "{query}").queryParam("size", "{limit}")
                .encode().buildAndExpand(query, limit).toUri();
        return documents(uri);
    }

    private JsonNode documents(URI uri) {
        if (!key.matches("[A-Za-z0-9_-]+") || !calls.tryAcquire()) {
            throw new Failure(HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                    .header("Authorization", "KakaoAK " + key).GET().build();
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) {
                    throw new Failure(switch (response.statusCode()) {
                        case 429, 503 -> HttpStatus.SERVICE_UNAVAILABLE;
                        case 408, 504 -> HttpStatus.GATEWAY_TIMEOUT;
                        default -> HttpStatus.BAD_GATEWAY;
                    });
                }
                JsonNode body = json.readTree(response.body());
                if (body == null || !body.path("documents").isArray()) {
                    throw new Failure(HttpStatus.BAD_GATEWAY);
                }
                return body.path("documents");
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new Failure(HttpStatus.SERVICE_UNAVAILABLE);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new Failure(HttpStatus.GATEWAY_TIMEOUT);
            } catch (ExecutionException e) {
                throw new Failure(e.getCause() instanceof HttpTimeoutException
                        ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY);
            } catch (JacksonException e) {
                throw new Failure(HttpStatus.BAD_GATEWAY);
            }
        } finally {
            calls.release();
        }
    }

    private static void addPlace(List<Place> places, JsonNode document, String name, String address, int limit,
                                 boolean addressSupplement) {
        if (places.size() >= limit || name == null) {
            return;
        }
        double latitude = coordinate(document, "y");
        double longitude = coordinate(document, "x");
        if (!validCoordinate(latitude, 90) || !validCoordinate(longitude, 180)) {
            return;
        }
        var place = new Place(name, address, latitude, longitude);
        boolean duplicate = addressSupplement
                ? places.stream().anyMatch(found -> address.equals(found.address())
                        || (latitude == found.latitude() && longitude == found.longitude()))
                : places.contains(place);
        if (!duplicate) {
            places.add(place);
        }
    }

    private static double coordinate(JsonNode document, String field) {
        try {
            return Double.parseDouble(document.path(field).asText(""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean validCoordinate(double value, int max) {
        return Double.isFinite(value) && Math.abs(value) <= max;
    }

    private static String text(JsonNode document, String field) {
        JsonNode value = document.path(field);
        return value.isString() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    record Place(String name, String address, double latitude, double longitude) {}

    record Address(String road, String jibun) {
        String displayName() {
            return first(road, first(jibun, "선택한 위치"));
        }
    }

    static final class Failure extends RuntimeException {
        final HttpStatus status;

        Failure(HttpStatus status) {
            this.status = status;
        }
    }
}
