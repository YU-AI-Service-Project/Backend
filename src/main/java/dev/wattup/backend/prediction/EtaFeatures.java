package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class EtaFeatures {
    static final int SAMPLE_COUNT = 7;
    static final long GRID_SECONDS = 300;
    private static final ZoneId MODEL_ZONE = ZoneId.of("Asia/Seoul");

    static Optional<Map<String, Number>> build(Instant asOf, ChargerProfile profile, List<List<Reading>> samples,
                                               int reliableSamples, int changes) {
        if (reliableSamples != SAMPLE_COUNT || samples.size() != SAMPLE_COUNT
                || samples.stream().anyMatch(sample -> sample.isEmpty() || sample.stream().anyMatch(Reading::unknown))) {
            return Optional.empty();
        }
        var current = samples.getLast();
        int total = current.size();
        int[] available = samples.stream().mapToInt(EtaFeatures::availableCount).toArray();
        int charging = chargingCount(current);
        var modelTime = asOf.atZone(MODEL_ZONE);
        double hour = 2 * Math.PI * (modelTime.getHour() + modelTime.getMinute() / 60.0) / 24;
        double day = 2 * Math.PI * (modelTime.getDayOfWeek().getValue() - 1) / 7;
        var elapsed = current.stream().filter(reading -> reading.status.equals("3") && reading.chargeStartedAt != null)
                .mapToDouble(reading -> Duration.between(reading.chargeStartedAt, asOf).toMillis() / 60000.0)
                .filter(minutes -> minutes >= 0 && minutes <= 2880).summaryStatistics();
        var availableStats = java.util.Arrays.stream(available).summaryStatistics();
        var features = new LinkedHashMap<String, Number>();
        features.put("totalCount", total);
        features.put("availableCount", available[6]);
        features.put("chargingCount", charging);
        features.put("otherCount", total - available[6] - charging);
        features.put("unknownCount", 0);
        features.put("hourSin", Math.sin(hour));
        features.put("hourCos", Math.cos(hour));
        features.put("dowSin", Math.sin(day));
        features.put("dowCos", Math.cos(day));
        features.put("profileIndex", switch (profile) {
            case ALL -> 0;
            case AC_SLOW -> 1;
            case DC_COMBO -> 2;
        });
        features.put("availableRatio", available[6] / (double) total);
        features.put("chargingRatio", charging / (double) total);
        features.put("availableLag5", available[5]);
        features.put("availableLag15", available[3]);
        features.put("availableLag30", available[0]);
        features.put("chargingLag15", chargingCount(samples.get(3)));
        features.put("availableMean30", availableStats.getAverage());
        features.put("availableMin30", availableStats.getMin());
        features.put("availableTrend30", available[6] - available[0]);
        features.put("observedChanges30m", changes);
        features.put("changesPerCharger30m", changes / (double) total);
        features.put("chargingElapsedMeanMin", elapsed.getAverage());
        features.put("chargingElapsedMaxMin", elapsed.getCount() == 0 ? 0.0 : elapsed.getMax());
        features.put("chargingElapsedValidRatio", charging == 0 ? 0.0 : elapsed.getCount() / (double) charging);
        features.put("meanOutputKw", current.stream().mapToDouble(Reading::outputKw).filter(kw -> kw > 0).average()
                .orElse(0.0));
        features.put("acSlowFraction", fraction(current, ChargerProfile.AC_SLOW));
        features.put("dcComboFraction", fraction(current, ChargerProfile.DC_COMBO));
        features.put("coverageLast30m", 1.0);
        features.put("historyKnown30m", 1);
        return Optional.of(Collections.unmodifiableMap(features));
    }

    private static int availableCount(List<Reading> readings) {
        return (int) readings.stream().filter(reading -> reading.status.equals("2")).count();
    }

    private static int chargingCount(List<Reading> readings) {
        return (int) readings.stream().filter(reading -> reading.status.equals("3")).count();
    }

    private static double fraction(List<Reading> readings, ChargerProfile profile) {
        return readings.stream().filter(reading -> profile.includes(reading.typeCode)).count() / (double) readings.size();
    }

    record Reading(String status, String typeCode, double outputKw, Instant chargeStartedAt) {
        boolean unknown() {
            return status == null || switch (status) {
                case "2", "3", "4", "5", "6" -> false;
                default -> true;
            };
        }
    }
}
