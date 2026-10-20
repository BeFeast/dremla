#!/usr/bin/env python3
"""Offline adversarial tests of the real lock and publication implementation."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("release_tools", Path(__file__).with_name("release-tools.py"))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dremla-release-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.lock = self.root / "delivery/release-lock.json"
        self.asset = "dremla-0.1.0.apk"
        self.apk = b"runtime APK fixture"
        self.cert = hashlib.sha256(b"runtime certificate").hexdigest()
        self.sha = hashlib.sha1(b"runtime commit").hexdigest()
        self.digest = hashlib.sha256(self.apk).hexdigest()
        self.data = dict(tag="v0.1.0", tagSha=self.sha, assetName=self.asset,
                         apkSha256=self.digest, versionCode=1, versionName="0.1.0",
                         certificateSha256=self.cert, releaseUrlPath="/BeFeast/dremla/releases/tag/v0.1.0")
        env = dict(DREMLA_RELEASE_TAG="v0.1.0", RELEASE_SIGNING_CERT_SHA256=self.cert,
                   DREMLA_VERSION_CODE="1", DREMLA_VERSION_NAME="0.1.0", DREMLA_SOURCE_SHA=self.sha,
                   DREMLA_FORGE_URL="https://forge.example", DREMLA_RELEASE_TOKEN="runtime-token")
        for mock in (patch.dict(os.environ, env), patch.object(r, "ROOT", self.root),
                     patch.object(r, "LOCK", self.lock)):
            mock.start()
            self.addCleanup(mock.stop)
        self.release = {"draft": False, "tag_name": "v0.1.0", "assets": [
            {"name": n, "browser_download_url": "https://forge.example/download/" + n}
            for n in (self.asset, "SHA256SUMS")]}
        self.downloads = {"/download/" + self.asset: self.apk,
                          "/download/SHA256SUMS": f"{self.digest}  {self.asset}\n".encode()}
        self.calls = []
        self.fail_upload = 0
        self.tag_exists = False
        self.tag_conflict = False

    def api_json(self, suffix, **kwargs):
        self.calls.append((suffix, kwargs))
        if suffix == "/releases/tags/v0.1.0":
            return self.release
        if suffix == "/tags/v0.1.0":
            return None if kwargs.get("missing") and not self.tag_exists else {"commit": {"sha": self.sha}}
        if suffix == "/tags":
            if self.tag_conflict:
                raise r.ReleaseError("Forgejo request failed: HTTP 409")
            return {}
        if suffix == "/releases":
            self.assertTrue(kwargs["data"]["draft"])
            return {"id": 7, "draft": True}
        if "assets?name=" in suffix:
            if self.fail_upload == sum("assets?name=" in c[0] for c in self.calls):
                raise ValueError("fixture upload failure")
            name = r.urllib.parse.unquote(suffix.split("name=")[1])
            payload = self.apk if name == self.asset else self.downloads["/download/SHA256SUMS"]
            self.assertIn(payload, kwargs["data"])
            return {"name": name, "size": len(payload)}
        if suffix == "/releases/7/assets":
            return self.release["assets"]
        if suffix == "/releases/7":
            return {"draft": False}
        raise AssertionError(suffix)

    @contextlib.contextmanager
    def fake_api(self):
        with patch.object(r.API, "json", side_effect=self.api_json), \
             patch.object(r.API, "request", side_effect=lambda path: self.downloads[path]), \
             patch.object(r, "inspect", return_value=(1, "0.1.0", self.cert)):
            yield

    def test_schema(self):
        r.validate_lock(self.data)
        for field, value in [("tagSha", "a" * 39), ("apkSha256", "A" * 64),
                             ("certificateSha256", ""), ("versionCode", True),
                             ("versionCode", 0), ("versionCode", 2100000001),
                             ("versionCode", "1"), ("versionName", "01.0.0"),
                             ("versionName", "1.0.0-01"), ("assetName", "../x.apk"),
                             ("tag", "v2.0.0"), ("releaseUrlPath", "https://forge.example/x"),
                             ("releaseUrlPath", "/../dremla/releases/tag/v0.1.0")]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                r.validate_lock(dict(self.data, **{field: value}))
        for field in self.data:
            data = dict(self.data)
            del data[field]
            with self.assertRaises(ValueError):
                r.validate_lock(data)
        with self.assertRaises(ValueError):
            r.validate_lock(dict(self.data, extra=1))
        for name in ("1.2.3", "1.0.0-alpha.1", "1.2.3+build.9", "0.1.0-rc.1+build"):
            r.versions("1", name)

    def test_checker_entrypoint_and_duplicates(self):
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("release-tools.py", "check-release-lock.sh"):
            (scripts / name).write_bytes(Path(__file__).with_name(name).read_bytes())
        def check(expected):
            result = subprocess.run(["bash", str(scripts / "check-release-lock.sh")], capture_output=True, text=True)
            self.assertEqual(result.returncode, expected, result.stderr)
            return result.stdout
        self.assertIn("no release lock yet", check(0))
        self.lock.parent.mkdir()
        self.lock.write_text('{"tag": "v0.1.0", "tag": "v0.2.0"}')
        check(1)
        self.lock.write_text("{")
        check(1)
        self.lock.unlink()
        self.lock.symlink_to(self.root / "absent")
        check(1)

    def check_committed(self, data):
        self.lock.parent.mkdir(exist_ok=True)
        original = json.dumps(data) + "\n"
        self.lock.write_text(original)
        try:
            with patch.object(sys, "argv", ["release-tools.py", "check"]):
                r.main()
        finally:
            self.assertEqual(self.lock.read_text(), original)
            self.assertFalse(any(c[1].get("method", "GET") != "GET" for c in self.calls))

    def test_checker_verifies_every_identity_field(self):
        with self.fake_api():
            self.check_committed(self.data)
        mutations = dict(tag="v0.2.0", tagSha="0" * 40, assetName="dremla-0.2.0.apk",
                         apkSha256="0" * 64, versionCode=2, versionName="0.2.0",
                         certificateSha256="0" * 64,
                         releaseUrlPath="/other/repo/releases/tag/v0.1.0")
        self.assertEqual(set(mutations), r.FIELDS)
        for field, value in mutations.items():
            with self.subTest(field=field), self.fake_api(), self.assertRaises(ValueError):
                self.check_committed(dict(self.data, **{field: value}))
        # A mutually consistent fabricated version must also fail remote verification.
        forged = dict(self.data, tag="v0.2.0", versionName="0.2.0",
                      assetName="dremla-0.2.0.apk",
                      releaseUrlPath="/BeFeast/dremla/releases/tag/v0.2.0")
        r.validate_lock(forged)
        with self.fake_api():
            r.API.json.side_effect = lambda *a, **kw: self.release
            with self.assertRaises(ValueError):
                self.check_committed(forged)

    def test_checker_fetch_and_published_identity_failures(self):
        for case in ("release", "tag", "download", "checksum", "code", "name", "cert", "asset"):
            with self.subTest(case=case), self.fake_api():
                if case in ("release", "tag"):
                    def fetch(suffix, **kwargs):
                        if suffix.startswith("/releases/" if case == "release" else "/tags/"):
                            raise r.ReleaseError("Forgejo request failed: transport error")
                        return self.api_json(suffix, **kwargs)
                    r.API.json.side_effect = fetch
                if case == "download":
                    r.API.request.side_effect = r.ReleaseError("Forgejo request failed: HTTP 404")
                if case == "checksum":
                    r.API.request.side_effect = lambda path: b"wrong" if path.endswith("SHA256SUMS") else self.apk
                if case == "code": r.inspect.return_value = (2, "0.1.0", self.cert)
                if case == "name": r.inspect.return_value = (1, "0.2.0", self.cert)
                if case == "cert": r.inspect.return_value = (1, "0.1.0", "0" * 64)
                with patch.dict(self.release, assets=[] if case == "asset" else self.release["assets"]):
                    with self.assertRaises(ValueError):
                        self.check_committed(self.data)

    def test_workflow_token_boundary(self):
        # Deliberately strict static check of this workflow's step blocks. No YAML dependency.
        workflow = Path(__file__).resolve().parent.parent / ".forgejo/workflows/release.yml"
        text = workflow.read_text()
        prefix, *steps = text.split("      - name: ")
        self.assertNotIn("DREMLA_RELEASE_TOKEN", prefix)
        token_steps = []
        for step in steps:
            if "DREMLA_RELEASE_TOKEN" not in step:
                continue
            token_steps.append(step)
            before, run = step.split("        run: |\n")
            self.assertIn("          DREMLA_RELEASE_TOKEN:", before)
            lines = [line.strip() for line in run.splitlines() if line.strip()]
            self.assertIn(lines, [
                ["set +x", "python3 -I scripts/release-tools.py preflight"],
                ["set +x", "python3 -I scripts/release-tools.py publish"],
            ])
        self.assertEqual(len(token_steps), 2)

    def test_publication_tag_race_and_incomplete_assets(self):
        (self.root / "release-out").mkdir()
        (self.root / "release-out" / self.asset).write_bytes(self.apk)
        for case in ("tag-conflict", "incomplete-assets"):
            self.calls.clear()
            self.tag_conflict = case == "tag-conflict"
            os.environ["DREMLA_RELEASE_TOKEN"] = "runtime-token"
            with self.fake_api(), patch.object(r.subprocess, "run"), \
                 patch.object(r.subprocess, "check_output", return_value=self.sha), \
                 patch.dict(self.release, assets=self.release["assets"][:1]):
                with self.assertRaisesRegex(ValueError, "HTTP 409" if self.tag_conflict else "assets incomplete"):
                    r.publish()
            self.assertFalse(any(c[1].get("method") == "PATCH" for c in self.calls))
            if self.tag_conflict:
                self.assertEqual(self.calls[-1][0], "/tags")
                self.assertFalse(any(c[0] == "/releases" for c in self.calls))

    def test_generate_from_published_bytes(self):
        with self.fake_api():
            r.generate_lock()
        self.assertEqual(json.loads(self.lock.read_text()), self.data)

    def test_generation_failures_preserve_lock(self):
        self.lock.parent.mkdir()
        self.lock.write_text("previous lock")
        cases = ("draft", "checksum", "duplicate", "foreign", "sha", "cert", "version", "download", "inspection")
        for case in cases:
            release, downloads, sha = copy.deepcopy(self.release), dict(self.downloads), self.sha
            with self.subTest(case=case), self.fake_api():
                if case == "draft": self.release["draft"] = True
                if case == "checksum": self.downloads["/download/SHA256SUMS"] = b"bad\n"
                if case == "duplicate": self.release["assets"].append(self.release["assets"][0])
                if case == "foreign": self.release["assets"][0]["browser_download_url"] = "https://other.example/file"
                if case == "sha": self.sha = "main"
                if case == "cert": r.inspect.return_value = (1, "0.1.0", "0" * 64)
                if case == "version": r.inspect.return_value = (1, "0.2.0", self.cert)
                if case == "download": r.API.request.side_effect = ValueError("download failure")
                if case == "inspection": r.inspect.side_effect = subprocess.CalledProcessError(1, "fixture")
                with self.assertRaises((ValueError, subprocess.SubprocessError)):
                    r.generate_lock()
                self.assertEqual(self.lock.read_text(), "previous lock")
            self.release, self.downloads, self.sha = release, downloads, sha

    def test_publication_is_draft_until_both_uploads(self):
        (self.root / "release-out").mkdir()
        (self.root / "release-out" / self.asset).write_bytes(self.apk)
        with self.fake_api(), patch.object(r.subprocess, "run"), \
             patch.object(r.subprocess, "check_output", return_value=self.sha):
            r.publish()
        self.assertEqual(self.calls[-1], ("/releases/7", {"method": "PATCH", "data": {"draft": False}}))
        self.assertEqual(sum("assets?name=" in c[0] for c in self.calls), 2)
        for failure_at in (1, 2):
            self.calls.clear()
            self.fail_upload = failure_at
            os.environ["DREMLA_RELEASE_TOKEN"] = "runtime-token"
            with self.fake_api(), patch.object(r.subprocess, "run"), \
                 patch.object(r.subprocess, "check_output", return_value=self.sha), self.assertRaises(ValueError):
                r.publish()
            self.assertEqual(sum("assets?name=" in c[0] for c in self.calls), failure_at)
            self.assertFalse(any(c[1].get("method") == "PATCH" for c in self.calls))
        self.calls.clear()
        os.environ["DREMLA_RELEASE_TOKEN"] = "runtime-token"
        with self.fake_api(), patch.object(r.subprocess, "run"), \
             patch.object(r.subprocess, "check_output", return_value=self.sha):
            r.inspect.return_value = (1, "0.1.0", "0" * 64)
            with self.assertRaises(ValueError):
                r.publish()
        self.assertFalse(any(c[1].get("method") == "POST" for c in self.calls))

    def test_preflight_fails_closed(self):
        for failure in ("tag", "ancestry", "head"):
            self.calls.clear()
            os.environ["DREMLA_RELEASE_TOKEN"] = "runtime-token"
            self.tag_exists = failure == "tag"
            with self.fake_api(), patch.object(r.subprocess, "run") as run, \
                 patch.object(r.subprocess, "check_output", return_value="0" * 40 if failure == "head" else self.sha):
                if failure == "ancestry": run.side_effect = subprocess.CalledProcessError(1, "git")
                with self.assertRaises((ValueError, subprocess.SubprocessError)):
                    r.publish()
            self.assertFalse(any(c[1].get("method") == "POST" for c in self.calls))

    def test_api_token_never_in_child_environment_or_error(self):
        api = r.API()
        self.assertNotIn("DREMLA_RELEASE_TOKEN", os.environ)
        with patch.object(api.opener, "open", side_effect=r.urllib.error.HTTPError(
                "https://forge.example", 403, "runtime-token", {}, None)):
            with self.assertRaisesRegex(ValueError, "^Forgejo request failed: HTTP 403$"):
                api.request("/test")
        request = r.urllib.request.Request("https://forge.example/download", headers={"Authorization": "token fixture"})
        handler = r.ForgeRedirect()
        for target in ("https://other.example/file", "http://forge.example/file", "https://user@forge.example/file"):
            self.assertIsNone(handler.redirect_request(request, None, 302, "", {}, target))
        redirect = handler.redirect_request(request, None, 302, "", {}, "https://forge.example/attachments/file")
        self.assertEqual(redirect.get_header("Authorization"), "token fixture")
        request = r.urllib.request.Request("https://forge.example/release", method="POST")
        self.assertIsNone(handler.redirect_request(request, None, 302, "", {}, "https://forge.example/other"))

    def test_real_inspection_with_runtime_sdk_fixtures(self):
        scripts = self.root / "scripts"
        scripts.mkdir()
        inspector = scripts / "inspect-apk.sh"
        inspector.write_bytes(Path(__file__).with_name("inspect-apk.sh").read_bytes())
        inspector.chmod(0o755)
        sdk = self.root / "sdk/build-tools/34.0.0"
        sdk.mkdir(parents=True)
        java = self.root / "jdk/bin/java"
        java.parent.mkdir(parents=True)
        java.write_text("#!/bin/sh\nexit 0\n")
        java.chmod(0o755)
        apk = self.root / self.asset
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "fixture")
        certs = self.root / "certs"
        certs.write_text("Signer #1 certificate SHA-256 digest: " + self.cert + "\n")
        badging = self.root / "badging"
        good_badging = ("package: name='com.befeast.sleepmusic' versionCode='1' versionName='0.1.0'\n"
                        "sdkVersion:'28'\ntargetSdkVersion:'34'\n")
        badging.write_text(good_badging)
        for tool, source in (("apksigner", certs), ("aapt2", badging)):
            stub = sdk / tool
            stub.write_text('#!/bin/sh\n[ -z "${DREMLA_RELEASE_TOKEN+x}" ] || exit 5\ncat "' + str(source) + '"\n')
            stub.chmod(0o755)
        with patch.dict(os.environ, ANDROID_HOME=str(self.root / "sdk"), JAVA_HOME=str(self.root / "jdk")):
            self.assertEqual(r.inspect(apk), (1, "0.1.0", self.cert))
            certs.write_text("Signer #1 certificate SHA-256 digest: " + self.cert + "\n"
                            "Signer #2 certificate SHA-256 digest: " + self.cert + "\n")
            with self.assertRaises(ValueError):
                r.inspect(apk)
            certs.write_text("Signer #1 certificate SHA-256 digest: " + self.cert + "\n")
            for text in (good_badging.replace("com.befeast.sleepmusic", "other.package"),
                         good_badging.replace("versionCode='1'", "versionCode='0'"),
                         good_badging + "native-code: 'fixture'\n"):
                badging.write_text(text)
                with self.assertRaises((ValueError, subprocess.SubprocessError)):
                    r.inspect(apk)
            badging.write_text(good_badging)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/fixture.so", "fixture")
            with self.assertRaises(subprocess.SubprocessError):
                r.inspect(apk)

    def test_version_hook(self):
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("release-tools.py", "verify-outcome.sh"):
            (scripts / name).write_bytes(Path(__file__).with_name(name).read_bytes())
        for name in ("inspect-apk", "test-hygiene", "check-public-hygiene", "test-forbidden-apis",
                     "check-forbidden-apis", "test-kit-port", "check-kit-port", "test-privacy",
                     "check-privacy", "test-signing", "test-collection-service", "test-device", "test-release-lock", "check-release-lock"):
            p = scripts / (name + ".sh")
            p.write_text("#!/bin/sh\nexit 0\n")
            p.chmod(0o755)
        gradle = self.root / "gradlew"
        gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > gradle-args\n')
        gradle.chmod(0o755)
        with patch.dict(os.environ, DREMLA_OUTCOME_SCOPE="repo"):
            result = subprocess.run(["bash", str(scripts / "verify-outcome.sh")], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            args = (self.root / "gradle-args").read_text().splitlines()
            self.assertIn("-Pdremla.versionCode=1", args)
            self.assertIn("-Pdremla.versionName=0.1.0", args)
            for key, value in (("DREMLA_VERSION_CODE", "0"), ("DREMLA_VERSION_NAME", "1.0.0;false")):
                (self.root / "gradle-args").unlink(missing_ok=True)
                with patch.dict(os.environ, {key: value}):
                    result = subprocess.run(["bash", str(scripts / "verify-outcome.sh")], capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / "gradle-args").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
