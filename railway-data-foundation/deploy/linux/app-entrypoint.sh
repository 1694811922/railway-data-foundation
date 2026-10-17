#!/bin/sh
set -eu
if [ "${SERVICE_KIND:-}" = foundation ]; then
  test -r /run/railway-public/cluster.id || { echo 'Kafka initialization is incomplete' >&2; exit 1; }
  KAFKA_SUBSCRIPTION_CLUSTER_ID=$(cat /run/railway-public/cluster.id)
  case "$KAFKA_SUBSCRIPTION_CLUSTER_ID" in ''|*[!A-Za-z0-9_-]*) echo 'Invalid Kafka cluster identity' >&2; exit 1;; esac
  export KAFKA_SUBSCRIPTION_CLUSTER_ID
fi
exec java -XX:MaxRAMPercentage=65 -Dfile.encoding=UTF-8 -jar /opt/app/app.jar "$@"
