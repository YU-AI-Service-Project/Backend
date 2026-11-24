package dev.wattup.backend.prediction;

import java.time.Duration;
import java.time.Instant;

record ForecastTiming(String status, Instant requestedArrivalAt, Instant forecastAt, Integer horizonMinutes,
                      Double forecastRoundingSeconds, double featureAgeSeconds) {
    private static final long GRID_SECONDS = 300;

    static ForecastTiming calculate(Instant asOf, Instant requestAt, long etaSeconds) {
        double age = secondsBetween(asOf, requestAt);
        if (age < 0 || age > 450 || etaSeconds < 0) {
            String status = age < 0 ? "FEATURE_TIME_AFTER_REQUEST" : age > 450
                    ? "STALE_FEATURES" : "INVALID_ETA_SECONDS";
            return new ForecastTiming(status, null, null, null, null, age);
        }
        Instant arrivalAt = requestAt.plusSeconds(etaSeconds);
        if (age + etaSeconds > 3600) {
            return new ForecastTiming("HORIZON_UNSUPPORTED", arrivalAt, null, null, null, age);
        }
        long gridSteps = Math.max(1, (long) Math.ceil((age + etaSeconds) / GRID_SECONDS));
        Instant forecastAt = asOf.plusSeconds(gridSteps * GRID_SECONDS);
        return new ForecastTiming("OK", arrivalAt, forecastAt, (int) gridSteps * 5,
                secondsBetween(arrivalAt, forecastAt), age);
    }

    private static double secondsBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
