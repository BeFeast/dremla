#!/usr/bin/env python3
"""Host-side Docker assertions, called only by test-collection-image.sh.

The shell owns all named resources and cleanup, including on interruption.
HTTP requests execute inside the isolated container's loopback namespace.
"""
import json
from pathlib import Path
import subprocess
import sys
import time

IMAGE, REVISION, PREFIX, SCRATCH = sys.argv[1:]
TESTS = Path(__file__).resolve().parent


def docker(*args, timeout=30, check=True):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise AssertionError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result


def inside(name, code, *args, timeout=30):
    return docker("exec", name, "python3", "-I", "-c", code, *args, timeout=timeout).stdout.strip()


def data_mounts(mode):
    mounts = []
    for part in ("state", "published"):
        volume = f"{PREFIX}-{mode}-{part}"
        docker("volume", "create", "--label", f"dremla.image-test={PREFIX}", volume)
        mounts += ["--mount", f"type=volume,src={volume},dst=/data/{part}"]
    docker("run", "--name", f"{PREFIX}-{mode}-setup", "--network", "none", "--read-only",
           "--label", f"dremla.image-test={PREFIX}", "--cpu-shares", "2", "--cpus", "2",
           "--user", "0:0", *mounts, IMAGE, "chown", "1000:1000", "/data/state", "/data/published")
    docker("rm", f"{PREFIX}-{mode}-setup")
    return mounts


def run_flags(mode):
    return ["--name", f"{PREFIX}-{mode}-service", "--read-only", "--tmpfs", "/tmp",
            "--user", "1000:1000", "--network", "none", "--cpus", "2", "--cpu-shares", "2",
            "--label", f"dremla.image-test={PREFIX}", *data_mounts(mode)]


HTTP = '''
import http.client, json, sys, time
def request(method, path, body=None):
    conn = http.client.HTTPConnection("127.0.0.1", 8765, timeout=5)
    try:
        conn.request(method, path, None if body is None else json.dumps(body),
                     {"Host": "127.0.0.1:8765", "Content-Type": "application/json",
                      "X-Dremla-Request": "1"})
        response = conn.getresponse()
        data = response.read()
        assert response.status == 200, (response.status, data)
        return json.loads(data) if path.startswith("/api/") else data
    finally:
        conn.close()
'''


def health(name):
    inside(name, HTTP + '''
deadline = time.monotonic() + 15
while True:
    try:
        assert request("GET", "/")
        assert request("GET", "/api/jobs")["workerAlive"] is True
        break
    except (ConnectionError, TimeoutError):
        assert time.monotonic() < deadline, "startup timeout"
        time.sleep(0.1)
''')


def top(name):
    rows = docker("top", name, "-eo", "pid,comm").stdout.splitlines()[1:]
    return [(int(pid), comm) for pid, comm in (row.split(maxsplit=1) for row in rows)]


def process_identity(pid):
    try:
        # starttime distinguishes a leaked child from an unrelated reused PID.
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
    except FileNotFoundError:
        return None


def root_unchanged(name):
    changes = set(docker("diff", name).stdout.splitlines())
    if docker("inspect", "--format", "{{.HostConfig.Init}}", name).stdout.strip() == "true":
        # Docker injects tini before applying the read-only root. On merged-/usr
        # images its mountpoint appears under /usr/sbin; older engines use /sbin.
        # Only that engine-created file and its parent directory metadata may differ.
        changes -= {"C /usr", "C /usr/sbin", "A /usr/sbin/docker-init",
                    "C /sbin", "A /sbin/docker-init"}
    assert not changes, f"writes outside data mounts/tmpfs: {sorted(changes)}"


def lifecycle(mode):
    name = f"{PREFIX}-{mode}-service"
    flags = run_flags(mode)
    if mode == "init":
        flags += ["--init"]
    docker("run", "-d", *flags, IMAGE)
    health(name)
    print(f"PASS health ({mode}): / and /api/jobs 200, workerAlive=true", flush=True)
    # Check the real PID-1 layout, rather than trusting the requested flags.
    comm = inside(name, 'from pathlib import Path; print(Path("/proc/1/comm").read_text().strip())')
    assert ("init" in comm) if mode == "init" else comm == "python3", comm
    inside(name, HTTP + '''
draft = request("POST", "/api/drafts", {"url": "https://www.youtube.com/playlist?list=PLFixtureCalm01"})
request("POST", "/api/drafts/" + draft["id"] + "/process", {})
''')
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        active = json.loads(inside(name, HTTP + '''
print(json.dumps([j["id"] for j in request("GET", "/api/jobs")["jobs"] if j["state"] == "processing"]))
'''))
        processes = top(name)
        media = [(pid, process_identity(pid)) for pid, command in processes if command == "ffmpeg"]
        if active and media:
            break
        time.sleep(0.05)
    else:
        raise AssertionError("did not observe an active ffmpeg job")
    job_id = active[0]
    started = time.monotonic()
    docker("stop", "-t", "20", name, timeout=25)
    elapsed = time.monotonic() - started
    state = json.loads(docker("inspect", "--format", "{{json .State}}", name).stdout)
    assert not state["Running"] and state["ExitCode"] in (0, 143), state
    assert elapsed < 20, f"stop exceeded grace period: {elapsed:.3f}s"
    assert all(identity is None or process_identity(pid) != identity for pid, identity in media), "ffmpeg survived stop"
    # docker top must report no running processes after stop.
    assert docker("top", name, check=False).returncode != 0
    root_unchanged(name)
    print(f"PASS graceful stop ({mode}): exit={state['ExitCode']}, {elapsed:.3f}s < 20s; "
          f"docker top saw {len(media)} ffmpeg, none survived", flush=True)
    docker("start", name)
    health(name)
    inside(name, HTTP + '''
job_id = sys.argv[1]
jobs = request("GET", "/api/jobs")["jobs"]
job = next(j for j in jobs if j["id"] == job_id)
assert job["state"] == "interrupted", job
attempt = job["attempt"]
request("POST", "/api/jobs/" + job_id + "/retry", {})
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    job = next(j for j in request("GET", "/api/jobs")["jobs"] if j["id"] == job_id)
    if job["state"] == "ready":
        assert job["attempt"] == attempt + 1
        assert not job["result"]["reject_reasons"]
        break
    assert job["state"] in ("queued", "acquiring", "processing"), job
    time.sleep(0.1)
else:
    raise AssertionError("retry timed out")
''', job_id, timeout=190)
    root_unchanged(name)
    # Wait until the remaining playlist has drained before checking for stray children.
    inside(name, HTTP + '''
deadline = time.monotonic() + 180
while any(j["state"] in ("queued", "acquiring", "processing") for j in request("GET", "/api/jobs")["jobs"]):
    assert time.monotonic() < deadline, "playlist timed out"
    time.sleep(0.1)
''', timeout=190)
    assert not any(command in ("ffmpeg", "ffprobe") for _, command in top(name))
    docker("stop", "-t", "20", name, timeout=25)
    print(f"PASS restart/retry ({mode}): interrupted -> ready; no media children; no application root writes", flush=True)


def main():
    config = json.loads(docker("image", "inspect", "--format", "{{json .Config}}", IMAGE).stdout)
    assert config["Labels"]["org.opencontainers.image.revision"] == REVISION
    assert config["Labels"]["org.opencontainers.image.source"] == "https://git.oklabs.uk/BeFeast/dremla"
    assert config["Labels"]["org.opencontainers.image.title"] == "dremla-collection"
    assert config["User"] == "1000:1000" and config["WorkingDir"] == "/app"
    assert config["StopSignal"] == "SIGTERM"
    assert config["Cmd"] == ["python3", "-I", "/app/collection-service/run.py"]
    name = f"{PREFIX}-smoke-service"
    flags = run_flags("smoke")
    # Inspect the unmounted image first: tests and Android sources are absent.
    docker("run", *flags, IMAGE, "python3", "-I", "-c", '''
from pathlib import Path
import os, sys, tempfile
assert Path("/app/REVISION").read_text().strip() == sys.argv[1]
assert {p.name for p in Path("/app").iterdir()} == {"REVISION", "collection-service"}
assert {p.name for p in Path("/app/collection-service").iterdir()} == {"run.py", "dremla_collection"}
assert not list(Path("/app").rglob("*.pyc"))
assert os.getuid() == 1000 and os.getgid() == 1000
assert tempfile.gettempdir() == "/tmp"
''', REVISION)
    docker("rm", name)
    print(f"PASS provenance/layout: label and /app/REVISION = {REVISION}; numeric non-root, runtime files only", flush=True)
    # /mnt already exists in Debian: no mountpoint creation in the image's root layer.
    output = docker("run", *flags, "--init", "--mount",
                    f"type=bind,src={TESTS},dst=/mnt,readonly",
                    "-e", "DREMLA_SMOKE_ROOT=/data/state", "-e", "DREMLA_SMOKE_APP_ROOT=/app", IMAGE,
                    "python3", "-I", "/mnt/smoke.py", timeout=300)
    Path(SCRATCH, "output").write_text(output.stdout + output.stderr)
    assert "SMOKE PASS" in output.stdout
    root_unchanged(name)
    for line in output.stdout.splitlines():
        if line.startswith(("Versions:", "Measured ")):
            print(line, flush=True)
    print("PASS smoke: real entrypoint, fixture/ffmpeg, v1 -> v2 -> v3 rollback, -24 +/- 1 LUFS, read-only root", flush=True)
    lifecycle("init")
    lifecycle("bare")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL container checks: {error}", file=sys.stderr)
        sys.exit(1)
