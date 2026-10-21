"""Optional catalog delivery to one public URL, against a fake origin on an ephemeral loopback port."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import socket
import threading

from dremla_collection.common import ServiceError
from dremla_collection.delivery import CatalogDelivery, check_url, from_environment
from dremla_collection.fixtures import SINGLE
from dremla_collection.server import CollectionHTTPServer
from support import ServiceCase

TOKEN = "fixture-token-not-a-secret"
PATH = "/t/dremla/catalog/current.json"


class FakeOrigin:
    """The publication origin: anonymous GET, bearer PUT; failures are injected per test."""

    def __init__(self):
        self.content, self.fail_put, self.serve_instead, self.on_get = None, None, None, None
        self.calls = []
        origin = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                origin.calls.append(("GET", self.headers.get("Authorization")))
                if origin.on_get:
                    origin.on_get()
                body = origin.serve_instead if origin.serve_instead is not None else origin.content
                if self.path != PATH or body is None:
                    self.send_response(404); self.end_headers(); return
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(body)

            def do_PUT(self):
                origin.calls.append(("PUT", self.headers.get("Authorization"), self.headers.get("private"),
                                     self.headers.get("expire")))
                body = self.rfile.read(int(self.headers["Content-Length"]))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    self.send_response(401); self.end_headers(); return
                if origin.fail_put:
                    self.send_response(origin.fail_put); self.end_headers(); return
                origin.content = body
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(b'{"url":"x","short":null,"expires_at":null,"private":false}')

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}{PATH}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class GatedLock:
    """The delivery lock, held by the test; `waiting` is set once a delivery tries to take it."""

    def __init__(self):
        self.inner, self.waiting = threading.Lock(), threading.Event()

    def __enter__(self):
        self.waiting.set()
        self.inner.acquire()

    def __exit__(self, *exc):
        self.inner.release()


def in_thread(call):
    outcome = {}

    def run():
        try:
            outcome["value"] = call()
        except ServiceError as error:
            outcome["error"] = error
    thread = threading.Thread(target=run)
    thread.start()
    return thread, outcome


class CatalogDeliveryTests(ServiceCase):
    def setUp(self):
        super().setUp()
        self.origin = FakeOrigin()
        self.addCleanup(self.origin.close)
        self.token = self.root / "delivery-token"
        self.token.write_text(TOKEN + "\n")

    def with_delivery(self, url=None, token=None):
        self.service.close()
        delivery = CatalogDelivery(self.root / "state", url or self.origin.url, token or self.token, timeout=5)
        self.service = self.open_service(start_worker=False, catalog_delivery=delivery)
        return self.service.catalog

    def populate(self, catalog, title=None):
        if not catalog.draft["tracks"]:
            catalog.add_track({"url": SINGLE})
            catalog.add_playlist(dict(id="night", title="Night", trackIds=["yt-fxAmbient01"]))
            catalog.set_field("active", "night")
        if title:
            catalog.edit_track("yt-fxAmbient01", {"title": title})

    def publish(self, catalog):
        view = catalog.view()
        return catalog.dispatch("POST", ["publish"], {"parentVersion": view["currentVersion"], "confirm": True,
                                                       "draftRevision": view["draftRevision"]})

    def current_bytes(self, catalog):
        return (catalog.root / "current.json").read_bytes()

    def test_without_configuration_publishing_stays_local_only(self):
        catalog = self.service.catalog
        self.populate(catalog)
        self.assertEqual(self.publish(catalog)["version"], 1)
        self.assertEqual(catalog.view()["delivery"], {"configured": False})
        self.assertEqual(self.origin.calls, [])
        self.assert_code("not-found", lambda: catalog.dispatch("POST", ["delivery", "retry"], {"version": 1, "confirm": True}))

    def test_publish_delivers_exact_bytes_and_checks_an_anonymous_fetch(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.publish(catalog)
        self.assertEqual(self.origin.content, self.current_bytes(catalog))
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["version"], status["error"]), ("delivered", 1, None))
        self.assertEqual(status["url"], self.origin.url)
        methods = [c[0] for c in self.origin.calls]
        self.assertEqual(methods, ["GET", "PUT", "GET"])
        self.assertIsNone(self.origin.calls[0][1], "the pre-check must be anonymous")
        self.assertEqual(self.origin.calls[1][1:], ("Bearer " + TOKEN, "false", "never"))
        self.assertIsNone(self.origin.calls[2][1], "the verification fetch must be anonymous")
        everything = json.dumps(catalog.view()) + (self.root / "state/catalog-delivery.json").read_text()
        self.assertNotIn(TOKEN, everything)

    def test_failed_upload_keeps_the_publication_and_retry_delivers_without_a_new_version(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.origin.fail_put = 500
        self.assertEqual(self.publish(catalog)["version"], 1)
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["version"], status["error"]), ("failed", 1, "upload-http-500"))
        self.assertIsNone(self.origin.content)
        self.assertEqual(catalog.current()["version"], 1)
        self.origin.fail_put = None
        self.assert_code("confirmation-required", lambda: catalog.retry_delivery(1, None))
        view = catalog.dispatch("POST", ["delivery", "retry"], {"version": 1, "confirm": True})
        self.assertEqual((view["delivery"]["state"], view["currentVersion"]), ("delivered", 1))
        self.assertEqual(self.origin.content, self.current_bytes(catalog))
        self.assertEqual([e["version"] for e in catalog.history()], [1])

    def test_rejected_token_is_reported_without_the_secret(self):
        bad = self.root / "bad-token"
        bad.write_text("wrong-token-value")
        catalog = self.with_delivery(token=bad)
        self.populate(catalog)
        self.publish(catalog)
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["error"]), ("failed", "upload-http-401"))
        self.assertNotIn("wrong-token-value", json.dumps(catalog.view()))
        self.assertIsNone(self.origin.content)

    def test_a_newer_catalog_seen_at_the_url_is_not_overwritten(self):
        newer = json.dumps({"schema": "dremla.catalog/1", "version": 5}).encode()
        self.origin.content = newer
        catalog = self.with_delivery()
        self.populate(catalog)
        self.publish(catalog)
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["error"]), ("failed", "remote-newer"))
        self.assertEqual(self.origin.content, newer)
        self.assertNotIn("PUT", [c[0] for c in self.origin.calls])

    def test_only_the_current_version_is_delivered_and_a_newer_publication_supersedes(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.origin.fail_put = 503
        self.publish(catalog)
        self.origin.fail_put = None
        self.populate(catalog, title="Second version")
        self.assertEqual(self.publish(catalog)["version"], 2)
        self.assertEqual(json.loads(self.origin.content)["version"], 2)
        self.assert_code("stale-delivery", lambda: catalog.retry_delivery(1, True))
        self.assertEqual(json.loads(self.origin.content)["version"], 2)
        # Re-delivering the same current bytes is a no-op upload.
        before = len(self.origin.calls)
        catalog.retry_delivery(2, True)
        self.assertEqual([c[0] for c in self.origin.calls[before:]], ["GET"])

    def publish_locally(self, catalog):
        """A new current version, without its own delivery (that would wait for the held lock)."""
        self.populate(catalog, title="Second version")
        view = catalog.view()
        self.assertEqual(catalog.publish(view["currentVersion"], True, view["draftRevision"])["version"], 2)

    def failed_first_version(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.origin.fail_put = 500
        self.publish(catalog)
        self.origin.fail_put = None
        self.assertEqual(catalog.view()["delivery"]["error"], "upload-http-500")
        return catalog

    def test_a_retry_waiting_behind_another_delivery_never_uploads_a_newer_version(self):
        catalog = self.failed_first_version()
        gate = GatedLock()
        catalog.delivery.lock = gate
        gate.inner.acquire()  # another delivery is running
        before = len(self.origin.calls)
        thread, outcome = in_thread(lambda: catalog.dispatch("POST", ["delivery", "retry"],
                                                             {"version": 1, "confirm": True}))
        self.assertTrue(gate.waiting.wait(10), "the retry must reach the delivery lock")
        self.publish_locally(catalog)  # version 2 becomes current while the retry waits
        gate.inner.release()
        thread.join(10)
        self.assertEqual((outcome["error"].code, outcome["error"].status), ("stale-delivery", 409))
        self.assertEqual(self.origin.calls[before:], [], "nothing is sent on behalf of the version 1 retry")
        self.assertIsNone(self.origin.content)
        self.assertEqual(catalog.view()["delivery"]["version"], 1)
        view = catalog.dispatch("POST", ["delivery", "retry"], {"version": 2, "confirm": True})
        self.assertEqual((view["delivery"]["state"], view["delivery"]["version"]), ("delivered", 2))
        self.assertEqual(self.origin.content, self.current_bytes(catalog))

    def test_a_version_superseded_during_the_pre_check_is_not_uploaded(self):
        catalog = self.failed_first_version()
        at_origin, release = threading.Event(), threading.Event()
        self.origin.on_get = lambda: (at_origin.set(), release.wait(10))
        before = len(self.origin.calls)
        thread, outcome = in_thread(lambda: catalog.retry_delivery(1, True))
        self.assertTrue(at_origin.wait(10), "the retry's pre-check must reach the origin")
        self.origin.on_get = None
        self.publish_locally(catalog)  # version 2 becomes current during the network wait
        release.set()
        thread.join(10)
        self.assertEqual(outcome["error"].code, "stale-delivery")
        self.assertEqual([c[0] for c in self.origin.calls[before:]], ["GET"], "no upload after the pre-check")
        self.assertIsNone(self.origin.content)

    def test_mismatched_published_bytes_fail_verification(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.origin.serve_instead = None
        original_put = self.origin.server.RequestHandlerClass.do_PUT

        def put_then_serve_other(handler):
            original_put(handler)
            self.origin.serve_instead = b'{"version": 1, "other": true}'
        self.origin.server.RequestHandlerClass.do_PUT = put_then_serve_other
        self.publish(catalog)
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["error"]), ("failed", "verify-mismatch"))

    def test_unreachable_origin_is_a_network_failure(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        catalog = self.with_delivery(url=f"http://127.0.0.1:{port}{PATH}")
        self.populate(catalog)
        self.assertEqual(self.publish(catalog)["version"], 1)
        status = catalog.view()["delivery"]
        self.assertEqual((status["state"], status["error"]), ("failed", "network"))

    def test_configuration_is_validated(self):
        for url in ("http://example.org/t/x/current.json", "https://user:pw@example.org/c.json",
                    "https://example.org/t/x/current.html", "https://example.org/c.json?x=1", "ftp://example.org/c.json"):
            with self.subTest(url=url):
                self.assert_code("invalid-config", lambda: check_url(url))
        self.assertEqual(check_url("https://html.me.uk/t/dremla/catalog/current.json"),
                         "https://html.me.uk/t/dremla/catalog/current.json")
        self.assertIsNone(from_environment(self.root / "state", {}))
        self.assert_code("invalid-config", lambda: from_environment(self.root / "state",
                                                                    {"DREMLA_CATALOG_DELIVERY_URL": self.origin.url}))
        self.assert_code("invalid-config", lambda: CatalogDelivery(self.root / "state", self.origin.url,
                                                                   self.root / "absent-token"))

    def test_retry_route_uses_the_existing_write_guards(self):
        catalog = self.with_delivery()
        self.populate(catalog)
        self.origin.fail_put = 500
        self.publish(catalog)
        self.origin.fail_put = None
        server = CollectionHTTPServer(self.service, 0)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            def post(headers, body):
                conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
                try:
                    conn.request("POST", "/api/catalog/delivery/retry", json.dumps(body), headers)
                    response = conn.getresponse()
                    return response.status, json.loads(response.read())
                finally:
                    conn.close()
            self.assertEqual(post({"Content-Type": "application/json"}, {"version": 1, "confirm": True})[0], 403)
            status, result = post({"Content-Type": "application/json", "X-Dremla-Request": "1"},
                                  {"version": 1, "confirm": True, "token": "x"})
            self.assertEqual((status, result["error"]), (400, "invalid-request"))
            status, result = post({"Content-Type": "application/json", "X-Dremla-Request": "1"},
                                  {"version": 1, "confirm": True})
            self.assertEqual((status, result["delivery"]["state"]), (200, "delivered"))
            self.assertNotIn(TOKEN, json.dumps(result))
        finally:
            server.shutdown()
            thread.join()
            server.server_close()
