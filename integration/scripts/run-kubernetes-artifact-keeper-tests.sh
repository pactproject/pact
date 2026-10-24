#!/bin/sh
set -eu
umask 077

if [ "${PACT_IT_ARTIFACT_KEEPER_API_PROFILE:-current}" != current ]; then
    echo "Kubernetes/Artifact Keeper E2E requires Artifact Keeper 1.11.1 / current" >&2
    exit 2
fi

# The API server's generated admin kubeconfig belongs only to this disposable stack.
sed 's/127.0.0.1:6443/pact-kubernetes:6443/g' /kubernetes/k3s.yaml > "$KUBECONFIG"
trap 'rm -f "$KUBECONFIG"' EXIT
login_response=$(curl -fsS -X POST "$PACT_IT_ARTIFACT_KEEPER_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(jq -n --arg password "$PACT_IT_ARTIFACT_KEEPER_ADMIN_PASSWORD" \
        '{username: "admin", password: $password}')")
PACT_IT_ARTIFACT_KEEPER_TOKEN=$(printf '%s' "$login_response" | jq -er '.access_token')
export PACT_IT_ARTIFACT_KEEPER_TOKEN

mvn -B -pl pact-kubernetes -am \
    -Dtest=KubernetesArtifactKeeperLiveIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
