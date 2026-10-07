#!/usr/bin/env bash
set -euo pipefail

bootstrap_servers=${1:?Usage: provision-topics.sh <bootstrap-servers> [replication-factor] [min-isr]}
replication_factor=${2:-1}
minimum_isr=${3:-1}
if [[ ! $replication_factor =~ ^[1-9][0-9]*$ || ! $minimum_isr =~ ^[1-9][0-9]*$ ]] \
    || (( minimum_isr > replication_factor )); then
  printf 'Replication factor and minimum ISR are invalid.\n' >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
topics_tool=${KAFKA_TOOLS_DIR:-/opt/kafka/bin}/kafka-topics.sh
catalog=${KAFKA_TOPIC_CATALOG:-$script_dir/topics.tsv}
connection=(--bootstrap-server "$bootstrap_servers")
if [[ -n ${KAFKA_COMMAND_CONFIG:-} ]]; then
  [[ -r $KAFKA_COMMAND_CONFIG ]] || { printf 'Private command configuration is unreadable.\n' >&2; exit 2; }
  connection+=(--command-config "$KAFKA_COMMAND_CONFIG")
fi

while IFS=$'\t' read -r topic partitions retention_ms maximum_bytes; do
  [[ -z $topic || $topic == \#* ]] && continue
  [[ $topic =~ ^[a-zA-Z0-9._-]+$ && $partitions =~ ^[1-9][0-9]*$ \
      && $retention_ms =~ ^[1-9][0-9]*$ && $maximum_bytes =~ ^[1-9][0-9]*$ ]] \
      || { printf 'Topic catalog is invalid.\n' >&2; exit 2; }
  "$topics_tool" "${connection[@]}" --create --if-not-exists --topic "$topic" \
    --partitions "$partitions" --replication-factor "$replication_factor" \
    --config "min.insync.replicas=$minimum_isr" --config "retention.ms=$retention_ms" \
    --config "max.message.bytes=$maximum_bytes" --config cleanup.policy=delete
  "$topics_tool" "${connection[@]}" --describe --topic "$topic"
done < "$catalog"

# Existing topics are described, not altered. Review drift explicitly before enabling producers.
