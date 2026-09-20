#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "DEPENDENCY_SUPPLY_CHAIN_REJECTED:$1" >&2
  exit 1
}

test -s settings.gradle.kts || fail "settings-missing"
test -s build.gradle.kts || fail "root-build-missing"
test -s gradle/wrapper/gradle-wrapper.properties || fail "wrapper-properties-missing"
test -s gradle/verification-metadata.xml || fail "verification-metadata-missing"

grep -Fq 'repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)' settings.gradle.kts ||
  fail "project-repositories-not-forbidden"

python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(".")
build_files = sorted(
    p for p in root.rglob("*")
    if p.is_file() and p.name in {"build.gradle.kts", "build.gradle"}
)
for path in build_files:
    text = path.read_text(encoding="utf-8")
    if path.as_posix() != "settings.gradle.kts" and re.search(r"\brepositories\s*\{", text):
        raise SystemExit(f"project-repository-block-forbidden:{path}")

    for lineno, line in enumerate(text.splitlines(), start=1):
        compact = line.strip()
        if not compact or compact.startswith("//"):
            continue
        if re.search(r'["\'][^"\']*(?:latest\.(?:release|integration)|\+|\[[^"\']*|\([^"\']*,[^"\']*\))[^"\']*["\']', compact, re.I):
            if any(token in compact for token in (
                "implementation(", "api(", "compileOnly(", "runtimeOnly(",
                "testImplementation(", "androidTestImplementation(", "version ",
                "version(",
            )):
                raise SystemExit(f"dynamic-dependency-version:{path}:{lineno}:{compact}")

settings = Path("settings.gradle.kts").read_text(encoding="utf-8")
allowed = {"google()", "mavenCentral()", "gradlePluginPortal()"}
for line in settings.splitlines():
    compact = line.strip().rstrip(";")
    if compact.startswith("maven {") or "maven(" in compact:
        raise SystemExit(f"unapproved-maven-repository:{compact}")
for token in ("google()", "mavenCentral()", "gradlePluginPortal()"):
    if token not in settings:
        raise SystemExit(f"required-repository-missing:{token}")

root_build = Path("build.gradle.kts").read_text(encoding="utf-8")
plugin_lines = [line.strip() for line in root_build.splitlines() if ' id("' in line or line.startswith('id("')]
for line in plugin_lines:
    if " version " not in line:
        raise SystemExit(f"unpinned-root-plugin:{line}")
    match = re.search(r'version\s+"([^"]+)"', line)
    if not match or not re.fullmatch(r"\d+(?:\.\d+)*(?:[-.][A-Za-z0-9]+)*", match.group(1)):
        raise SystemExit(f"invalid-root-plugin-version:{line}")

wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
if "distributionSha256Sum=" not in wrapper:
    raise SystemExit("gradle-wrapper-sha256-missing")
if not re.search(r"distributionUrl=https\\://services\.gradle\.org/distributions/gradle-[0-9.]+-bin\.zip", wrapper):
    raise SystemExit("gradle-wrapper-distribution-not-pinned")

metadata = Path("gradle/verification-metadata.xml")
try:
    tree = ET.parse(metadata)
except ET.ParseError as exc:
    raise SystemExit(f"verification-metadata-invalid-xml:{exc}")

ns = {"d": "https://schema.gradle.org/dependency-verification"}
root = tree.getroot()
verify_metadata = root.find("./d:configuration/d:verify-metadata", ns)
verify_signatures = root.find("./d:configuration/d:verify-signatures", ns)
if verify_metadata is None or (verify_metadata.text or "").strip().lower() != "true":
    raise SystemExit("verification-metadata-verification-disabled")
if verify_signatures is None or (verify_signatures.text or "").strip().lower() != "false":
    raise SystemExit("verification-signature-mode-drift")

trusted = root.findall("./d:configuration/d:trusted-artifacts/d:trust", ns)
if trusted:
    raise SystemExit(f"verification-trusted-artifacts-forbidden:{len(trusted)}")

components = root.findall("./d:components/d:component", ns)
artifacts = root.findall("./d:components/d:component/d:artifact", ns)
if len(components) < 400:
    raise SystemExit(f"verification-component-set-truncated:{len(components)}")
if len(artifacts) < 700:
    raise SystemExit(f"verification-artifact-set-truncated:{len(artifacts)}")

for artifact in artifacts:
    name = artifact.get("name", "<unnamed>")
    checksums = artifact.findall("./d:sha256", ns)
    if len(checksums) != 1:
        raise SystemExit(f"verification-sha256-count:{name}:{len(checksums)}")
    value = checksums[0].get("value", "")
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SystemExit(f"verification-sha256-invalid:{name}:{value}")

for weak in ("md5", "sha1"):
    if root.findall(f".//d:{weak}", ns):
        raise SystemExit(f"weak-verification-checksum-present:{weak}")

scan_suffixes = {".sh", ".yml", ".yaml", ".gradle", ".kts"}
for path in Path(".").rglob("*"):
    if not path.is_file() or path.suffix not in scan_suffixes:
        continue
    if path.as_posix() == ".github/scripts/ci-dependency-supply-chain-contract.sh":
        continue
    source = path.read_text(encoding="utf-8", errors="ignore")
    if re.search(r"--dependency-verification(?:=|\\s+)(?:off|lenient)\\b", source, re.I):
        raise SystemExit(f"dependency-verification-bypass:{path}")

print("DEPENDENCY_SUPPLY_CHAIN_OK")
PY
