from pathlib import Path
import tempfile
import time
import unittest

from dremla_collection.adapters import FixtureAcquisition, FixtureProcessor
from dremla_collection.common import ServiceError, digest
from dremla_collection.fixtures import SINGLE
from dremla_collection.jobs import CollectionService


class ServiceCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dremla-collection-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.service = self.open_service()
        self.addCleanup(lambda: self.service.close())

    def open_service(self, **kwargs):
        return CollectionService(self.root / "state", self.root / "dest",
                                 kwargs.pop("acquisition", FixtureAcquisition()),
                                 kwargs.pop("processor", FixtureProcessor()), **kwargs)

    def wait_job(self, identity, states=("ready", "failed", "cancelled", "interrupted")):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            job = next(j for j in self.service.list_jobs() if j["id"] == identity)
            if job["state"] in states:
                return job
            time.sleep(0.01)
        self.fail("Job did not reach " + str(states))

    def ready(self, url=SINGLE):
        draft = self.service.add_draft(url)
        job = self.service.process_draft(draft["id"])[0]
        result = self.wait_job(job["id"])
        self.assertEqual(result["state"], "ready", result)
        return result

    def approved(self):
        job = self.ready()
        self.service.set_review(job["item"]["id"], {"approved": True, "job": job["id"]})
        return job

    def assert_code(self, code, fn):
        with self.assertRaises(ServiceError) as raised:
            fn()
        self.assertEqual(raised.exception.code, code)


def publication(root):
    root.mkdir(parents=True, exist_ok=True)
    audio, preview = root / "input.wav", root / "preview.wav"
    audio.write_bytes(b"fixture audio bytes")
    preview.write_bytes(b"fixture preview bytes")
    tid = "yt-fxAmbient01"
    file = f"audio/{tid}.{digest(audio)}.wav"
    prev = f"previews/{tid}.{digest(preview)}.wav"
    track = dict(id=tid, title="Still Lantern", artist="Fixture Ensemble", category="ambient", duration_sec=90,
                 file=file, bytes=audio.stat().st_size, sha256=digest(audio), preview=prev, license="CC0-1.0",
                 source_url=SINGLE, loudness={"i_lufs": -24.0, "lra": 1.0}, flags=["short"])
    snapshot = dict(tracks=[track], selection=[tid])
    return snapshot, {file: audio, prev: preview}
