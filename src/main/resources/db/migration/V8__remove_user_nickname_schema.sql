ALTER TABLE users
    ALTER COLUMN public_id DROP DEFAULT,
    DROP COLUMN nickname;

DROP SEQUENCE user_public_id_compat_seq;
