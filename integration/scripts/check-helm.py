"""Validate parsed Helm output: default chart, CA mounts and optional init container.

Usage: python integration/scripts/check-helm.py default.yaml ca.yaml init.yaml
Requires PyYAML; Helm rendering itself is performed by the caller.
"""
import sys
from pathlib import Path
import yaml


def pod(path):
    docs = list(yaml.safe_load_all(Path(path).read_text(encoding="utf-8")))
    return next(d for d in docs if d and d.get("kind") == "Deployment")["spec"]["template"]["spec"]


default, ca, init = map(pod, sys.argv[1:])
assert {v["name"] for v in default["volumes"]} == {"config", "tmp"}
assert "initContainers" not in default
assert {v["name"] for v in ca["volumes"]} == {"config", "tmp", "elasticsearch-ca"}
mount = next(v for v in ca["containers"][0]["volumeMounts"] if v["name"] == "elasticsearch-ca")
assert mount == {"name": "elasticsearch-ca", "mountPath": "/etc/pact-ca/elasticsearch", "readOnly": True}
assert next(v for v in ca["volumes"] if v["name"] == "elasticsearch-ca")["configMap"]["name"] == "elasticsearch-ca"
assert next(v for v in ca["containers"][0]["env"] if v["name"] == "ES_PASSWORD")["valueFrom"]["secretKeyRef"]["key"] == "password"
assert init["initContainers"][0]["name"] == "prepare-truststore"
assert init["initContainers"][0]["volumeMounts"][0]["mountPath"] == "/truststore"
assert next(v for v in init["volumes"] if v["name"] == "truststore")["emptyDir"] == {}
assert init["containers"][0]["securityContext"]["readOnlyRootFilesystem"] is True
print("Helm defaults, CA mounts and init container: passed")
