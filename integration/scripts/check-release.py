#!/usr/bin/env python3
"""Check PACT's integer release and Maven/image/Helm alignment (stdlib only)."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def pom(path):
    root = ET.parse(path).getroot()
    for node in root.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    return root


def main():
    root = pom(ROOT / "pom.xml")
    version = root.findtext("version", "")
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    require(re.fullmatch(r"[1-9][0-9]*", version), "PACT version must be a positive integer without leading zeroes")
    require(len(sys.argv) <= 2, "Usage: check-release.py [release-number]")
    if len(sys.argv) == 2:
        require(sys.argv[1] == version, f"Requested release {sys.argv[1]!r} differs from Maven version {version!r}")
    api = root.findtext("properties/pact.api.version")
    require(api == version, "pact.api.version must match the PACT release")

    modules = {module.text: pom(ROOT / module.text / "pom.xml") for module in root.findall("modules/module")}
    versions = {name: model.findtext("version", version) for name, model in modules.items()}
    for name, model in modules.items():
        require(model.findtext("parent/version") == version, f"{name}: parent version differs from PACT")
        own_version = model.findtext("version", version)
        if name in {"pact-api", "pact-core", "pact-app"}:
            require(own_version == version, f"{name}: version differs from PACT")
        for dependency in model.findall("dependencies/dependency"):
            if dependency.findtext("groupId") != "io.github.pactproject":
                continue
            artifact = dependency.findtext("artifactId")
            if artifact not in versions:
                continue
            declared = dependency.findtext("version")
            resolved = {"${pact.api.version}": api, "${project.version}": own_version}.get(declared, declared)
            expected = versions[artifact]
            require(resolved == expected, f"{name}: dependency {artifact} resolves to {resolved!r}, expected {expected!r}")

    chart = (ROOT / "charts/pact/Chart.yaml").read_text(encoding="utf-8")
    values = (ROOT / "charts/pact/values.yaml").read_text(encoding="utf-8")
    require(re.search(rf'^appVersion: [\"\']{re.escape(version)}[\"\']\s*$', chart, re.M), "Helm appVersion must be the quoted PACT release")
    image = re.search(r"^image:\s*\n((?:[ \t]+.*\n|\n)*)", values)
    require(image and re.search(rf'^[ \t]+tag: [\"\']{re.escape(version)}[\"\']\s*$', image.group(1), re.M), "Default image.tag must be the quoted PACT release")
    require(re.search(r"^version: [0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?\s*$", chart, re.M), "Helm chart package version must remain SemVer")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"PACT release {version}: Maven, shared dependencies, image and Helm versions aligned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
