"""Filesystem storage: immutable assets and records, current manifest committed last."""
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import os
import threading

from .common import ServiceError, atomic_write, digest, json_bytes, parse_json, read_json, require, sync_directory
from .validate import local_path, validate


def now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def revision(snapshot):
    """Hash proposal content, independent of object key order and publication time/version."""
    content = {k: v for k, v in snapshot.items() if k not in ("version", "generated")}
    return hashlib.sha256(json.dumps(content, sort_keys=True, ensure_ascii=False,
                                    allow_nan=False, separators=(",", ":")).encode()).hexdigest()


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
        self.pending()

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

    @staticmethod
    def entry(manifest, encoded, rollback_of=None):
        return dict(version=manifest["version"], generated=manifest["generated"],
                    sha256=hashlib.sha256(encoded).hexdigest(),
                    counts={"tracks": len(manifest["tracks"]), "selection": len(manifest["selection"])},
                    parent=manifest["version"] - 1, rollbackOf=rollback_of)

    def pending(self):
        """Validate the next immutable record, including its published asset bytes."""
        with self.lock:
            version = self.current()["version"] + 1
            try:
                record = local_path(self.root, f"snapshots/v{version}/manifest.json")
                if not record.exists():
                    return None
                raw = record.read_bytes()
                manifest = validate(parse_json(raw), self.root)
                require(manifest["version"] == version, "invalid-base", "Invalid pending publication.")
                index = local_path(self.root, "snapshots/index.json")
                entries = read_json(index) if index.exists() else []
                require(isinstance(entries, list), "invalid-base", "Invalid pending publication.")
                pending_entries = [e for e in entries if isinstance(e, dict) and e.get("version") == version]
                rollback_of = None
                if pending_entries:
                    entry = pending_entries[0]
                    rollback_of = entry.get("rollbackOf")
                    require(len(pending_entries) == 1 and type(entry.get("version")) is int
                            and type(entry.get("parent")) is int
                            and (rollback_of is None or type(rollback_of) is int and 1 <= rollback_of < version)
                            and entry == self.entry(manifest, raw, rollback_of),
                            "invalid-base", "Invalid pending publication.")
                return manifest, raw, rollback_of
            except (OSError, ValueError, KeyError, TypeError):
                raise ServiceError("invalid-base", "Invalid pending publication or published assets.") from None

    def require_no_pending(self):
        require(self.pending() is None, "pending-publication",
                "An interrupted publication must be reviewed and made current before publishing newer changes.", 409)

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

    def publish(self, snapshot, assets, parent_version, confirm, proposal_revision, rollback_of=None):
        with self.lock:
            self.require_no_pending()
            current = self.current()
            require(type(parent_version) is int and parent_version == current["version"],
                    "stale-parent", "The collection version changed; refresh the review.", 409)
            require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
            require(isinstance(proposal_revision, str) and len(proposal_revision) == 64,
                    "invalid-request", "The reviewed proposal revision is required.")
            require(proposal_revision == revision(snapshot), "stale-proposal",
                    "The proposal changed after you reviewed it. Refresh and review it again.", 409)
            manifest = dict(deepcopy(snapshot), version=current["version"] + 1, generated=now())
            record = local_path(self.root, f"snapshots/v{manifest['version']}/manifest.json")
            for relative, source in assets.items():
                self.put_asset(source, relative)
            self.failure_hook("after-assets")
            validate(manifest, self.root)
            encoded = json_bytes(manifest)
            history = self.history()
            # The manifest format has no rollback field. Persist rollback intent in the
            # existing index before the record, so even an after-snapshot interruption
            # retains it. Uncommitted entries are hidden by history().
            if rollback_of is not None:
                atomic_write(local_path(self.root, "snapshots/index.json"),
                             json_bytes(history + [self.entry(manifest, encoded, rollback_of)]))
            else:
                index = local_path(self.root, "snapshots/index.json")
                if index.exists() and read_json(index) != history:
                    # A rollback may have stopped after recording intent but before
                    # creating a snapshot. It reserves no immutable version.
                    atomic_write(index, json_bytes(history))
            atomic_write(record, encoded)
            self.failure_hook("after-snapshot")
            return self.commit(manifest, encoded, history, rollback_of)

    def complete_pending(self, sha256, confirm):
        with self.lock:
            pending = self.pending()
            require(pending is not None, "not-found", "No interrupted publication to complete.", 404)
            manifest, raw, rollback_of = pending
            require(sha256 == hashlib.sha256(raw).hexdigest(), "stale-pending",
                    "The interrupted publication changed; refresh and review it again.", 409)
            require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
            return self.commit(manifest, raw, self.history(), rollback_of)

    def commit(self, manifest, encoded, history, rollback_of=None):
        """History, then current LAST, preserving the immutable record's exact bytes."""
        atomic_write(local_path(self.root, "snapshots/index.json"),
                     json_bytes(history + [self.entry(manifest, encoded, rollback_of)]))
        self.failure_hook("after-index")
        atomic_write(local_path(self.root, "manifest.json"), encoded)
        return manifest

    def rollback(self, version, parent_version, confirm):
        with self.lock:
            self.require_no_pending()
            require(type(version) is int and any(e["version"] == version for e in self.history()),
                    "not-found", "Snapshot not found.", 404)
            snapshot = validate(read_json(local_path(self.root, f"snapshots/v{version}/manifest.json")), self.root)
            return self.publish(snapshot, {}, parent_version, confirm, revision(snapshot), rollback_of=version)
