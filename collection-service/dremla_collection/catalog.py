"""Reference-only catalog drafts and immutable dremla.catalog/1 publications.

No acquisition adapter is used: metadata comes from local fixtures or manual input.
The owner holds exclusive directory locks; every operation uses its process lock.
"""
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import re

from .common import ServiceError, atomic_write, digest, json_bytes, read_json, require, require_utf8
from .fixtures import CATALOGUE, PLAYLISTS
from .urls import parse_url

VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")
PLAYLIST_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
DRAFT_KEYS = {"tracks", "playlists", "excluded", "active"}
CATALOG_KEYS = DRAFT_KEYS | {"schema", "version", "generated"}
TRACK_KEYS = {"id", "source", "title", "artist", "category", "durationSec"}
METADATA = {"title", "artist", "category", "durationSec", "gainDb"}


def need(condition, message):
    require(condition, "invalid-catalog", message)


def validate_track(t):
    need(isinstance(t, dict) and TRACK_KEYS <= set(t) <= TRACK_KEYS | {"gainDb"},
         "Unexpected track fields.")
    s = t["source"]
    need(isinstance(s, dict) and set(s) == {"kind", "videoId", "url"} and s["kind"] == "youtube", "Invalid source.")
    need(isinstance(s["videoId"], str) and VIDEO_ID.fullmatch(s["videoId"]), "Invalid videoId.")
    need(t["id"] == "yt-" + s["videoId"], "ID must be yt-<videoId>.")
    need(s["url"] == "https://www.youtube.com/watch?v=" + s["videoId"], "URL must be canonical.")
    for key in ("title", "artist"):
        need(isinstance(t[key], str) and t[key].strip(), "Required " + key + ".")
    need(t["category"] in ("ambient", "piano"), "Category must be ambient or piano.")
    need(type(t["durationSec"]) is int and t["durationSec"] > 0, "Duration must be positive whole seconds.")
    if "gainDb" in t:
        need(type(t["gainDb"]) in (int, float) and -40 <= t["gainDb"] <= 0,
             "gainDb must be in -40..0 (attenuate only).")


def selection(catalog):
    active = next((p for p in catalog["playlists"] if p["id"] == catalog["active"]), None)
    excluded = set(catalog["excluded"])
    return [t for t in active["trackIds"] if t not in excluded] if active else []


def track_ids(value, known, label):
    need(isinstance(value, list) and all(isinstance(t, str) for t in value), label + " must be a list of track IDs.")
    need(len(value) == len(set(value)), "Duplicate track in " + label + ".")
    need(all(t in known for t in value), "Unknown track in " + label + ".")


def validate_draft(c):
    need(isinstance(c, dict) and set(c) == DRAFT_KEYS, "Unexpected draft fields.")
    require_utf8(c)
    need(isinstance(c["tracks"], list), "Tracks must be a list.")
    ids = set()
    for t in c["tracks"]:
        validate_track(t)
        need(t["id"] not in ids, "Duplicate track.")
        ids.add(t["id"])
    need(isinstance(c["playlists"], list), "Playlists must be a list.")
    playlist_ids = set()
    for p in c["playlists"]:
        need(isinstance(p, dict) and set(p) == {"id", "title", "trackIds"}, "Unexpected playlist fields.")
        need(isinstance(p["id"], str) and PLAYLIST_ID.fullmatch(p["id"]) and p["id"] not in playlist_ids,
             "Invalid or duplicate playlist ID.")
        playlist_ids.add(p["id"])
        need(isinstance(p["title"], str) and p["title"].strip(), "Required playlist title.")
        track_ids(p["trackIds"], ids, "playlist")
    track_ids(c["excluded"], ids, "excluded")
    need(c["active"] is None or isinstance(c["active"], str) and c["active"] in playlist_ids,
         "Unknown active playlist.")
    return c


def validate_catalog(c):
    """Runtime copy of the contract rules; fixture agreement is tested in test_catalog."""
    need(isinstance(c, dict) and set(c) == CATALOG_KEYS, "Unexpected catalog fields.")
    need(c["schema"] == "dremla.catalog/1", "Invalid schema.")
    need(type(c["version"]) is int and c["version"] >= 1, "Invalid version.")
    need(isinstance(c["generated"], str) and c["generated"].endswith("Z"), "Invalid generated time.")
    validate_draft({k: c[k] for k in DRAFT_KEYS})
    need(c["active"] is not None, "Choose an active playlist.")
    need(selection(c), "Empty selection: include at least one non-excluded track in the active playlist.")
    return c


def fixture_track(video):
    item = CATALOGUE[video]
    return dict(id="yt-" + video, source=dict(kind="youtube", videoId=video,
                url="https://www.youtube.com/watch?v=" + video), title=item["title"],
                artist=item["artist"], category=item["category"], durationSec=item["duration_sec"])


def revision(draft):
    """Content hash of a draft: a publish confirms exactly the draft the operator reviewed."""
    return hashlib.sha256(json_bytes(draft)).hexdigest()


def fields(body, allowed, required=()):
    require(isinstance(body, dict) and set(required) <= set(body) <= set(allowed),
            "invalid-request", "Missing or unexpected request fields.")
    require_utf8(body)


class Catalog:
    def __init__(self, data, destination, lock, failure_hook=lambda stage: None, delivery=None):
        self.data, self.root, self.lock = Path(data), Path(destination), lock
        self.failure_hook = failure_hook
        self.delivery = delivery  # optional CatalogDelivery; None keeps publishing local-only
        self.draft_path = self.path(self.data, "catalog-draft.json")
        self.draft = validate_draft(read_json(self.draft_path)) if self.draft_path.exists() else dict(
            tracks=[], playlists=[], excluded=[], active=None)
        # Reject an invalid existing destination before serving requests.
        self.history()
        self.pending()

    @staticmethod
    def path(root, name):
        path = root / name
        require(not path.is_symlink(), "invalid-base", "Catalog files must not be symlinks.")
        return path

    def document(self, version):
        return dict(deepcopy(self.draft), schema="dremla.catalog/1", version=version,
                    generated=datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"))

    def current(self):
        with self.lock:
            path = self.path(self.root, "current.json")
            if not path.exists():
                return None
            current = validate_catalog(read_json(path))
            record = self.path(self.root, f"v{current['version']}.json")
            require(record.is_file() and record.read_bytes() == path.read_bytes(),
                    "invalid-base", "Current catalog must match its immutable version.")
            return current

    @staticmethod
    def counts(c):
        return dict(tracks=len(c["tracks"]), playlists=len(c["playlists"]), selection=len(selection(c)))

    def history(self):
        with self.lock:
            current = self.current()
            version = current["version"] if current else 0
            path = self.path(self.root, "index.json")
            entries = read_json(path) if path.exists() else []
            require(isinstance(entries, list), "invalid-base", "Invalid catalog history.")
            committed = [e for e in entries if isinstance(e, dict) and type(e.get("version")) is int and e["version"] <= version]
            require([e["version"] for e in committed] == list(range(1, version + 1)),
                    "invalid-base", "Incomplete catalog history.")
            for e in committed:
                record = self.path(self.root, f"v{e['version']}.json")
                require(record.is_file() and digest(record) == e.get("sha256"), "invalid-base", "Invalid catalog record.")
                c = validate_catalog(read_json(record))
                require(set(e) == {"version", "generated", "sha256", "counts", "parent"}
                        and c["version"] == e["version"] and c["generated"] == e["generated"]
                        and e["counts"] == self.counts(c) and type(e["parent"]) is int and e["parent"] == e["version"] - 1,
                        "invalid-base", "Invalid catalog history fields.")
            return committed

    def pending(self):
        """The next version's immutable record, left by an interrupted publish and not yet current."""
        with self.lock:
            current = self.current()
            version = (current["version"] if current else 0) + 1
            record = self.path(self.root, f"v{version}.json")
            if not record.exists():
                return None
            raw = record.read_bytes()
            try:
                document = validate_catalog(read_json(record))
            except (ValueError, KeyError, TypeError):
                document = None
            require(document is not None and document["version"] == version,
                    "invalid-base", "Invalid pending catalog record.")
            return document, raw

    def view(self):
        with self.lock:
            current = self.current()
            version = current["version"] if current else 0
            errors = []
            try:
                validate_catalog(self.document(version + 1))
            except ServiceError as error:
                errors.append(error.message)
            pending = self.pending()
            if pending:
                document, raw = pending
                titles = {t["id"]: t["title"] for t in document["tracks"]}
                pending = dict(version=document["version"], generated=document["generated"],
                               sha256=hashlib.sha256(raw).hexdigest(), counts=self.counts(document),
                               selection=[dict(id=t, title=titles[t]) for t in selection(document)],
                               matchesDraft={k: document[k] for k in DRAFT_KEYS} == self.draft, document=document)
                errors.insert(0, f"Version {version + 1} was saved but never became current. "
                                 "Review it and make it current before publishing newer changes.")
            return dict(draft=deepcopy(self.draft), draftRevision=revision(self.draft), currentVersion=version,
                        selection=selection(self.draft), validation=dict(valid=not errors, errors=errors),
                        pending=pending, fixtures=[fixture_track(video) for video in CATALOGUE],
                        delivery=self.delivery.status() if self.delivery else dict(configured=False))

    def save(self, candidate):
        validate_draft(candidate)
        try:
            atomic_write(self.path(self.data, "catalog-draft.json"), json_bytes(candidate))
        except OSError:
            raise ServiceError("storage-failed", "Catalog draft could not be saved.") from None
        self.draft = candidate
        return self.view()

    def add_track(self, body):
        fields(body, METADATA | {"url"}, {"url"})
        source = parse_url(body["url"])
        if source.kind == "playlist":
            require(source.source_id in PLAYLISTS, "unsupported-url", "Only fixture playlists can be imported.")
            require(set(body) == {"url"}, "invalid-request", "Fixture playlists use their own metadata.")
            tracks = [fixture_track(video) for video in PLAYLISTS[source.source_id]]
        else:
            if source.source_id in CATALOGUE:
                track = fixture_track(source.source_id)
            else:
                require(METADATA - {"gainDb"} <= set(body), "metadata-required",
                        "Supply title, artist, durationSec and category for a non-fixture track.")
                track = dict(id="yt-" + source.source_id, source=dict(kind="youtube", videoId=source.source_id, url=source.canonical))
            track.update({k: v for k, v in body.items() if k in METADATA})
            tracks = [track]
        with self.lock:
            existing = {t["id"] for t in self.draft["tracks"]}
            require(not any(t["id"] in existing for t in tracks), "track-exists", "Track already exists in the catalog.", 409)
            candidate = deepcopy(self.draft)
            candidate["tracks"].extend(tracks)
            return self.save(candidate)

    def edit_track(self, identity, body):
        fields(body, METADATA)
        with self.lock:
            candidate = deepcopy(self.draft)
            track = self.find(candidate["tracks"], identity)
            track.update(body)
            # JSON null clears the optional manual gain; it is never stored as null.
            if track.get("gainDb", 0) is None:
                del track["gainDb"]
            return self.save(candidate)

    @staticmethod
    def find(items, identity):
        item = next((t for t in items if t["id"] == identity), None)
        require(item is not None, "not-found", "Catalog item not found.", 404)
        return item

    def remove_track(self, identity):
        with self.lock:
            candidate = deepcopy(self.draft)
            candidate["tracks"].remove(self.find(candidate["tracks"], identity))
            for playlist in candidate["playlists"]:
                playlist["trackIds"] = [t for t in playlist["trackIds"] if t != identity]
            candidate["excluded"] = [t for t in candidate["excluded"] if t != identity]
            return self.save(candidate)

    def add_playlist(self, body):
        fields(body, {"id", "title", "trackIds"}, {"id", "title"})
        with self.lock:
            require(not any(p["id"] == body["id"] for p in self.draft["playlists"]),
                    "playlist-exists", "Playlist already exists.", 409)
            candidate = deepcopy(self.draft)
            candidate["playlists"].append({"trackIds": [], **deepcopy(body)})
            return self.save(candidate)

    def edit_playlist(self, identity, body):
        fields(body, {"title", "trackIds"})
        with self.lock:
            candidate = deepcopy(self.draft)
            self.find(candidate["playlists"], identity).update(deepcopy(body))
            return self.save(candidate)

    def remove_playlist(self, identity):
        with self.lock:
            candidate = deepcopy(self.draft)
            candidate["playlists"].remove(self.find(candidate["playlists"], identity))
            if candidate["active"] == identity:
                candidate["active"] = None
            return self.save(candidate)

    def set_field(self, name, value):
        require(name in ("excluded", "active"), "invalid-request", "Unknown catalog field.")
        with self.lock:
            candidate = deepcopy(self.draft)
            candidate[name] = deepcopy(value)
            return self.save(candidate)

    def publish(self, parent_version, confirm, draft_revision):
        with self.lock:
            current = self.current()
            version = current["version"] if current else 0
            require(type(parent_version) is int and parent_version == version,
                    "stale-parent", "The catalog version changed; refresh before publishing.", 409)
            require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
            require(isinstance(draft_revision, str), "invalid-request", "Publishing needs the reviewed draftRevision.")
            require(draft_revision == revision(self.draft), "stale-draft",
                    "The draft changed after you reviewed it. Review the refreshed selection and publish again.", 409)
            # An interrupted record is never rewritten or reused implicitly; see complete_pending.
            require(self.pending() is None, "pending-publication",
                    f"Version {version + 1} was saved but never became current. "
                    "Review it and make it current before publishing newer changes.", 409)
            document = validate_catalog(self.document(version + 1))
            encoded = json_bytes(document)
            history = self.history()
            atomic_write(self.path(self.root, f"v{version + 1}.json"), encoded)
            self.failure_hook("after-snapshot")
            return self.commit(document, encoded, history)

    def complete_pending(self, sha256, confirm):
        """Make the reviewed interrupted record current, byte for byte, without reading the draft."""
        with self.lock:
            pending = self.pending()
            require(pending is not None, "not-found", "No interrupted publication to complete.", 404)
            document, raw = pending
            require(sha256 == hashlib.sha256(raw).hexdigest(), "stale-pending",
                    "The interrupted publication changed; refresh and review it again.", 409)
            require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
            return self.commit(document, raw, self.history())

    def commit(self, document, encoded, history):
        """History, then current LAST; the immutable record already exists."""
        entry = dict(version=document["version"], generated=document["generated"],
                     sha256=hashlib.sha256(encoded).hexdigest(), counts=self.counts(document),
                     parent=document["version"] - 1)
        atomic_write(self.path(self.root, "index.json"), json_bytes(history + [entry]))
        self.failure_hook("after-index")
        atomic_write(self.path(self.root, "current.json"), encoded)
        return document

    def current_record(self):
        """(version, exact bytes) of the current published catalog, or None."""
        with self.lock:
            current = self.current()
            if current is None:
                return None
            return current["version"], self.path(self.root, "current.json").read_bytes()

    def deliver(self, version):
        # Runs after the publication committed and outside the service lock: network time never
        # blocks other requests, and a failed delivery never undoes the local publication.
        # None: `version` is no longer current and nothing was sent for it.
        if self.delivery:
            return self.delivery.deliver(self.current_record, version)

    def retry_delivery(self, version, confirm):
        require(self.delivery is not None, "not-found", "Catalog delivery is not configured.", 404)
        require(confirm is True, "confirmation-required", "Explicit confirmation is required.")
        require(self.deliver(version) is not None, "stale-delivery",
                "Only the current published version can be delivered; refresh first.", 409)
        return self.view()

    def exported(self, name):
        with self.lock:
            if name == "current.json":
                current = self.current()
                require(current is not None, "not-found", "No catalog has been published.", 404)
                return current
            match = re.fullmatch(r"v([1-9][0-9]*)\.json", name)
            require(match is not None, "not-found", "Catalog version not found.", 404)
            version = int(match[1])
            require(any(e["version"] == version for e in self.history()), "not-found", "Catalog version not found.", 404)
            return validate_catalog(read_json(self.path(self.root, name)))

    def dispatch(self, method, parts, body):
        """Catalog routes share the HTTP handler's Host/CSRF/JSON guards."""
        if method == "GET" and not parts:
            return self.view()
        if method == "GET" and parts == ["versions"]:
            return self.history()
        if method == "POST" and parts == ["publish"]:
            fields(body, {"parentVersion", "confirm", "draftRevision"})
            document = self.publish(body.get("parentVersion"), body.get("confirm"), body.get("draftRevision"))
            self.deliver(document["version"])
            return document
        if method == "POST" and parts == ["pending", "complete"]:
            fields(body, {"sha256", "confirm"})
            document = self.complete_pending(body.get("sha256"), body.get("confirm"))
            self.deliver(document["version"])
            return document
        if method == "POST" and parts == ["delivery", "retry"]:
            fields(body, {"version", "confirm"}, {"version", "confirm"})
            return self.retry_delivery(body.get("version"), body.get("confirm"))
        if method == "PUT" and parts in (["excluded"], ["active"]):
            fields(body, {parts[0]}, {parts[0]})
            return self.set_field(parts[0], body[parts[0]])
        if parts and parts[0] in ("tracks", "playlists"):
            track = parts[0] == "tracks"
            if method == "POST" and len(parts) == 1:
                return (self.add_track if track else self.add_playlist)(body)
            if method == "PATCH" and len(parts) == 2:
                return (self.edit_track if track else self.edit_playlist)(parts[1], body)
            if method == "DELETE" and len(parts) == 2:
                fields(body, set())
                return (self.remove_track if track else self.remove_playlist)(parts[1])
        raise ServiceError("not-found", "Catalog route not found.", 404)
