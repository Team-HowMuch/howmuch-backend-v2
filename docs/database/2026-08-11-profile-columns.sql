-- Apply before deploying the profile feature to an existing PostgreSQL database.
-- Do not silently truncate existing names; resolve records longer than 20 characters first.

BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_file_name VARCHAR(100);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE nickname IS NULL AND char_length(name) > 20) THEN
        RAISE EXCEPTION 'Cannot initialize profile nickname: users.name exceeds 20 characters.';
    END IF;
END $$;

UPDATE users
SET nickname = name
WHERE nickname IS NULL;

ALTER TABLE users
    ALTER COLUMN nickname SET NOT NULL;

COMMIT;

-- Rollback (only when profile columns are not yet used):
-- BEGIN;
-- ALTER TABLE users DROP COLUMN IF EXISTS avatar_file_name;
-- ALTER TABLE users DROP COLUMN IF EXISTS nickname;
-- COMMIT;
