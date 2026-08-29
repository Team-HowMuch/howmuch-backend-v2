UPDATE users
SET fcm_token = NULL
WHERE fcm_token IS NOT NULL
  AND BTRIM(fcm_token) = '';

WITH ranked_targets AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY fcm_token
               ORDER BY updated_at DESC, id DESC
           ) AS target_rank
    FROM users
    WHERE fcm_token IS NOT NULL
)
UPDATE users
SET fcm_token = NULL
FROM ranked_targets
WHERE users.id = ranked_targets.id
  AND ranked_targets.target_rank > 1;

ALTER TABLE users
    ADD CONSTRAINT uk_users_fcm_token UNIQUE (fcm_token);
