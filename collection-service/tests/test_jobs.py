from unittest.mock import patch
import json
import threading

from dremla_collection.common import ServiceError, atomic_write, digest, read_json

from dremla_collection.adapters import FixtureAcquisition, FixtureProcessor
from dremla_collection.jobs import CollectionService
import wave

from dremla_collection.fixtures import SINGLE, PLAYLIST
from support import ServiceCase


class JobTests(ServiceCase):
    def test_success_transitions_and_persisted_result(self):
        observed = []
        transition = self.service._transition
        def capture(job, state, **kwargs):
            transition(job, state, **kwargs)
            observed.append(state)
        with patch.object(self.service, "_transition", side_effect=capture):
            job = self.ready()
        self.assertEqual(observed, ["acquiring", "processing", "ready"])
        path = self.service.media(["jobs", job["id"], "preview"])
        with wave.open(str(path)) as wav:
            self.assertEqual(wav.getnframes() / wav.getframerate(), 20)
        self.service.close()
        self.service = self.open_service()
        self.assertEqual(self.service.list_jobs()[0], job)

    def test_failure_retry_success_and_attempt_isolation(self):
        self.service.acquisition.fail_once.add("fxAmbient01")
        draft = self.service.add_draft(SINGLE)
        identity = self.service.process_draft(draft["id"])[0]["id"]
        failed = self.wait_job(identity)
        self.assertEqual((failed["state"], failed["error"]["error"]), ("failed", "acquisition-failed"))
        self.service.control(identity, "retry")
        retried = self.wait_job(identity)
        self.assertEqual((retried["state"], retried["attempt"]), ("ready", 2))
        self.assert_code("invalid-job-state", lambda: self.service.control(identity, "retry"))

    def test_fixture_playlist_failure_and_rejection(self):
        draft = self.service.add_draft(PLAYLIST)
        jobs = [self.wait_job(j["id"]) for j in self.service.process_draft(draft["id"])]
        self.assertEqual([j["state"] for j in jobs], ["ready", "ready", "failed", "ready"])
        self.assertIn("LRA", jobs[-1]["result"]["reject_reasons"][0])
        self.assert_code("processor-rejected", lambda: self.service.set_review(jobs[-1]["item"]["id"], {"approved": True, "job": jobs[-1]["id"]}))

    def test_cancel_queued_and_retry(self):
        self.service.close()
        self.service = self.open_service(start_worker=False)
        draft = self.service.add_draft(SINGLE)
        job = self.service.process_draft(draft["id"])[0]
        self.assertEqual(self.service.control(job["id"], "cancel")["state"], "cancelled")
        self.service.control(job["id"], "retry")
        self.service.close()
        self.service = self.open_service()
        self.assertEqual(self.wait_job(job["id"])["state"], "ready")

    def test_cancel_running_acquisition_and_processing(self):
        for state in ("acquiring", "processing"):
            with self.subTest(state=state):
                self.service.acquisition.delay = 0.002 if state == "acquiring" else 0
                self.service.processor.delay = 0.002 if state == "processing" else 0
                draft = self.service.add_draft(SINGLE)
                job = self.service.process_draft(draft["id"])[0]
                self.wait_job(job["id"], (state,))
                self.service.control(job["id"], "cancel")
                self.assertEqual(self.wait_job(job["id"])["state"], "cancelled")
                self.assert_code("not-ready", lambda: self.service.set_review(job["item"]["id"], {"approved": True, "job": job["id"]}))

    def test_restart_marks_each_running_state_interrupted(self):
        for state in ("acquiring", "processing"):
            with self.subTest(state=state):
                self.service.close()
                self.service = self.open_service(start_worker=False)
                draft = self.service.add_draft(SINGLE)
                job = self.service.process_draft(draft["id"])[0]
                self.service.jobs[job["id"]]["state"] = state
                self.service.save("jobs")
                self.service.close()
                self.service = self.open_service()
                self.assertEqual(self.wait_job(job["id"])["state"], "interrupted")
                self.service.control(job["id"], "retry")
                self.assertEqual(self.wait_job(job["id"])["state"], "ready")

    def test_single_worker_and_duplicate_queue_guard(self):
        self.service.acquisition.delay = 0.003
        draft = self.service.add_draft(PLAYLIST)
        jobs = self.service.process_draft(draft["id"])
        self.wait_job(jobs[0]["id"], ("acquiring",))
        states = [j["state"] for j in self.service.list_jobs()]
        self.assertEqual(states, ["acquiring", "queued", "queued", "queued"])
        self.assertEqual(self.service.process_draft(draft["id"]), [])
        for job in jobs:
            self.service.control(job["id"], "cancel")

    def test_state_and_destination_locks(self):
        self.assert_code("already-running", lambda: self.open_service())
        self.assert_code("already-running", lambda: CollectionService(
            self.root / "other-state", self.root / "dest", FixtureAcquisition(), FixtureProcessor()))

    def test_unknown_adapter_errors_are_redacted(self):
        with patch.object(self.service.acquisition, "fetch", side_effect=OSError("private fixture diagnostic")):
            draft = self.service.add_draft(SINGLE)
            job = self.wait_job(self.service.process_draft(draft["id"])[0]["id"])
        self.assertEqual(job["error"], {"error": "processing-failed", "message": "Local acquisition or processing failed."})


    def test_worker_survives_transition_and_handler_storage_failures(self):
        for failing_state in ("acquiring", "processing", "ready", "failed", "all"):
            with self.subTest(state=failing_state):
                self.service.close()
                self.service = self.open_service(start_worker=False)
                draft = self.service.add_draft(SINGLE)
                first = self.service.process_draft(draft["id"])[0]
                other = self.service.add_draft("https://youtu.be/fxPiano0001")
                second = self.service.process_draft(other["id"])[0]
                if failing_state == "failed":
                    self.service.acquisition.fail_once.add("fxAmbient01")
                fault = threading.Event()
                def fail_write(path, data):
                    candidate = json.loads(data)
                    if (path.name == "jobs.json" and candidate[first["id"]] != self.service.jobs[first["id"]]
                            and candidate[first["id"]]["state"] in ({"acquiring", "failed"} if failing_state == "all" else {failing_state})):
                        fault.set()
                        raise OSError("fixture storage fault")
                    return atomic_write(path, data)
                with patch("dremla_collection.jobs.atomic_write", side_effect=fail_write):
                    self.service.worker = threading.Thread(target=self.service._work, daemon=True)
                    self.service.worker.start()
                    self.assertTrue(fault.wait(5))
                    self.assertEqual(self.wait_job(second["id"])["state"], "ready")
                    self.assertTrue(self.service.worker.is_alive())
                    job = self.service.jobs[first["id"]]
                    self.assertEqual(job["error"]["error"], "storage-failed")
                    persisted = read_json(self.root / "state/jobs.json")[first["id"]]
                    self.assertEqual(job["state"], persisted["state"])
                # A new job and a retry can progress once the storage fault is removed.
                self.service.control(first["id"], "retry")
                self.assertEqual(self.wait_job(first["id"])["state"], "ready")

    def test_transition_failure_preserves_previous_state_and_result(self):
        job = self.ready()
        with patch("dremla_collection.jobs.atomic_write", side_effect=OSError("fixture fault")):
            self.assert_code("storage-failed", lambda: self.service._transition(
                self.service.jobs[job["id"]], "failed", result=None))
        self.assertEqual(self.service.jobs[job["id"]], job)
        self.assertEqual(read_json(self.root / "state/jobs.json")[job["id"]], job)

    def test_shutdown_maps_processor_error_to_interrupted(self):
        entered, release = threading.Event(), threading.Event()
        def fail_during_stop(*args):
            entered.set()
            release.wait(5)
            raise OSError("fixture shutdown failure")
        with patch.object(self.service.processor, "process", side_effect=fail_during_stop):
            draft = self.service.add_draft(SINGLE)
            job = self.service.process_draft(draft["id"])[0]
            self.assertTrue(entered.wait(5))
            self.service.stopping.set()
            release.set()
            self.assertEqual(self.wait_job(job["id"])["state"], "interrupted")


class ReviewTests(ServiceCase):
    def test_empty_selection_and_unapproved(self):
        job = self.ready()
        self.assert_code("invalid-manifest", lambda: self.publish(0, True))
        self.assert_code("unapproved", lambda: self.service.set_review(job["item"]["id"], {"inSelection": True}))
        self.service.set_review(job["item"]["id"], {"approved": True, "job": job["id"], "inSelection": False})
        self.assert_code("invalid-manifest", lambda: self.publish(0, True))

    def test_not_ready_and_running_approved_job(self):
        job = self.approved()
        for state in ("failed", "processing"):
            self.service.jobs[job["id"]]["state"] = state
            self.assert_code("not-ready", lambda: self.publish(0, True))
            self.assert_code("not-ready", lambda: self.service.set_review(job["item"]["id"], {"approved": True, "job": job["id"]}))
        self.service.jobs[job["id"]]["state"] = "ready"
        self.service.acquisition.delay = 0.01
        draft = self.service.add_draft(SINGLE)
        other = self.service.process_draft(draft["id"])[0]
        self.assert_code("job-running", lambda: self.publish(0, True))
        self.service.control(other["id"], "cancel")

    def test_rejected_cannot_bypass_publish_gate(self):
        job = self.approved()
        self.service.jobs[job["id"]]["result"]["reject_reasons"] = ["LRA 18.0 LU > 12"]
        self.assert_code("processor-rejected", lambda: self.publish(0, True))

    def test_stale_parent_confirmation_and_current_selection(self):
        job = self.approved()
        self.assert_code("confirmation-required", lambda: self.publish(0, False))
        self.assert_code("stale-parent", lambda: self.publish(1, True))
        first = self.publish(0, True)
        self.assert_code("stale-parent", lambda: self.publish(0, True))
        self.assert_code("stale-parent", lambda: self.publish(True, True))
        self.assertEqual(self.service.review()["proposed"]["selection"], first["selection"])
        self.assertFalse(self.service.review_decisions)
        self.service.set_review(job["item"]["id"], {"inSelection": False})
        self.assertEqual(self.service.review()["diff"]["removedFromSelection"], first["selection"])
        self.assert_code("invalid-manifest", lambda: self.publish(1, True))

    def test_review_decisions_survive_restart(self):
        self.approved()
        before = self.service.review()
        self.service.close()
        self.service = self.open_service()
        self.assertEqual(self.service.review(), before)

    def test_reject_revision_restores_selection_and_reports_all_differences(self):
        first = self.approved()
        piano = self.ready("https://youtu.be/fxPiano0001")
        track = piano["item"]["id"]
        self.service.set_review(track, {"approved": True, "job": piano["id"], "inSelection": False})
        current = self.publish(0, True)
        draft = self.service.add_draft("https://youtu.be/fxPiano0001")
        self.service.edit_item(draft["id"], track, {"include": True})
        revision = self.wait_job(self.service.process_draft(draft["id"])[0]["id"])
        # A distinct content revision, without changing the published immutable asset.
        audio = self.root / "state/work" / revision["id"] / "1" / revision["result"]["audio"]
        with audio.open("ab") as output:
            output.write(b"revision")
        self.service.set_review(track, {"approved": True, "job": revision["id"]})
        self.assertEqual(self.service.review()["diff"], dict(added=[], addedToSelection=[track],
                                                           removedFromSelection=[], replaced=[track]))
        self.service.set_review(track, {"approved": False})
        review = self.service.review()
        self.assertEqual(review["proposed"]["selection"], current["selection"])
        self.assertEqual(review["diff"], dict(added=[], addedToSelection=[], removedFromSelection=[], replaced=[]))
        # Explicit selection choices survive approve/reject, including across restart.
        self.service.set_review(track, {"inSelection": True})
        self.service.set_review(track, {"approved": True, "job": revision["id"]})
        self.service.close()
        self.service = self.open_service()
        self.service.set_review(track, {"approved": False})
        self.assertIn(track, self.service.review()["proposed"]["selection"])
        self.service.set_review(first["item"]["id"], {"inSelection": False})
        self.assertEqual(self.service.review()["diff"]["removedFromSelection"], current["selection"])

    def test_approval_binds_reviewed_job_despite_later_terminal_job(self):
        reviewed = self.ready()
        track = reviewed["item"]["id"]
        self.service.close()
        self.service = self.open_service(start_worker=False)
        draft = self.service.add_draft(SINGLE)
        later = self.service.process_draft(draft["id"])[0]
        for state in ("queued", "acquiring", "processing"):
            self.service._transition(self.service.jobs[later["id"]], state)
            self.assert_code("job-running", lambda: self.service.set_review(track, {"approved": True, "job": reviewed["id"]}))
        for state in ("cancelled", "failed", "interrupted"):
            with self.subTest(state=state):
                self.service._transition(self.service.jobs[later["id"]], state)
                decision = self.service.set_review(track, {"approved": True, "job": reviewed["id"]})
                self.assertEqual(decision["job"], reviewed["id"])
                published = self.publish(self.service.storage.current()["version"], True)
                self.assertEqual(published["tracks"][0]["title"], reviewed["item"]["title"])
                self.assertEqual(published["selection"], [track])
        self.assert_code("invalid-request", lambda: self.service.set_review(track, {"approved": True}))
        self.assert_code("not-ready", lambda: self.service.set_review(track, {"approved": True, "job": "missing"}))
        self.assert_code("not-ready", lambda: self.service.set_review(track, {"approved": True, "job": later["id"]}))
        self.service.add_draft("https://youtu.be/fxPiano0001")
        self.service.process_draft(next(reversed(self.service.drafts)))
        self.assert_code("not-ready", lambda: self.service.set_review("yt-fxPiano0001", {"approved": True, "job": reviewed["id"]}))


class RecoveryTests(ServiceCase):
    def restart(self):
        self.service.close()
        self.service = self.open_service()

    def immutable_files(self):
        return {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.service.storage.root.rglob("*")
                if p.is_file() and p.relative_to(self.service.storage.root).as_posix()
                not in ("manifest.json", "snapshots/index.json")}

    def assert_unchanged(self, before):
        for p, expected in before.items():
            self.assertEqual((p.read_bytes(), p.stat().st_mtime_ns), expected)

    def test_every_interruption_review_changes_restart_and_recovery(self):
        initial_root = self.root
        for first_publication in (False, True):
            for boundary in ("after-assets", "after-snapshot", "after-index"):
                with self.subTest(first=first_publication, boundary=boundary):
                    self.service.close()
                    self.root = initial_root / (str(first_publication) + boundary)
                    self.service = self.open_service()
                    ambient = self.approved()
                    parent = 0 if first_publication else self.publish()["version"]
                    original = self.service.storage.current()
                    piano = self.ready("https://youtu.be/fxPiano0001")
                    self.service.set_review(piano["item"]["id"], {"approved": True, "job": piano["id"]})
                    proposal = self.service.review()["proposed"]
                    before = self.immutable_files()
                    def fail(stage):
                        if stage == boundary:
                            raise OSError("fixture interruption")
                    self.service.storage.failure_hook = fail
                    with self.assertRaises(OSError):
                        self.publish(parent)
                    self.assertEqual(self.service.storage.current(), original)
                    self.assert_unchanged(before)
                    if boundary != "after-assets":
                        self.assertTrue(self.service.review()["pending"]["matchesProposal"])
                    self.service.set_review(piano["item"]["id"], {"approved": False, "inSelection": False})
                    self.service.set_review(ambient["item"]["id"], {"approved": True, "job": ambient["id"], "inSelection": True})
                    decisions = self.service.review_decisions.copy()
                    self.restart()
                    view = self.service.review()
                    self.assertEqual(view["current"], original)
                    self.assertEqual(self.service.review_decisions, decisions)
                    if boundary == "after-assets":
                        self.assertIsNone(view["pending"])
                        self.assert_code("not-found", lambda: self.service.complete_pending("0" * 64, True))
                        # Copied assets reserve no version until an immutable record exists.
                        self.assertEqual(self.publish(parent)["version"], parent + 1)
                    else:
                        pending = view["pending"]
                        record = self.service.storage.root / f"snapshots/v{parent + 1}/manifest.json"
                        raw = record.read_bytes()
                        self.assertEqual((pending["version"], pending["sha256"], pending["matchesProposal"],
                                          pending["counts"], pending["rollbackOf"]),
                                         (parent + 1, digest(record), False, {"tracks": 2, "selection": 2}, None))
                        self.assertEqual(pending["generated"], read_json(record)["generated"])
                        self.assertEqual(pending["selection"], [dict(id=t["id"], title=t["title"])
                                                               for t in proposal["tracks"]])
                        preserved = self.immutable_files()
                        for operation, code, status in (
                                (lambda: self.publish(parent), "pending-publication", 409),
                                (lambda: self.service.rollback(1, parent, True), "pending-publication", 409),
                                (lambda: self.service.complete_pending("0" * 64, True), "stale-pending", 409),
                                (lambda: self.service.complete_pending(pending["sha256"], False), "confirmation-required", 400)):
                            with self.assertRaises(ServiceError) as raised:
                                operation()
                            self.assertEqual((raised.exception.code, raised.exception.status), (code, status))
                        self.assertEqual(self.service.storage.current(), original)
                        # Completion must not consult jobs, review or their staging assets.
                        with patch.object(self.service, "_proposal", side_effect=AssertionError("proposal consulted")):
                            self.service.complete_pending(pending["sha256"], True)
                        self.assertEqual((self.service.storage.root / "manifest.json").read_bytes(), raw)
                        self.assert_unchanged(preserved)
                        self.assertEqual(self.service.review_decisions, decisions)
                        self.assertIsNone(self.service.review()["pending"])
                        self.assert_code("not-found", lambda: self.service.complete_pending(pending["sha256"], True))
                    third = self.publish(parent + 1)
                    self.assertEqual(third["version"], parent + 2)
                    self.assertEqual(third["selection"], [ambient["item"]["id"]])
                    history = self.service.storage.history()
                    self.assertEqual([e["version"] for e in history], list(range(1, parent + 3)))
                    for entry in history:
                        self.assertEqual(entry["sha256"], digest(self.service.storage.root /
                                         f"snapshots/v{entry['version']}/manifest.json"))
                    self.assert_unchanged(before)

    def test_interrupted_rollback_preserves_target_after_restart(self):
        self.approved()
        first = self.publish()
        piano = self.ready("https://youtu.be/fxPiano0001")
        self.service.set_review(piano["item"]["id"], {"approved": True, "job": piano["id"]})
        self.publish(1)
        for boundary in ("after-snapshot", "after-index"):
            with self.subTest(boundary=boundary):
                parent = self.service.storage.current()["version"]
                before = self.immutable_files()
                def fail(stage):
                    if stage == boundary:
                        raise OSError("rollback interruption")
                self.service.storage.failure_hook = fail
                with self.assertRaises(OSError):
                    self.service.rollback(1, parent, True)
                self.restart()
                pending = self.service.review()["pending"]
                self.assertEqual((pending["version"], pending["rollbackOf"], pending["counts"]),
                                 (parent + 1, 1, {"tracks": 1, "selection": 1}))
                self.assert_code("pending-publication", lambda: self.service.rollback(2, parent, True))
                record = self.service.storage.root / f"snapshots/v{parent + 1}/manifest.json"
                raw = record.read_bytes()
                # A second interruption during completion is explicitly recoverable too.
                self.service.storage.failure_hook = fail if boundary == "after-index" else lambda stage: None
                if boundary == "after-index":
                    with self.assertRaises(OSError):
                        self.service.complete_pending(pending["sha256"], True)
                    self.assertEqual(self.service.storage.current()["version"], parent)
                    self.restart()
                # A review decision made while the rollback was pending is cleared by completing it,
                # as an uninterrupted rollback clears the review.
                self.service.set_review(piano["item"]["id"], {"approved": True, "job": piano["id"]})
                self.assertTrue(self.service.review_decisions)
                completed = self.service.complete_pending(pending["sha256"], True)
                self.assertEqual(completed["tracks"], first["tracks"])
                self.assertEqual(self.service.review_decisions, {})
                self.restart()
                self.assertEqual(self.service.review_decisions, {})
                self.assertEqual((self.service.storage.root / "manifest.json").read_bytes(), raw)
                self.assertEqual(self.service.storage.history()[-1]["rollbackOf"], 1)
                self.assert_unchanged(before)

    def test_pending_visible_and_completable_when_proposal_cannot_be_built(self):
        job = self.approved()
        def fail(stage):
            if stage == "after-snapshot":
                raise OSError("interruption")
        self.service.storage.failure_hook = fail
        with self.assertRaises(OSError):
            self.publish()
        self.restart()
        pending = self.service.review()["pending"]
        for state in ("interrupted", "ready"):
            self.service.jobs[job["id"]]["state"] = state
            if state == "ready":
                audio = self.root / "state/work" / job["id"] / "1" / job["result"]["audio"]
                audio.unlink()
            view = self.service.review()
            self.assertIsNone(view["proposalRevision"])
            self.assertIsNone(view["proposed"])
            self.assertEqual(view["pending"]["sha256"], pending["sha256"])
            self.assertFalse(view["pending"]["matchesProposal"])
            self.assert_code("pending-publication", lambda: self.service.publish(0, True, "0" * 64))
        self.service.complete_pending(pending["sha256"], True)
        self.assertEqual(self.service.storage.current()["version"], 1)
