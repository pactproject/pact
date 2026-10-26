#!/usr/bin/env python3
"""Check TESTING.md's test inventory and named Java test references (stdlib only)."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
DOCUMENT = ROOT / "TESTING.md"


def main() -> int:
    text = DOCUMENT.read_text(encoding="utf-8")
    errors = []
    listed = set()
    references = 0
    for label, target in re.findall(r"\[([^\]\n]+)\]\(([^)\n]+)\)", text):
        if "://" in target or target.startswith("#"):
            continue
        path = (ROOT / target.split("#", 1)[0]).resolve()
        if not path.is_file():
            errors.append(f"Missing linked file: {target}")
            continue
        if not target.endswith("Test.java"):
            continue
        listed.add(path)
        references += 1
        parts = label.split("#", 1)
        if parts[0] != path.stem:
            errors.append(f"Test class label does not match file: {label} -> {target}")
        if len(parts) == 2:
            source = path.read_text(encoding="utf-8")
            method = re.escape(parts[1])
            if not re.search(r"\bvoid\s+" + method + r"\s*\(", source):
                errors.append(f"Missing referenced test method: {label} -> {target}")

    actual = {path.resolve() for path in ROOT.glob("pact-*/src/test/java/**/*Test.java")}
    for path in sorted(actual - listed):
        errors.append(f"Test class missing from TESTING.md: {path.relative_to(ROOT)}")
    for path in sorted(listed - actual):
        errors.append(f"Listed test is outside the module test inventory: {path.relative_to(ROOT)}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"TESTING.md: {len(actual)} test classes, {references} valid test references")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
