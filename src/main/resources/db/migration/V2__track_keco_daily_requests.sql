CREATE TABLE keco_daily_requests (
  request_date DATE NOT NULL PRIMARY KEY,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB;

ALTER TABLE charger_status_observations
  ADD COLUMN used_for_current BOOLEAN NOT NULL DEFAULT TRUE AFTER observed_at;
