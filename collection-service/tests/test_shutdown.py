"""Signal the real entrypoint while it owns a long-running real ffmpeg child."""
import http.client
import json
import os
from pathlib import Path
import selectors
import shlex
import shutil
import signal
import socket
import subprocess
import time
import unittest

from dremla_collection.common import read_json
from dremla_collection.fixtures import SINGLE
from support import ServiceCase


@unittest.skipUnless(shutil.which("ffmpeg"), "SKIPPED (no ffmpeg)")
class ShutdownTests(ServiceCase):
    def start(self, env):
        script = Path(__file__).resolve().parents[2] / "scripts/collection-service.sh"
        process = subprocess.Popen([str(script)], env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, text=True)
        self.addCleanup(self.stop, process)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(10), "Entrypoint did not start")
        line = process.stdout.readline().strip()
        self.assertTrue(line.startswith("Dremla collection: http://127.0.0.1:"), line)
        self.port = int(line.rsplit(":", 1)[1].rstrip("/"))
        return process

    def stop(self, process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        process.stdout.close()

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request(method, "/api/" + path, None if body is None else json.dumps(body),
                         {"Content-Type": "application/json", "X-Dremla-Request": "1"})
            response = conn.getresponse()
            result = json.loads(response.read())
            self.assertEqual(response.status, 200, result)
            return result
        finally:
            conn.close()

    def test_signals_stop_child_and_preserve_retriable_interrupted_job(self):
        for sig in (signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signal=sig):
                work = self.root / sig.name
                binary = work / "bin"
                binary.mkdir(parents=True)
                marker = work / "child-pid"
                wrapper = binary / "ffmpeg"
                # Only the first invocation is prolonged. Retry executes the normal processor.
                wrapper.write_text("#!/bin/sh\nif [ ! -e " + shlex.quote(str(marker)) + " ]; then\n"
                                   "  echo $$ > " + shlex.quote(str(marker)) + "\n"
                                   "  exec " + shlex.quote(shutil.which("ffmpeg")) +
                                   " -nostdin -re -f lavfi -i sine=frequency=220 -t 120 -f null -\nfi\n"
                                   "exec " + shlex.quote(shutil.which("ffmpeg")) + ' "$@"\n')
                wrapper.chmod(0o755)
                env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                           DREMLA_COLLECTION_DATA=str(work / "state"),
                           DREMLA_COLLECTION_DEST=str(work / "dest"), DREMLA_COLLECTION_PORT="0",
                           DREMLA_COLLECTION_ACQUISITION="fixture", DREMLA_COLLECTION_PROCESSOR="ffmpeg")
                process = self.start(env)
                draft = self.request("POST", "drafts", {"url": SINGLE})
                job = self.request("POST", f"drafts/{draft['id']}/process", {})[0]
                deadline = time.monotonic() + 10
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(marker.exists(), "No ffmpeg child started")
                child = int(marker.read_text())
                try:
                    # An idle browser connection must not delay cancellation or exit.
                    with socket.create_connection(("127.0.0.1", self.port), timeout=5):
                        started = time.monotonic()
                        process.send_signal(sig)
                        self.assertEqual(process.wait(timeout=9), 0)
                        self.assertLess(time.monotonic() - started, 10)
                    with self.assertRaises(ProcessLookupError):
                        os.kill(child, 0)
                    saved = read_json(work / "state/jobs.json")[job["id"]]
                    self.assertEqual(saved["state"], "interrupted")
                    restarted = self.start(env)
                    self.assertEqual(self.request("GET", "jobs")["jobs"][0]["state"], "interrupted")
                    self.request("POST", f"jobs/{job['id']}/retry", {})
                    deadline = time.monotonic() + 60
                    while time.monotonic() < deadline:
                        result = self.request("GET", "jobs")["jobs"][0]
                        if result["state"] not in ("queued", "acquiring", "processing"):
                            break
                        time.sleep(0.05)
                    self.assertEqual((result["state"], result["attempt"]), ("ready", 2))
                    self.stop(restarted)
                finally:
                    try:
                        os.kill(child, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
