#!/usr/bin/env bash
set -euo pipefail
if [ ! -f /certificates/server/server.crt ]; then
  bin/elasticsearch-certutil ca --silent --pem --out /tmp/ca.zip
  unzip -q /tmp/ca.zip -d /certificates
  cat > /tmp/instances.yml <<'YAML'
instances:
  - name: server
    dns: [localhost, pact-elasticsearch]
    ip: [127.0.0.1]
YAML
  bin/elasticsearch-certutil cert --silent --pem \
    --in /tmp/instances.yml --ca-cert /certificates/ca/ca.crt \
    --ca-key /certificates/ca/ca.key --out /tmp/server.zip
  unzip -q /tmp/server.zip -d /certificates
  chown -R root:root /certificates
  find /certificates -type d -exec chmod 750 {} \;
  find /certificates -type f -exec chmod 640 {} \;
fi
