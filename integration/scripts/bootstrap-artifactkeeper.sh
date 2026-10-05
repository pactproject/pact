#!/bin/sh
set -eu

login_response=$(curl -sS -X POST "$ARTIFACT_KEEPER_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(jq -n --arg username admin \
        --arg password "$ARTIFACT_KEEPER_ADMIN_PASSWORD" \
        '{username: $username, password: $password}')" || true)
admin_token=$(printf '%s' "$login_response" | jq -r '.access_token // empty' 2>/dev/null || true)

if [ -z "$admin_token" ]; then
    initial_response=$(curl -fsS -X POST "$ARTIFACT_KEEPER_URL/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n --arg username admin \
            --arg password "$ARTIFACT_KEEPER_INITIAL_PASSWORD" \
            '{username: $username, password: $password}')")
    initial_token=$(printf '%s' "$initial_response" | jq -er '.access_token')
    curl -fsS -X POST "$ARTIFACT_KEEPER_URL/api/v1/users/me/password" \
        -H "Authorization: Bearer $initial_token" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n --arg password "$ARTIFACT_KEEPER_ADMIN_PASSWORD" \
            '{new_password: $password}')" >/dev/null
    login_response=$(curl -fsS -X POST "$ARTIFACT_KEEPER_URL/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n --arg username admin \
            --arg password "$ARTIFACT_KEEPER_ADMIN_PASSWORD" \
            '{username: $username, password: $password}')")
    admin_token=$(printf '%s' "$login_response" | jq -er '.access_token')
    echo "Initialized the Artifact Keeper admin account"
fi

users=$(curl -fsS "$ARTIFACT_KEEPER_URL/api/v1/users?page=1&per_page=100" \
    -H "Authorization: Bearer $admin_token")
if printf '%s' "$users" | jq -e --arg username "$ARTIFACT_KEEPER_TEST_USER" \
    '.items | any(.[]; .username == $username)' >/dev/null; then
    echo "Artifact Keeper test user already exists"
else
    curl -fsS -X POST "$ARTIFACT_KEEPER_URL/api/v1/users" \
        -H "Authorization: Bearer $admin_token" \
        -H 'Content-Type: application/json' \
        --data "$(jq -n \
            --arg username "$ARTIFACT_KEEPER_TEST_USER" \
            --arg email "$ARTIFACT_KEEPER_TEST_USER@example.invalid" \
            --arg password "$ARTIFACT_KEEPER_TEST_USER_PASSWORD" \
            '{username: $username, email: $email, password: $password, is_admin: false}')" \
        >/dev/null
fi

repositories=$(curl -fsS \
    "$ARTIFACT_KEEPER_URL/api/v1/repositories?page=1&per_page=100" \
    -H "Authorization: Bearer $admin_token")
for repository in "$ARTIFACT_KEEPER_TEST_REPOSITORY" pact-it-repo-b; do
    if printf '%s' "$repositories" | jq -e --arg key "$repository" \
        '.items | any(.[]; .key == $key)' >/dev/null; then
        echo "Artifact Keeper repository $repository already exists"
    else
        curl -fsS -X POST "$ARTIFACT_KEEPER_URL/api/v1/repositories" \
            -H "Authorization: Bearer $admin_token" \
            -H 'Content-Type: application/json' \
            --data "$(jq -n --arg key "$repository" \
                '{key: $key, name: $key, format: "maven", repo_type: "local", is_public: true}')" \
            >/dev/null
    fi
done

permissions=$(curl -fsS \
    "$ARTIFACT_KEEPER_URL/api/v1/permissions?page=1&per_page=100" \
    -H "Authorization: Bearer $admin_token" | jq '.items | length')
if [ "$permissions" != "0" ]; then
    echo "Artifact Keeper test instance has $permissions permissions; expected 0." >&2
    echo "Use a fresh test volume or remove the permissions before running tests." >&2
    exit 1
fi
