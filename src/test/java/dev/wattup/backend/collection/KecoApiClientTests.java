package dev.wattup.backend.collection;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KecoApiClientTests {

    private final AtomicReference<URI> request = new AtomicReference<>();
    private HttpServer upstream;
    private KecoApiClient client;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.start();
        client = new KecoApiClient(HttpClient.newHttpClient(), new JsonMapper(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/EvCharger/"), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void requestsRecentUpdatesAndPreservesIdentifiersAndKoreanSourceTime() {
        reply("getChargerStatus", 200, """
                {"response":{"header":{"resultCode":"00"},"body":{"pageNo":2,"numOfRows":10,"totalCount":11,
                "items":{"item":[{"statId":"ME001234","chgerId":"01","stat":"3",
                "statUpdDt":"20261124121500","nowTsdt":"20261124121400"}]}}}}
                """);

        KecoPage page = client.fetch(KecoEndpoint.CHANGED_STATUS, "test+/=", "27", 2, 10);

        assertThat(request.get().getRawQuery()).contains("serviceKey=test%2B%2F%3D", "pageNo=2",
                "numOfRows=10", "zcode=27", "period=10", "_type=json");
        assertThat(page.readings()).singleElement().satisfies(reading -> {
            assertThat(reading.chargerId()).isEqualTo("01");
            assertThat(reading.providerUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 3, 15));
            assertThat(reading.chargeStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 3, 14));
            assertThat(reading.status()).isEqualTo(ChargerStatus.CHARGING);
        });
    }

    @Test
    void readsXmlInventoryWithPaginationInTheHeader() {
        reply("getChargerInfo", 200, """
                <?xml version="1.0" encoding="UTF-8"?>
                <response>
                  <header><resultCode>00</resultCode><totalCount>11</totalCount>
                    <pageNo>2</pageNo><numOfRows>10</numOfRows></header>
                  <body><items><item>
                    <statNm>대구 &amp; 테스트</statNm><statId>ME001234</statId><chgerId>01</chgerId>
                    <stat>2</stat><statUpdDt>20261124121500</statUpdDt><nowTsdt/>
                  </item></items></body>
                </response>
                """);

        KecoPage page = client.fetch(KecoEndpoint.FULL_INVENTORY, "fixture", "27", 2, 10);

        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.totalCount()).isEqualTo(11);
        assertThat(page.readings()).singleElement().satisfies(reading -> {
            assertThat(reading.stationId()).isEqualTo("ME001234");
            assertThat(reading.chargerId()).isEqualTo("01");
            assertThat(reading.attributes().path("statNm").asText()).isEqualTo("대구 & 테스트");
            assertThat(reading.providerUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 24, 3, 15));
            assertThat(reading.chargeStartedAt()).isNull();
        });
    }

    @Test
    void readsAnEmptyXmlStatusPage() {
        reply("getChargerStatus", 200, """
                <response><header><resultCode>00</resultCode><totalCount>0</totalCount>
                  <pageNo>1</pageNo><numOfRows>0</numOfRows></header><body><items/></body></response>
                """);

        KecoPage page = client.fetch(KecoEndpoint.CHANGED_STATUS, "fixture", "27", 1, 10);

        assertThat(page.totalCount()).isZero();
        assertThat(page.readings()).isEmpty();
    }

    @Test
    void rejectsXmlDocumentTypes() {
        reply("getChargerInfo", 200, """
                <!DOCTYPE response [<!ENTITY total "0">]>
                <response><header><resultCode>00</resultCode><totalCount>&total;</totalCount>
                  <pageNo>1</pageNo><numOfRows>10</numOfRows></header><body><items/></body></response>
                """);

        assertThatThrownBy(() -> client.fetch(KecoEndpoint.FULL_INVENTORY, "fixture", "27", 1, 10))
                .isInstanceOf(KecoException.class).hasMessage("UPSTREAM_PAGE_INVALID");
    }

    @Test
    void readsFullInventoryWithoutPassingTheDeltaWindow() {
        reply("getChargerInfo", 200, """
                {"resultCode":"00","pageNo":"1","numOfRows":"10","totalCount":"1","items":{"item":
                {"statId":"ME001234","chgerId":"01","statNm":"Test station","stat":2,"nowTsdt":"00000000000000"}}}
                """);

        KecoPage page = client.fetch(KecoEndpoint.FULL_INVENTORY, "test-key", "27", 1, 10);

        assertThat(request.get().getRawQuery()).doesNotContain("period");
        assertThat(page.readings()).singleElement().satisfies(reading -> {
            assertThat(reading.attributes().path("statNm").asText()).isEqualTo("Test station");
            assertThat(reading.status()).isEqualTo(ChargerStatus.AVAILABLE);
            assertThat(reading.chargeStartedAt()).isNull();
        });
    }

    @Test
    void keepsQuotaErrorsOutOfTheRetryPathAndDoesNotLogTheKey() {
        reply("getChargerInfo", 200, """
                <OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>22</returnReasonCode>
                <errMsg>secret-value</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>
                """);

        assertThatThrownBy(() -> client.fetch(KecoEndpoint.FULL_INVENTORY, "secret-value", "27", 1, 10))
                .isInstanceOfSatisfying(KecoException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("UPSTREAM_QUOTA");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure).hasMessageNotContaining("secret-value");
                });
    }

    @Test
    void rejectsAMalformedProviderTimestamp() {
        reply("getChargerStatus", 200, """
                {"resultCode":"00","pageNo":1,"numOfRows":10,"totalCount":1,"items":[
                {"statId":"ME001234","chgerId":"01","stat":"2","statUpdDt":"20261124999999"}]}
                """);

        assertThatThrownBy(() -> client.fetch(KecoEndpoint.CHANGED_STATUS, "fixture", "27", 1, 10))
                .isInstanceOf(KecoException.class).hasMessage("UPSTREAM_PAGE_INVALID");
    }

    @Test
    void timesOutWhenHeadersArriveButTheResponseBodyStalls() {
        var headersSent = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        upstream.createContext("/EvCharger/getChargerInfo", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        client = new KecoApiClient(HttpClient.newHttpClient(), new JsonMapper(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/EvCharger/"),
                Duration.ofMillis(750));
        try {
            assertThatThrownBy(() -> client.fetch(KecoEndpoint.FULL_INVENTORY, "fixture", "27", 1, 10))
                    .isInstanceOf(KecoException.class).hasMessage("UPSTREAM_TIMEOUT");
            assertThat(headersSent.getCount()).isZero();
        } finally {
            releaseBody.countDown();
        }
    }

    private void reply(String path, int status, String body) {
        upstream.createContext("/EvCharger/" + path, exchange -> {
            request.set(exchange.getRequestURI());
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(status, data.length);
                exchange.getResponseBody().write(data);
            }
        });
    }
}
