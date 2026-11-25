"""Strict authoring contract, including the actual local asset bytes."""
from datetime import datetime
import math
from pathlib import Path
import re
from urllib.parse import urlsplit

from .common import digest, require

ROOT_FIELDS = {"version", "generated", "tracks", "selection"}
TRACK_FIELDS = {"id", "title", "artist", "category", "duration_sec", "file", "bytes", "sha256",
                "preview", "license", "source_url", "loudness", "flags"}
HEX = r"[a-f0-9]{64}"
ID = r"[A-Za-z0-9_-]+"


def valid(condition, message):
    require(condition, "invalid-manifest", message)


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def local_path(root, relative):
    """Only canonical relative paths, with no symlinks in any component."""
    valid(isinstance(relative, str) and bool(relative) and not relative.startswith("/")
          and "\\" not in relative and all(p not in ("", ".", "..") for p in relative.split("/")),
          "Unsafe asset path.")
    root = Path(root).resolve()
    path = root
    for part in relative.split("/"):
        path /= part
        valid(not path.is_symlink(), "Symlink assets are not allowed.")
    valid(root == path.resolve() or root in path.resolve().parents, "Asset is outside the destination.")
    return path


def validate(manifest, root):
    valid(isinstance(manifest, dict) and set(manifest) == ROOT_FIELDS, "Unexpected manifest fields.")
    valid(type(manifest["version"]) is int and manifest["version"] > 0, "Invalid version.")
    generated = manifest["generated"]
    valid(isinstance(generated, str) and matches(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:Z|\+00:00)", generated),
          "Invalid UTC generated time.")
    try:
        datetime.fromisoformat(generated.replace("Z", "+00:00"))
    except ValueError:
        valid(False, "Invalid generated date.")
    valid(isinstance(manifest["tracks"], list), "Tracks must be an array.")
    ids = set()
    for t in manifest["tracks"]:
        valid(isinstance(t, dict) and set(t) == TRACK_FIELDS, "Unexpected track fields.")
        tid = t["id"]
        valid(matches(ID, tid) and tid not in ids, "Invalid or duplicate track ID.")
        ids.add(tid)
        for field in ("title", "artist", "license"):
            valid(isinstance(t[field], str) and bool(t[field].strip()), "Invalid " + field + ".")
        valid(t["category"] in ("ambient", "piano"), "Invalid category.")
        # The app contract and the prototype builder use whole seconds.
        valid(type(t["duration_sec"]) is int and t["duration_sec"] > 0, "Invalid duration.")
        valid(type(t["bytes"]) is int and t["bytes"] > 0, "Invalid asset size.")
        valid(matches(HEX, t["sha256"]), "Invalid SHA-256.")
        loud = t["loudness"]
        valid(isinstance(loud, dict) and set(loud) == {"i_lufs", "lra"}
              and number(loud["i_lufs"]) and number(loud["lra"]) and loud["lra"] >= 0, "Invalid loudness.")
        valid(isinstance(t["flags"], list) and all(isinstance(f, str) and f for f in t["flags"]), "Invalid flags.")
        try:
            u = urlsplit(t["source_url"]) if isinstance(t["source_url"], str) else None
            valid(u is not None and u.scheme == "https" and bool(u.hostname) and not u.username
                  and not u.password and u.port is None, "Invalid HTTPS source URL.")
        except ValueError:
            valid(False, "Invalid HTTPS source URL.")
        for field, folder in (("file", "audio"), ("preview", "previews")):
            pattern = folder + "/" + re.escape(tid) + r"\.([a-f0-9]{64})\.(?:wav|opus|mp3)"
            valid(matches(pattern, t[field]), "Invalid content-addressed asset path.")
            expected = re.fullmatch(pattern, t[field])[1]
            path = local_path(root, t[field])
            valid(path.is_file() and path.stat().st_size > 0, "Referenced asset is missing or empty.")
            valid(digest(path) == expected, "Asset SHA-256 mismatch.")
            if field == "file":
                valid(expected == t["sha256"] and path.stat().st_size == t["bytes"], "Audio identity mismatch.")
    selection = manifest["selection"]
    valid(isinstance(selection, list) and bool(selection) and all(isinstance(i, str) and i in ids for i in selection),
          "Selection must contain known track IDs.")
    valid(len(set(selection)) == len(selection), "Duplicate selection ID.")
    return manifest
