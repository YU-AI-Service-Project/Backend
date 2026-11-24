package dev.wattup.backend;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
class WattUpDatabaseTests {

    @Test
    void migratesTheChargingStationAndObservationSchema() {
        String testDatabaseUrl = System.getenv("WATTUP_TEST_DB_URL");
        if (!testDatabaseUrl.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to the wattup_test MySQL schema");
        }

        try (var application = new SpringApplicationBuilder(WattUpApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + testDatabaseUrl, "--wattup.collection.enabled=false")) {
            var jdbc = application.getBean(JdbcTemplate.class);
            List<String> tables = jdbc.queryForList("""
                    SELECT table_name
                      FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                    """, String.class);
            assertThat(tables).contains(
                    "charging_stations", "chargers", "collection_runs", "collection_run_pages",
                    "charger_status_observations", "charger_current_status", "charger_state_changes",
                    "users", "agreement_policies", "user_consents", "user_social_accounts", "user_email_credentials",
                    "user_refresh_sessions", "auth_email_challenges", "auth_action_tickets", "auth_request_buckets");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE
                    """, Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForList("""
                    SELECT column_name
                      FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'charger_status_observations'
                    """, String.class)).contains("charge_started_at", "observed_at", "provider_status_updated_at");
            assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        }
    }
}
