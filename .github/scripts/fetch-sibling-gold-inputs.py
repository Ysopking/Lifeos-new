#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

API = "https://api.github.com"
REQUIRED = {
    "Core Fast Gate": (),
    "Android Debug CI": ("LIFEOS-Next-debug", "LIFEOS-validation"),
    "Android Emulator Recovery": ("LIFEOS-android-emulator-recovery",),
}


class EvidenceReuseError(RuntimeError):
    pass


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is None:
            return None
        old_host = urllib.parse.urlparse(req.full_url).netloc
        new_host = urllib.parse.urlparse(newurl).netloc
        if old_host != new_host:
            redirected.remove_header("Authorization")
        return redirected


def request(url: str, token: str) -> bytes:
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "lifeos-product-gold-evidence-reuse",
        },
    )
    opener = urllib.request.build_opener(SafeRedirectHandler())
    try:
        with opener.open(req, timeout=60) as response:
            return response.read()
    except Exception as error:
        raise EvidenceReuseError(f"github-api-request-failed:{url}:{error}") from error


def api_json(path: str, token: str) -> dict:
    try:
        return json.loads(request(API + path, token))
    except json.JSONDecodeError as error:
        raise EvidenceReuseError(f"github-api-invalid-json:{path}") from error


def latest_exact_head_runs(repository: str, head_sha: str, current_run_id: int, token: str) -> dict[str, dict]:
    query = urllib.parse.urlencode({"head_sha": head_sha, "per_page": 100})
    payload = api_json(f"/repos/{repository}/actions/runs?{query}", token)
    candidates: dict[str, list[dict]] = {name: [] for name in REQUIRED}
    for run in payload.get("workflow_runs", []):
        name = run.get("name")
        if name not in candidates:
            continue
        if int(run.get("id", 0)) == current_run_id:
            continue
        if run.get("head_sha") != head_sha:
            continue
        candidates[name].append(run)

    selected: dict[str, dict] = {}
    for name, runs in candidates.items():
        if runs:
            selected[name] = max(runs, key=lambda item: int(item.get("id", 0)))
    return selected


def wait_for_green_runs(
    repository: str,
    head_sha: str,
    current_run_id: int,
    token: str,
    timeout_seconds: int,
) -> dict[str, dict]:
    deadline = time.monotonic() + timeout_seconds
    last_state = ""
    while True:
        selected = latest_exact_head_runs(repository, head_sha, current_run_id, token)
        state = ",".join(
            f"{name}={selected.get(name, {}).get('status', 'missing')}:"
            f"{selected.get(name, {}).get('conclusion', '-')}"
            for name in REQUIRED
        )
        if state != last_state:
            print(f"EXACT_HEAD_SIBLINGS:{state}", flush=True)
            last_state = state

        failed = [
            name
            for name, run in selected.items()
            if run.get("status") == "completed" and run.get("conclusion") != "success"
        ]
        if failed:
            raise EvidenceReuseError(
                "exact-head-sibling-failed:" + ",".join(
                    f"{name}:{selected[name].get('conclusion')}" for name in failed
                )
            )

        if len(selected) == len(REQUIRED) and all(
            run.get("status") == "completed" and run.get("conclusion") == "success"
            for run in selected.values()
        ):
            return selected

        if time.monotonic() >= deadline:
            missing = sorted(set(REQUIRED) - set(selected))
            raise EvidenceReuseError(
                "exact-head-sibling-timeout:"
                + ("missing=" + ",".join(missing) if missing else state)
            )
        time.sleep(10)


def artifact_for_run(repository: str, run_id: int, name: str, token: str) -> dict:
    payload = api_json(
        f"/repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100",
        token,
    )
    matches = [
        artifact
        for artifact in payload.get("artifacts", [])
        if artifact.get("name") == name and not artifact.get("expired", False)
    ]
    if len(matches) != 1:
        raise EvidenceReuseError(
            f"artifact-count-invalid:{name}:run={run_id}:count={len(matches)}"
        )
    return matches[0]


def safe_extract(payload: bytes, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        for member in archive.infolist():
            parts = PurePosixPath(member.filename).parts
            if member.filename.startswith("/") or ".." in parts:
                raise EvidenceReuseError(f"unsafe-artifact-path:{member.filename}")
            target = (destination / member.filename).resolve()
            try:
                target.relative_to(root)
            except ValueError as error:
                raise EvidenceReuseError(f"unsafe-artifact-target:{member.filename}") from error
        archive.extractall(destination)


def download_artifact(repository: str, artifact: dict, destination: Path, token: str) -> None:
    artifact_id = int(artifact["id"])
    payload = request(
        f"{API}/repos/{repository}/actions/artifacts/{artifact_id}/zip",
        token,
    )
    safe_extract(payload, destination)


def install_inputs(repository: str, runs: dict[str, dict], token: str) -> dict:
    summary: dict[str, dict] = {}
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)

        for workflow_name, artifact_names in REQUIRED.items():
            run = runs[workflow_name]
            run_id = int(run["id"])
            artifacts_summary = []
            for artifact_name in artifact_names:
                artifact = artifact_for_run(repository, run_id, artifact_name, token)
                artifacts_summary.append(
                    {
                        "id": int(artifact["id"]),
                        "name": artifact_name,
                        "digest": artifact.get("digest"),
                        "size_in_bytes": artifact.get("size_in_bytes"),
                    }
                )

                if artifact_name == "LIFEOS-Next-debug":
                    apk_tmp = tmp_root / "apk"
                    download_artifact(repository, artifact, apk_tmp, token)
                    matches = list(apk_tmp.rglob("app-debug.apk"))
                    if len(matches) != 1:
                        raise EvidenceReuseError(
                            f"debug-apk-count-invalid:{len(matches)}"
                        )
                    target = Path("app/build/outputs/apk/debug/app-debug.apk")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(matches[0], target)
                elif artifact_name == "LIFEOS-validation":
                    download_artifact(repository, artifact, Path("."), token)
                elif artifact_name == "LIFEOS-android-emulator-recovery":
                    download_artifact(
                        repository,
                        artifact,
                        Path("android-emulator-recovery"),
                        token,
                    )

            summary[workflow_name] = {
                "run_id": run_id,
                "head_sha": run.get("head_sha"),
                "event": run.get("event"),
                "conclusion": run.get("conclusion"),
                "html_url": run.get("html_url"),
                "artifacts": artifacts_summary,
            }
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--current-run-id", required=True, type=int)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=2400)
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise EvidenceReuseError("github-token-missing")
    if len(args.head_sha) != 40:
        raise EvidenceReuseError("head-sha-invalid")

    runs = wait_for_green_runs(
        args.repository,
        args.head_sha,
        args.current_run_id,
        token,
        args.timeout_seconds,
    )
    summary = {
        "schema_version": 1,
        "head_sha": args.head_sha,
        "workflows": install_inputs(args.repository, runs, token),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(summary, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"EXACT_HEAD_EVIDENCE_REUSED:{args.head_sha}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvidenceReuseError as error:
        print(f"EXACT_HEAD_EVIDENCE_REUSE_REJECTED:{error}", file=sys.stderr)
        raise SystemExit(1)
