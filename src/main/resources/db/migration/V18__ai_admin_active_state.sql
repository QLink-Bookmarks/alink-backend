ALTER TABLE ai_providers ADD COLUMN active_at TIMESTAMP NULL;
UPDATE ai_providers SET active_at = created_at;

ALTER TABLE available_models ADD COLUMN active_at TIMESTAMP NULL;
UPDATE available_models SET active_at = created_at;
