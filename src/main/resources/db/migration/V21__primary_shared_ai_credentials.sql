ALTER TABLE user_providers
    ADD COLUMN is_primary_shared_key BOOLEAN,
    ADD CONSTRAINT user_providers_primary_shared_key_check
        CHECK (is_primary_shared_key IS NULL OR (is_primary_shared_key AND user_role = 'SUPER_ADMIN' AND user_id IS NULL)),
    ADD CONSTRAINT user_providers_primary_shared_key_unique UNIQUE (provider_id, is_primary_shared_key);

UPDATE user_providers
SET is_primary_shared_key = TRUE
WHERE id IN (
    SELECT MIN(id)
    FROM user_providers
    WHERE user_role = 'SUPER_ADMIN' AND user_id IS NULL
    GROUP BY provider_id
);
