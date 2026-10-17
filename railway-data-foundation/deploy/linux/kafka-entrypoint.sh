#!/bin/sh
set -eu
umask 077
base=/var/lib/railway/kafka
meta=$base/data/meta.properties
identity=/run/railway/kafka.identity
config=/run/railway/server.properties
run_local() {
  java -Xmx256m -Dlogback.configurationFile=/opt/kafka/logback.xml -cp '/opt/kafka/classes:/opt/kafka/libs/*' LocalKafka "$@"
}
case "${1:-serve}" in
  serve)
    if [ ! -f "$meta" ]; then
      test -f /run/railway-bootstrap/first-boot.allowed || { echo 'Kafka storage was initialized previously; restore it, do not format' >&2; exit 1; }
      test ! -f /run/railway-public/cluster.id || { echo 'Original Kafka metadata is missing; restore it' >&2; exit 1; }
      if [ -d "$base/data" ] && [ -n "$(find "$base/data" -mindepth 1 -print -quit)" ]; then
        echo 'Kafka data exists without metadata; refusing to reformat' >&2; exit 1
      fi
      test ! -f "$base/backend.env" || { echo 'Kafka metadata missing; restore matching data' >&2; exit 1; }
      touch "$base/initializing"
      run_local format "$identity" "$config"
    elif [ ! -f "$base/backend.env" ] && [ ! -f "$base/initializing" ]; then
      echo 'Kafka private overlay missing; refusing credential recreation' >&2; exit 1
    fi
    exec java -Xms256m -Xmx1024m -Dlogback.configurationFile=/opt/kafka/logback.xml -cp '/opt/kafka/classes:/opt/kafka/libs/*' LocalKafka serve "$config" /tmp/railway-stop.request
    ;;
  initialize)
    if [ -f "$base/backend.env" ]; then
      run_local setup "$identity" "$meta"
    elif [ -f "$base/initializing" ]; then
      run_local initialize "$identity" "$meta"
      rm -f "$base/initializing"
    else
      echo 'Not a fresh Kafka initialization; restore the original private configuration' >&2; exit 1
    fi
    # Only the public cluster ID is shared with Foundation, never message files.
    actual=$(sed -n 's/^cluster.id=//p' "$meta")
    if [ -f /run/railway-public/cluster.id ] && [ "$(cat /run/railway-public/cluster.id)" != "$actual" ]; then
      echo 'Kafka cluster identity changed; refusing to repin existing grants' >&2; exit 1
    fi
    umask 022
    printf '%s\n' "$actual" > /run/railway-public/cluster.id
    test -s /run/railway-public/cluster.id
    rm -f /run/railway-bootstrap/first-boot.allowed
    ;;
  health) run_local check "$identity" "$meta" ;;
  *) echo 'Unknown Kafka operation' >&2; exit 1 ;;
esac
