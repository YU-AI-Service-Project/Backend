package dev.wattup.backend.collection;

import dev.wattup.backend.operations.InternalFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/internal/collector")
public class CollectorOperationsController {
    private static final Set<String> TYPES = Set.of("STATUS_DELTA", "STATUS_FULL", "METADATA_FULL");
    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED");
    private final NamedParameterJdbcTemplate db;
    private final CollectionRunRepository collection;
    private final boolean enabled;
    private final boolean manualEnabled;
    private final String region;
    private final Clock clock;

    @Autowired
    CollectorOperationsController(NamedParameterJdbcTemplate db, JdbcTemplate jdbc,
            @Value("${wattup.collection.enabled:false}") boolean enabled,
            @Value("${wattup.internal.manual-collection-enabled:false}") boolean manualEnabled,
            @Value("${wattup.collection.region:}") String region) {
        this(db, new CollectionRunRepository(jdbc),
                enabled, manualEnabled, region, Clock.systemUTC());
    }

    CollectorOperationsController(NamedParameterJdbcTemplate db, CollectionRunRepository collection,
                                  boolean enabled, boolean manualEnabled, String region, Clock clock) {
        this.db = db;
        this.collection = collection;
        this.enabled = enabled;
        this.manualEnabled = manualEnabled;
        this.region = region;
        this.clock = clock;
    }

    @GetMapping("/health")
    Health health() {
        var params = new MapSqlParameterSource("region", region);
        var last = db.query("""
                SELECT run_type, status, received_records FROM collection_runs
                WHERE (:region='' OR region_code=:region) AND status<>'QUEUED'
                ORDER BY started_at DESC, run_id DESC LIMIT 1
                """, params, (rs, i) -> new Recent(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .stream().findFirst().orElse(new Recent(null, null, null));
        var success = db.queryForObject("""
                SELECT MAX(CASE WHEN run_type='STATUS_DELTA' THEN completed_at END),
                       MAX(CASE WHEN run_type='STATUS_FULL' THEN completed_at END)
                FROM collection_runs WHERE is_complete=TRUE AND status='SUCCEEDED' AND (:region='' OR region_code=:region)
                """, params, (rs, i) -> new Success(instant(rs, 1), instant(rs, 2)));
        Instant now = clock.instant();
        boolean healthy = recent(success.delta, now, Duration.ofMinutes(10))
                && recent(success.full, now, Duration.ofMinutes(75))
                && Set.of("SUCCEEDED", "RUNNING").contains(last.status == null ? "" : last.status);
        return new Health(!enabled ? "NOT_CONFIGURED" : healthy ? "HEALTHY" : "DEGRADED",
                success.delta, success.full, last.type, last.status, last.received);
    }

    @GetMapping("/runs")
    RunPage runs(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if ((type != null && !TYPES.contains(type)) || (status != null && !STATUSES.contains(status))
                || page < 0 || size < 1 || size > 100) {
            throw new InternalFailure(400, "INVALID_REQUEST");
        }
        long offset = (long) page * size;
        var params = new MapSqlParameterSource("region", region).addValue("type", type).addValue("status", status)
                .addValue("size", size).addValue("offset", offset);
        String where = """
                WHERE (:region='' OR r.region_code=:region) AND (:type IS NULL OR r.run_type=:type)
                    AND (:status IS NULL OR r.status=:status)
                """;
        long total = db.queryForObject("SELECT COUNT(*) FROM collection_runs r " + where, params, Long.class);
        var items = offset >= total ? List.<Run>of() : db.query("""
                SELECT r.run_id, r.run_type, r.status, r.started_at, r.completed_at, r.received_records,
                    (SELECT COUNT(*) FROM charger_state_changes c JOIN charger_status_observations o
                     ON o.observation_id=c.new_observation_id WHERE o.collection_run_id=r.run_id),
                    r.error_code, r.received_pages
                FROM collection_runs r
                """ + where + " ORDER BY r.run_id DESC LIMIT :size OFFSET :offset", params, (rs, i) -> new Run(
                rs.getLong(1), rs.getString(2), "QUEUED".equals(rs.getString(3)) ? null : instant(rs, 4),
                instant(rs, 5), rs.getString(3), rs.getInt(6), rs.getLong(7), rs.getString(8), rs.getInt(9)));
        return new RunPage(items, total, offset + items.size() < total);
    }

    @PostMapping("/runs")
    ResponseEntity<Queued> enqueue(@RequestBody Manual request) {
        if (!enabled || !manualEnabled) {
            throw new InternalFailure(503, "COLLECTION_MANUAL_UNAVAILABLE");
        }
        KecoEndpoint endpoint = switch (request.type == null ? "" : request.type) {
            case "STATUS_FULL" -> KecoEndpoint.FULL_INVENTORY;
            case "STATUS_DELTA" -> KecoEndpoint.CHANGED_STATUS;
            default -> throw new InternalFailure(400, "INVALID_REQUEST");
        };
        String reason = request.reason == null ? "" : request.reason.strip();
        if (reason.isEmpty() || reason.length() > 160 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new InternalFailure(400, "INVALID_REQUEST");
        }
        long id = collection.withCollectionLock(() -> {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (collection.nextQueued(region).isPresent()) {
                throw new InternalFailure(409, "COLLECTION_ALREADY_QUEUED");
            }
            if (collection.requestedSince(region, now.minusMinutes(5))) {
                throw new InternalFailure(429, "RATE_LIMITED");
            }
            if (endpoint == KecoEndpoint.CHANGED_STATUS && !collection.hasFullSnapshot(region)) {
                throw new InternalFailure(409, "COLLECTION_FULL_SNAPSHOT_REQUIRED");
            }
            return collection.enqueue(endpoint, region, reason, now);
        }).orElseThrow(() -> new InternalFailure(409, "COLLECTION_IN_PROGRESS"));
        return ResponseEntity.accepted().body(new Queued(Long.toString(id), "QUEUED"));
    }

    private static boolean recent(Instant value, Instant now, Duration max) {
        return value != null && !value.isAfter(now) && !value.isBefore(now.minus(max));
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    record Health(String status, Instant lastIncrementalSuccessAt, Instant lastFullSyncSuccessAt, String lastRunType,
                  String lastRunStatus, Integer lastCollectedCount) {}
    record Run(long runId, String type, Instant startedAt, Instant completedAt, String status, int receivedCount,
               long changedCount, String errorCode, int pageCount) {}
    record RunPage(List<Run> items, long total, boolean hasNext) {}
    record Manual(String type, String reason) {}
    record Queued(String runId, String status) {}
    private record Recent(String type, String status, Integer received) {}
    private record Success(Instant delta, Instant full) {}
}
