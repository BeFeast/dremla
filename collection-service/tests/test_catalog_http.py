"""Catalog HTTP flow through the real guarded server on an ephemeral port."""
import http.client
import json
import threading
from unittest.mock import patch

from dremla_collection.catalog import selection, validate_catalog
from dremla_collection.fixtures import SINGLE
from dremla_collection.server import CollectionHTTPServer
from support import ServiceCase

MANUAL = dict(url="https://youtu.be/manual00001", title="Manual Night", artist="Manual Artist", durationSec=120, category="piano")
IDS = ["yt-fxAmbient01", "yt-manual00001"]


class CatalogHTTPTests(ServiceCase):
    def setUp(self):
        super().setUp()
        self.server = CollectionHTTPServer(self.service, 0)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.stop_http)

    def stop_http(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def request(self, method, path, body=None, headers=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        try:
            conn.request(method, path, body=json.dumps(body) if raw is None and body is not None else raw,
                         headers=headers if headers is not None else {"Content-Type": "application/json", "X-Dremla-Request": "1"})
            response = conn.getresponse()
            payload = response.read()
            result = json.loads(payload) if response.getheader("Content-Type", "").startswith("application/json") else payload
            return response.status, result, dict(response.getheaders())
        finally:
            conn.close()

    def ok(self, method, path, body=None):
        status, result, _ = self.request(method, path, body)
        self.assertEqual(status, 200, result)
        return result

    def publish(self, parent):
        revision = self.ok("GET", "/api/catalog")["draftRevision"]
        return self.ok("POST", "/api/catalog/publish", dict(parentVersion=parent, confirm=True, draftRevision=revision))

    def night(self):
        self.ok("POST", "/api/catalog/tracks", {"url": SINGLE})
        self.ok("POST", "/api/catalog/tracks", MANUAL)
        self.ok("POST", "/api/catalog/playlists", dict(id="night", title="Night", trackIds=IDS))
        return self.ok("PUT", "/api/catalog/active", {"active": "night"})

    def test_publish_confirms_only_the_draft_revision_the_operator_reviewed(self):
        self.night()
        tab_a = self.ok("GET", "/api/catalog")
        self.assertEqual(tab_a["selection"], IDS)
        # Tab B changes the draft on the same parent without publishing.
        tab_b = self.ok("PUT", "/api/catalog/excluded", {"excluded": IDS[:1]})
        self.assertNotEqual(tab_b["draftRevision"], tab_a["draftRevision"])
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(
            parentVersion=tab_a["currentVersion"], confirm=True, draftRevision=tab_a["draftRevision"]))
        self.assertEqual((status, result["error"]), (409, "stale-draft"))
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(parentVersion=0, confirm=True))
        self.assertEqual((status, result["error"]), (400, "invalid-request"))
        self.assertEqual(list(self.service.catalog.root.iterdir()), [])
        self.assertEqual(self.request("GET", "/catalog/current.json")[0], 404)
        # Tab A refreshes, reviews the changed selection and publishes exactly that.
        refreshed = self.ok("GET", "/api/catalog")
        self.assertEqual((refreshed["draftRevision"], refreshed["selection"]), (tab_b["draftRevision"], IDS[1:]))
        published = self.ok("POST", "/api/catalog/publish", dict(
            parentVersion=0, confirm=True, draftRevision=refreshed["draftRevision"]))
        self.assertEqual(selection(published), refreshed["selection"])
        self.assertEqual(self.request("GET", "/catalog/current.json")[1], published)

    def test_interrupted_publication_is_reviewed_and_completed_over_http(self):
        self.night()
        first = self.publish(0)
        before = (self.service.catalog.root / "current.json").read_bytes()
        def fail(stage):
            if stage == "after-index":
                raise OSError("injected publication interruption")
        self.service.catalog.failure_hook = fail
        self.ok("PATCH", "/api/catalog/tracks/" + IDS[0], {"title": "Interrupted"})
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(
            parentVersion=1, confirm=True, draftRevision=self.ok("GET", "/api/catalog")["draftRevision"]))
        self.assertEqual((status, result["error"]), (500, "local-failure"))
        self.service.catalog.failure_hook = lambda stage: None
        view = self.ok("PATCH", "/api/catalog/tracks/" + IDS[0], {"title": "Edited later"})
        pending = view["pending"]
        self.assertEqual((view["currentVersion"], pending["version"], pending["matchesDraft"]), (1, 2, False))
        self.assertEqual(pending["selection"][0], dict(id=IDS[0], title="Interrupted"))
        self.assertFalse(view["validation"]["valid"])
        self.assertEqual(self.request("GET", "/catalog/v2.json")[0], 404)
        self.assertEqual([e["version"] for e in self.ok("GET", "/api/catalog/versions")], [1])
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(
            parentVersion=1, confirm=True, draftRevision=view["draftRevision"]))
        self.assertEqual((status, result["error"]), (409, "pending-publication"))
        for body, expected in (({"sha256": "0" * 64, "confirm": True}, (409, "stale-pending")),
                               ({"sha256": pending["sha256"]}, (400, "confirmation-required")),
                               ({"sha256": pending["sha256"], "confirm": True, "draft": {}}, (400, "invalid-request"))):
            status, result, _ = self.request("POST", "/api/catalog/pending/complete", body)
            self.assertEqual((status, result["error"]), expected)
        self.assertEqual((self.service.catalog.root / "current.json").read_bytes(), before)
        completed = self.ok("POST", "/api/catalog/pending/complete", {"sha256": pending["sha256"], "confirm": True})
        self.assertEqual((completed["version"], completed["tracks"][0]["title"]), (2, "Interrupted"))
        third = self.publish(2)
        self.assertEqual((third["version"], third["tracks"][0]["title"]), (3, "Edited later"))
        for path, expected in (("/catalog/v1.json", first), ("/catalog/v2.json", completed), ("/catalog/current.json", third)):
            self.assertEqual(self.request("GET", path)[1], expected)
        self.assertEqual([e["version"] for e in self.ok("GET", "/api/catalog/versions")], [1, 2, 3])
        status, result, _ = self.request("POST", "/api/catalog/pending/complete", {"sha256": pending["sha256"], "confirm": True})
        self.assertEqual((status, result["error"]), (404, "not-found"))

    def test_full_http_flow_versions_reorder_cascades_and_static_page(self):
        initial = self.ok("GET", "/api/catalog")
        self.assertEqual(initial["draft"]["tracks"], [])
        self.assertFalse(initial["validation"]["valid"])
        for path, marker in (("/", b'href="/catalog"'), ("/catalog", b"Add a track"), ("/catalog.js", b"X-Dremla-Request"),
                             ("/catalog.js", b"draftRevision: shown.draftRevision"), ("/catalog", b'id="complete-pending"'),
                             ("/catalog", b'id="pending-document"')):
            status, result, headers = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertIn(marker, result)
            self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        self.ok("POST", "/api/catalog/tracks", {"url": SINGLE})
        manual = dict(url="https://youtu.be/manual00001", title="<script>alert('x')</script>", artist="Manual", category="piano", durationSec=120)
        self.ok("POST", "/api/catalog/tracks", manual)
        ids = ["yt-fxAmbient01", "yt-manual00001"]
        self.ok("POST", "/api/catalog/playlists", dict(id="night", title="Night", trackIds=ids))
        self.ok("PATCH", "/api/catalog/playlists/night", dict(title="Late night", trackIds=ids[::-1]))
        self.ok("PUT", "/api/catalog/active", {"active": "night"})
        reviewed = self.ok("PUT", "/api/catalog/excluded", {"excluded": ids})["draftRevision"]
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(parentVersion=0, confirm=True, draftRevision=reviewed))
        self.assertEqual((status, result["error"]), (400, "invalid-catalog"))
        self.ok("PUT", "/api/catalog/excluded", {"excluded": ids[1:]})
        first = self.publish(0)
        self.assertEqual(self.ok("GET", "/api/catalog")["selection"], ids[:1])
        self.ok("PATCH", "/api/catalog/tracks/" + ids[0], {"title": "Second version", "gainDb": -3.5})
        second = self.publish(1)
        for path, expected in (("/catalog/current.json", second), ("/catalog/v1.json", first), ("/catalog/v2.json", second)):
            status, document, headers = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertTrue(headers["Content-Type"].startswith("application/json"))
            self.assertEqual(document, expected)
            validate_catalog(document)
        self.assertEqual([e["version"] for e in self.ok("GET", "/api/catalog/versions")], [1, 2])
        status, result, _ = self.request("POST", "/api/catalog/publish", dict(
            parentVersion=1, confirm=True, draftRevision=self.ok("GET", "/api/catalog")["draftRevision"]))
        self.assertEqual((status, result["error"]), (409, "stale-parent"))
        self.ok("DELETE", "/api/catalog/tracks/" + ids[1], {})
        draft = self.ok("GET", "/api/catalog")["draft"]
        self.assertEqual(draft["excluded"], [])
        self.assertEqual(draft["playlists"][0]["trackIds"], ids[:1])
        self.ok("DELETE", "/api/catalog/playlists/night", {})
        self.assertIsNone(self.ok("GET", "/api/catalog")["draft"]["active"])
        self.assertEqual(self.service.drafts, {})
        self.assertEqual(self.service.jobs, {})

    def test_host_csrf_json_and_unknown_fields_refused_on_all_writes(self):
        endpoints = [("POST", "/api/catalog/tracks", {"url": SINGLE}),
                     ("PATCH", "/api/catalog/tracks/yt-fxAmbient01", {"title": "Change"}),
                     ("DELETE", "/api/catalog/tracks/yt-fxAmbient01", {}),
                     ("PUT", "/api/catalog/excluded", {"excluded": []}),
                     ("PUT", "/api/catalog/active", {"active": None}),
                     ("POST", "/api/catalog/publish", {"parentVersion": 0, "confirm": True}),
                     ("POST", "/api/catalog/pending/complete", {"sha256": "0" * 64, "confirm": True})]
        for method, path, body in endpoints:
            for headers, status, code in (({"Host": "foreign.example"}, 403, "invalid-host"),
                                          ({"Content-Type": "application/json"}, 403, "csrf-refused"),
                                          ({"Content-Type": "text/plain", "X-Dremla-Request": "1"}, 415, "invalid-content-type")):
                got, result, _ = self.request(method, path, body, headers=headers)
                self.assertEqual((got, result["error"]), (status, code))
        for path in ("/api/catalog", "/catalog", "/catalog/current.json", "/catalog/v1.json"):
            self.assertEqual(self.request("GET", path, headers={"Host": "foreign.example"})[0], 403)
        for raw in ('[]', '{', '{"url":NaN}', '{"url":"x","url":"y"}', '{"url":"\\ud800"}'):
            self.assertEqual(self.request("POST", "/api/catalog/tracks", raw=raw)[0], 400)
        for key in ("cookie", "streamUrl", "session", "file", "source"):
            status, result, _ = self.request("POST", "/api/catalog/tracks", {"url": SINGLE, key: "forbidden"})
            self.assertEqual((status, result["error"]), (400, "invalid-request"))
        self.assertEqual(self.ok("GET", "/api/catalog")["draft"]["tracks"], [])

    def test_export_paths_missing_confirmation_and_redacted_failure(self):
        for path in ("/catalog/current.json", "/catalog/v1.json", "/catalog/index.json", "/catalog/../catalog-draft.json",
                     "/catalog/v01.json", "/api/catalog/no-route", "/catalog/%2e%2e/state"):
            self.assertEqual(self.request("GET", path)[0], 404)
        status, result, _ = self.request("POST", "/api/catalog/publish", {"parentVersion": 0})
        self.assertEqual((status, result["error"]), (400, "confirmation-required"))
        with patch.object(self.service.catalog, "view", side_effect=OSError("private fixture diagnostic")):
            status, result, _ = self.request("GET", "/api/catalog")
            self.assertEqual((status, result), (500, {"error": "local-failure", "message": "Local storage or processing failed."}))
