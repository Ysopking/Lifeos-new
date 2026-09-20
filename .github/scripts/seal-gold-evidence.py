#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

SCHEMA_VERSION = 2
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class GoldEvidenceError(RuntimeError):
    pass


@dataclass(frozen=True)
class EvidenceFile:
    path: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class JUnitSummary:
    tests: int
    failures: int
    errors: int
    skipped: int
    files: list[EvidenceFile]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def manifest_fingerprint(value: dict) -> str:
    payload = dict(value)
    payload.pop("manifest_sha256", None)
    return hashlib.sha256(canonical_json(payload)).hexdigest()


def atomic_write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=path.parent,
        delete=False,
    ) as handle:
        handle.write(encoded)
        temp_path = Path(handle.name)
    os.replace(temp_path, path)


def _display_path(path: Path, root: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        return resolved.as_posix()


def evidence_file(path: Path, root: Path) -> EvidenceFile:
    if not path.is_file():
        raise GoldEvidenceError(f"evidence-file-missing:{path}")
    size = path.stat().st_size
    if size <= 0:
        raise GoldEvidenceError(f"evidence-file-empty:{path}")
    digest = sha256_file(path)
    if not SHA256.fullmatch(digest):
        raise GoldEvidenceError(f"evidence-file-invalid-sha256:{path}")
    return EvidenceFile(
        path=_display_path(path, root),
        sha256=digest,
        bytes=size,
    )


def _int_attr(element: ET.Element, name: str) -> int:
    raw = element.attrib.get(name, "0")
    try:
        value = int(raw)
    except ValueError as error:
        raise GoldEvidenceError(f"junit-invalid-{name}:{raw}") from error
    if value < 0:
        raise GoldEvidenceError(f"junit-negative-{name}:{raw}")
    return value


def scan_junit(root: Path) -> JUnitSummary:
    files = sorted(
        path
        for path in root.glob("**/build/test-results/**/TEST-*.xml")
        if path.is_file()
    )
    if not files:
        raise GoldEvidenceError("junit-reports-missing")

    tests = 0
    failures = 0
    errors = 0
    skipped = 0
    evidence: list[EvidenceFile] = []
    for path in files:
        try:
            xml_root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            raise GoldEvidenceError(f"junit-unreadable:{path}") from error

        suites = (
            [xml_root]
            if xml_root.tag == "testsuite"
            else list(xml_root.iter("testsuite"))
        )
        if not suites:
            raise GoldEvidenceError(f"junit-testsuite-missing:{path}")
        for suite in suites:
            tests += _int_attr(suite, "tests")
            failures += _int_attr(suite, "failures")
            errors += _int_attr(suite, "errors")
            skipped += _int_attr(suite, "skipped")
        evidence.append(evidence_file(path, root))

    if tests <= 0:
        raise GoldEvidenceError("junit-zero-tests")
    if failures != 0 or errors != 0:
        raise GoldEvidenceError(
            f"junit-not-green:failures={failures}:errors={errors}"
        )
    return JUnitSummary(
        tests=tests,
        failures=failures,
        errors=errors,
        skipped=skipped,
        files=evidence,
    )


def scan_lint(root: Path) -> list[EvidenceFile]:
    files = sorted(
        path
        for path in root.glob("app/build/reports/lint-results-debug.*")
        if path.is_file()
    )
    if not files:
        raise GoldEvidenceError("lint-evidence-missing")
    return [evidence_file(path, root) for path in files]


def scan_emulator(root: Path) -> list[EvidenceFile]:
    if not root.is_dir():
        raise GoldEvidenceError(
            f"emulator-evidence-directory-missing:{root}"
        )
    files = sorted(path for path in root.rglob("*") if path.is_file())
    if not files:
        raise GoldEvidenceError("emulator-evidence-empty")

    success_marker = any(
        "OK (" in path.read_text(encoding="utf-8", errors="replace")
        for path in files
        if path.suffix == ".txt"
    )
    if not success_marker:
        raise GoldEvidenceError("emulator-test-success-marker-missing")
    return [evidence_file(path, root.parent) for path in files]


def _require_sha40(name: str, value: str) -> str:
    normalized = value.lower()
    if not SHA40.fullmatch(normalized):
        raise GoldEvidenceError(f"{name}-invalid:{value}")
    return normalized


def build_pre_manifest(
    candidate_sha: str,
    source_head_sha: str,
    apk: Path,
    reports_root: Path,
) -> dict:
    candidate = _require_sha40("candidate-sha", candidate_sha)
    source = _require_sha40("source-head-sha", source_head_sha)
    root = reports_root.resolve()

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "stage": "pre-emulator",
        "candidate_sha": candidate,
        "source_head_sha": source,
        "workflow": {
            "run_id": os.environ.get("GOLD_RUN_ID", "local"),
            "job": os.environ.get("GOLD_JOB", "local"),
            "event_name": os.environ.get("GITHUB_EVENT_NAME", "local"),
            "git_ref": os.environ.get("GITHUB_REF", "local"),
            "git_ref_name": os.environ.get("GITHUB_REF_NAME", "local"),
        },
        "apk": asdict(evidence_file(apk, root)),
        "junit": asdict(scan_junit(root)),
        "lint": [asdict(item) for item in scan_lint(root)],
        "emulator_required": True,
    }
    manifest["manifest_sha256"] = manifest_fingerprint(manifest)
    return manifest


def verify_manifest(manifest: dict, expected_stage: str) -> None:
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise GoldEvidenceError("manifest-schema-version-mismatch")
    if manifest.get("stage") != expected_stage:
        raise GoldEvidenceError(
            f"manifest-stage-mismatch:{manifest.get('stage')}"
        )

    _require_sha40("candidate-sha", manifest.get("candidate_sha", ""))
    _require_sha40("source-head-sha", manifest.get("source_head_sha", ""))

    stored = manifest.get("manifest_sha256", "")
    if not SHA256.fullmatch(stored):
        raise GoldEvidenceError("manifest-fingerprint-invalid")
    if stored != manifest_fingerprint(manifest):
        raise GoldEvidenceError("manifest-fingerprint-mismatch")


def build_final_manifest(
    pre_path: Path,
    emulator_root: Path,
) -> dict:
    try:
        pre = json.loads(pre_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GoldEvidenceError("pre-manifest-unreadable") from error

    verify_manifest(pre, "pre-emulator")
    emulator_files = scan_emulator(emulator_root)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "stage": "final",
        "candidate_sha": pre["candidate_sha"],
        "source_head_sha": pre["source_head_sha"],
        "workflow": pre["workflow"],
        "apk": pre["apk"],
        "junit": pre["junit"],
        "lint": pre["lint"],
        "pre_manifest_sha256": pre["manifest_sha256"],
        "emulator": {
            "files": [asdict(item) for item in emulator_files],
            "file_count": len(emulator_files),
        },
        "product_gold": True,
    }
    manifest["manifest_sha256"] = manifest_fingerprint(manifest)
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seal LIFEOS Product Gold evidence"
    )
    subparsers = parser.add_subparsers(dest="mode", required=True)

    pre = subparsers.add_parser("pre")
    pre.add_argument("--candidate-sha", required=True)
    pre.add_argument("--source-head-sha", required=True)
    pre.add_argument("--apk", required=True, type=Path)
    pre.add_argument("--reports-root", required=True, type=Path)
    pre.add_argument("--out", required=True, type=Path)

    final = subparsers.add_parser("final")
    final.add_argument("--pre", required=True, type=Path)
    final.add_argument("--emulator-root", required=True, type=Path)
    final.add_argument("--out", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.mode == "pre":
            manifest = build_pre_manifest(
                args.candidate_sha,
                args.source_head_sha,
                args.apk,
                args.reports_root,
            )
        else:
            manifest = build_final_manifest(
                args.pre,
                args.emulator_root,
            )
        atomic_write_json(args.out, manifest)
        print(
            f"GOLD_EVIDENCE_{args.mode.upper()}_SEALED:"
            f"{manifest['manifest_sha256']}"
        )
        return 0
    except GoldEvidenceError as error:
        print(f"GOLD_EVIDENCE_REJECTED:{error}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
