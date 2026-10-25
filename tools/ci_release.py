#!/usr/bin/env python3
"""Version allocation and verified APK producer metadata. Never contacts devices."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

MAX_VERSION = 2_100_000_000
PUBLIC_ASSETS = (
    "speech-tone-ru.zip", "speech-ru.zip", "speech-gigaam-ru.zip",
    "silero_vad.onnx", "ocr/tessdata/rus.traineddata", "ocr/tessdata/eng.traineddata",
)
MISSING_ASSETS = ("voice-silero.zip", "voice-ru.zip")


def allocate_version(run_number, run_attempt, source_ref):
    # Reserve 100 slots per workflow run, including failed attempts. Never reuse
    # the legacy phone's 109. Keep this workflow's identity/counter stable.
    if run_number < 1 or not 1 <= run_attempt <= 99:
        raise ValueError("Run number must be positive and attempt must be 1..99")
    code = 1000 + run_number * 100 + run_attempt
    if code > MAX_VERSION:
        raise ValueError("Android versionCode exhausted")
    if source_ref.startswith("refs/tags/"):
        match = re.fullmatch(r"refs/tags/v(\d+\.\d+\.\d+)", source_ref)
        if not match:
            raise ValueError("Stable tags must use vMAJOR.MINOR.PATCH")
        return code, match[1], "stable"
    return code, f"dev.{code}", "dev"


def guard_version(code, tags):
    if not 109 < code <= MAX_VERSION:
        raise ValueError("Version must exceed installed legacy 109 and fit Android")
    published = [int(m[1]) for tag in tags
                 if (m := re.fullmatch(r"apk-(\d+)", tag.strip()))]
    if code <= max(published, default=109):
        raise ValueError("Version already published or older than the high-water mark")


def inspect_badging(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    abis = re.search(r"^native-code: (.+)$", badging, re.M)
    if not all((package, minimum, target, abis)):
        raise ValueError("Incomplete APK badging")
    return {
        "packageName": package[1], "versionCode": int(package[2]),
        "versionName": package[3], "minSdk": int(minimum[1]),
        "targetSdk": int(target[1]), "supportedAbis": re.findall(r"'([^']+)'", abis[1]),
    }


def signer_digest(verification):
    # Reject multiple/rotated signing identities instead of choosing one silently.
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", verification, re.M)
    if len(signers) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    return signers[0].lower()


def normalize_fingerprint(value):
    value = value.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Expected a SHA-256 signing certificate fingerprint")
    return value


def make_manifest(apk, badging, verification, expected_signer, code, name, channel,
                  commit, repository, run_id, run_attempt, source_ref):
    metadata = inspect_badging(badging)
    if (metadata["packageName"], metadata["versionCode"], metadata["versionName"]) != ("local.voice", code, name):
        raise ValueError("APK package/version differs from build intent")
    if metadata["supportedAbis"] != ["arm64-v8a"] or metadata["minSdk"] != 33 or metadata["targetSdk"] != 35:
        raise ValueError("Unexpected APK platform compatibility")
    signer = signer_digest(verification)
    if signer != normalize_fingerprint(expected_signer):
        raise ValueError("APK signing certificate differs from owner-provisioned fingerprint")
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or repository != "weisscorp/android-assistant":
        raise ValueError("Invalid source provenance")
    if not 1 <= run_attempt <= 99:
        raise ValueError("Invalid producing build attempt")
    if channel not in ("dev", "stable"):
        raise ValueError("Unknown release channel")
    guard_version(code, [])
    with zipfile.ZipFile(apk) as archive:
        bundled = set(archive.namelist())
        if not all("assets/" + asset in bundled for asset in PUBLIC_ASSETS):
            raise ValueError("Public ASR/OCR asset profile is incomplete")
        if any("assets/" + asset in bundled for asset in MISSING_ASSETS):
            raise ValueError("Undeclared voice asset in public ASR/OCR profile")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    tag = f"apk-{code}"
    return {
        "schemaVersion": 1, "artifactKind": "android-apk", **metadata,
        "channel": channel, "sha256": digest, "sizeBytes": apk.stat().st_size,
        "signingCertificateSha256": signer,
        "artifact": apk.name,
        "source": {"repository": repository, "commit": commit, "ref": source_ref},
        "build": {"workflow": ".github/workflows/android-apk.yml", "runId": str(run_id),
                  "runAttempt": run_attempt,
                  "url": f"https://github.com/{repository}/actions/runs/{run_id}"},
        "distribution": {"type": "github-release", "repository": repository, "tag": tag,
                         "url": f"https://github.com/{repository}/releases/download/{tag}/{apk.name}"},
        "assets": {"profile": "public-asr-ocr", "offlineVoiceComplete": False,
                   "included": list(PUBLIC_ASSETS), "omitted": list(MISSING_ASSETS)},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    subs.add_parser("version")
    guard = subs.add_parser("guard")
    guard.add_argument("--tags", type=Path, required=True)
    guard.add_argument("--version-code", type=int, required=True)
    manifest = subs.add_parser("manifest")
    manifest.add_argument("--apk", type=Path, required=True)
    manifest.add_argument("--build-tools", type=Path, required=True)
    manifest.add_argument("--build-run-attempt", type=int, required=True,
                          help="Attempt that produced the APK, retained in the build job outputs")
    args = parser.parse_args()
    if args.command == "version":
        code, name, channel = allocate_version(int(os.environ["GITHUB_RUN_NUMBER"]),
                                              int(os.environ["GITHUB_RUN_ATTEMPT"]), os.environ["SOURCE_REF"])
        print(f"version_code={code}\nversion_name={name}\nchannel={channel}")
    elif args.command == "guard":
        guard_version(args.version_code, args.tags.read_text().splitlines())
    else:
        # Only parse signed bytes after SDK tools independently verify them.
        def sdk(*argv):
            return subprocess.check_output([str(args.build_tools / argv[0]), *argv[1:]], text=True)
        sdk("zipalign", "-c", "-P", "16", "4", str(args.apk))
        verification = sdk("apksigner", "verify", "--verbose", "--print-certs", str(args.apk))
        badging = sdk("aapt", "dump", "badging", str(args.apk))
        result = make_manifest(args.apk, badging, verification, os.environ["EXPECTED_SIGNER_SHA256"],
                               int(os.environ["VERSION_CODE"]), os.environ["VERSION_NAME"],
                               os.environ["CHANNEL"], os.environ["GITHUB_SHA"], os.environ["GITHUB_REPOSITORY"],
                               os.environ["GITHUB_RUN_ID"], args.build_run_attempt, os.environ["GITHUB_REF"])
        print(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
