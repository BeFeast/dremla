"""Signal the real entrypoint while it owns a long-running real ffmpeg child."""
import ctypes
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

# The documented stop grace (docs/collection-service.md: "Allow at least 20 seconds to stop").
STOP_GRACE_S = 20


def children(pid):
    """Direct children as 'pid comm state' strings, for failure diagnostics."""
    found = []
    for stat in Path("/proc").glob("[0-9]*/stat"):
        try:
            fields = stat.read_text().rsplit(")", 1)
            comm, rest = fields[0].split("(", 1)[1], fields[1].split()
        except (OSError, IndexError):
            continue
        if int(rest[1]) == pid:
            found.append(f"{stat.parent.name} {comm} {rest[0]}")
    return found


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
        if not process.stdout.closed:
            process.stdout.close()

    def wait_stopped(self, process, child, started):
        """Wait up to the documented grace; on timeout report what was still running, then fail."""
        try:
            return process.wait(timeout=STOP_GRACE_S)
        except subprocess.TimeoutExpired:
            elapsed = time.monotonic() - started
            tree = children(process.pid)
            child_state = "n/a"
            if child is not None:
                try:
                    os.kill(child, 0)
                    child_state = "alive"
                except ProcessLookupError:
                    child_state = "gone"
            self.stop(process)
            self.fail(f"entrypoint did not exit {elapsed:.1f} s after the signal "
                      f"(grace {STOP_GRACE_S} s); ffmpeg child {child} {child_state}; children: {tree}")

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

    @unittest.skipUnless(hasattr(ctypes.CDLL(None), "tgkill") and Path("/proc/self/task").is_dir(),
                         "needs Linux tgkill")
    def test_signal_delivered_to_any_thread_stops_the_service(self):
        """A process-directed signal can land on any thread; the service must still stop (#53)."""
        libc = ctypes.CDLL(None, use_errno=True)
        for sig in (signal.SIGTERM, signal.SIGHUP):
            for position in (0, -1):
                with self.subTest(signal=sig, thread=position):
                    work = self.root / f"tgkill-{sig.name}-{position}"
                    env = dict(os.environ, DREMLA_COLLECTION_DATA=str(work / "state"),
                               DREMLA_COLLECTION_DEST=str(work / "dest"),
                               DREMLA_CATALOG_DEST=str(work / "catalog"), DREMLA_COLLECTION_PORT="0")
                    process = self.start(env)
                    try:
                        threads = sorted(int(t) for t in os.listdir(f"/proc/{process.pid}/task")
                                         if int(t) != process.pid)
                        self.assertTrue(threads, "service has no worker or server thread")
                        target = threads[position]
                        # Let the main thread settle into its wait, so delivery to another thread
                        # exercises the wake-up path rather than racing the startup.
                        time.sleep(0.5)
                        self.assertEqual(libc.tgkill(process.pid, target, int(sig)), 0)
                        started = time.monotonic()
                        self.assertEqual(self.wait_stopped(process, None, started), 0)
                    finally:
                        self.stop(process)

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
                # Each run owns all three locked directories; the catalog default is shared
                # (repo-relative), so a slow exit would otherwise block the next start.
                env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                           DREMLA_COLLECTION_DATA=str(work / "state"),
                           DREMLA_COLLECTION_DEST=str(work / "dest"),
                           DREMLA_CATALOG_DEST=str(work / "catalog"), DREMLA_COLLECTION_PORT="0",
                           DREMLA_COLLECTION_ACQUISITION="fixture", DREMLA_COLLECTION_PROCESSOR="ffmpeg")
                process = self.start(env)
                restarted = child = None
                try:
                    draft = self.request("POST", "drafts", {"url": SINGLE})
                    job = self.request("POST", f"drafts/{draft['id']}/process", {})[0]
                    deadline = time.monotonic() + 10
                    while not marker.exists() and time.monotonic() < deadline:
                        time.sleep(0.02)
                    self.assertTrue(marker.exists(), "No ffmpeg child started")
                    child = int(marker.read_text())
                    # An idle browser connection must not delay cancellation or exit.
                    with socket.create_connection(("127.0.0.1", self.port), timeout=5):
                        started = time.monotonic()
                        process.send_signal(sig)
                        self.assertEqual(self.wait_stopped(process, child, started), 0)
                        self.assertLess(time.monotonic() - started, STOP_GRACE_S)
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
                    # Stop this run's processes before the next subtest starts, also after a failure.
                    if restarted is not None:
                        self.stop(restarted)
                    self.stop(process)
                    if child is not None:
                        try:
                            os.kill(child, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
