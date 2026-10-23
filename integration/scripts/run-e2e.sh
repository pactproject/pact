#!/bin/sh
set -eu

login_response=$(curl -fsS -X POST "$PACT_IT_ARTIFACT_KEEPER_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(jq -n \
        --arg username admin \
        --arg password "$PACT_IT_ARTIFACT_KEEPER_ADMIN_PASSWORD" \
        '{username: $username, password: $password}')") || exit 1
PACT_IT_ARTIFACT_KEEPER_TOKEN=$(printf '%s' "$login_response" | jq -er '.access_token') || exit 1
export PACT_IT_ARTIFACT_KEEPER_TOKEN

java --enable-preview --source 21 -cp "/opt/pact/lib/*" \
    /etc/pact/PactE2E.java \
    /etc/pact/config.yaml /etc/pact/desired-state.yaml \
    /etc/pact/empty-state.yaml /opt/pact/plugins

permissions=$(curl -fsS "$PACT_IT_ARTIFACT_KEEPER_URL/api/v1/permissions?page=1&per_page=100" \
    -H "Authorization: Bearer $PACT_IT_ARTIFACT_KEEPER_TOKEN")
printf '%s' "$permissions" | jq -e '.pagination.total == 0' >/dev/null || {
    echo "Artifact Keeper E2E permissions remain after cleanup" >&2
    exit 1
}

policies=$(curl -fsS \
    -u "$PACT_IT_RANGER_USERNAME:$PACT_IT_RANGER_PASSWORD" \
    "$PACT_IT_RANGER_BASE_URL/public/v2/api/service/$PACT_IT_RANGER_SERVICE_NAME/policy")
printf '%s' "$policies" | jq -e \
    '[.[] | select((.policyLabels // []) | index("managed"))] | length == 0' >/dev/null || {
    echo "Ranger E2E managed policies remain after cleanup" >&2
    exit 1
}
echo "PACT E2E apply and verified cleanup succeeded"
