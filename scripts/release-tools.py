#!/usr/bin/env python3
"""Release implementation. Public entrypoints are the no-argument shell scripts.

Only API() reads the optional DREMLA_RELEASE_TOKEN; it stays in process memory,
never in child argv, child environment, files or diagnostic output.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / "delivery/release-lock.json"
NUM = r"(?:0|[1-9][0-9]*)"
IDENT = r"(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER = rf"{NUM}\.{NUM}\.{NUM}(?:-{IDENT}(?:\.{IDENT})*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
HEX = r"[0-9a-f]{64}"
FIELDS = {"tag", "tagSha", "assetName", "apkSha256", "versionCode", "versionName",
          "certificateSha256", "releaseUrlPath"}


class ReleaseError(ValueError):
    """A controlled, credential-free diagnostic."""


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def versions(code, name):
    require(matches(r"[1-9][0-9]{0,9}", code) and int(code) <= 2100000000,
            "versionCode must be a positive Android integer (at most 2100000000)")
    require(matches(SEMVER, name), "versionName must be SemVer")


def inputs():
    sha = os.environ.get("DREMLA_SOURCE_SHA", "")
    code = os.environ.get("DREMLA_VERSION_CODE", "")
    name = os.environ.get("DREMLA_VERSION_NAME", "")
    require(matches(r"[0-9a-fA-F]{40}", sha), "source_sha must be 40 hex digits")
    versions(code, name)
    require(matches(HEX, os.environ.get("RELEASE_SIGNING_CERT_SHA256", "")),
            "RELEASE_SIGNING_CERT_SHA256 must be 64 lowercase hex digits")
    return sha.lower(), code, name


def validate_lock(data):
    require(isinstance(data, dict) and set(data) == FIELDS, "release lock has unexpected fields")
    require(type(data["versionCode"]) is int, "versionCode must be an integer")
    versions(str(data["versionCode"]), data["versionName"])
    require(data["tag"] == "v" + data["versionName"], "tag/version mismatch")
    require(data["assetName"] == "dremla-" + data["versionName"] + ".apk", "asset/version mismatch")
    require(matches(r"[0-9a-f]{40}", data["tagSha"]), "invalid tag SHA")
    for field in ("apkSha256", "certificateSha256"):
        require(matches(HEX, data[field]), "invalid " + field)
    require(matches(r"/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/tag/" +
                    re.escape(data["tag"]), data["releaseUrlPath"]), "invalid release URL path")
    require(all(p not in (".", "..") for p in data["releaseUrlPath"].split("/")[1:3]),
            "invalid repository path")


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)


class ForgeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        old = urllib.parse.urlsplit(req.full_url)
        new = urllib.parse.urlsplit(newurl)
        # Attachment routes may redirect within the forge. Never forward a token
        # to another origin, downgrade HTTPS, or turn a release mutation into GET.
        if (req.get_method() != "GET" or new.scheme != "https" or
                new.netloc != old.netloc or new.username or new.password):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class API:
    def __init__(self):
        self.token = os.environ.pop("DREMLA_RELEASE_TOKEN", "")
        require(not re.search(r"[\r\n]", self.token), "invalid API credential")
        self.origin = os.environ.get("DREMLA_FORGE_URL", "").rstrip("/")
        u = urllib.parse.urlsplit(self.origin)
        require(u.scheme == "https" and u.netloc and not u.username and not u.password
                and u.path == "" and not u.query and not u.fragment, "forge URL must be an HTTPS origin")
        self.repo = os.environ.get("DREMLA_REPOSITORY", "BeFeast/dremla")
        require(matches(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", self.repo)
                and all(p not in (".", "..") for p in self.repo.split("/")), "invalid repository")
        self.base = "/api/v1/repos/" + self.repo
        self.opener = urllib.request.build_opener(ForgeRedirect())

    def request(self, path, method="GET", data=None, content_type="application/json", missing=False):
        require(path.startswith("/") and not path.startswith("//"), "invalid API path")
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "token " + self.token
        if isinstance(data, dict):
            data = json.dumps(data).encode()
        if data is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.origin + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            error.close()
            if missing and error.code == 404:
                return None
            # No response body, URL, header or exception repr in diagnostics.
            raise ReleaseError("Forgejo request failed: HTTP " + str(error.code)) from None
        except (OSError, urllib.error.URLError):
            raise ReleaseError("Forgejo request failed: transport error") from None

    def json(self, suffix, **kwargs):
        raw = self.request(self.base + suffix, **kwargs)
        return None if raw is None else json.loads(raw)


def tag_path(tag):
    return "/tags/" + urllib.parse.quote(tag, safe="")


def preflight(api, sha, name):
    require(api.json(tag_path("v" + name), missing=True) is None, "tag already exists; refusing overwrite")
    subprocess.run(["git", "merge-base", "--is-ancestor", sha, "refs/remotes/origin/main"],
                   cwd=ROOT, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(head == sha, "HEAD is not source_sha")


def publish():
    sha, code, name = inputs()
    api = API()
    require(bool(api.token), "automatic workflow token is missing")
    preflight(api, sha, name)  # Recheck immediately before creating anything.
    tag = "v" + name
    asset = "dremla-" + name + ".apk"
    apk = ROOT / "release-out" / asset
    require(apk.is_file(), "signed APK is missing")
    # Reinspect before release creation, including version and pinned certificate.
    metadata = inspect(apk)
    require(metadata == (int(code), name, os.environ["RELEASE_SIGNING_CERT_SHA256"]),
            "signed APK identity mismatch")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    sums = f"{digest}  {asset}\n".encode()
    (apk.parent / "SHA256SUMS").write_bytes(sums)
    api.json("/tags", method="POST", data={"tag_name": tag, "target": sha})
    # A failure after this point leaves at most a draft and a reserved tag, never a
    # public release missing assets. Do not auto-delete a tag another run may use.
    release = api.json("/releases", method="POST", data={
        "tag_name": tag, "target_commitish": sha, "name": tag, "draft": True,
        "prerelease": "-" in name.split("+")[0]})
    require(release.get("draft") is True, "Forgejo did not create a draft")
    rid = release["id"]
    require(type(rid) is int and rid > 0, "invalid release ID")
    for filename, payload in ((asset, apk.read_bytes()), ("SHA256SUMS", sums)):
        boundary = "dremla-" + os.urandom(24).hex()
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"attachment\"; "
                f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
        body += payload + f"\r\n--{boundary}--\r\n".encode()
        uploaded = api.json(f"/releases/{rid}/assets?name=" + urllib.parse.quote(filename, safe=""),
                            method="POST", data=body, content_type="multipart/form-data; boundary=" + boundary)
        require(uploaded.get("name") == filename and uploaded.get("size") == len(payload),
                "uploaded asset identity/size mismatch; release remains draft")
    assets = api.json(f"/releases/{rid}/assets")
    require(sorted(a["name"] for a in assets) == sorted([asset, "SHA256SUMS"]),
            "release assets incomplete; release remains draft")
    result = api.json(f"/releases/{rid}", method="PATCH", data={"draft": False})
    require(result.get("draft") is False, "release publication was not confirmed")
    print("release published: /" + api.repo + "/releases/tag/" + tag)


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME", "")
    tool = Path(sdk) / "build-tools/34.0.0" / name
    require(sdk and tool.is_file() and os.access(tool, os.X_OK),
            "build-tools 34.0.0 requires executable " + name + "; configure ANDROID_HOME")
    return tool


def inspect(apk):
    signer = build_tool("apksigner")
    aapt = build_tool("aapt2")
    env = dict(os.environ)
    env.pop("DREMLA_RELEASE_TOKEN", None)
    env["DREMLA_APK"] = str(apk)
    env["DREMLA_APK_REQUIRE_SIGNED"] = "1"
    subprocess.run([str(ROOT / "scripts/inspect-apk.sh")], env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    certs = subprocess.check_output([str(signer), "verify", "--print-certs", "-Werr", str(apk)],
                                    env=env, text=True, stderr=subprocess.DEVNULL)
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certs, re.M)
    require(len(digests) == 1, "APK must have exactly one signing certificate")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)],
                                      env=env, text=True, stderr=subprocess.DEVNULL)
    match = re.search(r"^package: name='com\.befeast\.sleepmusic' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    require(match is not None and not re.search(r"^native-code:", badging, re.M), "invalid APK badging")
    versions(match[1], match[2])
    return int(match[1]), match[2], digests[0].lower()


def published_identity(api, tag, output_directory=None):
    """Read-only verification of published bytes; injectable API keeps tests offline."""
    require(matches("v" + SEMVER, tag), "release tag must be v<SemVer>")
    release = api.json("/releases/tags/" + urllib.parse.quote(tag, safe=""))
    require(release.get("draft") is False and release.get("tag_name") == tag, "release is not published")
    tag_info = api.json(tag_path(tag))
    sha = tag_info["commit"]["sha"]
    asset = "dremla-" + tag[1:] + ".apk"
    require(sorted(a["name"] for a in release["assets"]) == sorted([asset, "SHA256SUMS"]),
            "expected exactly APK and SHA256SUMS assets")
    with tempfile.TemporaryDirectory(prefix="dremla-release-") as directory:
        work = Path(directory)
        for entry in release["assets"]:
            url = urllib.parse.urlsplit(entry["browser_download_url"])
            require(url.scheme + "://" + url.netloc == api.origin and not url.query and not url.fragment,
                    "asset download must stay on the forge origin")
            (work / entry["name"]).write_bytes(api.request(url.path))
        digest = hashlib.sha256((work / asset).read_bytes()).hexdigest()
        require((work / "SHA256SUMS").read_bytes() == f"{digest}  {asset}\n".encode(),
                "SHA256SUMS does not match the downloaded APK")
        code, name, cert = inspect(work / asset)
        require(name == tag[1:], "APK version/tag mismatch")
        data = dict(tag=tag, tagSha=sha, assetName=asset, apkSha256=digest,
                    versionCode=code, versionName=name, certificateSha256=cert,
                    releaseUrlPath="/" + api.repo + "/releases/tag/" + tag)
        validate_lock(data)
        if output_directory is not None:
            shutil.copyfile(work / asset, Path(output_directory) / asset)
        return data


def generate_lock():
    expected = os.environ.get("RELEASE_SIGNING_CERT_SHA256", "")
    require(matches(HEX, expected), "expected certificate SHA-256 is required")
    data = published_identity(API(), os.environ.get("DREMLA_RELEASE_TAG", ""))
    require(data["certificateSha256"] == expected, "APK certificate mismatch")
    # Atomic replacement; any verification failure preserves an existing lock.
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=LOCK.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            json.dump(data, output, indent=2)
            output.write("\n")
            output.flush()
            temporary.replace(LOCK)
        finally:
            temporary.unlink(missing_ok=True)
    print("release-lock: PASS (published asset verified; delivery/release-lock.json written)")


def check_lock():
    if not LOCK.exists():
        require(not LOCK.is_symlink(), "release lock is a broken symlink")
        print("release-lock: PASS (no release lock yet)")
        return
    require(not LOCK.is_symlink() and LOCK.is_file(), "release lock must be a regular file")
    data = read_json(LOCK)
    validate_lock(data)
    actual = published_identity(API(), data["tag"])
    for field in sorted(FIELDS):
        require(data[field] == actual[field], "published release mismatch: " + field)
    print("release-lock: PASS (published identity verified)")


def main():
    require(len(sys.argv) == 2, "internal command required")
    command = sys.argv[1]
    if command == "check":
        check_lock()
    elif command == "lock":
        generate_lock()
    elif command == "inputs":
        inputs()
    elif command == "preflight":
        sha, _, name = inputs()
        preflight(API(), sha, name)
    elif command == "publish":
        publish()
    else:
        raise ReleaseError("unknown release command")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print("release-tools: FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError):
        # Third-party exceptions may quote response bodies or credentials.
        print("release-tools: FAIL: missing input, invalid response or tool failure", file=sys.stderr)
        sys.exit(1)
