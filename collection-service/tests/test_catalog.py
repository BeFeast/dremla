"""Catalog contract, draft transactions, immutable publication and recovery."""
import hashlib
from pathlib import Path
import runpy
import threading
from unittest.mock import patch

from dremla_collection.catalog import Catalog, revision, selection, validate_catalog
from dremla_collection.common import ServiceError, atomic_write, digest, json_bytes, read_json
from dremla_collection.fixtures import SINGLE, PLAYLIST
from dremla_collection.jobs import CollectionService
from dremla_collection.adapters import FixtureAcquisition, FixtureProcessor
from support import ServiceCase

FIXTURES = Path(__file__).resolve().parents[2] / "contracts/fixtures"
MANUAL = dict(url="https://youtu.be/manual00001", title="Manual Night", artist="Manual Artist", durationSec=120, category="piano")
FIRST, SECOND = "yt-fxAmbient01", "yt-manual00001"


class CatalogTests(ServiceCase):
    @property
    def catalog(self):
        return self.service.catalog

    def populated(self):
        self.catalog.add_track({"url": SINGLE})
        self.catalog.add_track(MANUAL)
        self.catalog.add_playlist(dict(id="night", title="Night", trackIds=[FIRST, SECOND]))
        self.catalog.set_field("active", "night")

    def reopen(self):
        self.service.close()
        self.service = self.open_service(start_worker=False)

    def publish(self, parent):
        return self.catalog.publish(parent, True, self.catalog.view()["draftRevision"])

    def test_empty_draft_then_crud_reorder_exclusion_and_cascades(self):
        initial = self.catalog.view()
        self.assertEqual(initial["draft"], dict(tracks=[], playlists=[], excluded=[], active=None))
        self.assertEqual(initial["currentVersion"], 0)
        self.assertFalse(initial["validation"]["valid"])
        self.populated()
        self.catalog.edit_track(FIRST, dict(title="Edited", artist="A", durationSec=100, category="piano", gainDb=-4.5))
        self.catalog.edit_playlist("night", dict(title="Renamed", trackIds=[SECOND, FIRST]))
        self.assertEqual(self.catalog.view()["selection"], [SECOND, FIRST])
        self.catalog.set_field("excluded", [SECOND])
        self.assertEqual(self.catalog.view()["selection"], [FIRST])
        self.catalog.set_field("excluded", [])
        self.assertEqual(self.catalog.view()["selection"], [SECOND, FIRST])
        self.catalog.set_field("excluded", [FIRST])
        self.catalog.remove_track(FIRST)
        draft = self.catalog.view()["draft"]
        self.assertEqual(draft["playlists"][0]["trackIds"], [SECOND])
        self.assertEqual(draft["excluded"], [])
        self.reopen()
        self.assertEqual(self.catalog.view()["draft"], draft)
        self.catalog.remove_playlist("night")
        self.assertIsNone(self.catalog.view()["draft"]["active"])
        self.assertFalse(self.catalog.view()["validation"]["valid"])
        self.assertEqual(self.service.drafts, {})
        self.assertEqual(self.service.jobs, {})
        self.assertEqual(list(self.service.storage.root.iterdir()), [])

    def test_manual_metadata_required_fixture_defaults_and_clear_gain(self):
        for field in ("title", "artist", "durationSec", "category"):
            body = dict(MANUAL)
            del body[field]
            self.assert_code("metadata-required", lambda: self.catalog.add_track(body))
        self.catalog.add_track({"url": SINGLE})
        fixture = self.catalog.view()["draft"]["tracks"][0]
        self.assertEqual((fixture["title"], fixture["artist"], fixture["durationSec"]), ("Still Lantern", "Fixture Ensemble", 90))
        self.catalog.add_track(dict(MANUAL, gainDb=-40))
        self.catalog.edit_track(SECOND, {"gainDb": None})
        self.assertNotIn("gainDb", self.catalog.view()["draft"]["tracks"][1])
        self.assert_code("track-exists", lambda: self.catalog.add_track({"url": SINGLE}))
        self.assert_code("track-exists", lambda: self.catalog.add_track({"url": PLAYLIST}))
        self.assertEqual(len(self.catalog.view()["draft"]["tracks"]), 2)

    def test_fixture_playlist_and_url_validation_without_acquisition(self):
        with patch.object(self.service.acquisition, "resolve", side_effect=AssertionError("No acquisition")), \
                patch.object(self.service.acquisition, "fetch", side_effect=AssertionError("No acquisition")):
            self.catalog.add_track({"url": PLAYLIST})
        self.assertEqual(len(self.catalog.view()["draft"]["tracks"]), 4)
        self.assert_code("unsupported-url", lambda: self.catalog.add_track({"url": "https://www.youtube.com/playlist?list=PLUnknown"}))
        for url in ("http://youtube.com/watch?v=fxAmbient01", "https://foreign.example/watch?v=fxAmbient01",
                    "https://youtube.com.evil.example/watch?v=fxAmbient01", "https://user@youtube.com/watch?v=fxAmbient01",
                    "https://youtube.com:443/watch?v=fxAmbient01", "https://youtube.com/shorts/fxAmbient01",
                    "https://youtube.com/watch?v=fxAmbient01#fragment", "https://youtube.com/watch?v=fxAmbient01&v=fxPiano0001",
                    "https://youtu.be/not-an-id", "https://youtube.com/watch?v=fxAmbient01&list=", None):
            with self.subTest(url=url):
                self.assert_code("invalid-url", lambda: self.catalog.add_track({"url": url}))
        self.catalog.remove_track(FIRST)
        self.catalog.add_track({"url": SINGLE + "&list=PLFixtureCalm01"})
        self.assertEqual(self.catalog.view()["draft"]["tracks"][-1]["source"]["url"], SINGLE)

    def test_invalid_mutations_leave_memory_and_disk_unchanged(self):
        self.populated()
        before = self.catalog.view()["draft"]
        raw = self.catalog.draft_path.read_bytes()
        operations = []
        for field, values in {"durationSec": [0, True, 1.5, "90"], "title": ["", None], "artist": [" ", 42],
                              "category": ["rock", []], "gainDb": [1, -41, True, float("nan"), "-2"]}.items():
            for value in values:
                operations.append(lambda field=field, value=value: self.catalog.edit_track(FIRST, {field: value}))
        for ids in ([FIRST, FIRST], ["unknown"], [None], "tracks", None, [{}]):
            operations.extend([lambda ids=ids: self.catalog.edit_playlist("night", {"trackIds": ids}),
                               lambda ids=ids: self.catalog.set_field("excluded", ids)])
        operations.extend([lambda: self.catalog.set_field("active", "unknown"),
                           lambda: self.catalog.set_field("active", {}),
                           lambda: self.catalog.edit_playlist("night", {"title": ""}),
                           lambda: self.catalog.add_playlist({"id": "../night", "title": "Bad"})])
        for operation in operations:
            self.assert_code("invalid-catalog", operation)
            self.assertEqual(self.catalog.view()["draft"], before)
            self.assertEqual(self.catalog.draft_path.read_bytes(), raw)
        for body in ({"source": {}}, {"streamUrl": "forbidden"}, {"cookie": "forbidden"}, {"title": "\ud800"}):
            self.assert_code("invalid-request", lambda: self.catalog.edit_track(FIRST, body))
        self.assert_code("playlist-exists", lambda: self.catalog.add_playlist({"id": "night", "title": "Again"}))
        self.assert_code("not-found", lambda: self.catalog.remove_track("unknown"))
        self.assert_code("not-found", lambda: self.catalog.remove_playlist("unknown"))

    def test_failed_draft_write_retains_previous_state(self):
        self.populated()
        before = self.catalog.view()["draft"]
        with patch("dremla_collection.catalog.atomic_write", side_effect=OSError("scratch fault")):
            self.assert_code("storage-failed", lambda: self.catalog.remove_track(FIRST))
        self.assertEqual(self.catalog.view()["draft"], before)
        self.reopen()
        self.assertEqual(self.catalog.view()["draft"], before)

    def test_empty_catalog_destination_needs_no_write_for_media_only_startup(self):
        self.service.close()
        root = self.root / "catalog"
        root.chmod(0o500)
        self.addCleanup(lambda: root.chmod(0o700))
        with patch("dremla_collection.catalog.atomic_write", side_effect=AssertionError("No catalog write at startup")):
            self.service = self.open_service(start_worker=False)
            self.assertEqual(self.catalog.view()["currentVersion"], 0)
            self.assertEqual(self.catalog.history(), [])
            self.assertEqual(list(root.iterdir()), [])

    def test_published_validator_agrees_with_all_contract_vectors(self):
        checker = runpy.run_path(str(FIXTURES.parent / "check-catalog.py"))
        for path in sorted(FIXTURES.glob("catalog-v*.json")):
            with self.subTest(path=path.name):
                c = read_json(path)
                validate_catalog(c)
                checker["validate_catalog"](c)
                expected = read_json(FIXTURES / f"selection-v{c['version']}.json")
                self.assertEqual(selection(c), expected["selection"])
        for path in sorted((FIXTURES / "invalid").glob("*.json")):
            with self.subTest(path=path.name):
                c = read_json(path)
                self.assert_code("invalid-catalog", lambda: validate_catalog(c))
                with self.assertRaises((ValueError, KeyError, TypeError, StopIteration)):
                    checker["validate_catalog"](c)

    def test_publish_refuses_empty_missing_active_empty_selection_stale_and_unconfirmed(self):
        self.assert_code("invalid-catalog", lambda: self.publish(0))
        self.catalog.add_track({"url": SINGLE})
        self.catalog.add_playlist({"id": "night", "title": "Night", "trackIds": [FIRST]})
        self.assert_code("invalid-catalog", lambda: self.publish(0))
        self.catalog.set_field("active", "night")
        self.catalog.set_field("excluded", [FIRST])
        self.assert_code("invalid-catalog", lambda: self.publish(0))
        self.assertFalse((self.catalog.root / "current.json").exists())
        self.catalog.set_field("excluded", [])
        self.catalog.edit_playlist("night", {"trackIds": []})
        self.assert_code("invalid-catalog", lambda: self.publish(0))
        self.catalog.edit_playlist("night", {"trackIds": [FIRST]})
        reviewed = self.catalog.view()["draftRevision"]
        for confirm in (False, None, 1):
            self.assert_code("confirmation-required", lambda: self.catalog.publish(0, confirm, reviewed))
        for parent in (True, "0", -1, 1):
            self.assert_code("stale-parent", lambda: self.catalog.publish(parent, True, reviewed))
        for missing in (None, 1, ["x"]):
            self.assert_code("invalid-request", lambda: self.catalog.publish(0, True, missing))
        self.catalog.edit_track(FIRST, {"title": "Changed after review"})
        for stale in (reviewed, "", "0" * 64):
            self.assert_code("stale-draft", lambda: self.catalog.publish(0, True, stale))
        self.assertEqual(sorted(p.name for p in self.catalog.root.iterdir()), [])
        # The revision is the draft's content hash: restoring the reviewed content makes it current again.
        self.catalog.edit_track(FIRST, {"title": "Still Lantern"})
        self.assertEqual(self.catalog.view()["draftRevision"], reviewed)
        self.assertEqual(revision(self.catalog.draft), reviewed)
        self.assertEqual(self.catalog.publish(0, True, reviewed)["tracks"][0]["title"], "Still Lantern")
        self.assert_code("stale-parent", lambda: self.publish(0))

    def test_versions_are_immutable_current_last_hashes_and_contract_exports(self):
        self.populated()
        order = []
        def write(path, data):
            order.append(path.name)
            return atomic_write(path, data)
        with patch("dremla_collection.catalog.atomic_write", side_effect=write):
            first = self.publish(0)
        self.assertEqual(order, ["v1.json", "index.json", "current.json"])
        old = (self.catalog.root / "v1.json").read_bytes()
        self.catalog.edit_track(FIRST, {"title": "Version Two"})
        second = self.publish(1)
        self.assertEqual((first["version"], second["version"]), (1, 2))
        self.assertEqual((self.catalog.root / "v1.json").read_bytes(), old)
        self.assertEqual((self.catalog.root / "current.json").read_bytes(), (self.catalog.root / "v2.json").read_bytes())
        self.assertEqual(self.catalog.exported("current.json"), second)
        self.assertEqual(self.catalog.exported("v1.json"), first)
        checker = runpy.run_path(str(FIXTURES.parent / "check-catalog.py"))
        forbidden = {"file", "sha256", "bytes", "preview", "streamUrl", "stream_url", "cookies", "cookie", "session", "credentials"}
        def no_private_fields(obj):
            if isinstance(obj, dict):
                self.assertFalse(set(obj) & forbidden)
                for value in obj.values():
                    no_private_fields(value)
            elif isinstance(obj, list):
                for value in obj:
                    no_private_fields(value)
        for doc in (first, second):
            validate_catalog(doc)
            checker["validate_catalog"](doc)
            no_private_fields(doc)
        for entry in self.catalog.history():
            self.assertEqual(entry["parent"], entry["version"] - 1)
            self.assertEqual(entry["sha256"], digest(self.catalog.root / f"v{entry['version']}.json"))
            self.assertEqual(entry["counts"], dict(tracks=2, playlists=1, selection=2))
        self.reopen()
        self.assertEqual(self.catalog.current(), second)
        self.assertEqual(len(self.catalog.history()), 2)

    def test_interrupted_publish_is_shown_after_edits_and_restart_and_completed_only_explicitly(self):
        self.populated()
        self.publish(0)
        for stage in ("after-snapshot", "after-index"):
            with self.subTest(stage=stage):
                parent = self.catalog.current()["version"]
                published = {p.name: p.read_bytes() for p in self.catalog.root.glob("v*.json")}
                before = (self.catalog.root / "current.json").read_bytes()
                def fail(point):
                    if point == stage:
                        raise OSError("injected publication interruption")
                self.catalog.edit_track(FIRST, {"title": f"Reviewed {stage}"})
                self.catalog.failure_hook = fail
                with self.assertRaises(OSError):
                    self.publish(parent)
                self.catalog.failure_hook = lambda point: None
                stranded = (self.catalog.root / f"v{parent + 1}.json").read_bytes()
                # The operator keeps editing the draft, then the service restarts.
                self.catalog.edit_track(FIRST, {"title": f"Edited after {stage}"})
                self.reopen()
                self.assertEqual((self.catalog.root / "current.json").read_bytes(), before)
                self.assertEqual(len(self.catalog.history()), parent)
                self.assert_code("not-found", lambda: self.catalog.exported(f"v{parent + 1}.json"))
                view = self.catalog.view()
                pending = view["pending"]
                self.assertEqual((pending["version"], pending["sha256"], pending["matchesDraft"]),
                                 (parent + 1, hashlib.sha256(stranded).hexdigest(), False))
                self.assertEqual(pending["selection"], [dict(id=FIRST, title=f"Reviewed {stage}"),
                                                        dict(id=SECOND, title="Manual Night")])
                self.assertEqual(json_bytes(pending["document"]), stranded)
                self.assertFalse(view["validation"]["valid"])
                # Neither a normal publish nor a mismatched or unconfirmed completion makes it current.
                self.assert_code("pending-publication", lambda: self.publish(parent))
                self.assert_code("stale-pending", lambda: self.catalog.complete_pending("0" * 64, True))
                self.assert_code("confirmation-required", lambda: self.catalog.complete_pending(pending["sha256"], None))
                self.assertEqual((self.catalog.root / "current.json").read_bytes(), before)
                completed = self.catalog.complete_pending(pending["sha256"], True)
                self.assertEqual(completed["tracks"][0]["title"], f"Reviewed {stage}")
                self.assertEqual((self.catalog.root / "current.json").read_bytes(), stranded)
                self.assertEqual([e["version"] for e in self.catalog.history()], list(range(1, parent + 2)))
                self.assertIsNone(self.catalog.view()["pending"])
                self.assert_code("not-found", lambda: self.catalog.complete_pending(pending["sha256"], True))
                # The kept edits publish as the next version; every earlier record is byte-for-byte unchanged.
                latest = self.publish(parent + 1)
                self.assertEqual((latest["version"], latest["tracks"][0]["title"]), (parent + 2, f"Edited after {stage}"))
                self.assertEqual((self.catalog.root / f"v{parent + 1}.json").read_bytes(), stranded)
                for name, raw in published.items():
                    self.assertEqual((self.catalog.root / name).read_bytes(), raw)
                self.reopen()
                self.assertEqual(self.catalog.current(), latest)

    def test_first_publish_interruption_is_never_reused_implicitly(self):
        self.populated()
        def fail(stage):
            raise OSError("interruption")
        self.catalog.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish(0)
        self.catalog.failure_hook = lambda stage: None
        self.assertIsNone(self.catalog.current())
        self.assertEqual(self.catalog.history(), [])
        before = (self.catalog.root / "v1.json").read_bytes()
        pending = self.catalog.view()["pending"]
        self.assertTrue(pending["matchesDraft"])
        # Even with an unchanged draft, completion is its own confirmation of the stored record.
        self.assert_code("pending-publication", lambda: self.publish(0))
        self.catalog.edit_track(FIRST, {"title": "Different"})
        self.assertFalse(self.catalog.view()["pending"]["matchesDraft"])
        self.assertEqual(self.catalog.complete_pending(pending["sha256"], True)["version"], 1)
        self.assertEqual((self.catalog.root / "current.json").read_bytes(), before)
        self.assertEqual((self.catalog.root / "v1.json").read_bytes(), before)
        self.assertEqual(self.publish(1)["tracks"][0]["title"], "Different")

    def test_catalog_directory_lock_separate_trees_and_release_on_failure(self):
        def open_other(dest):
            return CollectionService(self.root / "other-state", self.root / "other-media", FixtureAcquisition(),
                                     FixtureProcessor(), start_worker=False, catalog_destination=dest)
        self.assert_code("already-running", lambda: open_other(self.catalog.root))
        for dest in (self.root, self.root / "other-state", self.root / "other-state/catalog",
                     self.root / "other-media", self.root / "other-media/catalog"):
            self.assert_code("invalid-config", lambda: open_other(dest))
        # A rejected start must release any locks acquired before its failure.
        other = open_other(self.root / "other-catalog")
        other.close()
        self.service.close()
        other = open_other(self.catalog.root)
        other.close()

    def test_startup_rejects_symlinks_changed_records_and_bad_history(self):
        self.populated()
        self.publish(0)
        root = self.catalog.root
        def reopen_store():
            return Catalog(self.service.data, root, self.service.lock)
        path = root / "index.json"
        original = path.read_bytes()
        for field, value in (("parent", True), ("counts", {}), ("generated", "changed"), ("sha256", "bad")):
            entries = read_json(path)
            entries[0][field] = value
            path.write_bytes(json_bytes(entries))
            self.assert_code("invalid-base", reopen_store)
            path.write_bytes(original)
        record = root / "v1.json"
        raw = record.read_bytes()
        record.write_bytes(b"{}")
        self.assert_code("invalid-base", reopen_store)
        record.write_bytes(raw)
        pending = root / "v2.json"
        for content in (b"{", b"{}", json_bytes(dict(read_json(record), version=3))):
            pending.write_bytes(content)
            self.assert_code("invalid-base", reopen_store)
        pending.unlink()
        for path in (root / "current.json", root / "index.json", record, self.catalog.draft_path):
            raw = path.read_bytes()
            path.unlink()
            path.symlink_to(self.root / "absent")
            self.assert_code("invalid-base", reopen_store)
            path.unlink()
            path.write_bytes(raw)

    def test_concurrent_publishes_accept_only_one_parent(self):
        self.populated()
        barrier, outcomes = threading.Barrier(2), []
        def publish():
            barrier.wait()
            try:
                outcomes.append(self.publish(0)["version"])
            except ServiceError as error:
                outcomes.append(error.code)
        threads = [threading.Thread(target=publish) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(10)
            self.assertFalse(thread.is_alive())
        self.assertCountEqual(outcomes, [1, "stale-parent"])
        self.assertEqual(len(self.catalog.history()), 1)
