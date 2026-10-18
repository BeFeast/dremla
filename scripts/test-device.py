#!/usr/bin/env python3
"""Offline device contract tests. All child tools use a private, minimal fake SDK/PATH."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.dont_write_bytecode = True
SOURCE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("device_tools", SOURCE / "device-tools.py")
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

# Independent fake protocol: it does not import the implementation's allowlist.
FAKE_ADB = r'''
import hashlib, json, os, pathlib, re, sys, time
state_path = pathlib.Path(os.environ["FIXTURE_STATE"])
s = json.loads(state_path.read_text())
a = sys.argv[1:]
with open(os.environ["FIXTURE_LOG"], "a") as f:
    f.write(json.dumps(a) + "\n")
def reject():
    with open(os.environ["FIXTURE_VIOLATIONS"], "a") as f: f.write(json.dumps(a) + "\n")
    sys.exit(7)
if "DREMLA_RELEASE_TOKEN" in os.environ: reject()
if len(a) < 3 or a[:2] != ["-s", s["serial"]]: reject()
a = a[2:]
if a == ["get-state"]:
    if s["state"] == "timeout": time.sleep(30)
    if s["state"] == "absent": sys.exit(1)
    print(s["state"])
elif a[:2] == ["shell", "getprop"] and len(a) == 3 and a[2] in s["props"]:
    print(s["props"][a[2]])
elif a[:3] == ["shell", "settings", "get"] and len(a) == 5 and " ".join(a[3:]) in s["settings"]:
    print(s["settings"][" ".join(a[3:])])
elif a == ["shell", "dumpsys", "activity", "services", "com.befeast.sleepmusic"]:
    print(s["activity_services"])
elif a[:2] == ["shell", "dumpsys"] and len(a) == 3 and a[2] in s["dumps"]:
    if s.get("failed_read") == a[2]: sys.exit(1)
    print(s["dumps"][a[2]])
elif a == ["shell", "df", "-k", "/data"]:
    print("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/data 99999 12345 87654 12% /data")
elif a == ["shell", "pm", "list", "packages", "--show-versioncode"]:
    for pkg, item in s["packages"].items(): print("package:" + pkg + " versionCode:" + str(item["code"]))
elif len(a) == 4 and a[:3] in (["shell", "dumpsys", "package"], ["shell", "pm", "path"]):
    if not re.fullmatch(r"com\.befeast\.sleepmusic(?:\.[A-Za-z][A-Za-z0-9_]*)*", a[3]): reject()
    item = s["packages"][a[3]]
    if a[1] == "dumpsys": print("versionCode=" + str(item["code"]) + " minSdk=28\nversionName=" + item["name"])
    else: print(item["path"])
elif len(a) == 3 and a[0] == "pull":
    parts = a[1].split("/")
    if len(parts) not in (5, 6) or parts[:3] != ["", "data", "app"] or parts[-1] != "base.apk": reject()
    if len(parts) == 6 and not re.fullmatch(r"~~[A-Za-z0-9_=+.~-]+", parts[3]): reject()
    if not re.fullmatch(r"com\.befeast\.sleepmusic(?:\.[A-Za-z][A-Za-z0-9_]*)*-[A-Za-z0-9_=+.~-]+", parts[-2]): reject()
    if any("~~" in part for part in ([parts[3][2:]] if len(parts) == 6 else []) + [parts[-2]]): reject()
    if pathlib.Path(a[2]).name != "installed.apk": reject()
    item = next(x for x in s["packages"].values() if x["path"] == "package:" + a[1])
    pathlib.Path(a[2]).write_bytes(bytes.fromhex(item["apk"]))
elif len(a) == 3 and a[:2] == ["install", "-r"]:
    receipts = list((pathlib.Path(os.environ["XDG_STATE_HOME"]) / "dremla/deliveries").glob("*/receipt.json"))
    s["receipt_at_install"] = json.loads(receipts[0].read_text()) if len(receipts) == 1 else None
    s["before_at_install"] = bool(receipts and receipts[0].with_name("before.txt").is_file())
    state_path.write_text(json.dumps(s))
    if s.get("install_failure"):
        print(s["serial"] + " raw tool failure", file=sys.stderr)
        sys.exit(1)
    if s.get("install_timeout"): time.sleep(30)
    raw = pathlib.Path(a[2]).read_bytes()
    s["installed_digest"] = hashlib.sha256(raw).hexdigest()
    s["packages"]["com.befeast.sleepmusic"] = s.get("bad_install", s["replacement"])
    state_path.write_text(json.dumps(s))
    print("Success")
else: reject()
'''
FAKE_SDK = r'''
import json, os, pathlib, sys, zipfile
if "DREMLA_RELEASE_TOKEN" in os.environ: sys.exit(9)
with zipfile.ZipFile(sys.argv[-1]) as z: item = json.loads(z.read("fixture.json"))
if pathlib.Path(sys.argv[0]).name == "apksigner":
    for n, cert in enumerate(item["certs"], 1): print("Signer #" + str(n) + " certificate SHA-256 digest: " + cert)
else:
    print("package: name='com.befeast.sleepmusic' versionCode='" + str(item["code"]) + "' versionName='" + item["name"] + "'")
    print("sdkVersion:'28'\ntargetSdkVersion:'34'")
'''


def apk_bytes(code=2, name="0.2.0", certs=None, extra=""):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        info = zipfile.ZipInfo("fixture.json", date_time=(2020, 1, 1, 0, 0, 0))
        archive.writestr(info, json.dumps(dict(code=code, name=name, certs=certs or ["a" * 64], extra=extra)))
    return output.getvalue()


def installed(raw, code=2, name="0.2.0", package=d.PACKAGE, modern=True):
    parent = "~~Random_abC-==/" if modern else ""
    return dict(code=code, name=name, apk=raw.hex(), path="package:/data/app/" + parent + package + "-Random_abC-==/base.apk")


SERVICE = d.PACKAGE + "/.playback.NightAccessibilityService"
ACTIVITY_HEADER = "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n"
SERVICE_RECORD = "  * ServiceRecord{1a2b3c u0 " + SERVICE + "}\n"
BOUND_RECORD = (SERVICE_RECORD + "    app=ProcessRecord{abc 123:com.befeast.sleepmusic/u0a123}\n"
                "    Bindings:\n    * IntentBindRec{abcdef CREATE}:\n"
                "      binder=android.os.BinderProxy@123abc\n"
                "      requested=true received=true hasBound=true doRebind=false\n"
                "    All Connections:\n"
                "      ConnectionRecord{abc123 u0 CR " + SERVICE + ":@123abc}\n")
A11Y_ENABLED = "  Enabled services:{{" + d.PACKAGE + "/" + d.PACKAGE + ".playback.NightAccessibilityService}}\n"
A11Y_BOUND = ("  Bound services:{Service[label=TV off, feedbackType[FEEDBACK_GENERIC], "
              "capabilities=0, eventTypes=0, notificationTimeout=0, requestA11yButton=false]}\n")


class DeviceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dremla-device-test-")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / "repo"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        for name in ("device-tools.py", "release-tools.py", "inspect-apk.sh", "probe-target.sh", "deliver.sh", "verify-outcome.sh"):
            shutil.copy2(SOURCE / name, scripts / name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        # No inherited PATH: no real adb, SDK, credentials, home or adb-server socket.
        for name in ("bash", "dirname", "python3", "git", "unzip"):
            (self.bin / name).symlink_to(shutil.which(name))
        self.tool(self.bin / "java", "#!/bin/sh\nexit 0\n", python=False)
        self.sdk = self.base / "sdk"
        self.tool(self.bin / "adb", FAKE_ADB)
        (self.sdk / "platform-tools").mkdir(parents=True)
        (self.sdk / "platform-tools/adb").symlink_to(self.bin / "adb")
        for name in ("apksigner", "aapt2"):
            self.tool(self.sdk / "build-tools/34.0.0" / name, FAKE_SDK)
        for name in ("home", "config", "state", "tmp"):
            (self.base / name).mkdir()
        self.env = dict(PATH=str(self.bin), HOME=str(self.base / "home"),
                        XDG_CONFIG_HOME=str(self.base / "config"), XDG_STATE_HOME=str(self.base / "state"),
                        ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        ANDROID_ADB_SERVER_PORT="1", ADB_SERVER_SOCKET=":".join(("tcp", "127.0.0.1", "1")),
                        TMPDIR=str(self.base / "tmp"), DREMLA_FORGE_URL="https://forge.example",
                        DREMLA_REPOSITORY="BeFeast/dremla", DREMLA_DELIVERY_APPROVAL="fixture-approval",
                        FIXTURE_STATE=str(self.base / "device.json"), FIXTURE_LOG=str(self.base / "calls.jsonl"),
                        FIXTURE_VIOLATIONS=str(self.base / "violations"), LC_ALL="C.UTF-8")
        for patcher in (patch.dict(os.environ, self.env, clear=True), patch.object(d, "ROOT", self.root),
                        patch.object(d.release, "ROOT", self.root), patch.object(tempfile, "tempdir", str(self.base / "tmp"))):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.apk = apk_bytes()
        self.lock = dict(tag="v0.2.0", tagSha="b" * 40, assetName="dremla-0.2.0.apk",
                         apkSha256=hashlib.sha256(self.apk).hexdigest(), versionCode=2, versionName="0.2.0",
                         certificateSha256="a" * 64, releaseUrlPath="/BeFeast/dremla/releases/tag/v0.2.0")
        lock_path = self.root / "delivery/release-lock.json"
        lock_path.parent.mkdir()
        lock_path.write_text(json.dumps(self.lock))
        for args in (["init", "-q"], ["add", "."], ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"]):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        self.config = self.base / "config/dremla/delivery.env"
        self.config.parent.mkdir()
        self.config.write_text("# private runtime fixture\nDREMLA_ADB_SERIAL=FAKE0SERIAL01\n")
        self.config.chmod(0o600)
        self.state = dict(serial="FAKE0SERIAL01", state="device",
                          props=dict(zip(d.PROPERTIES, ("Fixture", "SEI600GO", "14", "34", "armeabi-v7a", "brand/product/device:14/BUILD/123:user/release-keys"))),
                          settings={"secure attentive_timeout": "-1", "secure sleep_timeout": "0",
                                    "system screen_off_timeout": "60000", "secure accessibility_enabled": "1",
                                    "secure enabled_accessibility_services": d.PACKAGE + "/.playback.NightAccessibilityService",
                                    "global hdmi_control_enabled": "1"},
                          dumps=dict(power="mAttentiveTimeoutSetting=-1\nmSleepTimeoutSetting=0\nmScreenOffTimeoutSetting=60000",
                                     accessibility=A11Y_ENABLED + A11Y_BOUND,
                                     audio="- STREAM_MUSIC:\n Muted: false\n Max: 25\n Current: 400 (hdmi): 15\n- STREAM_ALARM:\n Muted: true\n Max: 7",
                                     hdmi_control="mHdmiControlEnabled=true\npower_control_mode=broadcast",
                                     input="Device 1: Remote\n  Descriptor: private\nDevice 2: Keyboard"),
                          activity_services=ACTIVITY_HEADER + BOUND_RECORD,
                          packages={d.PACKAGE: installed(self.apk)}, replacement=installed(self.apk))
        self.save()
        self.downloads = 0
        self.download_apk = self.apk
        self.bad_sums = False
        self.addCleanup(self.assert_isolation)

    def tool(self, path, text, python=True):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(("#!" + sys.executable + "\n" if python else "") + text)
        path.chmod(0o755)

    def save(self):
        (self.base / "device.json").write_text(json.dumps(self.state))

    def calls(self):
        path = self.base / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def assert_isolation(self):
        self.assertFalse((self.base / "violations").exists(), "fake adb rejected a forbidden command")
        for call in self.calls():
            self.assertEqual(call[:2], ["-s", self.state["serial"]])
        self.assertEqual(list((self.base / "tmp").iterdir()), [], "temporary APKs survived")

    def entry(self, command, expected=0, args=()):
        self.save()
        argv = (["python3", "-I", str(self.root / "scripts/device-tools.py"), "verify"] if command == "verify"
                else [str(self.root / "scripts" / (command + ".sh")), *args])
        result = subprocess.run(argv, env=dict(os.environ), capture_output=True, text=True, timeout=45)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        self.assertNotIn(self.state["serial"], result.stdout + result.stderr)
        return result.stdout + result.stderr

    def api_json(self, api, suffix, **kwargs):
        if suffix == "/tags/" + self.lock["tag"]:
            return {"commit": {"sha": self.lock["tagSha"]}}
        self.assertEqual(suffix, "/releases/tags/" + self.lock["tag"])
        return dict(draft=False, tag_name=self.lock["tag"], assets=[
            dict(name=name, browser_download_url=api.origin + "/download/" + name)
            for name in (self.lock["assetName"], "SHA256SUMS")])

    def api_request(self, api, path, **kwargs):
        self.downloads += 1
        if path.endswith("SHA256SUMS"):
            return b"wrong" if self.bad_sums else (hashlib.sha256(self.download_apk).hexdigest() + "  " + self.lock["assetName"] + "\n").encode()
        return self.download_apk

    def deliver(self, expected=0):
        self.save()
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(d.release.API, "json", autospec=True, side_effect=self.api_json), \
             patch.object(d.release.API, "request", autospec=True, side_effect=self.api_request), \
             patch.object(sys, "argv", ["device-tools.py", "deliver"]), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = d.cli()
        output = stdout.getvalue() + stderr.getvalue()
        self.assertEqual(code, expected, output)
        self.assertNotIn(self.state["serial"], output)
        self.assertNotIn("fixture-token", output)
        return output

    def test_probe_report_and_all_reads_are_allowed(self):
        report = self.entry("probe-target")
        for field in (*d.PROPERTIES, "attentive_timeout: -1", "data free KiB: 87654", "apkPath:",
                      "apkSha256:", "certificateSha256:", "accessibility bound: True", "accessibility enabled: True",
                      "STREAM_MUSIC muted: false", "STREAM_MUSIC max: 25", "STREAM_MUSIC volume index:",
                      "power_control_mode: broadcast", "input device names: Remote, Keyboard", "prerequisite: PASS"):
            self.assertIn(field, report)
        self.assertNotIn("Descriptor", report)
        self.assertFalse(any(c[2] == "install" for c in self.calls()))

    def test_aosp_accessibility_and_cec_dump_formats(self):
        self.state["dumps"]["hdmi_control"] = "power_control_mode (string): broadcast"
        report = self.entry("probe-target")
        self.assertIn("accessibility enabled (dumpsys): True", report)
        self.assertIn("accessibility bound: True", report)
        self.assertIn("power_control_mode: broadcast", report)
        self.state["dumps"]["accessibility"] = A11Y_ENABLED + "  Bound services:{}\n"
        self.state["activity_services"] = ACTIVITY_HEADER + "  (nothing)\n"
        report = self.entry("probe-target")
        self.assertIn("accessibility enabled (dumpsys): True", report)
        self.assertIn("accessibility bound: False", report)
        self.state["dumps"]["accessibility"] = "  Enabled services:{}\n  Bound services:{}\n"
        self.state["settings"]["secure enabled_accessibility_services"] = "null"
        self.state["settings"]["secure accessibility_enabled"] = "0"
        self.assertIn("accessibility enabled (dumpsys): False", self.entry("probe-target"))
        self.assertIn("accessibility bound: False", self.entry("probe-target"))

    def test_binding_requires_connection_in_matching_record(self):
        for text in ("unrecognized", ACTIVITY_HEADER + SERVICE_RECORD + "    Bindings:\n      binder=null\n",
                     ACTIVITY_HEADER + SERVICE_RECORD + "  Connection bindings to services:\n    ConnectionRecord{unrelated}\n",
                     ACTIVITY_HEADER + SERVICE_RECORD + "    Bindings:\n  Connection bindings to services:\n    ConnectionRecord{unrelated}\n",
                     ACTIVITY_HEADER + SERVICE_RECORD + BOUND_RECORD.replace(SERVICE, d.PACKAGE + "/.OtherService"),
                     ACTIVITY_HEADER + BOUND_RECORD + BOUND_RECORD):
            with self.subTest(text=text):
                self.state["activity_services"] = text
                self.assertIn("accessibility bound: UNKNOWN", self.entry("probe-target"))
        self.state["activity_services"] = (ACTIVITY_HEADER + BOUND_RECORD).replace(SERVICE, d.PACKAGE + "/" + d.PACKAGE + ".playback.NightAccessibilityService")
        self.assertIn("accessibility bound: True", self.entry("probe-target"))

    def test_binding_live_and_mixed_connections(self):
        for record in (BOUND_RECORD, BOUND_RECORD + "      ConnectionRecord{dead u0 CR DEAD " + SERVICE + ":@dead}\n"):
            with self.subTest(record=record):
                self.state["activity_services"] = ACTIVITY_HEADER + record
                self.assertIn("accessibility bound: True", self.entry("probe-target"))

    def test_binding_dead_only(self):
        self.state["activity_services"] = ACTIVITY_HEADER + BOUND_RECORD.replace("u0 CR ", "u0 CR DEAD ")
        self.assertIn("accessibility bound: False", self.entry("probe-target"))

    def test_binding_api_34_connection_flags_suffix(self):
        # Android 14 ConnectionRecord.toString() ends with " flags=0x<hex>" after the binder hash.
        flagged = BOUND_RECORD.replace(SERVICE + ":@123abc}", SERVICE + ":@123abc flags=0x4000001}")
        self.assertNotEqual(flagged, BOUND_RECORD)
        for record, expected in ((flagged, "True"), (flagged.replace("u0 CR ", "u0 CR DEAD "), "False"),
                                 (flagged.replace("flags=0x4000001", "flags=0x4000001 extra"), "UNKNOWN")):
            with self.subTest(expected=expected):
                self.state["activity_services"] = ACTIVITY_HEADER + record
                self.assertIn("accessibility bound: " + expected, self.entry("probe-target"))

    def test_binding_pending_app(self):
        self.state["activity_services"] = ACTIVITY_HEADER + BOUND_RECORD.replace(
            "app=ProcessRecord{abc 123:com.befeast.sleepmusic/u0a123}", "app=null")
        self.assertIn("accessibility bound: UNKNOWN", self.entry("probe-target"))

    def test_binding_pending_or_missing_evidence(self):
        for old, new in (("received=true", "received=false"),
                         ("binder=android.os.BinderProxy@123abc", "binder=null"),
                         ("app=ProcessRecord{abc 123:com.befeast.sleepmusic/u0a123}", ""),
                         ("received=true", ""), ("binder=android.os.BinderProxy@123abc", ""),
                         ("* IntentBindRec{abcdef CREATE}:", "* UnrecognizedBinding:")):
            with self.subTest(old=old, new=new):
                self.state["activity_services"] = ACTIVITY_HEADER + BOUND_RECORD.replace(old, new)
                self.assertIn("accessibility bound: UNKNOWN", self.entry("probe-target"))

    def test_binding_cannot_combine_partial_intent_bindings(self):
        first = BOUND_RECORD.replace("received=true", "received=false")
        second = ("    * IntentBindRec{other CREATE}:\n      binder=null\n"
                  "      requested=true received=true hasBound=true doRebind=false\n")
        self.state["activity_services"] = ACTIVITY_HEADER + first.replace("    All Connections:\n", second + "    All Connections:\n")
        self.assertIn("accessibility bound: UNKNOWN", self.entry("probe-target"))

    def test_binding_other_service_connections_do_not_count(self):
        for other in (d.PACKAGE + "/.OtherService", "org.example.other/.OtherService", SERVICE + "Extra"):
            for same_record in (False, True):
                with self.subTest(other=other, same_record=same_record):
                    if same_record:
                        record = BOUND_RECORD.replace(SERVICE + ":@", other + ":@")
                    else:
                        record = SERVICE_RECORD + BOUND_RECORD.replace(SERVICE, other)
                    self.state["activity_services"] = ACTIVITY_HEADER + record
                    self.assertIn("accessibility bound: UNKNOWN", self.entry("probe-target"))

    def test_api_29_and_34_apk_paths(self):
        for sdk, modern in (("29", False), ("34", True)):
            with self.subTest(sdk=sdk):
                self.state["props"]["ro.build.version.sdk"] = sdk
                self.state["packages"][d.PACKAGE] = installed(self.apk, modern=modern)
                self.assertIn("apkSha256: " + self.lock["apkSha256"], self.entry("probe-target"))

    def test_prerequisite_effective_values_fallback_and_unknown(self):
        for value, expected in (("-1", 0), ("0", 0), ("12600000", 0), ("12599999", 4), ("null", 4), ("unreadable", 4)):
            with self.subTest(value=value):
                self.state["settings"]["secure attentive_timeout"] = value
                self.state["dumps"]["power"] = ""
                output = self.entry("probe-target", expected)
                self.assertIn("prerequisite: " + ("PASS" if expected == 0 else "UNKNOWN" if value in ("null", "unreadable") else "FAIL"), output)
                if expected: self.assertIn("open a new P1 issue; do not change settings", output)
        self.state["dumps"]["power"] = "mAttentiveTimeoutSetting=-1\nmSleepTimeoutSetting=1"
        self.assertIn("prerequisite: FAIL", self.entry("probe-target", 4))
        self.state["dumps"]["power"] = "mAttentiveTimeoutSetting=-1\nmSleepTimeoutSetting=0"
        self.entry("probe-target")

    def test_unreachable_states_and_model(self):
        for state in ("offline", "unauthorized", "absent"):
            self.state["state"] = state
            self.assertIn("enable Wireless debugging", self.entry("probe-target", 1))
        self.state["state"] = "device"
        self.state["props"]["ro.product.model"] = "wrong model"
        self.assertIn("enable Wireless debugging", self.entry("verify", 1))

    def test_timeout_fails_closed(self):
        self.state["state"] = "timeout"
        self.save()
        with tempfile.TemporaryDirectory() as work, patch.object(d, "TIMEOUT", 0.1):
            device = d.Device(d.config(), work)
            with self.assertRaisesRegex(d.DeviceError, "enable Wireless debugging"):
                device.live()

    def test_config_errors(self):
        original = self.config.read_text()
        cases = ("", "UNKNOWN=value\n", original + original, "DREMLA_ADB_SERIAL=$(false)\n",
                 'DREMLA_ADB_SERIAL="FAKE0SERIAL01"\n', "DREMLA_ADB_SERIAL=abc;false\n")
        for value in cases:
            self.config.write_text(value)
            self.entry("probe-target", 2)
        self.config.write_text(original)
        for mode in (0o620, 0o602):
            self.config.chmod(mode)
            self.entry("probe-target", 2)
        self.config.unlink()
        self.assertIn("${XDG_CONFIG_HOME:-$HOME/.config}/dremla/delivery.env", self.entry("probe-target", 2))
        target = self.base / "other-config"
        target.write_text(original)
        self.config.symlink_to(target)
        self.entry("probe-target", 2)
        self.assertEqual(self.calls(), [])

    def test_config_is_data_and_sdk_path_fallback(self):
        self.config.write_text("DREMLA_ADB_SERIAL=FAKE0SERIAL01\nDREMLA_TARGET_MODEL=SEI600GO\nDREMLA_FORGE_URL=https://unused.example\n")
        (self.sdk / "platform-tools/adb").unlink()
        self.entry("probe-target")
        (self.bin / "adb").unlink()
        self.assertIn("adb not found", self.entry("probe-target", 1))

    def test_wrapper_arguments_and_approval_preflight(self):
        for name in ("deliver", "probe-target"):
            self.entry(name, 2, ("unexpected",))
        for approval in ("", "bad approval", "x" * 129, "$(false)"):
            with patch.dict(os.environ, DREMLA_DELIVERY_APPROVAL=approval):
                self.entry("deliver", 2)
        with patch.dict(os.environ, DREMLA_DELIVER_MODE="unknown"):
            self.entry("deliver", 2)
        self.assertEqual(self.calls(), [])

    def test_approval_rejects_serial_and_endpoints_before_network(self):
        for approval in (self.state["serial"], "approval-" + self.state["serial"],
                         ":".join(("fixture.example", "5555")), ":".join(("host", "80")),
                         ".".join(("203", "0", "113", "9")), ":".join(("[::1]", "5555"))):
            with self.subTest(approval=approval), patch.dict(os.environ, DREMLA_DELIVERY_APPROVAL=approval):
                self.deliver(2)
                self.entry("deliver", 2)
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.downloads, 0)
        self.assertEqual(list((self.base / "state").iterdir()), [])

    def test_missing_or_nonexecutable_apksigner_preflight(self):
        signer = self.sdk / "build-tools/34.0.0/apksigner"
        for missing in (False, True):
            if missing:
                signer.unlink()
            else:
                signer.chmod(0o600)
            for command in ("probe-target", "verify", "deliver"):
                self.assertIn("build-tools 34.0.0", self.entry(command, 1))
            self.assertIn("build-tools 34.0.0", self.deliver(1))
            with self.assertRaisesRegex(d.release.ReleaseError, "build-tools 34.0.0"):
                d.release.inspect(self.base / "absent.apk")
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.downloads, 0)

    def test_deliver_probe_has_no_download_install_or_receipt(self):
        with patch.dict(os.environ, DREMLA_DELIVER_MODE="probe", DREMLA_DELIVERY_APPROVAL=""):
            self.entry("deliver")
            self.deliver()
        self.assertEqual(self.downloads, 0)
        self.assertEqual(list((self.base / "state").iterdir()), [])
        self.assertFalse(any(c[2] == "install" for c in self.calls()))

    def test_install_verified_asset_and_private_receipt(self):
        self.state["packages"][d.PACKAGE] = installed(apk_bytes(1, "0.1.0"), 1, "0.1.0")
        with patch.dict(os.environ, DREMLA_RELEASE_TOKEN="fixture-token"):
            output = self.deliver()
        installs = [c for c in self.calls() if c[2] == "install"]
        self.assertEqual(len(installs), 1)
        self.assertEqual(installs[0][2:4], ["install", "-r"])
        self.assertEqual(json.loads((self.base / "device.json").read_text())["installed_digest"], self.lock["apkSha256"])
        self.assertFalse(Path(installs[0][-1]).exists())
        receipt = next((self.base / "state/dremla/deliveries").iterdir())
        self.assertEqual(stat.S_IMODE(receipt.stat().st_mode), 0o700)
        self.assertEqual({p.name for p in receipt.iterdir()}, {"before.txt", "after.txt", "receipt.json"})
        for path in receipt.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertNotIn(self.state["serial"], path.read_text())
            self.assertNotIn("fixture-token", path.read_text())
            self.assertNotIn("forge.example", path.read_text())
        data = json.loads((receipt / "receipt.json").read_text())
        self.assertEqual(data["lock"], self.lock)
        self.assertEqual(data["approvalId"], "fixture-approval")
        self.assertEqual(data["downloadedApkSha256"], self.lock["apkSha256"])
        self.assertEqual(data["installDecision"], "install")
        self.assertRegex(data["headSha"], r"^[0-9a-f]{40}$")
        self.assertIn("receipt:", output)
        self.check_receipt("PASS")
        at_install = json.loads((self.base / "device.json").read_text())
        self.assertEqual(at_install["receipt_at_install"]["result"], "STARTED")
        self.assertTrue(at_install["before_at_install"])
        self.assertNotIn("finishedUtc", at_install["receipt_at_install"])

    def test_delivery_redacts_token_after_api_consumes_environment(self):
        self.state["packages"] = {}
        self.state["dumps"]["input"] = "Device 1: Remote fixture-token"
        with patch.dict(os.environ, DREMLA_RELEASE_TOKEN="fixture-token"):
            output = self.deliver()
            self.assertNotIn("DREMLA_RELEASE_TOKEN", os.environ)
        self.assertIn("input device names: Remote <redacted>", output)
        receipt = self.check_receipt("PASS")
        self.assertIn("input device names: Remote <redacted>", (receipt / "before.txt").read_text())
        for name in ("before.txt", "after.txt", "receipt.json"):
            self.assertNotIn("fixture-token", (receipt / name).read_text())
        self.assertEqual(sum(c[2] == "install" for c in self.calls()), 1)
        # Fake adb and SDK tools reject even an empty token environment variable.
        self.assertFalse((self.base / "violations").exists())

    def test_printed_receipt_path_redacts_serial(self):
        state_home = self.base / ("state-" + self.state["serial"])
        with patch.dict(os.environ, XDG_STATE_HOME=str(state_home)):
            output = self.deliver()
        receipt = next((state_home / "dremla/deliveries").iterdir())
        self.assertIn("receipt: " + str(receipt).replace(self.state["serial"], "<redacted>"), output)
        self.assertEqual({p.name for p in receipt.iterdir()}, {"before.txt", "after.txt", "receipt.json"})
        self.assertEqual(json.loads((receipt / "receipt.json").read_text())["result"], "PASS")

    def check_receipt(self, result, diagnostic=None, after=True):
        receipts = list((self.base / "state/dremla/deliveries").iterdir())
        self.assertEqual(len(receipts), 1)
        receipt = receipts[0]
        self.assertEqual(stat.S_IMODE(receipt.stat().st_mode), 0o700)
        expected = {"before.txt", "receipt.json"} | ({"after.txt"} if after else set())
        self.assertEqual({p.name for p in receipt.iterdir()}, expected)
        self.assertIn("prerequisite:", (receipt / "before.txt").read_text())
        for path in receipt.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            for private in (self.state["serial"], "fixture-token", "raw tool failure"):
                self.assertNotIn(private, path.read_text())
        payload = json.loads((receipt / "receipt.json").read_text())
        self.assertEqual(payload["result"], result)
        self.assertIn("startedUtc", payload)
        self.assertIn("finishedUtc", payload)
        if diagnostic:
            self.assertTrue(payload["diagnostic"].startswith("device-tools: FAIL: "))
            self.assertIn(diagnostic, payload["diagnostic"])
        return receipt

    def test_fresh_install_with_prerequisite_warning(self):
        self.state["packages"] = {}
        self.state["dumps"]["power"] = "mAttentiveTimeoutSetting=42"
        self.assertIn("warning: prerequisite", self.deliver())
        self.assertEqual(sum(c[2] == "install" for c in self.calls()), 1)

    def test_equal_version_same_bytes_no_install(self):
        self.assertIn("already installed", self.deliver())
        self.assertFalse(any(c[2] == "install" for c in self.calls()))

    def test_install_refuses_downgrade_same_code_different_bytes_and_certificate(self):
        cases = ((installed(apk_bytes(3, "0.3.0"), 3, "0.3.0"), "downgrade"),
                 (installed(apk_bytes(extra="different")), "different APK"),
                 (installed(apk_bytes(1, "0.1.0", ["c" * 64]), 1, "0.1.0"), "certificate mismatch"))
        for item, message in cases:
            with self.subTest(message=message):
                self.state["packages"][d.PACKAGE] = item
                self.assertIn(message, self.deliver(1))
                shutil.rmtree(self.check_receipt("REFUSED", message, after=False))
        self.assertFalse(any(c[2] == "install" for c in self.calls()))

    def test_download_mismatches_fail_before_adb(self):
        for raw in (apk_bytes(extra="wrong SHA"), apk_bytes(certs=["c" * 64]), apk_bytes(3)):
            self.download_apk = raw
            self.deliver(1)
        self.download_apk = self.apk
        self.bad_sums = True
        self.deliver(1)
        self.assertEqual(self.calls(), [])
        self.assertEqual(list((self.base / "state").iterdir()), [])

    def test_modified_staged_and_symlinked_lock(self):
        path = self.root / "delivery/release-lock.json"
        original = path.read_text()
        path.write_text(original + "\n")
        self.deliver(1)
        subprocess.run(["git", "add", str(path)], cwd=self.root, check=True, capture_output=True)
        self.deliver(1)
        path.unlink()
        other = self.base / "lock"
        other.write_text(original)
        path.symlink_to(other)
        self.deliver(1)
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.downloads, 0)

    def test_bad_install_identity_never_uninstalls(self):
        self.state["packages"] = {}
        self.state["bad_install"] = installed(apk_bytes(extra="bad install"))
        self.assertIn("apkSha256", self.deliver(1))
        self.assertEqual(sum(c[2] == "install" for c in self.calls()), 1)
        self.assertFalse(any("uninstall" in c for c in self.calls()))
        self.check_receipt("FAIL", "apkSha256")

    def test_install_failure_persists_before_and_failure(self):
        self.state["packages"] = {}
        self.state["install_failure"] = True
        self.assertIn("receipt:", self.deliver(1))
        self.check_receipt("FAIL", "device read or tool failed")
        self.assertEqual(sum(c[2] == "install" for c in self.calls()), 1)

    def test_install_timeout_persists_failure(self):
        self.state["packages"] = {}
        self.state["install_timeout"] = True
        original = d.run
        def short_install(argv, timeout=d.TIMEOUT):
            return original(argv, timeout=0.2 if "install" in argv else timeout)
        with patch.object(d, "run", side_effect=short_install):
            self.assertIn("receipt:", self.deliver(1))
        self.check_receipt("FAIL", "timed out")

    def test_unreadable_after_identity_persists_failure(self):
        self.state["packages"] = {}
        self.state["bad_install"] = dict(installed(self.apk), path="package:/unsupported/base.apk")
        self.assertIn("receipt:", self.deliver(1))
        self.check_receipt("FAIL", "not followed", after=False)

    def test_unreachable_delivery_has_no_receipt(self):
        self.state["state"] = "offline"
        self.assertIn("enable Wireless debugging", self.deliver(1))
        self.assertEqual(list((self.base / "state").iterdir()), [])

    def test_verify_all_identity_fields_and_no_install(self):
        self.entry("verify")
        for field, item in (("versionCode", installed(apk_bytes(3), 3)),
                            ("versionName", installed(self.apk, name="0.9.0")),
                            ("apkSha256", installed(apk_bytes(extra="different"))),
                            ("certificateSha256", installed(apk_bytes(certs=["c" * 64])))):
            with self.subTest(field=field):
                self.state["packages"][d.PACKAGE] = item
                if field == "certificateSha256":
                    # Keep bytes identical and change only the pinned cert for this assertion.
                    self.state["packages"][d.PACKAGE] = installed(self.apk)
                    signer = self.sdk / "build-tools/34.0.0/apksigner"
                    signer.write_text("#!" + sys.executable + "\nprint('Signer #1 certificate SHA-256 digest: " + "c" * 64 + "')\n")
                self.assertIn(field, self.entry("verify", 1))
        self.state["packages"] = {}
        self.assertIn("package missing", self.entry("verify", 1))
        self.assertFalse(any(c[2] == "install" for c in self.calls()))

    def test_missing_config_full_stage_fails(self):
        self.config.unlink()
        self.assertIn("device identity: no delivery config (use DREMLA_OUTCOME_SCOPE=repo for host-only checks)", self.entry("verify", 1))
        self.assertEqual(self.calls(), [])

    def test_rejected_commands_never_reach_adb(self):
        forbidden = (["connect", "fake"], ["uninstall", d.PACKAGE], ["install", "-d", "fixture.apk"],
                     ["shell", "settings", "put", "secure", "sleep_timeout", "0"],
                     ["shell", "getprop", "ro.serialno"], ["shell", "input", "keyevent", "1"],
                     ["shell", "dumpsys", "package", d.PACKAGE + ";false"],
                     ["shell", "pm", "path", d.PACKAGE + "$(false)"], ["push", "a", "b"],
                     ["shell", "dumpsys", "activity", "services"],
                     ["shell", "dumpsys", "activity", "services", d.PACKAGE + ".debug"],
                     ["shell", "dumpsys", "activity", "services", d.PACKAGE, "extra"],
                     ["install", "-r", "unverified.apk"], ["pull", "/data/app/../base.apk", "elsewhere"])
        with tempfile.TemporaryDirectory() as work:
            device = d.Device(d.config(), work)
            for args in forbidden:
                with self.subTest(args=args), self.assertRaisesRegex(d.DeviceError, "allowlist"):
                    device.adb(args)
        self.assertEqual(self.calls(), [])

    def test_unsupported_apk_paths_multiple_signers_and_failed_reads(self):
        for path in ("package:/system/app/base.apk", "package:/data/app/fixture/base.apk\npackage:/data/app/fixture/split.apk", "package:/data/app/fixture/../../base.apk",
                     "package:/data/local/tmp/base.apk", "package:/data/app/../" + d.PACKAGE + "-abc/base.apk",
                     "package:/data/app/./" + d.PACKAGE + "-abc/base.apk",
                     "package:/data/app/~~abc/../" + d.PACKAGE + "-abc/base.apk",
                     "package:/data/app/" + d.PACKAGE + "-abc;false/base.apk",
                     "package:/data/app/" + d.PACKAGE + "-abc def/base.apk",
                     "package:/data/app/" + d.PACKAGE + ".debug-abc/base.apk",
                     "package:/data/app/org.example.other-abc/base.apk",
                     "package:/data/app/" + d.PACKAGE + "-abc~~def/base.apk"):
            self.state["packages"][d.PACKAGE]["path"] = path
            self.assertIn("not followed", self.entry("probe-target", 1))
        self.assertFalse(any(c[2] == "pull" for c in self.calls()))
        self.state["packages"][d.PACKAGE] = installed(apk_bytes(certs=["a" * 64, "c" * 64]))
        self.assertIn("one signing certificate", self.entry("probe-target", 1))
        self.state["failed_read"] = "power"
        self.entry("probe-target", 1)

    def test_reports_redact_serial_and_only_include_dremla_packages(self):
        self.state["props"]["ro.build.fingerprint"] = self.state["serial"]
        self.state["dumps"]["input"] = "Device 1: " + self.state["serial"]
        self.state["packages"][d.PACKAGE + ".debug"] = installed(apk_bytes(1, "0.1.0"), 1, "0.1.0", d.PACKAGE + ".debug")
        self.state["packages"]["org.example.other"] = installed(self.apk)
        output = self.entry("probe-target")
        self.assertIn(d.PACKAGE + ".debug versionCode", output)
        self.assertNotIn("org.example.other", output)
        self.assertIn("<redacted>", output)

    def test_field_aware_redaction_preserves_fingerprint_and_time(self):
        endpoints = [":".join(("fixture.example", "5555")), ":".join(("[::1]", "81")),
                     ":".join(("203.0.113.9", "80"))]
        self.state["dumps"]["input"] = "Device 1: Remote 12:34 " + " ".join(endpoints)
        self.state["packages"][d.PACKAGE]["name"] = endpoints[0]
        report = self.entry("probe-target")
        self.assertIn("ro.build.fingerprint: " + self.state["props"]["ro.build.fingerprint"], report)
        self.assertIn("Remote 12:34 <endpoint> <endpoint> <endpoint>", report)
        self.assertIn("versionName: <endpoint>", report)
        for endpoint in endpoints:
            self.assertNotIn(endpoint, report)

    def test_receipts_refuse_symlinked_parents_and_repo_paths(self):
        outside = self.base / "outside"
        outside.mkdir()
        (self.base / "state/dremla").symlink_to(outside)
        self.assertIn("symlinked", self.deliver(1))
        (self.base / "state/dremla").unlink()
        with patch.dict(os.environ, XDG_STATE_HOME=str(self.root / "state")):
            self.assertIn("outside the repository", self.deliver(1))
        self.assertFalse(any(c[2] == "install" for c in self.calls()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
