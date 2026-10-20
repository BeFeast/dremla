"""Loopback-only HTTP API and self-contained authoring UI."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import mimetypes
import os
from pathlib import Path
import re
import shutil
import signal
import threading
import sys
import time
from urllib.parse import unquote, urlsplit

from .adapters import FixtureAcquisition, FixtureProcessor
from .common import ServiceError, json_bytes, parse_json, require, require_utf8
from .ffmpeg import FFmpegProcessor
from .jobs import CollectionService

STATIC = Path(__file__).with_name("static")


class CollectionHTTPServer(ThreadingHTTPServer):
    daemon_threads = False

    def __init__(self, service, port=8765):
        self.service = service
        super().__init__(("127.0.0.1", port), Handler)


class Handler(BaseHTTPRequestHandler):
    # No request paths, operator data or exception text in access logs.
    def log_message(self, *args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(1)

    def send_error(self, code, message=None, explain=None):
        self.send_json({"error": "invalid-request", "message": "Unsupported or malformed HTTP request."}, code)

    def headers_for(self, status, content_type, length, extra=()):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        for name, value in extra:
            self.send_header(name, value)
        self.end_headers()

    def send_json(self, value, status=200):
        data = json_bytes(value)
        self.headers_for(status, "application/json; charset=utf-8", len(data))
        self.wfile.write(data)

    def send_file(self, path):
        require(path.is_file(), "not-found", "File not found.", 404)
        size = path.stat().st_size
        start, end, status = 0, size - 1, 200
        extra = [("Accept-Ranges", "bytes")]
        if self.headers.get("Range"):
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers["Range"])
            require(match is not None, "invalid-range", "Use a single byte range.", 416)
            start = int(match[1])
            end = min(size - 1, int(match[2])) if match[2] else size - 1
            require(0 <= start <= end < size, "invalid-range", "Range is outside the file.", 416)
            status = 206
            extra.append(("Content-Range", f"bytes {start}-{end}/{size}"))
        media_type = {".opus": "audio/ogg", ".wav": "audio/wav", ".mp3": "audio/mpeg"}.get(path.suffix)
        self.headers_for(status, media_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream", end - start + 1, extra)
        with path.open("rb") as source:
            source.seek(start)
            remaining = end - start + 1
            while remaining:
                block = source.read(min(65536, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)

    def guard(self, changing):
        port = self.server.server_port
        hosts = self.headers.get_all("Host", [])
        allowed = {f"localhost:{port}", f"127.0.0.1:{port}", f"[::1]:{port}"}
        require(len(hosts) == 1 and hosts[0].lower() in allowed, "invalid-host", "Use the configured loopback host and port.", 403)
        if changing:
            require(self.headers.get_all("X-Dremla-Request", []) == ["1"], "csrf-refused", "X-Dremla-Request: 1 is required.", 403)
            require(self.headers.get_all("Content-Type", []) == ["application/json"],
                    "invalid-content-type", "Use application/json.", 415)
            require(not self.headers.get_all("Transfer-Encoding"), "invalid-request", "Transfer encoding is not supported.")
            lengths = self.headers.get_all("Content-Length", [])
            require(len(lengths) == 1 and re.fullmatch(r"[0-9]{1,7}", lengths[0]) and int(lengths[0]) <= 65536,
                    "invalid-request", "A JSON body up to 65536 bytes is required.")
            body = parse_json(self.rfile.read(int(lengths[0])))
            require(isinstance(body, dict), "invalid-request", "JSON body must be an object.")
            require_utf8(body)
            return body
        return {}

    def dispatch(self):
        try:
            body = self.guard(self.command != "GET")
            path = unquote(urlsplit(self.path).path)
            parts = path.strip("/").split("/")
            service = self.server.service
            if parts[:2] == ["api", "catalog"]:
                self.send_json(service.catalog.dispatch(self.command, parts[2:], body))
                return
            if self.command == "GET" and len(parts) == 2 and parts[0] == "catalog":
                self.send_json(service.catalog.exported(parts[1]))
                return
            if self.command == "GET":
                if path in ("/", "/app.js", "/app.css", "/catalog", "/catalog.js"):
                    self.send_file(STATIC / {"/": "index.html", "/catalog": "catalog.html"}.get(path, path[1:]))
                    return
                if parts[0] == "media":
                    self.send_file(service.media(parts[1:]))
                    return
                if parts == ["api", "drafts"]:
                    with service.lock:
                        result = list(service.drafts.values())
                elif parts[:2] == ["api", "drafts"] and len(parts) == 3:
                    result = service.draft(parts[2])
                elif parts == ["api", "jobs"]:
                    result = dict(jobs=service.list_jobs(), workerAlive=bool(service.worker and service.worker.is_alive()))
                elif parts == ["api", "review"]:
                    result = service.review()
                elif parts == ["api", "snapshots"]:
                    result = service.storage.history()
                else:
                    raise ServiceError("not-found", "Route not found.", 404)
            elif self.command == "POST":
                if parts == ["api", "drafts"]:
                    result = service.add_draft(body.get("url"))
                elif parts[:2] == ["api", "drafts"] and len(parts) == 4 and parts[3] == "process":
                    result = service.process_draft(parts[2])
                elif parts[:2] == ["api", "jobs"] and len(parts) == 4 and parts[3] in ("cancel", "retry"):
                    result = service.control(parts[2], parts[3])
                elif parts[:2] == ["api", "review"] and len(parts) == 3:
                    result = service.set_review(parts[2], body)
                elif parts == ["api", "publish"]:
                    require({"parentVersion", "proposalRevision"} <= set(body) <=
                            {"parentVersion", "proposalRevision", "confirm"},
                            "invalid-request", "Missing or unexpected request fields.")
                    result = service.publish(body.get("parentVersion"), body.get("confirm"), body.get("proposalRevision"))
                elif parts == ["api", "publish", "pending", "complete"]:
                    require(set(body) <= {"sha256", "confirm"}, "invalid-request", "Unexpected request fields.")
                    result = service.complete_pending(body.get("sha256"), body.get("confirm"))
                elif parts[:2] == ["api", "snapshots"] and len(parts) == 4 and parts[3] == "rollback" and parts[2].isdigit():
                    result = service.rollback(int(parts[2]), body.get("parentVersion"), body.get("confirm"))
                else:
                    raise ServiceError("not-found", "Route not found.", 404)
            elif self.command == "PATCH" and parts[:2] == ["api", "drafts"] and len(parts) == 5 and parts[3] == "items":
                result = service.edit_item(parts[2], parts[4], body)
            else:
                raise ServiceError("not-found", "Route not found.", 404)
            self.send_json(result)
        except ServiceError as error:
            self.send_json({"error": error.code, "message": error.message}, error.status)
        except (ValueError, UnicodeError):
            self.send_json({"error": "invalid-json", "message": "Invalid JSON request or saved data."}, 400)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception:
            self.send_json({"error": "local-failure", "message": "Local storage or processing failed."}, 500)

    do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = dispatch


def main():
    require(len(sys.argv) == 1, "invalid-config", "The service takes no arguments.")
    acquisition = os.environ.get("DREMLA_COLLECTION_ACQUISITION", "fixture")
    require(acquisition == "fixture", "invalid-config", "live acquisition is not implemented (separate decision)")
    processor = os.environ.get("DREMLA_COLLECTION_PROCESSOR", "fixture")
    require(processor in ("fixture", "ffmpeg"), "invalid-config", "Processor must be fixture or ffmpeg.")
    if processor == "ffmpeg":
        require(shutil.which("ffmpeg") and shutil.which("ffprobe"), "invalid-config", "ffmpeg and ffprobe are required.")
    port = os.environ.get("DREMLA_COLLECTION_PORT", "8765")
    require(re.fullmatch(r"\d{1,5}", port) and 0 <= int(port) <= 65535, "invalid-config", "Invalid loopback port.")
    data = os.environ.get("DREMLA_COLLECTION_DATA", ".collection-service/state")
    destination = os.environ.get("DREMLA_COLLECTION_DEST", ".collection-service/published")
    catalog_destination = os.environ.get("DREMLA_CATALOG_DEST", ".collection-service/catalog")
    try:
        service = CollectionService(data, destination, FixtureAcquisition(),
                                    FFmpegProcessor() if processor == "ffmpeg" else FixtureProcessor(),
                                    catalog_destination=catalog_destination)
    except (OSError, ValueError, KeyError, TypeError):
        raise ServiceError("invalid-base", "Cannot start: invalid collection/state, unavailable directory, or directory already in use.") from None
    try:
        with CollectionHTTPServer(service, int(port)) as server:
            # The handler only records the signal: it must not take a lock the interrupted main
            # thread may hold (threading.Event.set would).
            stop = []
            previous = {sig: signal.signal(sig, lambda signum, _frame: stop.append(signum))
                        for sig in (signal.SIGTERM, signal.SIGHUP)}
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            print(f"Dremla collection: http://127.0.0.1:{server.server_port}/", flush=True)
            try:
                # CPython runs signal handlers only on the main thread, but the kernel may deliver
                # a process-directed SIGTERM/SIGHUP to any thread. An untimed wait would then never
                # wake to run the handler (#53), so poll in short sleeps.
                while not stop:
                    time.sleep(0.2)
            except KeyboardInterrupt:
                pass
            finally:
                server.shutdown()
                thread.join()
                service.close()
                for sig, handler in previous.items():
                    signal.signal(sig, handler)
    finally:
        service.close()
