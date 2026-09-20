#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "DEPENDENCY_VERIFICATION_REJECTED:$1" >&2
  exit 1
}

metadata="gradle/verification-metadata.xml"
test -s "$metadata" || fail "verification-metadata-missing"

python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

path = Path("gradle/verification-metadata.xml")
try:
    root = ET.parse(path).getroot()
except ET.ParseError as exc:
    raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:xml-parse:{exc}")

def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

if local(root.tag) != "verification-metadata":
    raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:root-element")

configuration = next((node for node in root if local(node.tag) == "configuration"), None)
if configuration is None:
    raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:configuration-missing")
settings = {local(node.tag): (node.text or "").strip().lower() for node in configuration}
if settings.get("verify-metadata") != "true":
    raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:verify-metadata-not-true")

components = next((node for node in root if local(node.tag) == "components"), None)
if components is None:
    raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:components-missing")

component_count = 0
artifact_count = 0
sha256_count = 0
hex64 = re.compile(r"^[0-9a-fA-F]{64}$")

for component in components:
    if local(component.tag) != "component":
        continue
    component_count += 1
    for key in ("group", "name", "version"):
        if not component.attrib.get(key):
            raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:component-{key}-missing")
    for artifact in component:
        if local(artifact.tag) != "artifact":
            continue
        artifact_count += 1
        hashes = [
            node.attrib.get("value", "")
            for node in artifact
            if local(node.tag) == "sha256"
        ]
        if not hashes:
            name = artifact.attrib.get("name", "<unnamed>")
            raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:artifact-without-sha256:{name}")
        for value in hashes:
            if not hex64.fullmatch(value):
                raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:invalid-sha256")
            sha256_count += 1

if component_count < 10:
    raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:component-count-too-small:{component_count}")
if artifact_count < 10 or sha256_count < artifact_count:
    raise SystemExit(
        f"DEPENDENCY_VERIFICATION_REJECTED:coverage-too-small:"
        f"artifacts={artifact_count}:sha256={sha256_count}"
    )

for trusted in root.iter():
    if local(trusted.tag) != "trusted-artifact":
        continue
    attrs = trusted.attrib
    if any(attrs.get(key) in {"*", ".*"} for key in ("group", "name", "version", "file")):
        raise SystemExit("DEPENDENCY_VERIFICATION_REJECTED:wildcard-trusted-artifact")

scan_roots = [Path(".github")]
optional = Path("gradle.properties")
scan_files = []
for scan_root in scan_roots:
    scan_files.extend(p for p in scan_root.rglob("*") if p.is_file())
if optional.exists():
    scan_files.append(optional)

for candidate in scan_files:
    if candidate == Path(".github/scripts/ci-dependency-verification-contract.sh"):
        continue
    try:
        text = candidate.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if re.search(r"--dependency-verification\s*=\s*(?:off|lenient)\b", text, re.I):
        raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:verification-disabled:{candidate}")
    if re.search(r"org\.gradle\.dependency\.verification\s*=\s*(?:off|lenient)\b", text, re.I):
        raise SystemExit(f"DEPENDENCY_VERIFICATION_REJECTED:verification-property-disabled:{candidate}")

print(
    "DEPENDENCY_VERIFICATION_OK:"
    f"components={component_count}:artifacts={artifact_count}:sha256={sha256_count}"
)
PY
