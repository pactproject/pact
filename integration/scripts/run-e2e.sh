#!/bin/sh
set -u

login_response=$(curl -fsS -X POST "$PACT_IT_ARTIFACT_KEEPER_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(jq -n \
        --arg username admin \
        --arg password "$PACT_IT_ARTIFACT_KEEPER_ADMIN_PASSWORD" \
        '{username: $username, password: $password}')") || exit 1
PACT_IT_ARTIFACT_KEEPER_TOKEN=$(printf '%s' "$login_response" | jq -er '.access_token') || exit 1
export PACT_IT_ARTIFACT_KEEPER_TOKEN

java --enable-preview -cp "/opt/pact/lib/*" \
    io.github.pactproject.app.Main \
    /etc/pact/config.yaml /opt/pact/plugins
apply_status=$?

java --enable-preview -cp "/opt/pact/lib/*" \
    io.github.pactproject.app.Main \
    /etc/pact/cleanup-config.yaml /opt/pact/plugins
cleanup_status=$?

if [ "$apply_status" -ne 0 ]; then
    echo "PACT E2E apply failed (exit $apply_status)" >&2
    exit "$apply_status"
fi
if [ "$cleanup_status" -ne 0 ]; then
    echo "PACT E2E cleanup failed (exit $cleanup_status)" >&2
    exit "$cleanup_status"
fi
