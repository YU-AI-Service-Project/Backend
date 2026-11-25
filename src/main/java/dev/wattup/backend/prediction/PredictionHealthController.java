package dev.wattup.backend.prediction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class PredictionHealthController {
    private static final Set<String> READY = Set.of("HEALTHY", "UP", "OK");
    private final JsonMapper json;
    private final EtaBatchClient batch;
    private final URI probe;
    private final String token;
    private final HttpClient http;
    private final Semaphore probes = new Semaphore(2);

    @Autowired
    PredictionHealthController(JsonMapper json, EtaBatchClient batch,
            @Value("${wattup.prediction.ai-health-url:}") String probe,
            @Value("${wattup.prediction.ai-health-token:}") String token) {
        this(json, batch, PredictionConfiguration.serviceEndpoint(probe, token), token,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    PredictionHealthController(JsonMapper json, EtaBatchClient batch, URI probe, String token, HttpClient http) {
        this.json = json;
        this.batch = batch;
        this.probe = probe;
        this.token = token;
        this.http = http;
    }

    @GetMapping("/internal/ai/health")
    Health health() {
        String upstream = upstream();
        var recorded = batch.activity();
        boolean recentSuccess = recorded.lastSuccessAt() != null
                && recorded.lastSuccessAt().isAfter(Instant.now().minus(Duration.ofMinutes(15)))
                && "SUCCEEDED".equals(recorded.lastRequestStatus());
        String status = !batch.configured() && probe == null ? "NOT_CONFIGURED"
                : recentSuccess && (probe == null || "HEALTHY".equals(upstream)) ? "HEALTHY" : "DEGRADED";
        return new Health(status, upstream, recorded.modelVersion(), recorded.lastSuccessAt(), recorded.latencyMs(),
                recorded.lastAttemptAt(), recorded.lastRequestStatus());
    }

    private String upstream() {
        if (probe == null) {
            return "NOT_CONFIGURED";
        }
        if (!probes.tryAcquire()) {
            return "UNAVAILABLE";
        }
        try {
            var request = HttpRequest.newBuilder(probe).timeout(Duration.ofSeconds(2)).header("Accept", "application/json").GET();
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            var result = http.sendAsync(request.build(), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 16 * 1024));
            try {
                var response = result.get(2, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    return "UNAVAILABLE";
                }
                var body = json.readTree(response.body());
                return body != null && READY.contains(body.path("status").asText()) ? "HEALTHY" : "DEGRADED";
            } catch (InterruptedException e) {
                result.cancel(true);
                Thread.currentThread().interrupt();
                return "UNAVAILABLE";
            } catch (TimeoutException e) {
                result.cancel(true);
                return "UNAVAILABLE";
            } catch (ExecutionException | JacksonException e) {
                return "UNAVAILABLE";
            }
        } finally {
            probes.release();
        }
    }

    record Health(String status, String upstreamStatus, String modelVersion, Instant lastSuccessAt, Long latencyMs,
                  Instant lastAttemptAt, String lastRequestStatus) {}
}
