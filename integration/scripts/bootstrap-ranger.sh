#!/bin/sh
set -eu

service_url="$RANGER_URL/public/v2/api/service"
attempt=0
while [ "$attempt" -lt 120 ]; do
    status=$(curl -sS -o /dev/null -w '%{http_code}' \
        -u "$RANGER_USERNAME:$RANGER_PASSWORD" "$service_url" || true)
    if [ "$status" = "200" ]; then break; fi
    attempt=$((attempt + 1))
    sleep 5
done
if [ "$status" != "200" ]; then
    echo "Ranger Admin did not become ready (last HTTP status: $status)" >&2
    exit 1
fi

ensure_service() {
    name=$1
    type=$2
    configs=$3
    status=$(curl -sS -o /tmp/ranger-service-response -w '%{http_code}' \
        -u "$RANGER_USERNAME:$RANGER_PASSWORD" "$service_url/name/$name")
    if [ "$status" = "200" ]; then
        if ! jq -e --arg type "$type" '.type == $type' /tmp/ranger-service-response >/dev/null; then
            echo "Ranger test service $name has an unexpected type (expected $type)" >&2
            exit 1
        fi
        echo "Ranger test service $name already exists ($type)"
        return
    fi
    if [ "$status" != "404" ]; then
        echo "Cannot inspect Ranger test service $name (HTTP $status)" >&2
        exit 1
    fi
    payload=$(jq -n --arg name "$name" --arg type "$type" --argjson configs "$configs" \
        '{name: $name, type: $type, configs: $configs}')
    status=$(curl -sS -o /tmp/ranger-service-response -w '%{http_code}' \
        -u "$RANGER_USERNAME:$RANGER_PASSWORD" -H 'Content-Type: application/json' \
        -X POST "$service_url" --data "$payload")
    case "$status" in
        200|201) echo "Created Ranger test service $name ($type)" ;;
        *) echo "Failed to create Ranger test service $name (HTTP $status)" >&2; exit 1 ;;
    esac
}

# ServiceDefs ship with the Ranger image; fresh databases need explicit services.
# These fixture URLs are never contacted by the policy API tests.
ensure_service "$RANGER_SERVICE_NAME" hdfs \
    '{"username":"hdfs","password":"hdfs","fs.default.name":"hdfs://localhost:9000","hadoop.security.authentication":"simple","hadoop.security.authorization":"true"}'
ensure_service "${RANGER_OZONE_SERVICE_NAME:-dev_ozone}" ozone \
    '{"username":"ozone","password":"ozone","ozone.om.http-address":"http://localhost:9874","hadoop.security.authentication":"simple"}'
ensure_service "${RANGER_TRINO_SERVICE_NAME:-dev_trino}" trino \
    '{"username":"trino","jdbc.driverClassName":"io.trino.jdbc.TrinoDriver","jdbc.url":"jdbc:trino://localhost:8080"}'
ensure_service "${RANGER_KAFKA_SERVICE_NAME:-dev_kafka}" kafka \
    '{"username":"kafka","password":"kafka","zookeeper.connect":"localhost:2181"}'
