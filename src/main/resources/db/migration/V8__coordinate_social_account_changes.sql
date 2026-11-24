CREATE TABLE auth_social_proof_slots (
  slot_id TINYINT UNSIGNED PRIMARY KEY,
  disconnected_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
