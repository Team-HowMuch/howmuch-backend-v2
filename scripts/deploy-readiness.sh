#!/usr/bin/env bash
set -euo pipefail

cd "${APP_DIR:?Set APP_DIR}"
container_id=
last_http=unavailable
report_failure() {
  result=$?
  if [ "$result" -ne 0 ]; then
    echo "Deployment failed: readiness HTTP=$last_http container=${container_id:-missing}" >&2
    if [ -n "$container_id" ]; then
      timeout 10 docker inspect --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}' "$container_id" >&2 || true
    fi
    echo 'Inspect application logs on the server with docker compose logs --tail=100 app.' >&2
  fi
}
trap report_failure EXIT
printf '%s' "${GHCR_PAT:?Set GHCR_PAT}" | docker login ghcr.io -u "${GHCR_USERNAME:?Set GHCR_USERNAME}" --password-stdin
docker compose pull
docker compose up -d

# Bound the entire readiness phase, including Docker daemon/exec stalls.
deadline=$((SECONDS + 180))
container_id=$(timeout 10 docker compose ps --all --quiet app)
if [[ -z "$container_id" || "$container_id" == *$'\n'* ]]; then
  echo 'Expected exactly one app container.' >&2
  exit 1
fi
while (( SECONDS < deadline )); do
  remaining=$((deadline - SECONDS))
  state=$(timeout "$remaining" docker inspect --format '{{.State.Status}}' "$container_id")
  case "$state" in
    running) ;;
    created) sleep 2; continue ;;
    *) echo "Application startup failed: $state" >&2; exit 1 ;;
  esac
  remaining=$((deadline - SECONDS))
  (( remaining > 0 )) || break
  last_http=$(timeout "$remaining" docker exec "$container_id" sh -c \
    'curl --silent --output /dev/null --write-out "%{http_code}" --connect-timeout 2 --max-time 5 "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-9091}/actuator/health/readiness"' 2>/dev/null) || last_http=unavailable
  if [ "$last_http" = 200 ] && (( SECONDS < deadline )); then
    echo "Application ready: container=$container_id"
    docker image prune -f
    exit 0
  fi
  sleep 2
done
echo 'Application readiness timed out after 180 seconds.' >&2
exit 1
