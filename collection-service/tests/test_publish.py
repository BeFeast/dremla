import copy
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from dremla_collection.common import ServiceError, atomic_write, digest, json_bytes, read_json
from dremla_collection.publish import FilesystemStorage, revision
from dremla_collection.validate import ROOT_FIELDS, TRACK_FIELDS, validate
from support import publication


class StorageCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dremla-publish-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.storage = FilesystemStorage(self.root / "dest")
        self.snapshot, self.assets = publication(self.root / "inputs")

    def publish(self, parent=0):
        return self.storage.publish(self.snapshot, self.assets, parent, True, revision(self.snapshot))


class PublicationTests(StorageCase):
    def test_exact_contract_hash_size_and_layout(self):
        result = self.publish()
        self.assertEqual(set(result), ROOT_FIELDS)
        self.assertEqual(set(result["tracks"][0]), TRACK_FIELDS)
        validate(result, self.storage.root)
        self.assertEqual(result["version"], 1)
        for track in result["tracks"]:
            self.assertEqual(digest(self.storage.root / track["file"]), track["sha256"])
            self.assertEqual((self.storage.root / track["file"]).stat().st_size, track["bytes"])
        expected = {*self.assets, "manifest.json", "snapshots/index.json", "snapshots/v1/manifest.json"}
        self.assertEqual({str(p.relative_to(self.storage.root)) for p in self.storage.root.rglob("*") if p.is_file()}, expected)

    def test_reuse_is_immutable(self):
        first = self.publish()
        before = {path: ((self.storage.root / path).stat().st_mtime_ns, (self.storage.root / path).read_bytes()) for path in self.assets}
        record = (self.storage.root / "snapshots/v1/manifest.json").read_bytes()
        second = self.publish(1)
        self.assertEqual(second["tracks"], first["tracks"])
        for path, expected in before.items():
            self.assertEqual(((self.storage.root / path).stat().st_mtime_ns, (self.storage.root / path).read_bytes()), expected)
        self.assertEqual((self.storage.root / "snapshots/v1/manifest.json").read_bytes(), record)

    def test_existing_asset_hash_checked_before_reuse(self):
        relative, source = next(iter(self.assets.items()))
        destination = self.storage.root / relative
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b"corrupt".ljust(source.stat().st_size, b"!"))
        with self.assertRaisesRegex(ServiceError, "Existing asset hash mismatch"):
            self.storage.put_asset(source, relative)
        self.assertNotEqual(destination.read_bytes(), source.read_bytes())
        self.assertFalse((self.storage.root / "manifest.json").exists())

    def test_source_hash_mismatch_fails(self):
        relative, source = next(iter(self.assets.items()))
        source.write_bytes(b"changed after review")
        with self.assertRaisesRegex(ServiceError, "Source asset hash mismatch"):
            self.storage.put_asset(source, relative)

    def test_manifest_last_failure_at_each_boundary_and_completion(self):
        self.publish()
        old_files = {p: (self.storage.root / p).read_bytes() for p in self.assets}
        original_record = (self.storage.root / "snapshots/v1/manifest.json").read_bytes()
        self.snapshot["tracks"][0]["title"] = "Later Lantern"
        for boundary in ("after-assets", "after-snapshot", "after-index"):
            parent = self.storage.current()["version"]
            original = (self.storage.root / "manifest.json").read_bytes()
            def fail(stage):
                if stage == boundary:
                    raise OSError("injected failure")
            self.storage.failure_hook = fail
            with self.subTest(boundary=boundary), self.assertRaises(OSError):
                self.publish(parent)
            self.assertEqual((self.storage.root / "manifest.json").read_bytes(), original)
            for path, content in old_files.items():
                self.assertEqual((self.storage.root / path).read_bytes(), content)
            self.assertEqual((self.storage.root / "snapshots/v1/manifest.json").read_bytes(), original_record)
            self.assertEqual([e["version"] for e in self.storage.history()], list(range(1, parent + 1)))
            validate(read_json(self.storage.root / "manifest.json"), self.storage.root)
            self.storage = FilesystemStorage(self.storage.root)
            if boundary == "after-assets":
                self.assertIsNone(self.storage.pending())
                self.assertEqual(self.publish(parent)["version"], parent + 1)
            else:
                record = self.storage.root / f"snapshots/v{parent + 1}/manifest.json"
                before = record.read_bytes()
                with self.assertRaises(ServiceError) as raised:
                    self.publish(parent)
                self.assertEqual((raised.exception.code, raised.exception.status), ("pending-publication", 409))
                self.storage.complete_pending(digest(record), True)
                self.assertEqual((self.storage.root / "manifest.json").read_bytes(), before)
                self.assertEqual(record.read_bytes(), before)
        self.assertEqual([e["version"] for e in self.storage.history()], [1, 2, 3, 4])

    def test_validation_failure_preserves_current(self):
        self.publish()
        before = (self.storage.root / "manifest.json").read_bytes()
        self.snapshot["selection"] = []
        with self.assertRaises(ServiceError):
            self.publish(1)
        self.assertEqual((self.storage.root / "manifest.json").read_bytes(), before)
        self.assertFalse((self.storage.root / "snapshots/v2/manifest.json").exists())

    def test_stale_parent_and_confirmation_refused(self):
        self.publish()
        for parent, confirm, code in ((0, True, "stale-parent"), (True, True, "stale-parent"),
                                     (1, False, "confirmation-required"), (1, 1, "confirmation-required")):
            with self.subTest(parent=parent, confirm=confirm), self.assertRaises(ServiceError) as raised:
                self.storage.publish(self.snapshot, self.assets, parent, confirm, revision(self.snapshot))
            self.assertEqual(raised.exception.code, code)
        self.assertEqual(self.storage.current()["version"], 1)

    def test_history_and_rollback_create_new_version(self):
        first = self.publish()
        self.snapshot["tracks"][0]["title"] = "Version Two Lantern"
        second = self.publish(1)
        third = self.storage.rollback(1, 2, True)
        self.assertEqual(third["version"], 3)
        self.assertEqual(third["tracks"], first["tracks"])
        self.assertEqual(third["selection"], first["selection"])
        self.assertNotEqual(second["tracks"], third["tracks"])
        history = self.storage.history()
        self.assertEqual([e["parent"] for e in history], [0, 1, 2])
        self.assertEqual([e["rollbackOf"] for e in history], [None, None, 1])
        for entry in history:
            record = self.storage.root / f"snapshots/v{entry['version']}/manifest.json"
            self.assertEqual(entry["sha256"], digest(record))
            self.assertEqual(entry["counts"], {"tracks": 1, "selection": 1})
        with self.assertRaises(ServiceError):
            self.storage.rollback(99, 3, True)
        with self.assertRaises(ServiceError):
            self.storage.rollback(1, 2, True)

    def test_invalid_base_and_nonservice_manifest_refused(self):
        self.publish()
        first_asset = next(iter(self.assets))
        (self.storage.root / first_asset).write_bytes(b"broken")
        with self.assertRaises(ServiceError):
            FilesystemStorage(self.storage.root)
        (self.storage.root / first_asset).write_bytes(self.assets[first_asset].read_bytes())
        (self.storage.root / "snapshots/v1/manifest.json").unlink()
        with self.assertRaises(ServiceError):
            FilesystemStorage(self.storage.root)

    def test_startup_refuses_broken_manifest_symlink_and_bad_history(self):
        path = self.storage.root / "manifest.json"
        path.symlink_to(self.root / "missing.json")
        with self.assertRaises(ServiceError):
            FilesystemStorage(self.storage.root)
        path.unlink()
        self.publish()
        history_path = self.storage.root / "snapshots/index.json"
        history = read_json(history_path)
        for field, value in (("counts", {"tracks": 9, "selection": 1}), ("parent", True),
                             ("rollbackOf", 2), ("generated", "changed")):
            bad = copy.deepcopy(history)
            bad[0][field] = value
            history_path.write_bytes(json_bytes(bad))
            with self.subTest(field=field), self.assertRaises(ServiceError):
                FilesystemStorage(self.storage.root)
        history_path.write_bytes(json_bytes(history))

    def test_new_content_revision_keeps_id_and_old_assets(self):
        first = self.publish()
        old = {path: (self.storage.root / path).read_bytes() for path in self.assets}
        audio = self.root / "inputs/revised.wav"
        audio.write_bytes(b"new fixture content")
        track = self.snapshot["tracks"][0]
        revised = f"audio/{track['id']}.{digest(audio)}.wav"
        self.assets.pop(track["file"])
        self.assets[revised] = audio
        track.update(file=revised, sha256=digest(audio), bytes=audio.stat().st_size)
        second = self.publish(1)
        self.assertEqual(first["tracks"][0]["id"], second["tracks"][0]["id"])
        self.assertNotEqual(first["tracks"][0]["file"], second["tracks"][0]["file"])
        for path, content in old.items():
            self.assertEqual((self.storage.root / path).read_bytes(), content)
        validate(first, self.storage.root)
        validate(second, self.storage.root)

    def test_stranded_record_is_never_overwritten(self):
        def fail(stage):
            if stage == "after-snapshot":
                raise OSError("interruption")
        self.storage.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish()
        before = (self.storage.root / "snapshots/v1/manifest.json").read_bytes()
        self.snapshot["tracks"][0]["title"] = "Different Proposal"
        self.storage.failure_hook = lambda stage: None
        with self.assertRaisesRegex(ServiceError, "interrupted publication"):
            self.publish()
        self.assertEqual((self.storage.root / "snapshots/v1/manifest.json").read_bytes(), before)

    def test_pending_startup_and_completion_refuse_invalid_record_or_assets(self):
        def fail(stage):
            if stage == "after-snapshot":
                raise OSError("interruption")
        self.storage.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish()
        self.storage.failure_hook = lambda stage: None
        record = self.storage.root / "snapshots/v1/manifest.json"
        original = record.read_bytes()
        sha = digest(record)
        for path in (record, *(self.storage.root / p for p in self.assets)):
            saved = path.read_bytes()
            for content in (None, b"corrupt"):
                if path == record and content is None:
                    continue
                with self.subTest(path=path.name, missing=content is None):
                    if content is None:
                        path.unlink()
                    else:
                        path.write_bytes(content)
                    for operation in (lambda: FilesystemStorage(self.storage.root),
                                      lambda: self.storage.complete_pending(sha, True)):
                        with self.assertRaises(ServiceError) as raised:
                            operation()
                        self.assertEqual(raised.exception.code, "invalid-base")
                    self.assertFalse((self.storage.root / "manifest.json").exists())
                    self.assertFalse((self.storage.root / "snapshots/index.json").exists())
                    path.write_bytes(saved)
        wrong_version = read_json(record)
        wrong_version["version"] = 2
        record.write_bytes(json_bytes(wrong_version))
        with self.assertRaises(ServiceError) as raised:
            FilesystemStorage(self.storage.root)
        self.assertEqual(raised.exception.code, "invalid-base")
        record.write_bytes(original)
        self.storage.complete_pending(sha, True)
        self.assertEqual((self.storage.root / "manifest.json").read_bytes(), original)

    def test_rollback_intent_before_record_does_not_reserve_version(self):
        self.publish()
        def fail_record(path, data):
            if path == self.storage.root / "snapshots/v2/manifest.json":
                raise OSError("before record")
            atomic_write(path, data)
        with patch("dremla_collection.publish.atomic_write", side_effect=fail_record):
            with self.assertRaises(OSError):
                self.storage.rollback(1, 1, True)
        self.storage = FilesystemStorage(self.storage.root)
        self.assertIsNone(self.storage.pending())
        self.snapshot["tracks"][0]["title"] = "Different proposal"
        def fail(stage):
            if stage == "after-snapshot":
                raise OSError("after record")
        self.storage.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish(1)
        self.storage = FilesystemStorage(self.storage.root)
        self.assertIsNone(self.storage.pending()[2])
        record = self.storage.root / "snapshots/v2/manifest.json"
        self.storage.complete_pending(digest(record), True)
        self.assertIsNone(self.storage.history()[-1]["rollbackOf"])

    def test_revision_is_canonical_and_content_addressed(self):
        reordered = dict(selection=self.snapshot["selection"], tracks=[dict(reversed(list(t.items())))
                                                                    for t in self.snapshot["tracks"]])
        self.assertEqual(revision(self.snapshot), revision(reordered))
        self.assertEqual(revision(self.snapshot), revision(dict(reordered, version=9, generated="ignored")))
        before = {p: p.read_bytes() for p in self.storage.root.rglob("*") if p.is_file()}
        for value, code in ((None, "invalid-request"), ("0" * 64, "stale-proposal")):
            with self.assertRaises(ServiceError) as raised:
                self.storage.publish(self.snapshot, self.assets, 0, True, value)
            self.assertEqual(raised.exception.code, code)
        self.assertEqual({p: p.read_bytes() for p in self.storage.root.rglob("*") if p.is_file()}, before)

    def test_completion_preserves_record_encoding_and_validates_pending_index(self):
        def fail(stage):
            if stage == "after-snapshot":
                raise OSError("interruption")
        self.storage.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish()
        record = self.storage.root / "snapshots/v1/manifest.json"
        document = read_json(record)
        raw = json.dumps(document, sort_keys=True, separators=(",", ":")).encode()
        record.write_bytes(raw)
        index = self.storage.root / "snapshots/index.json"
        good = self.storage.entry(document, raw)
        for entries in ([dict(good, sha256="0" * 64)], [dict(good, rollbackOf=True)], [good, good]):
            index.write_bytes(json_bytes(entries))
            with self.assertRaises(ServiceError) as raised:
                FilesystemStorage(self.storage.root)
            self.assertEqual(raised.exception.code, "invalid-base")
        index.write_bytes(json_bytes([good]))
        self.storage = FilesystemStorage(self.storage.root)
        self.storage.complete_pending(digest(record), True)
        self.assertEqual((self.storage.root / "manifest.json").read_bytes(), raw)
        self.assertEqual(record.read_bytes(), raw)


class ValidatorTests(StorageCase):
    def test_rejects_every_malformed_field_class(self):
        manifest = self.publish()
        bad_root = {"version": [0, -1, True, "1"], "generated": ["yesterday", "2026-99-01T00:00:00Z", "2026-01-01T00:00:00+01:00"],
                    "tracks": [None, {}], "selection": [None, [], ["unknown"], [True], [manifest["selection"][0]] * 2]}
        bad_track = {"id": ["../bad", "", 5], "title": ["", None], "artist": ["", 4], "license": ["", False],
                     "category": ["rock", None], "duration_sec": [0, -1, True, "90", float("inf"), 90.5],
                     "bytes": [0, -1, True, "10", 123], "sha256": ["A" * 64, "a" * 63, 4],
                     "file": ["../file", "/audio/file", "audio/../file", "https://example.invalid/a", "audio/a.wav"],
                     "preview": ["../preview", "previews/../preview", "previews/a.mp3"],
                     "source_url": ["http://example.invalid/x", "file:///tmp/fixture", "https://", None],
                     "loudness": [None, {}, {"i_lufs": "-24", "lra": 1}, {"i_lufs": float("nan"), "lra": 1},
                                  {"i_lufs": True, "lra": 1}, {"i_lufs": -24, "lra": -1}],
                     "flags": [None, "short", [1]]}
        for field, values in bad_root.items():
            for value in values:
                candidate = copy.deepcopy(manifest)
                candidate[field] = value
                with self.subTest(root=field, value=value), self.assertRaises(ServiceError):
                    validate(candidate, self.storage.root)
        for field, values in bad_track.items():
            for value in values:
                candidate = copy.deepcopy(manifest)
                candidate["tracks"][0][field] = value
                with self.subTest(track=field, value=value), self.assertRaises(ServiceError):
                    validate(candidate, self.storage.root)
        for target in ("root", "track"):
            fields = ROOT_FIELDS if target == "root" else TRACK_FIELDS
            for field in [*fields, "extra"]:
                candidate = copy.deepcopy(manifest)
                obj = candidate if target == "root" else candidate["tracks"][0]
                if field == "extra":
                    obj[field] = 1
                else:
                    del obj[field]
                with self.subTest(target=target, field=field), self.assertRaises(ServiceError):
                    validate(candidate, self.storage.root)
        duplicate = copy.deepcopy(manifest)
        duplicate["tracks"] *= 2
        with self.assertRaises(ServiceError):
            validate(duplicate, self.storage.root)

    def test_missing_changed_preview_and_symlink_assets(self):
        manifest = self.publish()
        for field in ("file", "preview"):
            path = self.storage.root / manifest["tracks"][0][field]
            original = path.read_bytes()
            path.unlink()
            with self.assertRaises(ServiceError):
                validate(manifest, self.storage.root)
            path.write_bytes(b"x" * len(original))
            with self.assertRaises(ServiceError):
                validate(manifest, self.storage.root)
            path.unlink()
            target = self.root / "outside.wav"
            target.write_bytes(original)
            path.symlink_to(target)
            with self.assertRaises(ServiceError):
                validate(manifest, self.storage.root)
            path.unlink()
            path.write_bytes(original)
