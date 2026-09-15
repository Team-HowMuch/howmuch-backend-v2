#!/usr/bin/env bash
set -euo pipefail

# Use disposable databases only. Never source the application's .env here.
: "${DB_URL:?Set DB_URL to a disposable PostgreSQL database}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${REDIS_HOST:?Set REDIS_HOST to a disposable Redis instance}"
: "${REDIS_PORT:?Set REDIS_PORT}"

export SPRING_PROFILES_ACTIVE=local
export JWT_SECRET=ci-only-jwt-signing-key-never-use-outside-tests-307
export AUTH_IDENTITY_HMAC_SECRET=ci-only-identity-hmac-key-307
export SMS_SENDER=discord
export DISCORD_SMS_WEBHOOK_URL=http://127.0.0.1:9/unused
export CODEF_BASE_URL=http://127.0.0.1:9/unused
export CODEF_OAUTH_URL=http://127.0.0.1:9/unused
export CODEF_CLIENT_ID=ci-unused
export CODEF_CLIENT_SECRET=ci-unused
export CODEF_PUBLIC_KEY=
export CODEF_CONNECTED_ID_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZg==
export FIREBASE_ENABLED=false
export TREASURER_LLM_PROVIDER=none
export SETTLEMENT_NOTIFICATION_OUTBOX_ENABLED=false
export SETTLEMENT_AUTOMATIC_REMINDER_ENABLED=false
export TREASURER_ANSWER_RETENTION_ENABLED=false
unset SETTLEMENT_TRANSFER_LINK_ADAPTER
export GROUP_IMAGE_STORAGE_DIR="$PWD/build/test-storage/groups"
export PROFILE_IMAGE_STORAGE_DIR="$PWD/build/test-storage/profiles"

if [ "$#" -eq 0 ]; then
  set -- clean test
fi
./gradlew "$@" --no-daemon
mkdir -p build/reports
node --test src/test/js/*.test.cjs | tee build/reports/javascript-tests.txt
