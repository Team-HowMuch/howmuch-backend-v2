CREATE SEQUENCE user_public_id_compat_seq;

ALTER TABLE users
    ADD COLUMN public_id VARCHAR(20);

UPDATE users
SET public_id = 'u_' || lpad(to_hex(id), 16, '0')
WHERE public_id IS NULL;

ALTER TABLE users
    ALTER COLUMN public_id SET DEFAULT (
        'compat_' || lpad(to_hex(nextval('user_public_id_compat_seq')), 12, '0')
    ),
    ALTER COLUMN public_id SET NOT NULL,
    ALTER COLUMN nickname DROP NOT NULL,
    ADD CONSTRAINT uk_users_public_id UNIQUE (public_id),
    ADD CONSTRAINT chk_users_public_id_format
        CHECK (public_id ~ '^[a-z0-9._]{4,20}$');

ALTER SEQUENCE user_public_id_compat_seq OWNED BY users.public_id;
