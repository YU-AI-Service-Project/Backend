ALTER TABLE collection_runs
  MODIFY status ENUM('RUNNING','SUCCEEDED','PARTIAL','FAILED','QUEUED') NOT NULL DEFAULT 'RUNNING',
  ADD COLUMN manual_requested_at DATETIME(3) NULL,
  ADD COLUMN manual_reason VARCHAR(160) NULL,
  ADD COLUMN manual_active_region VARCHAR(16) GENERATED ALWAYS AS
    (CASE WHEN manual_requested_at IS NOT NULL AND status IN ('QUEUED','RUNNING') THEN region_code END) STORED,
  ADD UNIQUE KEY uk_collection_manual_active_region (manual_active_region),
  ADD KEY idx_collection_manual_cooldown (region_code, manual_requested_at),
  ADD KEY idx_collection_manual_queue (region_code, status, run_id);
