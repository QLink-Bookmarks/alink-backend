ALTER TABLE folders ADD COLUMN public_id VARCHAR(36);

CREATE UNIQUE INDEX folders_public_id_idx ON folders (public_id);
