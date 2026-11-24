-- Application DATETIME values are stored in UTC.
CREATE TABLE charging_stations (
  station_id VARCHAR(90) PRIMARY KEY,
  source_station_id VARCHAR(80) NOT NULL,
  station_name VARCHAR(255) NOT NULL,
  address VARCHAR(500) NULL,
  latitude DECIMAL(10,7) NULL,
  longitude DECIMAL(10,7) NULL,
  operator_id VARCHAR(30) NULL,
  operator_name VARCHAR(150) NULL,
  operator_phone VARCHAR(50) NULL,
  operating_hours VARCHAR(255) NULL,
  parking_free_code VARCHAR(10) NULL,
  access_restriction TEXT NULL,
  fee_info TEXT NULL,
  source_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  source_limited BOOLEAN NOT NULL DEFAULT FALSE,
  last_metadata_seen_at DATETIME(3) NULL,
  source_payload JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_station_source_id (source_station_id),
  KEY idx_station_location (latitude, longitude),
  KEY idx_station_active (source_deleted, source_limited),
  CONSTRAINT chk_station_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT chk_station_lng CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chargers (
  charger_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  station_id VARCHAR(90) NOT NULL,
  source_charger_id VARCHAR(40) NOT NULL,
  charger_type_code VARCHAR(16) NULL,
  connector_type_label VARCHAR(100) NULL,
  output_kw DECIMAL(8,2) NULL,
  charging_method VARCHAR(100) NULL,
  source_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  source_limited BOOLEAN NOT NULL DEFAULT FALSE,
  last_metadata_seen_at DATETIME(3) NULL,
  source_payload JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_charger_station_source (station_id, source_charger_id),
  KEY idx_charger_type_output (charger_type_code, output_kw),
  CONSTRAINT fk_charger_station FOREIGN KEY (station_id)
    REFERENCES charging_stations(station_id),
  CONSTRAINT chk_charger_kw CHECK (output_kw IS NULL OR output_kw >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collection_runs (
  run_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  run_type ENUM('METADATA_FULL','STATUS_DELTA','STATUS_FULL') NOT NULL,
  status ENUM('RUNNING','SUCCEEDED','PARTIAL','FAILED') NOT NULL DEFAULT 'RUNNING',
  region_code VARCHAR(16) NULL,
  subregion_code VARCHAR(16) NULL,
  window_from DATETIME(3) NULL,
  window_to DATETIME(3) NULL,
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  expected_pages INT UNSIGNED NULL,
  provider_total_count INT UNSIGNED NULL,
  received_pages INT UNSIGNED NOT NULL DEFAULT 0,
  received_records INT UNSIGNED NOT NULL DEFAULT 0,
  saved_observations INT UNSIGNED NOT NULL DEFAULT 0,
  is_complete BOOLEAN NOT NULL DEFAULT FALSE,
  error_code VARCHAR(100) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_runs_type_time (run_type, completed_at),
  KEY idx_runs_success (run_type, status, is_complete, completed_at),
  CONSTRAINT chk_runs_window CHECK (
    window_from IS NULL OR window_to IS NULL OR window_to >= window_from
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE collection_run_pages (
  run_id BIGINT UNSIGNED NOT NULL,
  page_number INT UNSIGNED NOT NULL,
  status ENUM('SUCCEEDED','FAILED') NOT NULL,
  attempt_count INT UNSIGNED NOT NULL,
  provider_total_count INT UNSIGNED NULL,
  received_records INT UNSIGNED NOT NULL DEFAULT 0,
  completed_at DATETIME(3) NOT NULL,
  error_code VARCHAR(100) NULL,
  PRIMARY KEY (run_id, page_number),
  CONSTRAINT fk_collection_page_run FOREIGN KEY (run_id)
    REFERENCES collection_runs(run_id),
  CONSTRAINT chk_collection_page_number CHECK (page_number > 0),
  CONSTRAINT chk_collection_page_attempt_count CHECK (attempt_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE charger_status_observations (
  observation_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  charger_id BIGINT UNSIGNED NOT NULL,
  collection_run_id BIGINT UNSIGNED NOT NULL,
  raw_status_code VARCHAR(20) NOT NULL,
  normalized_status ENUM('AVAILABLE','CHARGING','UNAVAILABLE','UNKNOWN') NOT NULL,
  provider_status_updated_at DATETIME(3) NULL,
  charge_started_at DATETIME(3) NULL,
  observed_at DATETIME(3) NOT NULL,
  source_event_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_observation_run_event (charger_id, collection_run_id, source_event_fingerprint),
  UNIQUE KEY uk_observation_charger_id (charger_id, observation_id),
  KEY idx_observation_charger_time (charger_id, observed_at),
  KEY idx_observation_time (observed_at),
  CONSTRAINT fk_observation_charger FOREIGN KEY (charger_id)
    REFERENCES chargers(charger_id),
  CONSTRAINT fk_observation_run FOREIGN KEY (collection_run_id)
    REFERENCES collection_runs(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE charger_current_status (
  charger_id BIGINT UNSIGNED PRIMARY KEY,
  latest_observation_id BIGINT UNSIGNED NOT NULL,
  raw_status_code VARCHAR(20) NOT NULL,
  normalized_status ENUM('AVAILABLE','CHARGING','UNAVAILABLE','UNKNOWN') NOT NULL,
  provider_status_updated_at DATETIME(3) NULL,
  last_observed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_current_status_time (normalized_status, last_observed_at),
  CONSTRAINT fk_current_charger FOREIGN KEY (charger_id)
    REFERENCES chargers(charger_id),
  CONSTRAINT fk_current_observation FOREIGN KEY (charger_id, latest_observation_id)
    REFERENCES charger_status_observations(charger_id, observation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE charger_state_changes (
  change_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  charger_id BIGINT UNSIGNED NOT NULL,
  previous_observation_id BIGINT UNSIGNED NOT NULL,
  new_observation_id BIGINT UNSIGNED NOT NULL,
  previous_status_code VARCHAR(20) NOT NULL,
  new_status_code VARCHAR(20) NOT NULL,
  change_observed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_change_new_observation (new_observation_id),
  KEY idx_change_charger_time (charger_id, change_observed_at),
  CONSTRAINT fk_change_charger FOREIGN KEY (charger_id)
    REFERENCES chargers(charger_id),
  CONSTRAINT fk_change_previous FOREIGN KEY (charger_id, previous_observation_id)
    REFERENCES charger_status_observations(charger_id, observation_id),
  CONSTRAINT fk_change_new FOREIGN KEY (charger_id, new_observation_id)
    REFERENCES charger_status_observations(charger_id, observation_id),
  CONSTRAINT chk_change_status CHECK (previous_status_code <> new_status_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
