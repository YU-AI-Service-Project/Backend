package dev.wattup.backend.prediction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class PredictionConfiguration {
    @Bean
    PredictionService predictionService(EtaHistoryRepository history, JsonMapper json,
            @Value("${wattup.prediction.kakao-rest-api-key}") String kakaoKey,
            @Value("${wattup.prediction.ai-batch-url}") String aiBatchUrl,
            @Value("${wattup.prediction.ai-bearer-token}") String aiToken) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var clock = Clock.systemUTC();
        var routes = new KakaoRouteClient(http, json, URI.create("https://apis-navi.kakaomobility.com/v1/directions"),
                kakaoKey, Duration.ofSeconds(2), clock);
        var ai = new EtaBatchClient(http, json, batchEndpoint(aiBatchUrl), aiToken, Duration.ofSeconds(2));
        return new PredictionService(history, routes, ai, clock);
    }

    private static URI batchEndpoint(String value) {
        if (value.isBlank()) {
            return null;
        }
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid prediction AI batch URL");
        }
        return uri;
    }
}
