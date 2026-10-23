#!/usr/bin/env python
"""Validate the signed APK and prepare GitHub Release assets (no upload)."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "will-bw/PennyPilot"
PACKAGE = "com.pennypilot.app"


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def android_tools():
    local = ROOT / "android/local.properties"
    match = re.search(r"^sdk.dir=(.+)$", local.read_text(), re.M) if local.exists() else None
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and match:
        sdk = match.group(1).strip()
    if not sdk:
        raise SystemExit("Set ANDROID_HOME or sdk.dir in android/local.properties.")
    candidates = [
        path for path in (Path(sdk) / "build-tools").iterdir()
        if re.fullmatch(r"\d+\.\d+\.\d+", path.name)
        and (path / "aapt").exists() and (path / "apksigner").exists()
    ]
    if not candidates:
        raise SystemExit("Install Android SDK build-tools (aapt and apksigner).")
    return max(candidates, key=lambda path: tuple(map(int, path.name.split("."))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "build/app/outputs/flutter-apk/app-release.apk")
    args = parser.parse_args()
    match = re.search(r"^version:\s*(\d+\.\d+\.\d+)\+(\d+)\s*$", (ROOT / "pubspec.yaml").read_text(), re.M)
    if not match:
        raise SystemExit("pubspec.yaml must use a stable version: X.Y.Z+BUILD.")
    version, build = match.groups()
    if not args.apk.is_file():
        raise SystemExit("Build a signed release first: ./tool/flutterw build apk --release")
    tools = android_tools()
    signature = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk))
    if "CN=Android Debug" in signature or "CN=AndroidDebug" in signature.replace(" ", ""):
        raise SystemExit("Refusing to publish an APK signed with an Android debug key.")
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    if certificate is None:
        raise SystemExit("Could not verify the release signing certificate.")
    expected_certificate = (ROOT / "android/release-certificate.sha256").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_certificate) or certificate.group(1).lower() != expected_certificate:
        raise SystemExit("APK signing certificate does not match the pinned PennyPilot release key.")
    metadata = run(str(tools / "aapt"), "dump", "badging", str(args.apk))
    actual = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    if actual is None or actual.groups() != (PACKAGE, build, version):
        raise SystemExit("APK identity/version differs from pubspec.yaml; rebuild before publishing.")
    if "application-debuggable" in metadata:
        raise SystemExit("Refusing to publish a debuggable APK.")
    size = args.apk.stat().st_size
    if not 0 < size <= 200 * 1024 * 1024:
        raise SystemExit("APK size is outside the updater's supported range (1–200 MiB).")
    destination = ROOT / "build/releases" / f"v{version}"
    destination.mkdir(parents=True, exist_ok=True)
    name = f"PennyPilot-v{version}-android.apk"
    apk = destination / name
    shutil.copyfile(args.apk, apk)
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = {
        "schemaVersion": 1,
        "version": version,
        "versionCode": int(build),
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/v{version}/{name}",
        "sha256": digest,
        "size": size,
    }
    manifest_path = destination / "update.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    manifest_digest = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    (destination / "SHA256SUMS").write_text(f"{digest}  {name}\n{manifest_digest}  update.json\n")
    print(f"Validated {PACKAGE} {version} ({build}), {size:,} bytes")
    print(f"Signing certificate SHA-256: {certificate.group(1)}")
    print(f"Release assets: {destination}")


if __name__ == "__main__":
    main()
