package dev.wattup.backend.prediction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RouteEtaTests {
    private static final Instant AS_OF = Instant.parse("2026-11-12T01:00:00Z");

    @Test
    void routeUsesLongitudeLatitudeAndParsesSummary() throws Exception {
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var response = new AtomicReference<>("""
                {"routes":[{"result_code":0,"summary":{"duration":3494,"distance":19032}}]}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/directions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] data = response.get().getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, data.length > 128 * 1024 ? 0 : data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        try {
            var route = new KakaoRouteClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/directions"),
                    "test-key", Duration.ofSeconds(2), Clock.fixed(AS_OF, ZoneOffset.UTC));
            assertThat(route.find(35.1, 128.2, 35.3, 128.4))
                    .contains(new KakaoRouteClient.Route(3494, 19032, AS_OF, AS_OF));
            assertThat(authorization.get()).isEqualTo("KakaoAK test-key");
            assertThat(query.get()).contains("origin=128.2,35.1", "destination=128.4,35.3", "summary=true");
            response.set("""
                    {"routes":[{"result_code":0,"summary":{"duration":"0","distance":0}}]}
                    """);
            assertThat(route.find(35.1, 128.2, 35.3, 128.4)).isEmpty();
            response.set(" ".repeat(128 * 1024) + """
                    {"routes":[{"result_code":0,"summary":{"duration":3494,"distance":19032}}]}
                    """);
            assertThat(route.find(35.1, 128.2, 35.3, 128.4)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void roundsFromFeatureTimeAndPreservesActualArrival() {
        var result = ForecastTiming.calculate(AS_OF, AS_OF.plusSeconds(180), 600);
        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.requestedArrivalAt()).isEqualTo(AS_OF.plusSeconds(780));
        assertThat(result.forecastAt()).isEqualTo(AS_OF.plusSeconds(900));
        assertThat(result.horizonMinutes()).isEqualTo(15);
        assertThat(result.forecastRoundingSeconds()).isEqualTo(120);
        assertThat(result.featureAgeSeconds()).isEqualTo(180);
    }

    @Test
    void enforcesFreshnessMinimumHorizonAndUpperLimit() {
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF, 0).horizonMinutes()).isEqualTo(5);
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF, 3600).horizonMinutes()).isEqualTo(60);
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF.plusMillis(1), 3600).status())
                .isEqualTo("HORIZON_UNSUPPORTED");
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF.plusSeconds(450), 0).status()).isEqualTo("OK");
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF.plusMillis(450001), 0).status())
                .isEqualTo("STALE_FEATURES");
        assertThat(ForecastTiming.calculate(AS_OF, AS_OF.minusSeconds(1), 0).status())
                .isEqualTo("FEATURE_TIME_AFTER_REQUEST");
    }
}
