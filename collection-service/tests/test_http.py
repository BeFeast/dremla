import http.client
import json
import os
from pathlib import Path
import subprocess
import threading
from unittest.mock import patch

from dremla_collection.common import read_json
from dremla_collection.fixtures import SINGLE, PLAYLIST
from dremla_collection.server import CollectionHTTPServer
from support import ServiceCase


class HTTPTests(ServiceCase):
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
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        data = json.dumps(body).encode() if raw is None and body is not None else raw
        if headers is None:
            headers = {"Content-Type": "application/json", "X-Dremla-Request": "1"}
        try:
            connection.request(method, path, body=data, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            result = json.loads(payload) if response.getheader("Content-Type", "").startswith("application/json") else payload
            return response.status, result, dict(response.getheaders())
        finally:
            connection.close()

    def ok(self, method, path, body=None):
        status, result, _ = self.request(method, path, body)
        self.assertEqual(status, 200, result)
        return result

    def test_whole_flow_publish_two_versions_rollback_and_media(self):
        draft = self.ok("POST", "/api/drafts", {"url": SINGLE})
        self.assertEqual(self.ok("GET", "/api/drafts/" + draft["id"]), draft)
        self.ok("PATCH", f"/api/drafts/{draft['id']}/items/yt-fxAmbient01", {"title": "HTTP Lantern"})
        first = self.ok("POST", f"/api/drafts/{draft['id']}/process", {})[0]
        self.wait_job(first["id"])
        preview = self.ok("GET", f"/media/jobs/{first['id']}/preview")
        self.assertTrue(preview.startswith(b"RIFF"))
        status, part, headers = self.request("GET", f"/media/jobs/{first['id']}/preview", headers={"Range": "bytes=0-3"})
        self.assertEqual((status, part), (206, b"RIFF"))
        self.assertTrue(headers["Content-Range"].startswith("bytes 0-3/"))
        self.ok("POST", "/api/review/yt-fxAmbient01", {"approved": True, "job": first["id"]})
        v1 = self.ok("POST", "/api/publish", {"parentVersion": 0, "confirm": True})
        self.assertEqual(v1["version"], 1)
        self.assertEqual(v1["tracks"][0]["title"], "HTTP Lantern")
        self.assertEqual(self.ok("GET", "/media/published/" + v1["tracks"][0]["preview"]), preview)
        playlist = self.ok("POST", "/api/drafts", {"url": PLAYLIST})
        self.assertFalse(playlist["items"][0]["include"])
        jobs = self.ok("POST", f"/api/drafts/{playlist['id']}/process", {})
        for job in jobs:
            self.wait_job(job["id"])
        review = self.ok("GET", "/api/review")
        self.assertEqual(len(review["results"]), 3)
        for job in review["results"]:
            if not job["result"]["reject_reasons"]:
                self.ok("POST", "/api/review/" + job["item"]["id"], {"approved": True, "job": job["id"]})
        v2 = self.ok("POST", "/api/publish", {"parentVersion": 1, "confirm": True})
        self.assertEqual((v2["version"], len(v2["tracks"])), (2, 2))
        stale, result, _ = self.request("POST", "/api/publish", {"parentVersion": 1, "confirm": True})
        self.assertEqual((stale, result["error"]), (409, "stale-parent"))
        v3 = self.ok("POST", "/api/snapshots/1/rollback", {"parentVersion": 2, "confirm": True})
        self.assertEqual((v3["version"], v3["tracks"]), (3, v1["tracks"]))
        history = self.ok("GET", "/api/snapshots")
        self.assertEqual([s["version"] for s in history], [1, 2, 3])
        self.assertEqual(history[-1]["rollbackOf"], 1)
        self.assertEqual(read_json(self.root / "dest/manifest.json"), v3)
        self.assertEqual({p.name for p in (self.root / "dest").iterdir()},
                         {"manifest.json", "audio", "previews", "snapshots"})

    def test_host_csrf_content_type_and_duplicate_headers_refused(self):
        for host in ("foreign.example", "localhost", f"localhost:{self.server.server_port + 1}",
                     f"localhost.evil.example:{self.server.server_port}"):
            status, result, _ = self.request("GET", "/api/jobs", headers={"Host": host})
            self.assertEqual((status, result["error"]), (403, "invalid-host"))
        for headers in ({"Content-Type": "application/json"}, {"X-Dremla-Request": "0", "Content-Type": "application/json"}):
            status, result, _ = self.request("POST", "/api/drafts", {"url": SINGLE}, headers=headers)
            self.assertEqual((status, result["error"]), (403, "csrf-refused"))
        status, result, _ = self.request("POST", "/api/drafts", {"url": SINGLE}, headers={"X-Dremla-Request": "1", "Content-Type": "text/plain"})
        self.assertEqual((status, result["error"]), (415, "invalid-content-type"))
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        try:
            conn.putrequest("GET", "/api/jobs")
            conn.putheader("Host", f"localhost:{self.server.server_port}")
            conn.endheaders()
            response = conn.getresponse()
            self.assertEqual(response.status, 403)
            response.read()
        finally:
            conn.close()
        self.assertEqual(self.service.drafts, {})

    def test_static_ui_and_invalid_paths_json_errors(self):
        for path, marker in (("/", b"Review &amp; publish"), ("/app.js", b"X-Dremla-Request"), ("/app.css", b"color-scheme")):
            status, result, headers = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertIn(marker, result)
            self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
            self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        for path in ("/../run.py", "/media/../state/jobs.json", "/media/published/audio/%2e%2e", "/missing"):
            status, result, _ = self.request("GET", path)
            self.assertEqual(status, 404)
            self.assertEqual(set(result), {"error", "message"})
        for raw in (b'{', b'[]', b'{"url": "x", "url": "y"}', b'{"url": NaN}'):
            status, result, _ = self.request("POST", "/api/drafts", raw=raw)
            self.assertEqual(status, 400)
        with patch.object(self.service, "list_jobs", side_effect=OSError("private fixture path")):
            status, result, _ = self.request("GET", "/api/jobs")
            self.assertEqual((status, result), (500, {"error": "local-failure", "message": "Local storage or processing failed."}))

    def test_http_cancel_retry_review_and_publish_guards(self):
        self.service.acquisition.fail_once.add("fxAmbient01")
        draft = self.ok("POST", "/api/drafts", {"url": SINGLE})
        job = self.ok("POST", f"/api/drafts/{draft['id']}/process", {})[0]
        self.assertEqual(self.wait_job(job["id"])["state"], "failed")
        self.service.acquisition.delay = 0.003
        self.ok("POST", f"/api/jobs/{job['id']}/retry", {})
        self.wait_job(job["id"], ("acquiring",))
        self.ok("POST", f"/api/jobs/{job['id']}/cancel", {})
        self.assertEqual(self.wait_job(job["id"])["state"], "cancelled")
        self.service.acquisition.delay = 0
        self.ok("POST", f"/api/jobs/{job['id']}/retry", {})
        self.assertEqual(self.wait_job(job["id"])["state"], "ready")
        status, result, _ = self.request("POST", "/api/review/yt-fxAmbient01", {"inSelection": True})
        self.assertEqual((status, result["error"]), (409, "unapproved"))
        self.ok("POST", "/api/review/yt-fxAmbient01", {"approved": True, "job": job["id"]})
        status, result, _ = self.request("POST", "/api/publish", {"parentVersion": 0})
        self.assertEqual((status, result["error"]), (400, "confirmation-required"))

    def test_worker_health_and_edit_lock_in_every_job_state(self):
        draft = self.ok("POST", "/api/drafts", {"url": SINGLE})
        path = f"/api/drafts/{draft['id']}/items/yt-fxAmbient01"
        self.ok("PATCH", path, {"title": "Before processing"})
        job = self.ok("POST", f"/api/drafts/{draft['id']}/process", {})[0]
        self.wait_job(job["id"])
        health = self.ok("GET", "/api/jobs")
        self.assertIs(health["workerAlive"], True)
        self.assertEqual(health["jobs"], self.service.list_jobs())
        self.service.close()
        for state in ("queued", "acquiring", "processing", "ready", "failed", "cancelled", "interrupted"):
            with self.subTest(state=state):
                self.service.jobs[job["id"]]["state"] = state
                for changes in ({"title": "Too late"}, {"category": "piano"}, {"include": False}):
                    status, result, _ = self.request("PATCH", path, changes)
                    self.assertEqual((status, result["error"]), (409, "job-exists"))
                locked = self.ok("GET", "/api/drafts/" + draft["id"])["items"][0]
                self.assertTrue(locked["editLocked"])
                self.assertEqual(locked["title"], "Before processing")
        self.assertIs(self.ok("GET", "/api/jobs")["workerAlive"], False)
        fresh = self.ok("POST", "/api/drafts", {"url": SINGLE})
        self.ok("PATCH", f"/api/drafts/{fresh['id']}/items/yt-fxAmbient01", {"title": "New draft"})

    def test_surrogate_requests_rejected_without_state_change(self):
        draft = self.ok("POST", "/api/drafts", {"url": SINGLE})
        before = {name: (self.root / "state" / name).read_bytes() for name in ("drafts.json", "jobs.json")}
        for body in ({"title": "Lantern \ud800"}, {"\udfff": "value"}):
            status, result, _ = self.request("PATCH", f"/api/drafts/{draft['id']}/items/yt-fxAmbient01", body)
            self.assertEqual((status, result["error"]), (400, "invalid-request"))
        status, result, _ = self.request("POST", "/api/drafts", {"url": SINGLE + "\ud800"})
        self.assertEqual((status, result["error"]), (400, "invalid-request"))
        self.assertEqual(self.ok("GET", "/api/drafts/" + draft["id"]), draft)
        for name, data in before.items():
            self.assertEqual((self.root / "state" / name).read_bytes(), data)
        job = self.ok("POST", f"/api/drafts/{draft['id']}/process", {})[0]
        self.assertEqual(self.wait_job(job["id"])["state"], "ready")


class StartupTests(ServiceCase):
    def run_entrypoint(self, **env):
        root = Path(__file__).resolve().parents[2]
        environment = dict(os.environ, DREMLA_COLLECTION_DATA=str(self.root / "other-state"),
                           DREMLA_COLLECTION_DEST=str(self.root / "other-dest"), **env)
        return subprocess.run(["bash", str(root / "scripts/collection-service.sh")], env=environment,
                              capture_output=True, text=True, timeout=10)

    def test_unknown_acquisition_fails_with_contract_message(self):
        result = self.run_entrypoint(DREMLA_COLLECTION_ACQUISITION="live")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("live acquisition is not implemented (separate decision)", result.stderr)

    def test_invalid_base_refuses_start(self):
        destination = self.root / "other-dest"
        destination.mkdir()
        (destination / "manifest.json").write_text('{"version":1}')
        result = self.run_entrypoint()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid collection/state", result.stderr)
        self.assertNotIn(str(self.root), result.stderr)
