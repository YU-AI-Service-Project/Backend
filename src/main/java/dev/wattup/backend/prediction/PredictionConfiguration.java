package dev.wattup.backend.prediction;

import dev.wattup.backend.station.PredictionQualityCapture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class PredictionConfiguration {
    @Bean
    EtaBatchClient etaBatchClient(JsonMapper json,
            @Value("${wattup.prediction.ai-batch-url}") String aiBatchUrl,
            @Value("${wattup.prediction.ai-bearer-token}") String aiToken,
            @Value("${wattup.prediction.ai-feature-version}") String aiFeatureVersion) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new EtaBatchClient(http, json, serviceEndpoint(aiBatchUrl, aiToken), aiToken, Duration.ofSeconds(2),
                aiFeatureVersion);
    }

    @Bean
    PredictionService predictionService(EtaHistoryRepository history, JsonMapper json, EtaBatchClient ai,
            ObjectProvider<PredictionQualityCapture> quality,
            @Value("${wattup.prediction.kakao-rest-api-key}") String kakaoKey) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var clock = Clock.systemUTC();
        var routes = new KakaoRouteClient(http, json, URI.create("https://apis-navi.kakaomobility.com/v1/directions"),
                kakaoKey, Duration.ofSeconds(2), clock);
        return new PredictionService(history, routes, ai, clock, Duration.ofSeconds(9), quality.getIfAvailable(),
                ai.featureVersion());
    }

    static URI serviceEndpoint(String value, String token) {
        if (value.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid prediction service URL");
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid prediction service URL");
        }
        if (!token.isBlank() && !"https".equals(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("Prediction service HTTPS is required when a token is configured");
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
