#!/bin/sh
set -eu

service_url="$RANGER_URL/public/v2/api/service"
service_uri="$service_url/name/$RANGER_SERVICE_NAME"
payload=$(cat <<EOF
{"name":"$RANGER_SERVICE_NAME","type":"hdfs","configs":{"username":"hdfs","password":"hdfs","fs.default.name":"hdfs://localhost:9000","hadoop.security.authentication":"simple","hadoop.security.authorization":"true"}}
EOF
)

attempt=0
while [ "$attempt" -lt 120 ]; do
    status=$(curl -sS -o /dev/null -w '%{http_code}' \
        -u "$RANGER_USERNAME:$RANGER_PASSWORD" "$service_url" || true)
    if [ "$status" = "200" ]; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 5
done

if [ "$status" != "200" ]; then
    echo "Ranger Admin did not become ready (last HTTP status: $status)" >&2
    exit 1
fi

status=$(curl -sS -o /dev/null -w '%{http_code}' \
    -u "$RANGER_USERNAME:$RANGER_PASSWORD" "$service_uri")
if [ "$status" = "200" ]; then
    echo "Ranger test service $RANGER_SERVICE_NAME already exists"
    exit 0
fi
if [ "$status" != "404" ]; then
    echo "Cannot inspect Ranger service (HTTP $status)" >&2
    exit 1
fi

status=$(curl -sS -o /tmp/ranger-service-response -w '%{http_code}' \
    -u "$RANGER_USERNAME:$RANGER_PASSWORD" \
    -H 'Content-Type: application/json' \
    -X POST "$service_url" \
    --data "$payload")
case "$status" in
    200|201)
        echo "Created Ranger HDFS test service $RANGER_SERVICE_NAME"
        ;;
    *)
        cat /tmp/ranger-service-response >&2
        echo "Failed to create Ranger test service (HTTP $status)" >&2
        exit 1
        ;;
esac
