"""Optional delivery of the current published catalog to one configured public URL.

Without configuration the catalog stays local-only. With it, a publish (or an explicit retry)
uploads the exact bytes of that published version with an existing bearer token read from a
server-side file, then checks an anonymous fetch returns the same bytes. Deliveries run one at a
time and upload a version only while it is still current. A newer catalog seen at the URL is not
overwritten, but that check is not atomic with the upload: the service must be the URL's only writer.
"""
from datetime import datetime, timezone
import hashlib
import http.client
import json
from pathlib import Path
import threading
from urllib.parse import urlsplit

from .common import ServiceError, atomic_write, json_bytes, read_json, require

LOOPBACK = {"127.0.0.1", "localhost", "::1"}
TIMEOUT = 15
MAX_BYTES = 2 * 1024 * 1024


def check_url(url):
    """HTTPS with a path and no credentials; plain HTTP only to loopback (local test targets)."""
    parts = urlsplit(url)
    ok = (parts.scheme == "https" or parts.scheme == "http" and parts.hostname in LOOPBACK) and parts.hostname \
        and parts.path.endswith(".json") and not parts.username and not parts.password and not parts.query \
        and not parts.fragment
    require(ok, "invalid-config", "Catalog delivery URL must be https://<host>/<path>.json.")
    return url


class CatalogDelivery:
    def __init__(self, data, url, token_file, timeout=TIMEOUT):
        self.url = check_url(url)
        self.token_file = Path(token_file)
        require(self.token_file.is_file(), "invalid-config", "Catalog delivery token file is missing.")
        self.state_path = Path(data) / "catalog-delivery.json"
        self.timeout = timeout
        self.lock = threading.Lock()  # deliveries run one at a time, in publish order

    # ---- state ----

    def status(self):
        state = read_json(self.state_path) if self.state_path.exists() else {}
        if state.get("url") != self.url:
            state = {}
        return dict(configured=True, url=self.url, version=state.get("version"), sha256=state.get("sha256"),
                    state=state.get("state", "not-delivered"), error=state.get("error"), at=state.get("at"))

    def record(self, version, sha, state, error=None):
        at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        atomic_write(self.state_path, json_bytes(dict(url=self.url, version=version, sha256=sha, state=state,
                                                      error=error, at=at)))

    # ---- transport ----

    def request(self, method, body=None, auth=False):
        parts = urlsplit(self.url)
        connection = (http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection)(
            parts.hostname, parts.port, timeout=self.timeout)
        headers = {}
        if body is not None:
            headers.update({"Content-Type": "application/json; charset=utf-8", "private": "false", "expire": "never"})
        if auth:
            token = self.token_file.read_text().strip()
            require(token, "delivery-failed", "Catalog delivery token is empty.")
            headers["Authorization"] = "Bearer " + token
        try:
            connection.request(method, parts.path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read(MAX_BYTES + 1)
            return response.status, data
        finally:
            connection.close()

    def remote(self):
        """(status, bytes) of an anonymous fetch; redirects are not followed."""
        return self.request("GET")

    # ---- delivery ----

    def deliver(self, current, expected):
        """Deliver version `expected` while it is current; `current()` returns (version, raw bytes) or None.

        Returns the status, or None when `expected` is not current (checked under the lock and again
        just before the upload): nothing is sent for it, and a newer publication delivers itself."""
        with self.lock:
            record = current()
            if record is None or record[0] != expected:
                return None
            version, raw = record
            sha = hashlib.sha256(raw).hexdigest()
            try:
                code, existing = self.remote()
                if code == 200:
                    remote_version = None
                    try:
                        remote_version = json.loads(existing.decode("utf-8")).get("version")
                    except (ValueError, AttributeError, UnicodeDecodeError):
                        pass
                    if type(remote_version) is int and remote_version > version:
                        self.record(version, sha, "failed", "remote-newer")
                        return self.status()
                    if existing == raw:
                        self.record(version, sha, "delivered")
                        return self.status()
                elif code != 404:
                    self.record(version, sha, "failed", f"remote-check-http-{code}")
                    return self.status()
                # The pre-check took network time; never upload a version that is no longer current.
                again = current()
                if again is None or again[0] != version:
                    return None
                code, _ = self.request("PUT", body=raw, auth=True)
                if not 200 <= code < 300:
                    self.record(version, sha, "failed", f"upload-http-{code}")
                    return self.status()
                code, published = self.remote()
                if code != 200:
                    self.record(version, sha, "failed", f"verify-http-{code}")
                elif published != raw:
                    self.record(version, sha, "failed", "verify-mismatch")
                else:
                    self.record(version, sha, "delivered")
            except ServiceError as error:
                self.record(version, sha, "failed", error.code)
            except (OSError, http.client.HTTPException):
                # Never surface transport details: they may include the target's response text.
                self.record(version, sha, "failed", "network")
            return self.status()


def from_environment(data, environ):
    url = environ.get("DREMLA_CATALOG_DELIVERY_URL")
    token_file = environ.get("DREMLA_CATALOG_DELIVERY_TOKEN_FILE")
    if not url and not token_file:
        return None
    require(url and token_file, "invalid-config",
            "Set both DREMLA_CATALOG_DELIVERY_URL and DREMLA_CATALOG_DELIVERY_TOKEN_FILE, or neither.")
    return CatalogDelivery(data, url, token_file)


__all__ = ["CatalogDelivery", "check_url", "from_environment", "ServiceError"]
