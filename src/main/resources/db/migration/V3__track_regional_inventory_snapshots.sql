ALTER TABLE chargers
  ADD COLUMN inventory_region CHAR(2) NULL,
  ADD COLUMN inventory_run_id BIGINT UNSIGNED NULL,
  ADD KEY idx_chargers_inventory_region_run (inventory_region, inventory_run_id);
