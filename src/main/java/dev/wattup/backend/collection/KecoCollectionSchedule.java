package dev.wattup.backend.collection;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

final class KecoCollectionSchedule {
    private final KecoCollector collector;
    private final CollectionRunRepository runs;
    private final String region;

    KecoCollectionSchedule(KecoCollector collector, CollectionRunRepository runs, String region) {
        this.collector = collector;
        this.runs = runs;
        this.region = region;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    void refreshFullSnapshot() {
        collector.collect(KecoEndpoint.FULL_INVENTORY);
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    void refreshChangedStatuses() {
        if (runs.hasFullSnapshot(region)) {
            collector.collect(KecoEndpoint.CHANGED_STATUS);
        }
    }
}
