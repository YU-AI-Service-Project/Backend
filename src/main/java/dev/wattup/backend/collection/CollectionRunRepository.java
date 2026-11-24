package dev.wattup.backend.collection;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class CollectionRunRepository {
    private final JdbcTemplate jdbc;

    CollectionRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Long> withCollectionLock(Supplier<Long> collect) {
        return jdbc.execute((ConnectionCallback<Optional<Long>>) connection -> {
            if (!acquireLock(connection)) {
                return Optional.empty();
            }
            try {
                return Optional.of(collect.get());
            } finally {
                try (var release = connection.prepareStatement("SELECT RELEASE_LOCK(CONCAT(DATABASE(), ':wattup:keco'))")) {
                    release.execute();
                }
            }
        });
    }

    private boolean acquireLock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(), ':wattup:keco'), 0)");
             var result = statement.executeQuery()) {
            return result.next() && result.getInt(1) == 1;
        }
    }

    boolean hasFullSnapshot(String region) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM collection_runs
                  WHERE run_type = 'STATUS_FULL' AND region_code = ? AND is_complete = TRUE
                )
                """, Boolean.class, region));
    }

    void closeInterruptedRuns(LocalDateTime now) {
        jdbc.update("""
                UPDATE collection_runs SET status = 'FAILED', completed_at = ?, error_code = 'RUN_INTERRUPTED'
                WHERE status = 'RUNNING'
                """, now);
    }

    long start(KecoEndpoint endpoint, String region, LocalDateTime startedAt) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO collection_runs (run_type, region_code, started_at, window_from, window_to)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, endpoint.runType);
            statement.setString(2, region);
            statement.setObject(3, startedAt);
            statement.setObject(4, endpoint == KecoEndpoint.CHANGED_STATUS ? startedAt.minusMinutes(10) : null);
            statement.setObject(5, endpoint == KecoEndpoint.CHANGED_STATUS ? startedAt : null);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    boolean reserveRequest(LocalDate seoulDay, int limit) {
        jdbc.update("""
                INSERT INTO keco_daily_requests (request_date) VALUES (?) AS incoming
                ON DUPLICATE KEY UPDATE request_date = incoming.request_date
                """, seoulDay);
        return jdbc.update("""
                UPDATE keco_daily_requests SET attempt_count = attempt_count + 1
                WHERE request_date = ? AND attempt_count < ?
                """, seoulDay, limit) == 1;
    }

    void page(long runId, int number, int attempts, KecoPage page, LocalDateTime completedAt, String errorCode) {
        jdbc.update("""
                INSERT INTO collection_run_pages (run_id, page_number, status, attempt_count,
                  provider_total_count, received_records, completed_at, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, number, errorCode == null ? "SUCCEEDED" : "FAILED", attempts,
                page == null ? null : page.totalCount(), page == null ? 0 : page.readings().size(),
                completedAt, errorCode);
    }

    void finish(long runId, Integer expectedPages, Integer providerTotal, int pages, int records,
                int observations, LocalDateTime completedAt, String errorCode) {
        boolean complete = errorCode == null;
        String status = complete ? "SUCCEEDED" : pages > 0 ? "PARTIAL" : "FAILED";
        int updated = jdbc.update("""
                UPDATE collection_runs SET status = ?, is_complete = ?, completed_at = ?, expected_pages = ?,
                  provider_total_count = ?, received_pages = ?, received_records = ?, saved_observations = ?,
                  error_code = ?
                WHERE run_id = ? AND status = 'RUNNING'
                """, status, complete, completedAt, expectedPages, providerTotal, pages, records,
                observations, errorCode, runId);
        if (complete && updated != 1) {
            throw new KecoException("COLLECTION_RUN_SUPERSEDED", false);
        }
    }
}
