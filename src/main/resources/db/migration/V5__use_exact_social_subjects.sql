ALTER TABLE user_social_accounts
  MODIFY provider_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE auth_action_tickets
  MODIFY provider_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;
