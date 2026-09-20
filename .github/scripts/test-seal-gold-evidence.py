#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("seal-gold-evidence.py")
spec = importlib.util.spec_from_file_location(
    "seal_gold_evidence",
    SCRIPT,
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

SHA_A = "a" * 40
SHA_B = "b" * 40


def write_green_fixture(root: Path) -> tuple[Path, Path]:
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    apk.parent.mkdir(parents=True, exist_ok=True)
    apk.write_bytes(b"lifeos-apk-v1")

    junit = (
        root
        / "core/runtime/build/test-results/test/TEST-LifeOs.xml"
    )
    junit.parent.mkdir(parents=True, exist_ok=True)
    junit.write_text(
        '<testsuite name="LifeOs" tests="3" failures="0" '
        'errors="0" skipped="1"></testsuite>\n',
        encoding="utf-8",
    )

    lint = root / "app/build/reports/lint-results-debug.html"
    lint.parent.mkdir(parents=True, exist_ok=True)
    lint.write_text("<html>clean</html>\n", encoding="utf-8")

    emulator = root / "android-emulator-recovery"
    emulator.mkdir(parents=True, exist_ok=True)
    (emulator / "gold.txt").write_text(
        "OK (1 test)\n",
        encoding="utf-8",
    )
    (emulator / "cold-start.txt").write_text(
        "Status: ok\nLaunchState: COLD\n",
        encoding="utf-8",
    )
    return apk, emulator


def assert_raises(fn, expected: str) -> None:
    try:
        fn()
    except module.GoldEvidenceError as error:
        assert expected in str(error), (expected, str(error))
    else:
        raise AssertionError(
            f"expected GoldEvidenceError containing {expected!r}"
        )


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        apk, emulator = write_green_fixture(root)

        pre = module.build_pre_manifest(
            SHA_A,
            SHA_A,
            apk,
            root,
        )
        module.verify_manifest(pre, "pre-emulator")
        assert pre["junit"]["tests"] == 3
        assert pre["junit"]["failures"] == 0
        first_fingerprint = pre["manifest_sha256"]

        pre_path = (
            root
            / "product-gold-evidence/pre-emulator.json"
        )
        module.atomic_write_json(pre_path, pre)
        final = module.build_final_manifest(
            pre_path,
            emulator,
        )
        module.verify_manifest(final, "final")
        assert final["product_gold"] is True
        assert (
            final["pre_manifest_sha256"]
            == first_fingerprint
        )
        assert final["emulator"]["file_count"] == 2

        junit = (
            root
            / "core/runtime/build/test-results/test/TEST-LifeOs.xml"
        )
        junit.write_text(
            '<testsuite name="LifeOs" tests="3" '
            'failures="1" errors="0" skipped="0"></testsuite>\n',
            encoding="utf-8",
        )
        assert_raises(
            lambda: module.build_pre_manifest(
                SHA_A,
                SHA_A,
                apk,
                root,
            ),
            "junit-not-green",
        )

        junit.write_text(
            '<testsuite name="LifeOs" tests="3" '
            'failures="0" errors="0" skipped="0"></testsuite>\n',
            encoding="utf-8",
        )
        before_apk = module.build_pre_manifest(
            SHA_A,
            SHA_A,
            apk,
            root,
        )
        apk.write_bytes(b"lifeos-apk-v2")
        after_apk = module.build_pre_manifest(
            SHA_A,
            SHA_A,
            apk,
            root,
        )
        assert (
            before_apk["manifest_sha256"]
            != after_apk["manifest_sha256"]
        )
        assert (
            before_apk["apk"]["sha256"]
            != after_apk["apk"]["sha256"]
        )

        sha_changed = module.build_pre_manifest(
            SHA_B,
            SHA_A,
            apk,
            root,
        )
        assert (
            sha_changed["manifest_sha256"]
            != after_apk["manifest_sha256"]
        )
        assert sha_changed["candidate_sha"] == SHA_B

        tampered = dict(after_apk)
        tampered["candidate_sha"] = SHA_B
        assert_raises(
            lambda: module.verify_manifest(
                tampered,
                "pre-emulator",
            ),
            "manifest-fingerprint-mismatch",
        )

        encoded = json.dumps(final, sort_keys=True)
        assert "product_gold" in encoded
        assert "manifest_sha256" in encoded

    print("GOLD_EVIDENCE_SELFTEST_OK")


if __name__ == "__main__":
    main()
