"""Filesystem storage: immutable assets and records, current manifest committed last."""
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import shutil
import tempfile
import os
import threading

from .common import atomic_write, digest, json_bytes, read_json, require, sync_directory
from .validate import local_path, validate


def now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class FilesystemStorage:
    def __init__(self, destination, failure_hook=lambda stage: None):
        self.root = Path(destination).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.failure_hook = failure_hook
        current = self.current()
        if current["version"]:
            record = local_path(self.root, f"snapshots/v{current['version']}/manifest.json")
            require(record.is_file() and record.read_bytes() == (self.root / "manifest.json").read_bytes(),
                    "invalid-base", "Destination is not a service-written collection.")
            self.history()

    def current(self):
        with self.lock:
            path = local_path(self.root, "manifest.json")
            if not path.exists():
                return {"version": 0, "generated": "", "tracks": [], "selection": []}
            return validate(read_json(path), self.root)

    def history(self):
        with self.lock:
            version = self.current()["version"]
            path = local_path(self.root, "snapshots/index.json")
            entries = read_json(path) if path.exists() else []
            require(isinstance(entries, list), "invalid-base", "Invalid snapshot history.")
            committed = [e for e in entries if type(e) is dict and type(e.get("version")) is int and e["version"] <= version]
            require([e["version"] for e in committed] == list(range(1, version + 1)),
                    "invalid-base", "Incomplete snapshot history.")
            for e in committed:
                path = local_path(self.root, f"snapshots/v{e['version']}/manifest.json")
                require(path.is_file() and digest(path) == e.get("sha256"), "invalid-base", "Invalid snapshot record.")
                snapshot = read_json(path)
                require(set(e) == {"version", "generated", "sha256", "counts", "parent", "rollbackOf"}
                        and snapshot.get("version") == e["version"] and snapshot.get("generated") == e.get("generated")
                        and e.get("counts") == {"tracks": len(snapshot["tracks"]), "selection": len(snapshot["selection"])}
                        and type(e.get("parent")) is int and e["parent"] == e["version"] - 1
                        and (e.get("rollbackOf") is None or type(e["rollbackOf"]) is int
                             and 1 <= e["rollbackOf"] < e["version"]), "invalid-base", "Invalid snapshot history fields.")
            return committed

    def put_asset(self, source, relative):
        destination = local_path(self.root, relative)
        expected = Path(relative).name.split(".")[-2]
        require(digest(source) == expected, "asset-conflict", "Source asset hash mismatch.", 409)
        if destination.exists():
            require(digest(destination) == expected, "asset-conflict", "Existing asset hash mismatch.", 409)
            return
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
            temporary = Path(out.name)
            try:
                with Path(source).open("rb") as inp:
                    shutil.copyfileobj(inp, out)
                out.flush()
                os.fsync(out.fileno())
                require(digest(temporary) == expected, "asset-conflict", "Copied asset hash mismatch.", 409)
                os.replace(temporary, destination)
                sync_directory(destination.parent)
            finally:
                temporary.unlink(missing_ok=True)

    def publish(self, snapshot, assets, parent_version, confirm, rollback_of=None):
        with self.lock:
            current = self.current()
            require(type(parent_version) is int and parent_version == current["version"],
                    "stale-parent", "The collection version changed; refresh the review.", 409)
            require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
            manifest = dict(deepcopy(snapshot), version=current["version"] + 1, generated=now())
            # Reuse a stranded immutable record only for the exact same proposed content.
            record = local_path(self.root, f"snapshots/v{manifest['version']}/manifest.json")
            if record.exists():
                pending = read_json(record)
                require(dict(pending, generated=manifest["generated"]) == manifest,
                        "snapshot-conflict", "Retry the interrupted publication with its original content.", 409)
                manifest = pending
            for relative, source in assets.items():
                self.put_asset(source, relative)
            self.failure_hook("after-assets")
            validate(manifest, self.root)
            encoded = json_bytes(manifest)
            if not record.exists():
                atomic_write(record, encoded)
            self.failure_hook("after-snapshot")
            entry = dict(version=manifest["version"], generated=manifest["generated"],
                         sha256=hashlib.sha256(encoded).hexdigest(),
                         counts={"tracks": len(manifest["tracks"]), "selection": len(manifest["selection"])},
                         parent=current["version"], rollbackOf=rollback_of)
            atomic_write(local_path(self.root, "snapshots/index.json"), json_bytes(self.history() + [entry]))
            self.failure_hook("after-index")
            atomic_write(local_path(self.root, "manifest.json"), encoded)
            return manifest

    def rollback(self, version, parent_version, confirm):
        with self.lock:
            require(type(version) is int and any(e["version"] == version for e in self.history()),
                    "not-found", "Snapshot not found.", 404)
            snapshot = validate(read_json(local_path(self.root, f"snapshots/v{version}/manifest.json")), self.root)
            return self.publish(snapshot, {}, parent_version, confirm, rollback_of=version)
