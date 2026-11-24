CREATE TABLE agreement_policies (
  policy_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  policy_type ENUM('TERMS','PRIVACY','OPTIONAL_MARKETING') NOT NULL,
  policy_version VARCHAR(50) NOT NULL,
  title VARCHAR(150) NOT NULL,
  document_url VARCHAR(1000) NULL,
  is_required BOOLEAN NOT NULL,
  is_current BOOLEAN NOT NULL DEFAULT FALSE,
  published_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_policy_type_version (policy_type, policy_version),
  current_policy_type VARCHAR(24) GENERATED ALWAYS AS (IF(is_current, policy_type, NULL)) STORED,
  UNIQUE KEY uk_policy_current (current_policy_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
  user_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  nickname VARCHAR(60) NOT NULL,
  profile_image_url VARCHAR(1000) NULL,
  account_status ENUM('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  withdrawn_at DATETIME(3) NULL,
  access_tokens_valid_after DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_social_accounts (
  social_account_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  provider ENUM('KAKAO','GOOGLE') NOT NULL,
  provider_user_id VARCHAR(255) NOT NULL,
  provider_email VARCHAR(320) NULL,
  connected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_provider_account (provider, provider_user_id),
  UNIQUE KEY uk_user_provider (user_id, provider),
  CONSTRAINT fk_social_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_email_credentials (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  email_verified_at DATETIME(3) NOT NULL,
  password_changed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_email_credential_email (email),
  CONSTRAINT fk_email_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_consents (
  consent_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  consent_type ENUM('TERMS','PRIVACY','OPTIONAL_MARKETING') NOT NULL,
  policy_version VARCHAR(50) NOT NULL,
  agreed BOOLEAN NOT NULL,
  decided_at DATETIME(3) NOT NULL,
  KEY idx_consent_user_type (user_id, consent_type, decided_at),
  CONSTRAINT fk_consent_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_refresh_sessions (
  session_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  session_family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  device_label VARCHAR(120) NULL,
  refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  last_used_at DATETIME(3) NULL,
  revoked_reason ENUM('LOGOUT','LOGOUT_ALL','ROTATED','PASSWORD_CHANGED','WITHDRAWN','REPLAY_DETECTED','ADMIN_REVOKED') NULL,
  UNIQUE KEY uk_session_hash (refresh_token_hash),
  KEY idx_session_user_expiry (user_id, expires_at),
  KEY idx_session_family (session_family_id, issued_at),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_session_expiry CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_email_challenges (
  challenge_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(320) NOT NULL,
  purpose ENUM('SIGNUP') NOT NULL,
  code_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status ENUM('PENDING','VERIFIED','CONSUMED','EXPIRED','LOCKED')
    NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  issued_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  verified_at DATETIME(3) NULL,
  consumed_at DATETIME(3) NULL,
  KEY idx_email_challenge (email, purpose, issued_at),
  KEY idx_email_challenge_expiry (status, expires_at),
  CONSTRAINT chk_email_challenge_expiry CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_action_tickets (
  ticket_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ticket_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose ENUM('SOCIAL_SIGNUP','EMAIL_SIGNUP','PASSWORD_RESET',
    'REAUTH_WITHDRAW','REAUTH_LINK_SOCIAL','REAUTH_UNLINK_SOCIAL') NOT NULL,
  user_id BIGINT UNSIGNED NULL,
  bound_session_family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  email VARCHAR(320) NULL,
  provider ENUM('KAKAO','GOOGLE') NULL,
  provider_user_id VARCHAR(255) NULL,
  context_json JSON NULL,
  issued_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  consumed_at DATETIME(3) NULL,
  UNIQUE KEY uk_auth_ticket_hash (ticket_hash),
  KEY idx_auth_ticket_user_purpose (user_id, purpose, expires_at),
  KEY idx_auth_ticket_expiry (purpose, expires_at),
  CONSTRAINT fk_auth_ticket_user FOREIGN KEY (user_id)
    REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_auth_ticket_expiry CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_request_buckets (
  bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  window_started_at DATETIME(3) NOT NULL,
  hit_count INT UNSIGNED NOT NULL,
  KEY idx_auth_buckets_window (window_started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
