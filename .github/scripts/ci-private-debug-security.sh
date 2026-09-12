#!/usr/bin/env bash
set -Eeuo pipefail

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

manifest_path = Path("app/src/main/AndroidManifest.xml")
root = ET.parse(manifest_path).getroot()
android = "{http://schemas.android.com/apk/res/android}"

permissions = {
    node.attrib.get(android + "name", "")
    for node in root.findall("uses-permission")
}
for forbidden in {
    "android.permission.INTERNET",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
}:
    if forbidden in permissions:
        raise SystemExit(f"forbidden-private-debug-permission:{forbidden}")

application = root.find("application")
if application is None:
    raise SystemExit("application-node-missing")
if application.attrib.get(android + "allowBackup") != "false":
    raise SystemExit("allowBackup-must-be-false")
if application.attrib.get(android + "usesCleartextTraffic") != "false":
    raise SystemExit("usesCleartextTraffic-must-be-false")

for tag in ("provider", "receiver", "service"):
    for component in application.findall(tag):
        name = component.attrib.get(android + "name", "<unnamed>")
        if component.attrib.get(android + "exported") == "true":
            raise SystemExit(f"private-component-exported:{tag}:{name}")

main_activities = []
for activity in application.findall("activity"):
    has_launcher = any(
        category.attrib.get(android + "name") == "android.intent.category.LAUNCHER"
        for intent in activity.findall("intent-filter")
        for category in intent.findall("category")
    )
    if has_launcher:
        main_activities.append(activity)
if len(main_activities) != 1 or main_activities[0].attrib.get(android + "exported") != "true":
    raise SystemExit("exactly-one-exported-launcher-required")

print("PRIVATE_DEBUG_SECURITY_STATIC_OK")
PY

if grep -Eq 'assembleRelease|bundleRelease|signingConfig' .github/scripts/ci-v17-gold.sh .github/workflows/v17-gold.yml; then
  echo "release-path-present-in-private-gold-gate" >&2
  exit 1
fi

echo "PRIVATE_DEBUG_RELEASE_PATH_ABSENT_OK"
