package dev.wattup.backend.station;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
class RecommendationController {
    private final RecommendationService service;
    private final int candidateLimit;

    RecommendationController(RecommendationService service,
                             @Value("${wattup.recommendation.candidate-limit:10}") int candidateLimit) {
        if (candidateLimit < 1 || candidateLimit > 30) {
            throw new IllegalArgumentException("Recommendation candidate limit must be between 1 and 30");
        }
        this.service = service;
        this.candidateLimit = candidateLimit;
    }

    @GetMapping("/api/recommendations")
    RecommendationService.Result recommendations(@RequestParam double latitude, @RequestParam double longitude,
            @RequestParam(defaultValue = "3000") int radiusM, @RequestParam String profile,
            @RequestParam(required = false) BigDecimal minOutputKw, @RequestParam(defaultValue = "ARRIVAL_ETA") String mode,
            @RequestParam(defaultValue = "3") int limit) {
        if (limit < 1 || limit > 3) {
            throw StationApiError.invalid("INVALID_REQUEST", "limit");
        }
        if ("FIXED_30M".equals(mode)) {
            throw StationApiError.modelUnavailable();
        }
        if (!"ARRIVAL_ETA".equals(mode)) {
            throw StationApiError.binding("INVALID_REQUEST", "mode", "UNSUPPORTED");
        }
        var filter = ChargerFilter.forRecommendation(profile, minOutputKw);
        var search = StationSearch.of(latitude, longitude, radiusM, candidateLimit, false, "DISTANCE", filter);
        return service.recommend(search, limit);
    }
}
