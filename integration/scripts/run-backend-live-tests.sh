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
    -pl pact-ranger,pact-artifactkeeper,pact-postgresql,pact-elasticsearch -am \
    -Dtest=RangerLiveIntegrationTest,ArtifactKeeperLiveIntegrationTest,PostgreSqlLiveIntegrationTest,ElasticsearchLiveIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dpact.integration=true test
