UPDATE users
SET fcm_token = NULL
WHERE fcm_token IS NOT NULL
  AND BTRIM(fcm_token) = '';

WITH duplicate_targets AS (
    SELECT fcm_token
    FROM users
    WHERE fcm_token IS NOT NULL
    GROUP BY fcm_token
    HAVING COUNT(*) > 1
)
UPDATE users
SET fcm_token = NULL
FROM duplicate_targets
WHERE users.fcm_token = duplicate_targets.fcm_token;

ALTER TABLE users
    ADD CONSTRAINT uk_users_fcm_token UNIQUE (fcm_token);
