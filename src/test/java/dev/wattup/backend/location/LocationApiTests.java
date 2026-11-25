package dev.wattup.backend.location;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocationApiTests {
    private static final String ROOT = "/v2/local";
    private static final String SEARCH = "/api/locations/search";
    private static final String REVERSE = "/api/locations/reverse-geocode";
    private static final String KEY = "fixture-key";

    private HttpServer provider;
    private MockMvc api;

    @BeforeEach
    void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.start();
        api = api(KEY, Duration.ofSeconds(2));
    }

    @AfterEach
    void stopProvider() {
        provider.stop(0);
    }

    @Test
    void searchesByKeywordAndSupplementsWithDistinctAddresses() throws Exception {
        var request = new AtomicReference<URI>();
        var authorization = new AtomicReference<String>();
        provider.createContext(ROOT + "/search/keyword.json", exchange -> {
            request.set(exchange.getRequestURI());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, """
                    {"documents":[{"place_name":"대학교 정문","road_address_name":"대학로 1","address_name":"지번 1",
                      "x":"128.75","y":"35.83"}]}
                    """);
        });
        provider.createContext(ROOT + "/search/address.json", exchange -> reply(exchange, 200, """
                {"documents":[{"address_name":"대학로 1","x":"128.7500","y":"35.8300"},
                               {"address_name":"대학로 2","x":"128.76","y":"35.84"}]}
                """));

        api.perform(get(SEARCH).param("query", "  대학로 & +#  ").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.query").value("대학로 & +#"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("대학교 정문"))
                .andExpect(jsonPath("$.items[0].address").value("대학로 1"))
                .andExpect(jsonPath("$.items[0].latitude").value(35.83))
                .andExpect(jsonPath("$.items[0].longitude").value(128.75))
                .andExpect(jsonPath("$.items[1].name").value("대학로 2"));
        assertThat(request.get().getRawQuery()).isEqualTo("query=%EB%8C%80%ED%95%99%EB%A1%9C%20%26%20%2B%23&size=2");
        assertThat(authorization).hasValue("KakaoAK " + KEY);
    }

    @Test
    void supplementsEmptyKeywordResultsAndRejectsInvalidInputWithoutProviderTraffic() throws Exception {
        var calls = new AtomicInteger();
        provider.createContext(ROOT + "/search/keyword.json", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 200, "{\"documents\":[]}");
        });
        provider.createContext(ROOT + "/search/address.json", exchange -> {
            calls.incrementAndGet();
            reply(exchange, 200, "{\"documents\":[]}");
        });
        api.perform(get(SEARCH).param("query", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("query"));
        api.perform(get(SEARCH).param("query", "station").param("limit", "16")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("limit"));
        api.perform(get(REVERSE).param("longitude", "500").param("latitude", "NaN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COORDINATES"));
        assertThat(calls).hasValue(0);

        api.perform(get(SEARCH).param("query", "없는 장소")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertThat(calls).hasValue(2);
    }

    @Test
    void preservesKeywordResultsWhenAddressSupplementFails() throws Exception {
        provider.createContext(ROOT + "/search/keyword.json", exchange -> reply(exchange, 200, """
                {"documents":[{"place_name":"대학교","address_name":"대학로 1","x":"128.75","y":"35.83"}]}
                """));
        provider.createContext(ROOT + "/search/address.json", exchange -> reply(exchange, 503, "{}"));

        api.perform(get(SEARCH).param("query", "대학교")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("대학교"));
    }

    @Test
    void reversesInWgs84CoordinateOrderAndKeepsCoordinatesWithoutAnAddress() throws Exception {
        var request = new AtomicReference<URI>();
        var reply = new AtomicReference<>("""
                {"documents":[{"road_address":null,"address":{"address_name":"경북 경산시 대동 1"}}]}
                """);
        provider.createContext(ROOT + "/geo/coord2address.json", exchange -> {
            request.set(exchange.getRequestURI());
            String body = reply.get();
            reply(exchange, body == null ? 502 : 200, body == null ? "{}" : body);
        });

        api.perform(reverse()).andExpect(status().isOk())
                .andExpect(jsonPath("$.roadAddress").isEmpty())
                .andExpect(jsonPath("$.jibunAddress").value("경북 경산시 대동 1"))
                .andExpect(jsonPath("$.displayName").value("경북 경산시 대동 1"));
        assertThat(request.get().getQuery()).isEqualTo("x=128.75&y=35.83&input_coord=WGS84");

        reply.set("{\"documents\":[]}");
        assertCoordinateFallback(api);
        reply.set(null);
        assertCoordinateFallback(api);
        assertCoordinateFallback(api("", Duration.ofSeconds(2)));
    }

    @Test
    void distinguishesMalformedResponsesUnavailableServiceAndTimeout() throws Exception {
        var upstreamStatus = new AtomicInteger(200);
        provider.createContext(ROOT + "/search/keyword.json", exchange -> reply(exchange, upstreamStatus.get(),
                "{\"informational\":\"" + KEY + "\"}"));

        api.perform(get(SEARCH).param("query", "station")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY));
        upstreamStatus.set(429);
        api.perform(get(SEARCH).param("query", "station")).andExpect(status().isServiceUnavailable());
        api("", Duration.ofSeconds(2)).perform(get(SEARCH).param("query", "station"))
                .andExpect(status().isServiceUnavailable());

        var release = new CountDownLatch(1);
        provider.removeContext(ROOT + "/search/keyword.json");
        provider.createContext(ROOT + "/search/keyword.json", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 16);
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            api(KEY, Duration.ofMillis(200)).perform(get(SEARCH).param("query", "station"))
                    .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"));
        } finally {
            release.countDown();
        }
    }

    private MockMvc api(String key, Duration timeout) {
        URI endpoint = URI.create("http://127.0.0.1:" + provider.getAddress().getPort());
        var kakao = new KakaoLocalClient(new JsonMapper(), key, HttpClient.newHttpClient(), endpoint, timeout);
        return MockMvcBuilders.standaloneSetup(new LocationController(kakao)).build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reverse() {
        return get(REVERSE).param("latitude", "35.83").param("longitude", "128.75");
    }

    private static void assertCoordinateFallback(MockMvc mvc) throws Exception {
        mvc.perform(reverse()).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(35.83)).andExpect(jsonPath("$.longitude").value(128.75))
                .andExpect(jsonPath("$.roadAddress").isEmpty()).andExpect(jsonPath("$.jibunAddress").isEmpty())
                .andExpect(jsonPath("$.displayName").value("선택한 위치"));
    }

    private static void reply(HttpExchange exchange, int status, String payload) throws IOException {
        try (exchange) {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
