CREATE TABLE eta_prediction_samples (
  sample_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  station_id VARCHAR(90) NOT NULL,
  charger_profile ENUM('ALL','AC_SLOW','DC_COMBO') NOT NULL,
  model_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  feature_version VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  predicted_at DATETIME(3) NOT NULL,
  requested_arrival_at DATETIME(3) NOT NULL,
  forecast_at DATETIME(3) NOT NULL,
  probability DECIMAL(16,15) NOT NULL,
  charger_ids JSON NOT NULL,
  UNIQUE KEY uk_eta_sample_target (station_id, charger_profile, forecast_at, model_version, feature_version),
  KEY idx_eta_sample_retention (forecast_at),
  CONSTRAINT fk_eta_sample_station FOREIGN KEY (station_id)
    REFERENCES charging_stations(station_id) ON DELETE CASCADE,
  CONSTRAINT chk_eta_sample_probability CHECK (probability BETWEEN 0 AND 1),
  CONSTRAINT chk_eta_sample_timing CHECK (predicted_at <= requested_arrival_at AND requested_arrival_at <= forecast_at),
  CONSTRAINT chk_eta_sample_cohort CHECK (JSON_TYPE(charger_ids) = 'ARRAY' AND JSON_LENGTH(charger_ids) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
