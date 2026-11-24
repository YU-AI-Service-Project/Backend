package dev.wattup.backend.collection;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class KecoApiClient {
    static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SOURCE_TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern XML_RESULT = Pattern.compile("<returnReasonCode>\\s*(\\d{1,2})\\s*</returnReasonCode>");

    private final HttpClient http;
    private final JsonMapper json;
    private final URI baseUri;
    private final Duration timeout;

    KecoApiClient(HttpClient http, JsonMapper json, URI baseUri, Duration timeout) {
        this.http = http;
        this.json = json;
        this.baseUri = baseUri;
        this.timeout = timeout;
    }

    KecoPage fetch(KecoEndpoint endpoint, String serviceKey, String region, int pageNumber, int pageSize) {
        String query = "serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
                + "&pageNo=" + pageNumber + "&numOfRows=" + pageSize + "&zcode=" + region + "&_type=json";
        if (endpoint == KecoEndpoint.CHANGED_STATUS) {
            query += "&period=10";
        }
        var request = HttpRequest.newBuilder(baseUri.resolve(endpoint.path + "?" + query))
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();
        var response = send(request);
        int status = response.statusCode();
        if (status == 429) {
            throw new KecoException("UPSTREAM_QUOTA", false);
        }
        if (status >= 500) {
            throw new KecoException("UPSTREAM_HTTP_" + status, true);
        }
        if (status != 200) {
            throw new KecoException("UPSTREAM_HTTP_" + status, false);
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KecoException("COLLECTION_INTERRUPTED", false);
        } catch (IOException e) {
            throw new KecoException("UPSTREAM_CONNECTION", true);
        }
    }

    private KecoPage parse(String response) {
        var xmlCode = XML_RESULT.matcher(response);
        if (response.stripLeading().startsWith("<") && xmlCode.find()) {
            throw providerFailure(xmlCode.group(1));
        }
        JsonNode root;
        try {
            root = json.readTree(response);
        } catch (JacksonException e) {
            throw invalidPage();
        }
        if (root == null || !root.isObject()) {
            throw invalidPage();
        }
        JsonNode envelope = root.path("response");
        JsonNode body = envelope.isMissingNode() ? root : envelope.path("body");
        String result = envelope.isMissingNode()
                ? root.path("resultCode").asText("") : envelope.path("header").path("resultCode").asText("");
        if (!"00".equals(result) && !"0".equals(result)) {
            throw providerFailure(result);
        }
        int pageNumber = body.path("pageNo").asInt(-1);
        int pageSize = body.path("numOfRows").asInt(-1);
        int totalCount = body.path("totalCount").asInt(-1);
        if (pageNumber < 1 || pageSize < 1 || totalCount < 0) {
            throw invalidPage();
        }
        return new KecoPage(pageNumber, pageSize, totalCount, parseItems(body.path("items"), totalCount));
    }

    private List<KecoReading> parseItems(JsonNode items, int totalCount) {
        JsonNode entries = items.isObject() ? items.path("item") : items;
        var readings = new ArrayList<KecoReading>();
        if (entries.isArray()) {
            for (JsonNode item : entries) {
                readings.add(reading(item));
            }
        } else if (entries.isObject()) {
            readings.add(reading(entries));
        } else if (totalCount != 0 || !entries.asText("").isBlank()) {
            throw invalidPage();
        }
        return List.copyOf(readings);
    }

    private KecoReading reading(JsonNode item) {
        String stationId = item.path("statId").asText("");
        String chargerId = item.path("chgerId").asText("");
        String rawStatus = item.path("stat").asText("").strip();
        if (!stationId.matches("[A-Za-z0-9_-]{1,80}") || !chargerId.matches("[A-Za-z0-9_-]{1,40}")
                || !item.path("statId").isString() || !item.path("chgerId").isString() || rawStatus.length() > 20) {
            throw invalidPage();
        }
        return new KecoReading(stationId, chargerId, rawStatus,
                utcTimestamp(item.path("statUpdDt").asText("")), utcTimestamp(item.path("nowTsdt").asText("")), item);
    }

    private static LocalDateTime utcTimestamp(String value) {
        try {
            return LocalDateTime.parse(value, SOURCE_TIMESTAMP).atZone(PROVIDER_ZONE)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static KecoException providerFailure(String code) {
        return switch (code) {
            case "22" -> new KecoException("UPSTREAM_QUOTA", false);
            case "01", "02", "04", "05" -> new KecoException("UPSTREAM_TEMPORARY", true);
            case "20", "21", "30", "31", "32" -> new KecoException("UPSTREAM_AUTH", false);
            default -> new KecoException("UPSTREAM_RESULT_INVALID", false);
        };
    }

    private static KecoException invalidPage() {
        return new KecoException("UPSTREAM_PAGE_INVALID", false);
    }
}
