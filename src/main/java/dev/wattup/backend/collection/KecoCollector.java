package dev.wattup.backend.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class KecoCollector {
    private static final Logger log = LoggerFactory.getLogger(KecoCollector.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_READINGS_PER_RUN = 100_000;

    private final KecoApiClient api;
    private final CollectionRunRepository runs;
    private final ChargerCollectionRepository inventory;
    private final TransactionTemplate transaction;
    private final KecoCollectionSettings settings;
    private final Clock clock;

    KecoCollector(KecoApiClient api, CollectionRunRepository runs, ChargerCollectionRepository inventory,
                  PlatformTransactionManager transactions, KecoCollectionSettings settings, Clock clock) {
        this.api = api;
        this.runs = runs;
        this.inventory = inventory;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = settings;
        this.clock = clock;
    }

    Optional<Long> collect(KecoEndpoint endpoint) {
        return runs.withCollectionLock(() -> runCollection(endpoint, null));
    }

    void collectQueued() {
        runs.nextQueued(settings.region()).ifPresent(queued -> runs.withCollectionLock(() ->
                runCollection(queued.endpoint(), queued)));
    }

    private long runCollection(KecoEndpoint endpoint, CollectionRunRepository.Queued queued) {
        LocalDateTime startedAt = utcNow();
        runs.closeInterruptedRuns(startedAt);
        if (queued != null && !runs.claim(queued, settings.region(), startedAt)) {
            return queued.id();
        }
        long runId = queued == null ? runs.start(endpoint, settings.region(), startedAt) : queued.id();
        var batch = new Batch(settings.pageSize());
        try {
            fetchPages(runId, endpoint, batch);
            transaction.executeWithoutResult(status -> {
                int observations = inventory.publish(runId, endpoint, settings.region(), batch.readings);
                finish(runId, batch, observations, null);
            });
        } catch (KecoException e) {
            fail(runId, batch, e.code());
        } catch (DataAccessException e) {
            fail(runId, batch, "COLLECTION_DB_WRITE_FAILED");
        }
        return runId;
    }

    private void fail(long runId, Batch batch, String code) {
        finish(runId, batch, 0, code);
        log.warn("KECO collection {} finished with {}", runId, code);
    }

    private void fetchPages(long runId, KecoEndpoint endpoint, Batch batch) {
        for (int number = 1; batch.expectedPages == null || number <= batch.expectedPages; number++) {
            PageAttempt result = fetchPage(endpoint, number);
            LocalDateTime observedAt = utcNow();
            String error = result.error() != null ? result.error() : batch.accept(result.page(), number, observedAt);
            if (result.attempts() > 0) {
                runs.page(runId, number, result.attempts(), result.page(), observedAt, error);
            }
            if (error != null) {
                throw new KecoException(error, false);
            }
        }
    }

    private PageAttempt fetchPage(KecoEndpoint endpoint, int number) {
        for (int attempts = 1; attempts <= MAX_ATTEMPTS; attempts++) {
            LocalDate day = LocalDate.now(clock.withZone(KecoApiClient.PROVIDER_ZONE));
            if (!runs.reserveRequest(day, settings.dailyLimit())) {
                return new PageAttempt(null, attempts - 1, "LOCAL_QUOTA_EXHAUSTED");
            }
            try {
                return new PageAttempt(api.fetch(endpoint, settings.serviceKey(), settings.region(),
                        number, settings.pageSize()), attempts, null);
            } catch (KecoException e) {
                if (!e.retryable() || attempts == MAX_ATTEMPTS) {
                    return new PageAttempt(null, attempts, e.code());
                }
                if (!backoff(attempts)) {
                    return new PageAttempt(null, attempts, "COLLECTION_INTERRUPTED");
                }
            }
        }
        throw new IllegalStateException("KECO page attempts exhausted");
    }

    private static boolean backoff(int attempts) {
        try {
            Thread.sleep(Duration.ofMillis(200L << (attempts - 1)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void finish(long runId, Batch batch, int observations, String errorCode) {
        runs.finish(runId, batch.expectedPages, batch.totalCount, batch.pages, batch.readings.size(),
                observations, utcNow(), errorCode);
    }

    private record PageAttempt(KecoPage page, int attempts, String error) {
    }

    private record ChargerKey(String stationId, String chargerId) {
    }

    private static final class Batch {
        private final int pageSize;
        private final List<ObservedReading> readings = new ArrayList<>();
        private final Set<ChargerKey> chargerKeys = new HashSet<>();
        private Integer expectedPages;
        private Integer totalCount;
        private int pages;

        private Batch(int pageSize) {
            this.pageSize = pageSize;
        }

        private String accept(KecoPage page, int number, LocalDateTime observedAt) {
            if (page.pageNumber() != number || page.pageSize() != pageSize) {
                return "UPSTREAM_PAGE_MISMATCH";
            }
            if (totalCount == null) {
                totalCount = page.totalCount();
                if (totalCount > MAX_READINGS_PER_RUN) {
                    return "COLLECTION_REGION_TOO_LARGE";
                }
                expectedPages = Math.max(1, Math.ceilDiv(totalCount, pageSize));
            }
            int expectedRows = Math.min(pageSize, totalCount - readings.size());
            if (totalCount != page.totalCount() || page.readings().size() != expectedRows) {
                return "UPSTREAM_PAGE_COUNT_MISMATCH";
            }
            for (KecoReading reading : page.readings()) {
                if (!chargerKeys.add(new ChargerKey(reading.stationId(), reading.chargerId()))) {
                    return "UPSTREAM_DUPLICATE_CHARGER";
                }
            }
            for (KecoReading reading : page.readings()) {
                readings.add(new ObservedReading(reading, observedAt));
            }
            pages++;
            return null;
        }
    }
}
