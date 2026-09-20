#!/usr/bin/env bash
set -Eeuo pipefail

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

manifest_path = Path("app/src/main/AndroidManifest.xml")
root = ET.parse(manifest_path).getroot()
android = "{http://schemas.android.com/apk/res/android}"

permission_nodes = {
    node.attrib.get(android + "name", ""): node
    for node in root.findall("uses-permission")
}
permissions = set(permission_nodes)

profile_sources = [
    Path("app/src/main/java/app/lifeos/next/PrivatePermissionController.kt"),
    Path("app/src/main/java/app/lifeos/next/AndroidInitialDataSources.kt"),
]
for source in profile_sources:
    if not source.is_file():
        raise SystemExit(f"permission-profile-source-missing:{source}")
profile_text = "\n".join(source.read_text(encoding="utf-8") for source in profile_sources)
for permission in sorted(permissions):
    token = permission.removeprefix("android.permission.")
    if f"Manifest.permission.{token}" not in profile_text:
        raise SystemExit(f"manifest-permission-without-profile:{permission}")

for forbidden in {
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
}:
    if forbidden in permissions:
        raise SystemExit(f"forbidden-private-debug-permission:{forbidden}")

legacy_storage = permission_nodes.get("android.permission.READ_EXTERNAL_STORAGE")
if legacy_storage is not None:
    if legacy_storage.attrib.get(android + "maxSdkVersion") != "32":
        raise SystemExit("read-external-storage-must-be-max-sdk-32")

broad_storage = "android.permission.MANAGE_EXTERNAL_STORAGE" in permissions
if broad_storage:
    required = [
        Path("app/src/main/java/app/lifeos/next/AndroidSharedFilesInitialDataSource.kt"),
        Path("app/src/main/java/app/lifeos/next/ChatMainActivity.kt"),
        Path("app/src/main/java/app/lifeos/next/LifeOsApplication.kt"),
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("broad-storage-without-owner-contract:" + ",".join(missing))

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

internet = "android.permission.INTERNET" in permissions
if internet:
    required = [
        Path("app/src/main/java/app/lifeos/next/kernel/AndroidWebDeepSearchSource.kt"),
        Path("app/src/main/java/app/lifeos/next/kernel/WebDeepSearchOwnerPolicy.kt"),
        Path("app/src/main/java/app/lifeos/next/kernel/WebDeepSearchRuntime.kt"),
        Path("app/src/test/java/app/lifeos/next/kernel/AndroidWebDeepSearchSourceTest.kt"),
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise SystemExit("internet-without-web-deepsearch-contract:" + ",".join(missing))

print("PRIVATE_DEBUG_SECURITY_STATIC_OK")
PY

if grep -Eq 'assembleRelease|bundleRelease|signingConfig' .github/scripts/ci-v17-gold.sh .github/workflows/v17-gold.yml; then
  echo "release-path-present-in-private-gold-gate" >&2
  exit 1
fi

if grep -Fq 'android.permission.MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml; then
  grep -Fq 'Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION' app/src/main/java/app/lifeos/next/ChatMainActivity.kt || {
    echo "broad-storage-without-owner-system-confirmation" >&2
    exit 1
  }
  grep -Fq 'Environment.isExternalStorageManager()' app/src/main/java/app/lifeos/next/AndroidSharedFilesInitialDataSource.kt || {
    echo "broad-storage-without-runtime-authorization-check" >&2
    exit 1
  }
  grep -Fq 'AndroidSharedFilesInitialDataSource(this)' app/src/main/java/app/lifeos/next/LifeOsApplication.kt || {
    echo "broad-storage-without-productive-initial-data-source" >&2
    exit 1
  }
fi

if grep -Fq 'android.permission.INTERNET' app/src/main/AndroidManifest.xml; then
  grep -Fq 'OwnerEffectType.NETWORK_ACCESS' app/src/main/java/app/lifeos/next/kernel/WebDeepSearchOwnerPolicy.kt || {
    echo "internet-without-owner-network-policy" >&2
    exit 1
  }
  grep -Fq 'OwnerPolicyEffectGate' app/src/main/java/app/lifeos/next/kernel/AndroidWebDeepSearchSource.kt || {
    echo "internet-without-jit-owner-effect-gate" >&2
    exit 1
  }
  grep -Fq 'HttpsURLConnection' app/src/main/java/app/lifeos/next/kernel/AndroidWebDeepSearchSource.kt || {
    echo "internet-without-https-only-transport" >&2
    exit 1
  }
  grep -Fq 'DeepSearchPermissionRuntimeRegistry.install' app/src/main/java/app/lifeos/next/kernel/WebDeepSearchRuntime.kt || {
    echo "internet-without-dynamic-deepsearch-permission" >&2
    exit 1
  }
  if grep -Fq 'OwnerEffectType.NETWORK_ACCESS' app/src/main/java/app/lifeos/next/kernel/PrivateOwnerPolicyBaseline.kt; then
    echo "network-access-must-not-be-baseline-granted" >&2
    exit 1
  fi
fi

echo "PRIVATE_DEBUG_RELEASE_PATH_ABSENT_OK"
