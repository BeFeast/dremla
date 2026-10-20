"""Public job controls remain usable after partial processing and storage faults."""
import errno
import json
import threading
import time
from unittest.mock import patch

from dremla_collection.common import atomic_write, read_json
from dremla_collection.fixtures import PLAYLIST, SINGLE
from support import ServiceCase


class RecoveryControlTests(ServiceCase):
    def test_failed_write_after_processing_enospc_is_terminal_and_retriable(self):
        approved = self.approved()
        draft = self.service.add_draft(SINGLE)
        failed_write = threading.Event()

        def disk_full(path, data):
            if path.name == "jobs.json" and any(j["state"] == "failed" for j in json.loads(data).values()):
                failed_write.set()
                raise OSError(errno.ENOSPC, "fixture disk full")
            return atomic_write(path, data)

        with patch.object(self.service.processor, "process", side_effect=OSError(errno.ENOSPC, "fixture disk full")), \
                patch("dremla_collection.jobs.atomic_write", side_effect=disk_full):
            identity = self.service.process_draft(draft["id"])[0]["id"]
            self.assertTrue(failed_write.wait(5))
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                job = next(j for j in self.service.list_jobs() if j["id"] == identity)
                if job["state"] == "failed":
                    break
                time.sleep(0.01)
            self.assertEqual((job["state"], job["error"]["error"]), ("failed", "storage-failed"))
            self.assertTrue(self.service.worker.is_alive())
            self.assertIsNone(self.service.review()["error"])
        # No private transition is needed to release the approved track or retry.
        self.assertEqual(self.publish()["selection"], [approved["item"]["id"]])
        self.service.control(identity, "retry")
        retried = self.wait_job(identity)
        self.assertEqual((retried["state"], retried["attempt"]), ("ready", 2))
        self.assertEqual(read_json(self.root / "state/jobs.json")[identity], retried)

    def test_partial_draft_only_queues_new_items(self):
        draft = self.service.add_draft(PLAYLIST)
        first, second, *others = draft["items"]
        for item in [second, *others]:
            self.service.edit_item(draft["id"], item["id"], {"include": False})
        ready = self.wait_job(self.service.process_draft(draft["id"])[0]["id"])
        self.assertEqual(ready["state"], "ready")
        self.service.edit_item(draft["id"], second["id"], {"include": True})
        queued = self.service.process_draft(draft["id"])
        self.assertEqual([j["item"]["id"] for j in queued], [second["id"]])
        self.assertEqual(self.wait_job(queued[0]["id"])["state"], "ready")
        self.assertEqual(self.service.process_draft(draft["id"]), [])
        self.assertEqual(len(self.service.list_jobs()), 2)

    def test_partial_draft_skips_active_jobs_but_allows_new_items(self):
        self.service.close()
        self.service = self.open_service(start_worker=False)
        draft = self.service.add_draft(PLAYLIST)
        for item in draft["items"][1:]:
            self.service.edit_item(draft["id"], item["id"], {"include": False})
        self.service.process_draft(draft["id"])
        second = draft["items"][1]
        self.service.edit_item(draft["id"], second["id"], {"include": True})
        self.assertEqual([j["item"]["id"] for j in self.service.process_draft(draft["id"])], [second["id"]])
        self.assertEqual(self.service.process_draft(draft["id"]), [])
        other = self.service.add_draft(SINGLE)
        self.assert_code("job-running", lambda: self.service.process_draft(other["id"]))

    def test_process_draft_requeues_failed_items_only(self):
        draft = self.service.add_draft(PLAYLIST)
        jobs = [self.wait_job(j["id"]) for j in self.service.process_draft(draft["id"])]
        failed = [j["item"]["id"] for j in jobs if j["state"] == "failed"]
        self.assertTrue(failed)
        self.assertEqual([j["item"]["id"] for j in self.service.process_draft(draft["id"])], failed)
