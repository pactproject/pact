#!/bin/sh
set -eu

login_response=$(curl -fsS -X POST "$PACT_IT_ARTIFACT_KEEPER_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(jq -n \
        --arg username admin \
        --arg password "$PACT_IT_ARTIFACT_KEEPER_ADMIN_PASSWORD" \
        '{username: $username, password: $password}')")
PACT_IT_ARTIFACT_KEEPER_TOKEN=$(printf '%s' "$login_response" | jq -er '.access_token')
export PACT_IT_ARTIFACT_KEEPER_TOKEN

exec mvn -B \
    -pl "${PACT_IT_BACKEND_MODULES:-pact-ranger,pact-artifact-keeper,pact-postgresql,pact-elasticsearch}" -am \
    "-Dtest=${PACT_IT_BACKEND_TESTS:-RangerLiveIntegrationTest,ArtifactKeeperLiveIntegrationTest,ArtifactKeeperEnforcementLiveIntegrationTest,PostgreSqlLiveIntegrationTest,ElasticsearchLiveIntegrationTest}" \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dpact.integration=true test
