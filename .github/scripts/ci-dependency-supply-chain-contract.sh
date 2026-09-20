#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "DEPENDENCY_SUPPLY_CHAIN_REJECTED:$1" >&2
  exit 1
}

test -s settings.gradle.kts || fail "settings-missing"
test -s build.gradle.kts || fail "root-build-missing"
test -s gradle/wrapper/gradle-wrapper.properties || fail "wrapper-properties-missing"

grep -Fq 'repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)' settings.gradle.kts ||
  fail "project-repositories-not-forbidden"

python3 - <<'PY'
from pathlib import Path
import re

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

print("DEPENDENCY_SUPPLY_CHAIN_OK")
PY
