#!/usr/bin/env python3
"""Guarded device reads and approved replacement installs. No connection management."""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.parse

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("release_tools", Path(__file__).with_name("release-tools.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.befeast.sleepmusic"
PACKAGE_RE = re.escape(PACKAGE) + r"(?:\.[A-Za-z][A-Za-z0-9_]*)*"
APK_SEGMENT = r"(?![^/]*~~)[A-Za-z0-9_=+.~-]+"
APK_RE = r"/data/app/(?:~~" + APK_SEGMENT + r"/)?(?P<package>" + PACKAGE_RE + r")-" + APK_SEGMENT + r"/base\.apk"
IPV4 = r"(?:[0-9]{1,3}\.){3}[0-9]{1,3}"
ENDPOINT = (r"(?:" + IPV4 + r"|\[[0-9A-Fa-f:.%]+\]):[0-9]{1,5}\b|"
            r"\b[A-Za-z0-9_.-]*[A-Za-z][A-Za-z0-9_.-]*:[0-9]{4,5}\b")
PROPERTIES = ("ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
              "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.build.fingerprint")
SETTINGS = (("secure", "attentive_timeout"), ("secure", "sleep_timeout"),
            ("system", "screen_off_timeout"), ("secure", "enabled_accessibility_services"),
            ("secure", "accessibility_enabled"), ("global", "hdmi_control_enabled"))
DUMPS = ("power", "accessibility", "audio", "hdmi_control", "input")
REACHABILITY = ("target unavailable or identity mismatch; enable Wireless debugging and have the "
                "operator connect the box to this host's adb server; this script neither connects, "
                "pairs nor trusts a stored record")
TIMEOUT = 15


class DeviceError(ValueError):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


def require(condition, message, code=1):
    if not condition:
        raise DeviceError(message, code)


def child_env():
    env = dict(os.environ)
    env.pop("DREMLA_RELEASE_TOKEN", None)
    return env


def run(argv, timeout=TIMEOUT):
    try:
        result = subprocess.run(argv, env=child_env(), cwd=ROOT, capture_output=True,
                                text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        raise DeviceError("tool unavailable or timed out") from None
    require(result.returncode == 0, "device read or tool failed")
    return result.stdout.strip()


def config_path():
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "dremla/delivery.env"


def config():
    path = config_path()
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd) as source:
            mode = os.fstat(source.fileno()).st_mode
            require(stat.S_ISREG(mode) and not mode & 0o022,
                    "delivery config must be regular and not group/world writable", 2)
            lines = source.read().splitlines()
    except OSError:
        raise DeviceError("delivery config missing or unsafe; expected "
                          "${XDG_CONFIG_HOME:-$HOME/.config}/dremla/delivery.env", 2) from None
    values = {}
    allowed = {"DREMLA_ADB_SERIAL", "DREMLA_TARGET_MODEL", "DREMLA_FORGE_URL"}
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep and key in allowed and key not in values and value,
                "invalid, unknown or duplicate delivery config key", 2)
        patterns = {"DREMLA_ADB_SERIAL": r"[A-Za-z0-9_\[][A-Za-z0-9_.:\[\]%-]{0,254}",
                    "DREMLA_TARGET_MODEL": r"[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}",
                    "DREMLA_FORGE_URL": r"https://[A-Za-z0-9.:-]+/?"}
        require(re.fullmatch(patterns[key], value), "invalid delivery config value (no shell syntax)", 2)
        if key == "DREMLA_FORGE_URL":
            url = urllib.parse.urlsplit(value)
            require(url.hostname and not url.username and url.path in ("", "/"),
                    "forge URL must be an HTTPS origin", 2)
        values[key] = value
    require("DREMLA_ADB_SERIAL" in values, "delivery config requires DREMLA_ADB_SERIAL", 2)
    values.setdefault("DREMLA_TARGET_MODEL", "SEI600GO")
    return values


def committed_lock():
    path = ROOT / "delivery/release-lock.json"
    require(not path.is_symlink() and path.is_file(), "release lock must be a regular file")
    data = release.read_json(path)
    release.validate_lock(data)
    # Comparing bytes with HEAD also catches staged modifications and untracked locks.
    try:
        committed = subprocess.check_output(["git", "show", "HEAD:delivery/release-lock.json"],
                                            cwd=ROOT, env=child_env(), stderr=subprocess.DEVNULL,
                                            timeout=TIMEOUT)
    except (OSError, subprocess.SubprocessError):
        raise DeviceError("release lock must be committed at HEAD") from None
    require(path.read_bytes() == committed, "release lock has local modifications relative to HEAD")
    return data


def read_only(args):
    """Exact argv allowlist; adb shell still uses a device shell, so operands are restricted."""
    if args == ["get-state"]:
        return True
    if args[:1] == ["shell"]:
        cmd = args[1:]
        if len(cmd) == 2 and cmd[0] == "getprop" and cmd[1] in PROPERTIES:
            return True
        if len(cmd) == 4 and cmd[:2] == ["settings", "get"] and tuple(cmd[2:]) in SETTINGS:
            return True
        if len(cmd) == 2 and cmd[0] == "dumpsys" and cmd[1] in DUMPS:
            return True
        if cmd == ["dumpsys", "activity", "services", PACKAGE]:
            return True
        if cmd in (["df", "-k", "/data"], ["pm", "list", "packages", "--show-versioncode"]):
            return True
        if len(cmd) == 3 and cmd[:2] in (["dumpsys", "package"], ["pm", "path"]):
            return re.fullmatch(PACKAGE_RE, cmd[2]) is not None
    return False


class Device:
    def __init__(self, values, work, redaction_token=None):
        self.serial = values["DREMLA_ADB_SERIAL"]
        self.model = values["DREMLA_TARGET_MODEL"]
        self.work = Path(work)
        self.approved_apk = None
        self.signer = release.build_tool("apksigner")
        sdk_adb = Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"
        self.binary = str(sdk_adb) if sdk_adb.is_file() and os.access(sdk_adb, os.X_OK) else shutil.which("adb")
        require(self.binary, "adb not found; configure ANDROID_HOME or PATH")
        self.secret = os.environ.get("DREMLA_RELEASE_TOKEN", "") if redaction_token is None else redaction_token

    def adb(self, args):
        permitted = read_only(args)
        if (len(args) == 3 and args[0] == "pull" and re.fullmatch(APK_RE, args[1])
                and args[2] == str(self.work / "installed.apk")):
            permitted = True
        if (len(args) == 3 and args[:2] == ["install", "-r"] and self.approved_apk is not None
                and args[2] == str(self.approved_apk)):
            permitted = True
        require(permitted, "adb command rejected by read-only allowlist")
        return run([self.binary, "-s", self.serial, *args], timeout=120 if args[0] in ("pull", "install") else TIMEOUT)

    def live(self):
        try:
            require(self.adb(["get-state"]) == "device", REACHABILITY)
            require(self.adb(["shell", "getprop", "ro.product.model"]) == self.model, REACHABILITY)
        except DeviceError:
            raise DeviceError(REACHABILITY) from None

    def clean(self, value, free_text=False):
        value = str(value).replace(self.serial, "<redacted>")
        if self.secret:
            value = value.replace(self.secret, "<redacted>")
        if free_text:
            value = re.sub(ENDPOINT, "<endpoint>", value)
        return " ".join(value.split())

    def packages(self):
        output = self.adb(["shell", "pm", "list", "packages", "--show-versioncode"])
        result = {}
        for line in output.splitlines():
            match = re.fullmatch(r"package:(\S+) versionCode:([0-9]+)", line.strip())
            if match and re.fullmatch(PACKAGE_RE, match[1]):
                require(match[1] not in result, "duplicate installed package")
                result[match[1]] = int(match[2])
            elif line.startswith("package:" + PACKAGE) and (line[len("package:" + PACKAGE):][:1] in ("", ".", " ")):
                raise DeviceError("unreadable Dremla package listing")
        return result

    def identity(self, package, listed_code):
        dump = self.adb(["shell", "dumpsys", "package", package])
        code = re.search(r"\bversionCode=(\d+)\b", dump)
        name = re.search(r"\bversionName=([^\s]+)", dump)
        require(code and name and int(code[1]) == listed_code, "unreadable installed package version")
        path = self.adb(["shell", "pm", "path", package])
        match = re.fullmatch(APK_RE, path[8:]) if path.startswith("package:") else None
        require(match and match["package"] == package,
                "installed APK path unsupported (expected one base.apk under /data/app/); not followed")
        apk = self.work / "installed.apk"
        try:
            self.adb(["pull", path[8:], str(apk)])
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            certs = run([str(self.signer), "verify", "--print-certs", str(apk)])
            matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certs, re.M)
            require(len(matches) == 1, "installed APK must have exactly one signing certificate")
            return dict(versionCode=int(code[1]), versionName=name[1], apkSha256=digest,
                        certificateSha256=matches[0].lower(), apkPath=path[8:])
        finally:
            apk.unlink(missing_ok=True)

    def installed(self):
        packages = self.packages()
        return self.identity(PACKAGE, packages[PACKAGE]) if PACKAGE in packages else None

    def probe(self):
        lines = []
        def emit(key, value, free_text=False):
            lines.append(key + ": " + self.clean(value, free_text))
        emit("target", self.model + " (serial from delivery.env)")
        for prop in PROPERTIES:
            emit(prop, self.adb(["shell", "getprop", prop]) or "UNKNOWN")
        settings = {}
        for namespace, key in SETTINGS:
            settings[key] = self.adb(["shell", "settings", "get", namespace, key])
        power = self.adb(["shell", "dumpsys", "power"])
        verdicts = []
        for key, effective in (("attentive_timeout", "mAttentiveTimeoutSetting"),
                               ("sleep_timeout", "mSleepTimeoutSetting"),
                               ("screen_off_timeout", "mScreenOffTimeoutSetting")):
            match = re.search(r"\b" + effective + r"\s*=\s*([^\s]+)", power)
            value = match[1] if match else settings[key]
            emit(key, settings[key] or "UNKNOWN")
            emit(effective, match[1] if match else "absent (using settings)")
            verdict = "UNKNOWN"
            if re.fullmatch(r"-?\d+", value):
                verdict = "PASS" if int(value) <= 0 or int(value) >= 12600000 else "FAIL"
            if key != "screen_off_timeout":
                verdicts.append(verdict)
        emit("screen_off_timeout note", "informational; confirm keep-screen-on and continuity on device")
        disk = self.adb(["shell", "df", "-k", "/data"])
        rows = [line.split() for line in disk.splitlines() if line.strip()]
        require(len(rows) >= 2 and len(rows[-1]) >= 6 and rows[-1][3].isdigit(), "unreadable /data free space")
        emit("data free KiB", rows[-1][3])
        identities = {}
        for package, code in sorted(self.packages().items()):
            identity = self.identity(package, code)
            identities[package] = identity
            for key, value in identity.items():
                emit(package + " " + key, value, key == "versionName")
        if not identities:
            emit("Dremla packages", "none")
        emit("accessibility_enabled", settings["accessibility_enabled"] or "UNKNOWN")
        # Only service components, not arbitrary raw secure-setting text.
        services = settings["enabled_accessibility_services"].split(":")
        emit("enabled_accessibility_services", ", ".join(s for s in services if re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.]+", s)) or "none", True)
        accessibility = self.adb(["shell", "dumpsys", "accessibility"])
        service = re.escape(PACKAGE) + r"/(?:com\.befeast\.sleepmusic)?\.playback\.NightAccessibilityService"
        emit("Dremla accessibility enabled", bool(re.search(service, settings["enabled_accessibility_services"])))
        def service_state(pattern):
            sections = re.findall(pattern + r"\s*[:=]\s*([^\n]*)", accessibility, re.I | re.M)
            return any(re.search(service, section) for section in sections) if sections else "UNKNOWN"
        emit("Dremla accessibility enabled (dumpsys)",
             service_state(r"(?:mEnabledServices|enabledServices|enabled services)"))
        activity = self.adb(["shell", "dumpsys", "activity", "services", PACKAGE])
        emit("Dremla accessibility bound", accessibility_bound(activity, service))
        audio = self.adb(["shell", "dumpsys", "audio"])
        music = re.search(r"(?:^|\n)\s*-?\s*STREAM_MUSIC\s*:(.*?)(?=\n\s*-?\s*STREAM_[A-Z_]+\s*:|\Z)", audio, re.S)
        for label, pattern in (("muted", r"\bMuted:\s*(\w+)"), ("volume index", r"\bCurrent:\s*([^\n]+)"), ("max", r"\bMax:\s*(\d+)")):
            match = re.search(pattern, music[1], re.I) if music else None
            emit("STREAM_MUSIC " + label, match[1] if match else "UNKNOWN")
        hdmi = self.adb(["shell", "dumpsys", "hdmi_control"])
        enabled = re.search(r"(?:mHdmiControlEnabled|hdmi_control_enabled)\s*[:=]\s*(true|false|[01])", hdmi, re.I)
        mode = re.search(r"\bpower_control_mode(?:\s*\(string\))?\s*[:=]\s*([A-Za-z_]+)", hdmi)
        emit("HDMI-CEC enabled", enabled[1] if enabled else settings["hdmi_control_enabled"] or "UNKNOWN")
        emit("HDMI-CEC power_control_mode", mode[1] if mode else "UNKNOWN")
        inputs = self.adb(["shell", "dumpsys", "input"])
        names = re.findall(r'^\s*(?:Name:\s*|Device \d+:\s*)([^\n]+)', inputs, re.M)
        emit("input device names", ", ".join(dict.fromkeys(names)) or "UNKNOWN", True)
        verdict = "FAIL" if "FAIL" in verdicts else "UNKNOWN" if "UNKNOWN" in verdicts else "PASS"
        emit("prerequisite", verdict)
        if verdict != "PASS":
            lines.append("open a new P1 issue; do not change settings")
        return "\n".join(lines) + "\n", verdict, identities


def accessibility_bound(activity, service):
    # Only connection evidence inside this service's active record establishes binding.
    records = list(re.finditer(r"^([ \t]*)\* ServiceRecord\{([^\n]+)\}[ \t]*$", activity, re.M))
    found = []
    for index, record in enumerate(records):
        if not re.search(r"\bu[0-9]+ " + service + r"$", record[2]):
            continue
        end = records[index + 1].start() if index + 1 < len(records) else len(activity)
        body = activity[record.end():end]
        # Stop at a peer/outer section: global connection summaries are not this record.
        body = re.split(r"\n[^\S\n]{0," + str(len(record[1])) + r"}\S", body, maxsplit=1)[0]
        connections = [connection for connection in re.findall(r"\bConnectionRecord\{([^\n}]+)\}", body)
                       # Android 14 appends " flags=0x<hex>" after the binder hash.
                       if re.search(r"\s" + service + r":@[^\s}]+(?: flags=0x[0-9A-Fa-f]+)?$", connection)]
        live_connections = [connection for connection in connections if not re.search(r"\bDEAD\b", connection)]
        if connections and not live_connections:
            found.append(False)
            continue
        apps = re.findall(r"^\s*app=([^\n]+)", body, re.M)
        live_app = len(apps) == 1 and re.fullmatch(r"ProcessRecord\{[^\n}]+\}", apps[0])
        live_binding = False
        bindings = list(re.finditer(r"^([ \t]*)\* IntentBindRec(?:ord)?\{[^\n}]+\}:[ \t]*$", body, re.M))
        for binding in bindings:
            block = body[binding.end():]
            block = re.split(r"\n[^\S\n]{0," + str(len(binding[1])) + r"}\S", block, maxsplit=1)[0]
            binders = re.findall(r"^\s*binder=(\S+)[ \t]*$", block, re.M)
            received = re.findall(r"\breceived=(\w+)\b", block)
            if len(binders) == 1 and binders[0] != "null" and received == ["true"]:
                live_binding = True
        found.append(True if live_app and live_binding and live_connections else "UNKNOWN")
    if len(found) == 1:
        return found[0]
    if not found and re.search(r"^\s*\(nothing\)\s*$", activity, re.M):
        return False
    return "UNKNOWN"


def verify_identity(actual, lock):
    require(actual is not None, "installed package missing")
    for key in ("versionCode", "versionName", "apkSha256", "certificateSha256"):
        require(actual[key] == lock[key], "installed identity mismatch: " + key)


def identity_report(actual):
    return "\n".join(["package: " + PACKAGE] + [key + ": " + str(actual[key]) for key in
                     ("versionCode", "versionName", "apkSha256", "certificateSha256")]) + "\n"


def state_parent():
    path = Path(os.environ.get("XDG_STATE_HOME") or Path.home() / ".local/state") / "dremla/deliveries"
    require(path.is_absolute(), "delivery state directory must be absolute")
    require(not path.is_relative_to(ROOT.resolve()), "receipts must be outside the repository")
    for part in reversed([path, *path.parents]):
        require(not part.is_symlink(), "refusing symlinked delivery state parent")
        if part.exists():
            require(part.is_dir(), "delivery state parent must be a directory")
        else:
            part.mkdir(mode=0o700)
    require(not path.resolve().is_relative_to(ROOT.resolve()), "receipts must be outside the repository")
    return path


def utc():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def atomic_receipt(receipt, name, text):
    fd, temporary = tempfile.mkstemp(prefix=".pending-", dir=receipt)
    try:
        with os.fdopen(fd, "w") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, receipt / name)
    finally:
        Path(temporary).unlink(missing_ok=True)


def install_decision(installed, lock):
    if installed:
        require(installed["certificateSha256"] == lock["certificateSha256"], "installed certificate mismatch; uninstall is an operator decision")
        require(installed["versionCode"] <= lock["versionCode"], "refusing downgrade")
        if installed["versionCode"] == lock["versionCode"]:
            require(installed["apkSha256"] == lock["apkSha256"], "equal versionCode has different APK SHA-256")
            return "already installed"
    return "install"


def safe_identity_report(device, actual):
    if actual is None:
        return "package: absent\n"
    return "\n".join(device.clean(line, line.startswith("versionName:"))
                     for line in identity_report(actual).splitlines()) + "\n"


def deliver(values, approval):
    redaction_token = os.environ.get("DREMLA_RELEASE_TOKEN", "")
    lock = committed_lock()
    release.build_tool("apksigner")
    if "DREMLA_FORGE_URL" not in os.environ and "DREMLA_FORGE_URL" in values:
        os.environ["DREMLA_FORGE_URL"] = values["DREMLA_FORGE_URL"]
    started = utc()
    with tempfile.TemporaryDirectory(prefix="dremla-delivery-") as directory:
        work = Path(directory)
        actual = release.published_identity(release.API(), lock["tag"], output_directory=work)
        require(actual == lock, "downloaded release identity differs from committed lock")
        apk = work / lock["assetName"]
        require(hashlib.sha256(apk.read_bytes()).hexdigest() == lock["apkSha256"], "downloaded APK SHA-256 mismatch")
        device = Device(values, work, redaction_token=redaction_token)
        device.live()
        before, prerequisite, identities = device.probe()
        print(before, end="")
        if prerequisite != "PASS":
            print("warning: prerequisite not established; installation changes no settings")
        parent = state_parent()
        head = run(["git", "rev-parse", "HEAD"])
        refusal = None
        try:
            decision = install_decision(identities.get(PACKAGE), lock)
        except DeviceError as error:
            decision, refusal = "refused", error
        receipt = parent / (utc() + "-" + lock["tag"])
        receipt.mkdir(mode=0o700)
        payload = dict(approvalId=approval, headSha=head, lock=lock, downloadedApkSha256=actual["apkSha256"],
                       installDecision=decision, result="STARTED", startedUtc=started)
        after_read = False
        try:
            atomic_receipt(receipt, "before.txt", before)
            atomic_receipt(receipt, "receipt.json", json.dumps(payload, indent=2) + "\n")
            if refusal:
                raise refusal
            if decision == "install":
                device.approved_apk = apk
                try:
                    device.adb(["install", "-r", str(apk)])
                finally:
                    device.approved_apk = None
            after_identity = device.installed()
            after_read = True
            after = safe_identity_report(device, after_identity)
            atomic_receipt(receipt, "after.txt", after)
            verify_identity(after_identity, lock)
            after += "device identity: PASS\n"
            atomic_receipt(receipt, "after.txt", after)
            payload.update(result="PASS", finishedUtc=utc())
            atomic_receipt(receipt, "receipt.json", json.dumps(payload, indent=2) + "\n")
            print(after, end="")
            print(decision)
        except (DeviceError, release.ReleaseError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            message = str(error) if isinstance(error, (DeviceError, release.ReleaseError)) else "invalid input, response or tool failure"
            message = device.clean(message, True)
            payload.update(result="REFUSED" if refusal else "FAIL", finishedUtc=utc(),
                           diagnostic="device-tools: FAIL: " + message)
            atomic_receipt(receipt, "receipt.json", json.dumps(payload, indent=2) + "\n")
            if not after_read and not refusal:
                try:
                    atomic_receipt(receipt, "after.txt", safe_identity_report(device, device.installed()))
                except (DeviceError, release.ReleaseError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                    pass  # Preserve the original failure if the after-identity is unreadable.
            raise DeviceError(message) from None
        finally:
            print("receipt: " + device.clean(receipt))
    return 0


def main():
    require(len(sys.argv) == 2 and sys.argv[1] in ("probe", "deliver", "verify"), "unknown internal command", 2)
    command = sys.argv[1]
    if command == "deliver":
        mode = os.environ.get("DREMLA_DELIVER_MODE", "install")
        require(mode in ("probe", "install"), "unknown DREMLA_DELIVER_MODE", 2)
        if mode == "probe":
            command = "probe"
        else:
            approval = os.environ.get("DREMLA_DELIVERY_APPROVAL", "")
            require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", approval), "valid DREMLA_DELIVERY_APPROVAL is required", 2)
            values = config()
            private_approval = (values["DREMLA_ADB_SERIAL"] in approval
                                or re.search(IPV4 + r"|[A-Za-z0-9_.-]+:[0-9]+|\[[0-9A-Fa-f:.%]+\]", approval)
                                or (os.environ.get("DREMLA_RELEASE_TOKEN") and os.environ["DREMLA_RELEASE_TOKEN"] in approval))
            require(not private_approval, "approval id must not contain a serial, endpoint or token", 2)
            return deliver(values, approval)
    if command == "verify" and not config_path().exists() and not config_path().is_symlink():
        raise DeviceError("device identity: no delivery config (use DREMLA_OUTCOME_SCOPE=repo for host-only checks)")
    values = config()
    lock = committed_lock() if command == "verify" else None
    with tempfile.TemporaryDirectory(prefix="dremla-device-") as directory:
        device = Device(values, directory)
        device.live()
        if command == "probe":
            report, verdict, _ = device.probe()
            print(report, end="")
            return 0 if verdict == "PASS" else 4
        actual = device.installed()
        verify_identity(actual, lock)
        print(safe_identity_report(device, actual), end="")
        print("device identity: PASS")
    return 0


def cli():
    try:
        return main()
    except DeviceError as error:
        print("device-tools: FAIL: " + str(error), file=sys.stderr)
        return error.code
    except release.ReleaseError as error:
        print("device-tools: FAIL: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("device-tools: FAIL: invalid input, response or tool failure", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(cli())
