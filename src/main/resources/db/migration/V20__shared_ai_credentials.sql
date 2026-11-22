ALTER TABLE user_providers ALTER COLUMN user_id DROP NOT NULL;

UPDATE user_providers
SET user_id = NULL
WHERE user_role = 'SUPER_ADMIN';

ALTER TABLE user_providers
    ADD CONSTRAINT user_providers_shared_owner_check
    CHECK (user_id IS NOT NULL OR user_role = 'SUPER_ADMIN');
