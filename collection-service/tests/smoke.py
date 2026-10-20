#!/usr/bin/env python3
"""Real-entrypoint fixture smoke, including publication and rollback, in temporary directories.

Run only on a media-processing host. DREMLA_COLLECTION_PROCESSOR selects fixture or ffmpeg.
"""
import http.client
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "collection-service"))
from dremla_collection.fixtures import SINGLE, PLAYLIST
from dremla_collection.ffmpeg import analyze, duration
from dremla_collection.validate import validate


def main():
    with tempfile.TemporaryDirectory(prefix="dremla-collection-smoke-") as directory:
        work = Path(directory)
        env = dict(os.environ, DREMLA_COLLECTION_DATA=str(work / "state"), DREMLA_COLLECTION_DEST=str(work / "published"),
                   DREMLA_COLLECTION_PORT="0", DREMLA_COLLECTION_ACQUISITION="fixture")
        with (work / "server-errors").open("w+") as errors:
            process = subprocess.Popen([str(ROOT / "scripts/collection-service.sh")], env=env,
                                       stdout=subprocess.PIPE, stderr=errors, text=True)
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    assert selector.select(timeout=10), "server did not announce its loopback URL"
                    line = process.stdout.readline().strip()
                match = re.fullmatch(r"Dremla collection: http://127\.0\.0\.1:(\d+)/", line)
                assert match, "server startup failed"
                port = int(match[1])
                print("Processor:", env.get("DREMLA_COLLECTION_PROCESSOR", "fixture"), flush=True)
                print(line, flush=True)
                def request(method, path, body=None):
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
                    try:
                        conn.request(method, "/api/" + path, None if body is None else json.dumps(body),
                                     {"Content-Type": "application/json", "X-Dremla-Request": "1"})
                        response = conn.getresponse()
                        payload = json.loads(response.read())
                        assert response.status == 200, payload
                        return payload
                    finally:
                        conn.close()
                def add_process_approve(url):
                    draft = request("POST", "drafts", {"url": url})
                    jobs = request("POST", f"drafts/{draft['id']}/process", {})
                    wanted = {j["id"] for j in jobs}
                    deadline = time.monotonic() + 120
                    while time.monotonic() < deadline:
                        jobs = [j for j in request("GET", "jobs")["jobs"] if j["id"] in wanted]
                        if all(j["state"] not in ("queued", "acquiring", "processing") for j in jobs):
                            break
                        time.sleep(0.1)
                    assert all(j["state"] in ("ready", "failed") for j in jobs), "processing timeout"
                    for job in jobs:
                        result = job.get("result")
                        print(job["item"]["id"], job["state"], result["reject_reasons"] if result else job["error"], flush=True)
                        if result and not result["reject_reasons"]:
                            request("POST", "review/" + job["item"]["id"], {"approved": True, "job": job["id"]})
                add_process_approve(SINGLE)
                v1 = request("POST", "publish", {"parentVersion": 0, "confirm": True})
                add_process_approve(PLAYLIST)
                v2 = request("POST", "publish", {"parentVersion": 1, "confirm": True})
                v3 = request("POST", "snapshots/1/rollback", {"parentVersion": 2, "confirm": True})
                assert [v1["version"], v2["version"], v3["version"]] == [1, 2, 3]
                assert [len(v["tracks"]) for v in (v1, v2, v3)] == [1, 2, 1]
                assert v1["tracks"] == v3["tracks"] and v1["selection"] == v3["selection"]
                assert request("GET", "snapshots")[-1]["rollbackOf"] == 1
                dest = work / "published"
                validate(v3, dest)
                print("Versions: 1 (1 track), 2 (2 tracks), 3 (rollbackOf 1; 1 track)", flush=True)
                print("Destination tree:")
                for path in sorted(dest.rglob("*")):
                    if path.is_file():
                        print(f"  {path.relative_to(dest)} ({path.stat().st_size} bytes)")
                print("Current manifest:")
                print((dest / "manifest.json").read_text())
                if env.get("DREMLA_COLLECTION_PROCESSOR") == "ffmpeg":
                    for track in v2["tracks"]:
                        measured = analyze(dest / track["file"], threading.Event())
                        preview_duration = duration(dest / track["preview"], threading.Event())
                        assert abs(measured["i"] + 24) <= 1
                        assert preview_duration <= 61
                        print(f"Measured {track['id']}: {measured['i']} LUFS; LRA {measured['lra']} LU; true peak {measured['tp']} dBTP; preview {preview_duration:.3f} s")
                print("SMOKE PASS", flush=True)
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                process.stdout.close()


if __name__ == "__main__":
    main()
