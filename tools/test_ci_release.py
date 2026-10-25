"""Synthetic producer contract tests; no keys, network, Android runtime or devices."""
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

import ci_release
from ci_release import (MAX_VERSION, PUBLIC_ASSETS, allocate_version, guard_version,
                        inspect_badging, make_manifest, normalize_fingerprint, signer_digest)

CERT = "ab" * 32
BADGING = """package: name='local.voice' versionCode='1101' versionName='dev.1101'
sdkVersion:'33'
targetSdkVersion:'35'
native-code: 'arm64-v8a'
"""
VERIFIED = f"Signer #1 certificate SHA-256 digest: {CERT}\n"


class VersionTests(unittest.TestCase):
    def test_all_events_and_attempts_share_one_increasing_sequence(self):
        versions = [allocate_version(run, attempt, ref)[0]
                    for run in (1, 2) for attempt in (1, 2, 99)
                    for ref in ("refs/heads/main",)]
        self.assertEqual(sorted(set(versions)), versions)
        self.assertGreater(versions[0], 109)
        self.assertEqual((1101, "1.2.3", "stable"), allocate_version(1, 1, "refs/tags/v1.2.3"))
        self.assertEqual((1101, "dev.1101", "dev"), allocate_version(1, 1, "refs/heads/main"))

    def test_invalid_numbers_tags_and_exhaustion_fail_closed(self):
        for run, attempt, ref in [(0, 1, "main"), (1, 0, "main"), (1, 100, "main"),
                                  (MAX_VERSION, 1, "main"), (1, 1, "refs/tags/v1.2.3-rc.1"),
                                  (1, 1, "refs/tags/v1\nbad")]:
            with self.subTest(run=run, attempt=attempt, ref=ref), self.assertRaises(ValueError):
                allocate_version(run, attempt, ref)

    def test_publication_rejects_out_of_order_counter_reset_and_reuse(self):
        tags = ["v0.42.7", "apk-1101", "apk-1301", "unrelated", "apk-1201"]
        for code in (109, 1101, 1202, 1301, MAX_VERSION + 1):
            with self.subTest(code=code), self.assertRaises(ValueError):
                guard_version(code, tags)
        guard_version(1302, tags)
        guard_version(1101, [])


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.apk = Path(self.temporary.name) / "android-assistant-1101.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            for asset in PUBLIC_ASSETS:
                archive.writestr("assets/" + asset, b"synthetic fixture")

    def manifest(self, **overrides):
        args = dict(apk=self.apk, badging=BADGING, verification=VERIFIED,
                    expected_signer=CERT, code=1101, name="dev.1101", channel="dev",
                    commit="1" * 40, repository="weisscorp/android-assistant",
                    run_id="1234", run_attempt=1, source_ref="refs/heads/main")
        args.update(overrides)
        return make_manifest(**args)

    def test_verified_bytes_and_ota_mapping(self):
        m = self.manifest()
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), m["sha256"])
        self.assertEqual(self.apk.stat().st_size, m["sizeBytes"])
        self.assertEqual(CERT, m["signingCertificateSha256"])
        self.assertEqual(["arm64-v8a"], m["supportedAbis"])
        self.assertEqual("apk-1101", m["distribution"]["tag"])
        self.assertEqual("1" * 40, m["source"]["commit"])
        self.assertEqual("1234", m["build"]["runId"])
        self.assertFalse(m["assets"]["offlineVoiceComplete"])
        self.assertNotIn("generation", m)  # Assigned by the proxy, never the producer.

    def manifest_from_publish_rerun(self, producing_attempt):
        argv = ["ci_release.py", "manifest", "--apk", str(self.apk),
                "--build-tools", str(self.apk.parent / "synthetic-sdk")]
        if producing_attempt is not None:
            argv += ["--build-run-attempt", str(producing_attempt)]
        environment = {
            "GITHUB_RUN_ATTEMPT": "2",  # Only the failed publish job was retried.
            "GITHUB_RUN_ID": "1234", "GITHUB_SHA": "1" * 40,
            "GITHUB_REPOSITORY": "weisscorp/android-assistant",
            "GITHUB_REF": "refs/heads/main", "EXPECTED_SIGNER_SHA256": CERT,
            "VERSION_CODE": "1101", "VERSION_NAME": "dev.1101", "CHANNEL": "dev",
        }
        output = io.StringIO()
        with (mock.patch.dict(os.environ, environment, clear=True),
              mock.patch.object(sys, "argv", argv),
              mock.patch("ci_release.subprocess.check_output", side_effect=["", VERIFIED, BADGING]),
              redirect_stdout(output), redirect_stderr(io.StringIO())):
            ci_release.main()
        return json.loads(output.getvalue())

    def test_publication_only_rerun_preserves_the_artifact_producing_attempt(self):
        m = self.manifest_from_publish_rerun(producing_attempt=1)
        self.assertEqual(1, m["build"]["runAttempt"])
        self.assertEqual("1234", m["build"]["runId"])
        self.assertEqual(1101, m["versionCode"])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), m["sha256"])

    def test_publish_rerun_cannot_fall_back_to_the_current_attempt(self):
        with self.assertRaises(SystemExit) as error:
            self.manifest_from_publish_rerun(producing_attempt=None)
        self.assertEqual(2, error.exception.code)

    def test_metadata_and_signing_mismatches_fail(self):
        for override in [dict(expected_signer="cd" * 32), dict(code=1201), dict(name="other"),
                         dict(badging=BADGING.replace("local.voice", "other.app")),
                         dict(badging=BADGING.replace("arm64-v8a", "x86_64")),
                         dict(commit="main"), dict(repository="fork/android-assistant"),
                         dict(channel="arbitrary"), dict(verification=VERIFIED + VERIFIED),
                         dict(run_attempt=0), dict(run_attempt=100)]:
            with self.subTest(override=override), self.assertRaises(ValueError):
                self.manifest(**override)

    def test_missing_or_undeclared_assets_fail(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/speech-ru.zip", b"synthetic")
        with self.assertRaises(ValueError):
            self.manifest()
        self.setUp()
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("assets/voice-silero.zip", b"not provisioned")
        with self.assertRaises(ValueError):
            self.manifest()

    def test_missing_badging_and_multiple_signers_fail(self):
        with self.assertRaises(ValueError):
            inspect_badging("package: name='local.voice'")
        with self.assertRaises(ValueError):
            signer_digest("")
        self.assertEqual(CERT, normalize_fingerprint(":".join(["AB"] * 32)))
        with self.assertRaises(ValueError):
            normalize_fingerprint("not-a-certificate")


if __name__ == "__main__":
    unittest.main()
