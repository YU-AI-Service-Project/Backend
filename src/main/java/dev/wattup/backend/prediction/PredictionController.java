package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import dev.wattup.backend.station.StationApiError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

@RestController
public class PredictionController {
    private static final String ARRIVAL_ETA = "ARRIVAL_ETA";
    private static final Pattern STATION_ID = Pattern.compile("KECO:[A-Za-z0-9_-]{1,80}");
    private final PredictionService service;

    PredictionController(PredictionService service) {
        this.service = service;
    }

    @GetMapping("/api/charging-stations/{stationId}/prediction")
    PredictionService.StationPrediction single(@PathVariable String stationId, @RequestParam String profile,
            @RequestParam(defaultValue = ARRIVAL_ETA) String mode, @RequestParam(required = false) Double originLatitude,
            @RequestParam(required = false) Double originLongitude) {
        if (!validStation(stationId)) {
            throw StationApiError.binding("INVALID_REQUEST", "stationId", "INVALID_FORMAT");
        }
        ChargerProfile selected = profile(profile);
        requireArrivalMode(mode);
        var prediction = service.predict(List.of(stationId), selected,
                requireOrigin(new Origin(originLatitude, originLongitude))).items().getFirst();
        if ("STATION_NOT_FOUND".equals(prediction.prediction().status())) {
            throw StationApiError.notFound();
        }
        return prediction;
    }

    @PostMapping("/api/predictions/batch")
    PredictionService.Batch batch(@RequestBody BatchRequest request) {
        ChargerProfile selected = profile(request.profile);
        requireArrivalMode(request.mode);
        List<String> ids = request.stationIds;
        if (ids == null || ids.isEmpty() || ids.size() > EtaBatchClient.MAX_ITEMS
                || ids.stream().anyMatch(id -> !validStation(id)) || new HashSet<>(ids).size() != ids.size()) {
            throw StationApiError.binding("INVALID_REQUEST", "stationIds", "EXPECTED_1_TO_30_UNIQUE_STATIONS");
        }
        var result = service.predict(ids, selected, requireOrigin(request.origin));
        if (result.items().stream().allMatch(item -> "MODEL_UNAVAILABLE".equals(item.prediction().status()))) {
            throw StationApiError.modelUnavailable();
        }
        return result;
    }

    private static boolean validStation(String stationId) {
        return stationId != null && STATION_ID.matcher(stationId).matches();
    }

    private static ChargerProfile profile(String value) {
        if (value == null) {
            throw StationApiError.binding("INVALID_REQUEST", "profile", "REQUIRED");
        }
        try {
            return ChargerProfile.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw StationApiError.unsupported("UNSUPPORTED_PROFILE", "profile");
        }
    }

    private static void requireArrivalMode(String mode) {
        if ("FIXED_30M".equals(mode)) {
            throw StationApiError.modelUnavailable();
        }
        if (mode != null && !ARRIVAL_ETA.equals(mode)) {
            throw StationApiError.binding("INVALID_REQUEST", "mode", "UNSUPPORTED");
        }
    }

    private static Origin requireOrigin(Origin origin) {
        if (origin == null || origin.latitude == null || origin.longitude == null
                || !Double.isFinite(origin.latitude) || Math.abs(origin.latitude) > 90
                || !Double.isFinite(origin.longitude) || Math.abs(origin.longitude) > 180) {
            throw StationApiError.binding("INVALID_REQUEST", "origin", "INVALID_COORDINATES");
        }
        return origin;
    }

    record Origin(Double latitude, Double longitude) {
    }

    record BatchRequest(String profile, String mode, List<String> stationIds, Origin origin) {
    }
}
