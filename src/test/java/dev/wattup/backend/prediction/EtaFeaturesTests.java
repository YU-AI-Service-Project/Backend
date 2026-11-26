package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EtaFeaturesTests {
    private static final Instant AS_OF = Instant.parse("2026-11-12T01:00:00Z");

    @Test
    void reproducesSevenGridFeaturesWithChargersChangingState() {
        var samples = IntStream.range(0, 7).mapToObj(i -> List.of(
                new EtaFeatures.Reading(i >= 4 ? "3" : "2", "02", 7, AS_OF.minusSeconds(900)),
                new EtaFeatures.Reading("2", "04", 100, null))).toList();
        var features = EtaFeatures.build(AS_OF, ChargerProfile.ALL, samples, 7, 1).orElseThrow();
        assertThat(features).hasSize(29)
                .containsEntry("totalCount", 2).containsEntry("availableCount", 1)
                .containsEntry("chargingCount", 1).containsEntry("unknownCount", 0).containsEntry("otherCount", 0)
                .containsEntry("profileIndex", 0).containsEntry("historyKnown30m", 1)
                .containsEntry("availableRatio", 0.5).containsEntry("chargingRatio", 0.5)
                .containsEntry("availableLag5", 1).containsEntry("availableLag15", 2)
                .containsEntry("availableLag30", 2).containsEntry("chargingLag15", 0)
                .containsEntry("availableMin30", 1).containsEntry("availableTrend30", -1)
                .containsEntry("availableMean30", 11.0 / 7).containsEntry("coverageLast30m", 1.0)
                .containsEntry("observedChanges30m", 1).containsEntry("changesPerCharger30m", 0.5)
                .containsEntry("chargingElapsedMeanMin", 15.0)
                .containsEntry("chargingElapsedMaxMin", 15.0).containsEntry("chargingElapsedValidRatio", 1.0)
                .containsEntry("meanOutputKw", 53.5).containsEntry("acSlowFraction", 0.5)
                .containsEntry("dcComboFraction", 0.5);
        assertThat(features.get("hourSin").doubleValue()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(features.get("dowSin").doubleValue()).isCloseTo(Math.sin(6 * Math.PI / 7),
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(features.get("hourCos").doubleValue()).isCloseTo(-0.8660254037844387,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(features.get("dowCos").doubleValue()).isCloseTo(-0.900968867902419,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void buildsNineV9SignalsFromTimestampedReadingsAndRecentTransitions() {
        var current = List.of(
                new EtaFeatures.Reading("2", "02", 7, null, AS_OF.minusSeconds(1020)),
                new EtaFeatures.Reading("2", "02", 7, null, AS_OF.plusSeconds(1)),
                new EtaFeatures.Reading("3", "04", 100, AS_OF.minusSeconds(480), null),
                new EtaFeatures.Reading("3", "04", 100, AS_OF.minusSeconds(6000), null),
                new EtaFeatures.Reading("3", "04", 100, AS_OF.plusSeconds(600), null));
        var samples = java.util.Collections.nCopies(7, current);
        var movement = new EtaFeatures.Movement(7, 1, 2, 3, 4);
        var features = EtaFeatures.build(AS_OF, ChargerProfile.ALL, samples, 7, movement).orElseThrow();
        assertThat(features).hasSize(38)
                .containsEntry("observedChanges30m", 7)
                .containsEntry("availabilityLosses15", 1).containsEntry("availabilityGains15", 2)
                .containsEntry("chargeStarts30", 3).containsEntry("chargeEnds30", 4)
                .containsEntry("availableAgeMin", 17.0).containsEntry("availableAgeKnownFraction", 0.5)
                .containsEntry("chargingElapsedMin", 8.0)
                .containsEntry("recentChargingCount", 1L).containsEntry("longChargingCount", 1L);
    }

    @Test
    void refusesACollectionGapOrUnknownChargerStatus() {
        var valid = List.of(new EtaFeatures.Reading("2", "04", 100, null));
        var unknown = List.of(new EtaFeatures.Reading("9", "04", 100, null));
        assertThat(EtaFeatures.build(AS_OF, ChargerProfile.DC_COMBO,
                List.of(valid, valid, valid, valid, valid, valid, valid), 6, 0))
                .isEmpty();
        assertThat(EtaFeatures.build(AS_OF, ChargerProfile.DC_COMBO,
                List.of(valid, valid, unknown, valid, valid, valid, valid), 7, 0))
                .isEmpty();
    }
}
