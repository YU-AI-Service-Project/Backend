CREATE TABLE user_preferences (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  default_radius_m INT UNSIGNED NOT NULL DEFAULT 3000,
  default_profile ENUM('ALL','AC_SLOW','DC_COMBO') NOT NULL DEFAULT 'ALL',
  min_output_kw DECIMAL(8,2) NULL,
  default_charger_type_codes JSON NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_preferences_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_preference_radius CHECK (default_radius_m BETWEEN 100 AND 10000),
  CONSTRAINT chk_preference_output CHECK (
    min_output_kw IS NULL OR min_output_kw >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_favorites (
  favorite_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  station_id VARCHAR(90) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_favorite_user_station (user_id, station_id),
  KEY idx_favorite_station (station_id),
  CONSTRAINT fk_favorite_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_favorite_station FOREIGN KEY (station_id)
    REFERENCES charging_stations(station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
