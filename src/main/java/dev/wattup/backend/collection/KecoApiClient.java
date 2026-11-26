package dev.wattup.backend.collection;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class KecoApiClient {
    static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SOURCE_TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

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
                .header("Accept", "application/xml, application/json")
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
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 32 * 1024 * 1024));
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new KecoException("COLLECTION_INTERRUPTED", false);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new KecoException("UPSTREAM_TIMEOUT", true);
        } catch (ExecutionException e) {
            throw new KecoException("UPSTREAM_CONNECTION", true);
        }
    }

    private KecoPage parse(String response) {
        if (response.stripLeading().startsWith("<")) {
            return parseXml(response);
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
        return page(result, body, body.path("items"));
    }

    private KecoPage parseXml(String response) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler());
            Element root = parser.parse(new InputSource(new StringReader(response))).getDocumentElement();
            if ("OpenAPI_ServiceResponse".equals(root.getTagName())) {
                throw providerFailure(fields(child(root, "cmmMsgHeader")).path("returnReasonCode").asText(""));
            }
            if (!"response".equals(root.getTagName())) {
                throw invalidPage();
            }
            var header = fields(child(root, "header"));
            var items = child(child(root, "body"), "items");
            var entries = json.createArrayNode();
            if (items != null) {
                for (var node = items.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element item && "item".equals(item.getTagName())) {
                        entries.add(fields(item));
                    }
                }
            }
            return page(header.path("resultCode").asText(""), header, items == null ? null : entries);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw invalidPage();
        }
    }

    private static Element child(Element parent, String name) {
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getTagName())) {
                    return element;
                }
            }
        }
        return null;
    }

    private ObjectNode fields(Element element) {
        var fields = json.createObjectNode();
        if (element != null) {
            for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element field) {
                    fields.put(field.getTagName(), field.getTextContent().strip());
                }
            }
        }
        return fields;
    }

    private KecoPage page(String result, JsonNode pagination, JsonNode items) {
        if (!"00".equals(result) && !"0".equals(result)) {
            throw providerFailure(result);
        }
        int pageNumber = pagination.path("pageNo").asInt(-1);
        int pageSize = pagination.path("numOfRows").asInt(-1);
        int totalCount = pagination.path("totalCount").asInt(-1);
        if (pageNumber < 1 || pageSize < 0 || totalCount < 0 || (pageSize == 0 && totalCount > 0)) {
            throw invalidPage();
        }
        return new KecoPage(pageNumber, pageSize, totalCount,
                parseItems(items == null ? json.missingNode() : items, totalCount));
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
                || !item.path("statId").isString() || !item.path("chgerId").isString()
                || !rawStatus.matches("[0-9]")) {
            throw invalidPage();
        }
        return new KecoReading(stationId, chargerId, rawStatus,
                utcTimestamp(item.path("statUpdDt").asText("")), utcTimestamp(item.path("nowTsdt").asText("")), item);
    }

    private static LocalDateTime utcTimestamp(String value) {
        if (value.isBlank() || value.equals("00000000000000") || value.equals("0")) {
            return null;
        }
        try {
            LocalDateTime localTime = LocalDateTime.parse(value, SOURCE_TIMESTAMP);
            if (localTime.getYear() < 1001) {
                throw invalidPage();
            }
            return localTime.atZone(PROVIDER_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw invalidPage();
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
