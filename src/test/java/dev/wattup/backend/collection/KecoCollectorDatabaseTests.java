package dev.wattup.backend.collection;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.wattup.backend.WattUpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KecoCollectorDatabaseTests {
    private ConfigurableApplicationContext application;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;

    @BeforeAll
    void connectToTestDatabase() {
        String testUrl = System.getenv("WATTUP_TEST_DB_URL");
        if (!testUrl.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        application = new SpringApplicationBuilder(WattUpApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + testUrl, "--wattup.collection.enabled=false");
        jdbc = application.getBean(JdbcTemplate.class);
        transactions = application.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    void closeDatabase() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void loadsInventoryIdempotentlyAndDoesNotLetLateStatusReplaceTheLatestObservation() throws IOException {
        String station = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        String full = page(1, 1, row(station, "2", "20261124120000", ""));
        AtomicInteger deltaCalls = new AtomicInteger();
        HttpServer upstream = upstream(request -> {
            if (request.getRequestURI().getPath().endsWith("getChargerInfo")) {
                return full;
            }
            return switch (deltaCalls.incrementAndGet()) {
                case 1 -> page(1, 1, row(station, "3", "20261124120500", "20261124120400"));
                case 2 -> page(1, 1, row(station, "2", "20261124120200", ""));
                default -> page(1, 1, row(station, "3", "20261124120500", "20261124120430"));
            };
        });
        try {
            var collector = collector(upstream);
            assertThat(status(collector.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(collector.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(collector.collect(KecoEndpoint.CHANGED_STATUS).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(collector.collect(KecoEndpoint.CHANGED_STATUS).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(collector.collect(KecoEndpoint.CHANGED_STATUS).orElseThrow())).isEqualTo("SUCCEEDED");
        } finally {
            upstream.stop(0);
        }
        long charger = jdbc.queryForObject("""
                SELECT charger_id FROM chargers WHERE station_id = ? AND source_charger_id = '01'
                """, Long.class, "KECO:" + station);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chargers WHERE station_id = ?",
                Long.class, "KECO:" + station)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charger_status_observations WHERE charger_id = ?",
                Long.class, charger)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charger_state_changes WHERE charger_id = ?",
                Long.class, charger)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT DATE_FORMAT(o.charge_started_at, '%Y-%m-%d %H:%i:%s')
                FROM charger_current_status c
                JOIN charger_status_observations o ON o.observation_id = c.latest_observation_id
                WHERE c.charger_id = ? AND c.normalized_status = 'CHARGING'
                """, String.class, charger)).isEqualTo("2026-11-24 03:04:30");
    }

    @Test
    void publishesInventoryWhenTheLastPageReportsItsActualRowCount() throws IOException {
        String station = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        String readings = java.util.stream.IntStream.range(1, 11)
                .mapToObj(number -> row(station + number, "2", "20261124120000", ""))
                .collect(java.util.stream.Collectors.joining(","));
        HttpServer upstream = upstream(request -> request.getRequestURI().getQuery().contains("pageNo=1")
                ? page(1, 11, readings) : page(2, 1, 11, row(station + 11, "2", "20261124120000", "")));
        try {
            long run = collector(upstream).collect(KecoEndpoint.FULL_INVENTORY).orElseThrow();
            assertThat(status(run)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT saved_observations FROM collection_runs WHERE run_id = ?",
                    Integer.class, run)).isEqualTo(11);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void recordsAnIncompleteRunWithoutPublishingItsFirstPage() throws IOException {
        String station = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        String readings = java.util.stream.IntStream.range(1, 11)
                .mapToObj(number -> row(station + number, "2", "20261124120000", ""))
                .collect(java.util.stream.Collectors.joining(","));
        HttpServer upstream = upstream(request -> request.getRequestURI().getQuery().contains("pageNo=1")
                ? page(1, 11, readings) : page(2, 11, ""));
        try {
            long run = collector(upstream).collect(KecoEndpoint.FULL_INVENTORY).orElseThrow();
            assertThat(status(run)).isEqualTo("PARTIAL");
            assertThat(jdbc.queryForList("SELECT status FROM collection_run_pages WHERE run_id = ? ORDER BY page_number",
                    String.class, run)).containsExactly("SUCCEEDED", "FAILED");
        } finally {
            upstream.stop(0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charging_stations WHERE station_id LIKE ?",
                Long.class, "KECO:" + station + "%")).isZero();
    }

    @Test
    void spendsQuotaOnRetriesAndSkipsRequestsAfterTheLimit() throws IOException {
        var calls = new AtomicInteger();
        HttpServer upstream = upstream(request -> {
            calls.incrementAndGet();
            return """
                    {"response":{"header":{"resultCode":"01"}}}
                    """;
        });
        try {
            var collector = collector(upstream, 2);
            long firstRun = collector.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow();
            long secondRun = collector.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM collection_run_pages WHERE run_id = ?",
                    Integer.class, firstRun)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collection_run_pages WHERE run_id = ?",
                    Long.class, secondRun)).isZero();
            assertThat(jdbc.queryForList("SELECT error_code FROM collection_runs WHERE run_id IN (?, ?) ORDER BY run_id",
                    String.class, firstRun, secondRun)).containsExactly("LOCAL_QUOTA_EXHAUSTED", "LOCAL_QUOTA_EXHAUSTED");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void skipsOverlappingRunsWhileAnotherConnectionHoldsTheCollectionLock() throws Exception {
        var firstRequest = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer upstream = upstream(request -> {
            firstRequest.countDown();
            try {
                if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No response release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return page(1, 0, "");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var collector = collector(upstream);
            var activeRun = executor.submit(() -> collector.collect(KecoEndpoint.FULL_INVENTORY));
            try {
                assertThat(firstRequest.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(collector.collect(KecoEndpoint.FULL_INVENTORY)).isEmpty();
            } finally {
                releaseResponse.countDown();
            }
            assertThat(status(activeRun.get(5, TimeUnit.SECONDS).orElseThrow())).isEqualTo("SUCCEEDED");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void retiresMissingInventoryOnlyInTheRegionOfACompleteSnapshot() throws IOException {
        String removed = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        String retained = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        var regionalCalls = new AtomicInteger();
        HttpServer upstream = upstream(request -> {
            if (request.getRequestURI().getQuery().contains("zcode=11")) {
                return page(1, 1, row(retained, "2", "20261124120000", ""));
            }
            return regionalCalls.incrementAndGet() == 1
                    ? page(1, 1, row(removed, "2", "20261124120000", ""))
                    : page(1, 0, "");
        });
        try {
            var daegu = collector(upstream);
            var seoul = collector(upstream, 1000, "11");
            assertThat(status(daegu.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(seoul.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow())).isEqualTo("SUCCEEDED");
            assertThat(status(daegu.collect(KecoEndpoint.FULL_INVENTORY).orElseThrow())).isEqualTo("SUCCEEDED");
        } finally {
            upstream.stop(0);
        }
        assertThat(isDeleted(removed)).isTrue();
        assertThat(isDeleted(retained)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM charger_status_observations o JOIN chargers c ON c.charger_id = o.charger_id
                WHERE c.station_id = ?
                """, Long.class, "KECO:" + removed)).isEqualTo(1L);
    }

    @Test
    void rollsBackInventoryIfTheRunWasSupersededBeforePublishing() throws IOException {
        String station = "TEST" + UUID.randomUUID().toString().substring(0, 8);
        HttpServer upstream = upstream(request -> {
            jdbc.update("""
                    UPDATE collection_runs SET status = 'FAILED', error_code = 'RUN_INTERRUPTED'
                    WHERE status = 'RUNNING'
                    """);
            return page(1, 1, row(station, "2", "20261124120000", ""));
        });
        try {
            long run = collector(upstream).collect(KecoEndpoint.FULL_INVENTORY).orElseThrow();
            assertThat(status(run)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charging_stations WHERE station_id = ?",
                    Long.class, "KECO:" + station)).isZero();
        } finally {
            upstream.stop(0);
        }
    }

    private boolean isDeleted(String station) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT source_deleted FROM charging_stations WHERE station_id = ?", Boolean.class, "KECO:" + station));
    }

    @Test
    void queuesManualCollectionOnceAndPublishesItsExecutionInHistory() throws Exception {
        String region = "31";
        var clock = Clock.systemUTC();
        var operations = new CollectorOperationsController(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                new CollectionRunRepository(jdbc), true, true, region, clock);
        var request = new CollectorOperationsController.Manual("STATUS_FULL", "test manual refresh");
        var server = upstream(exchange -> page(1, 0, ""));
        try {
            var response = operations.enqueue(request);
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            long runId = Long.parseLong(java.util.Objects.requireNonNull(response.getBody()).runId());
            try {
                assertThat(status(runId)).isEqualTo("QUEUED");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> operations.enqueue(request))
                        .isInstanceOf(dev.wattup.backend.operations.InternalFailure.class);
                assertThat(operations.runs("STATUS_FULL", "QUEUED", 0, 10).items()).singleElement()
                        .satisfies(run -> assertThat(run.startedAt()).isNull());
                collector(server, 1000, region).collectQueued();
                assertThat(status(runId)).isEqualTo("SUCCEEDED");
                assertThat(operations.runs("STATUS_FULL", "SUCCEEDED", 0, 10).items())
                        .filteredOn(run -> run.runId() == runId).singleElement()
                        .satisfies(run -> {
                            assertThat(run.runId()).isEqualTo(runId);
                            assertThat(run.completedAt()).isNotNull();
                            assertThat(run.pageCount()).isEqualTo(1);
                        });
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> operations.enqueue(request))
                        .isInstanceOf(dev.wattup.backend.operations.InternalFailure.class);
            } finally {
                jdbc.update("DELETE FROM collection_run_pages WHERE run_id=?", runId);
                jdbc.update("DELETE FROM collection_runs WHERE run_id=?", runId);
            }
        } finally {
            server.stop(0);
        }
    }

    private KecoCollector collector(HttpServer upstream) {
        return collector(upstream, 1000);
    }

    private KecoCollector collector(HttpServer upstream, int dailyLimit) {
        return collector(upstream, dailyLimit, "27");
    }

    private KecoCollector collector(HttpServer upstream, int dailyLimit, String region) {
        var client = new KecoApiClient(HttpClient.newHttpClient(), new JsonMapper(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/"), Duration.ofSeconds(8));
        LocalDate fixtureDay = jdbc.queryForObject("""
                SELECT DATE_ADD(COALESCE(MAX(request_date), DATE '2026-11-24'), INTERVAL 1 DAY)
                FROM keco_daily_requests
                """, LocalDate.class);
        return new KecoCollector(client, new CollectionRunRepository(jdbc), new ChargerCollectionRepository(jdbc),
                transactions, new KecoCollectionSettings("fixture", region, 10, dailyLimit),
                Clock.fixed(fixtureDay.atTime(4, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private String status(long runId) {
        return jdbc.queryForObject("SELECT status FROM collection_runs WHERE run_id = ?", String.class, runId);
    }

    private static HttpServer upstream(Function<HttpExchange, String> respond) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] json = respond.apply(exchange).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
        });
        server.start();
        return server;
    }

    private static String page(int page, int total, String rows) {
        return page(page, 10, total, rows);
    }

    private static String page(int page, int size, int total, String rows) {
        return """
                {"response":{"header":{"resultCode":"00"},"body":{"pageNo":%d,"numOfRows":%d,"totalCount":%d,
                "items":{"item":[%s]}}}}
                """.formatted(page, size, total, rows);
    }

    private static String row(String station, String status, String updated, String started) {
        return """
                {"statId":"%s","chgerId":"01","statNm":"Fixture station","zcode":"27","stat":"%s",
                "statUpdDt":"%s","nowTsdt":"%s","lat":"35.8","lng":"128.6","chgerType":"02","output":"7"}
                """.formatted(station, status, updated, started);
    }
}
