"""Durable drafts, jobs and review state with one sequential worker."""
from copy import deepcopy
import fcntl
import os
from pathlib import Path
import threading
import uuid

from .common import Cancelled, ServiceError, atomic_write, checkpoint, digest, json_bytes, read_json, require, require_utf8
from .catalog import Catalog
from .publish import FilesystemStorage, revision
from .urls import parse_url
from .validate import local_path

ACTIVE = {"queued", "acquiring", "processing"}
RETRIABLE = {"failed", "cancelled", "interrupted"}


class CollectionService:
    def __init__(self, data, destination, acquisition, processor, start_worker=True, catalog_destination=None):
        self.data = Path(data).resolve()
        self.data.mkdir(parents=True, exist_ok=True)
        destination = Path(destination).resolve()
        require(self.data != destination and destination not in self.data.parents
                and self.data not in destination.parents, "invalid-config", "State and destination must be separate directories.")
        destination.mkdir(parents=True, exist_ok=True)
        catalog_destination = Path(catalog_destination if catalog_destination is not None else self.data.parent / "catalog").resolve()
        for root in (self.data, destination):
            require(root != catalog_destination and root not in catalog_destination.parents
                    and catalog_destination not in root.parents, "invalid-config",
                    "State, media and catalog destinations must be separate directory trees.")
        catalog_destination.mkdir(parents=True, exist_ok=True)
        self.file_locks = []
        self.worker = None
        try:
            # Separate roots are locked across processes as well as across request threads.
            for root in sorted([self.data, destination, catalog_destination]):
                handle = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
                self.file_locks.append(handle)
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise ServiceError("already-running", "State or destination is already in use.") from None
            self.storage = FilesystemStorage(destination)
            self.acquisition, self.processor = acquisition, processor
            self.lock = threading.RLock()
            self.catalog = Catalog(self.data, catalog_destination, self.lock)
            self.condition = threading.Condition(self.lock)
            self.stopping = threading.Event()
            self.events = {}
            self.drafts = self.load("drafts")
            self.jobs = self.load("jobs")
            self.review_decisions = self.load("review")
            for job in self.jobs.values():
                if job["state"] in {"acquiring", "processing"}:
                    job["state"] = "interrupted"
                    job["error"] = {"error": "interrupted", "message": "Processing stopped; retry is available."}
                elif job["state"] == "queued" and (job.get("error") or {}).get("error") == "storage-failed":
                    job["error"] = None
            self.save("jobs")
            if start_worker:
                self.worker = threading.Thread(target=self._work, name="collection-worker", daemon=True)
                self.worker.start()
        except Exception:
            for handle in self.file_locks:
                os.close(handle)
            raise

    def load(self, name):
        path = self.data / (name + ".json")
        result = read_json(path) if path.exists() else {}
        require(isinstance(result, dict), "invalid-state", "Invalid saved service state.")
        return result

    def save(self, name):
        value = self.review_decisions if name == "review" else getattr(self, name)
        atomic_write(self.data / (name + ".json"), json_bytes(value))

    def close(self):
        with self.condition:
            self.stopping.set()
            for event in self.events.values():
                event.set()
            self.condition.notify_all()
        if self.worker:
            self.worker.join()
        for handle in self.file_locks:
            os.close(handle)
        self.file_locks.clear()

    def add_draft(self, url):
        parse_url(url)  # Validation must precede even a substituted acquisition adapter.
        source = self.acquisition.resolve(url)
        with self.lock:
            existing = {t["id"] for t in self.storage.current()["tracks"]}
            draft = dict(id=uuid.uuid4().hex, kind=source["kind"], source_url=source["source_url"], items=[])
            seen = set()
            for item in source["items"]:
                if item["id"] not in seen:
                    draft["items"].append(dict(item, include=item["id"] not in existing,
                                               inCollection=item["id"] in existing))
                    seen.add(item["id"])
            self.drafts[draft["id"]] = draft
            self.save("drafts")
            return self.draft(draft["id"])

    def draft(self, identity):
        with self.lock:
            require(identity in self.drafts, "not-found", "Draft not found.", 404)
            draft = deepcopy(self.drafts[identity])
            for item in draft["items"]:
                item["editLocked"] = self.item_has_job(identity, item["id"])
            return draft

    def item_has_job(self, draft_id, item_id):
        # Legacy jobs lack a draft ID: conservatively lock matching source items.
        return any(j["item"]["id"] == item_id and j.get("draftId", draft_id) == draft_id
                   for j in self.jobs.values())

    def edit_item(self, draft_id, item_id, changes):
        require_utf8(changes)
        with self.lock:
            self.draft(draft_id)
            require(set(changes) <= {"include", "category", "title"}, "invalid-request", "Unknown draft field.")
            item = next((i for i in self.drafts[draft_id]["items"] if i["id"] == item_id), None)
            require(item is not None, "not-found", "Draft item not found.", 404)
            require(not self.item_has_job(draft_id, item_id), "job-exists",
                    "This item has a job; create a new draft to edit it.", 409)
            if "include" in changes:
                require(type(changes["include"]) is bool, "invalid-request", "Include must be boolean.")
            if "category" in changes:
                require(changes["category"] in ("ambient", "piano"), "invalid-request", "Category must be ambient or piano.")
            if "title" in changes:
                require(isinstance(changes["title"], str) and 0 < len(changes["title"].strip()) <= 300,
                        "invalid-request", "Title must contain 1 to 300 characters.")
            item.update(changes)
            self.save("drafts")
            return dict(deepcopy(item), editLocked=False)

    def process_draft(self, identity):
        with self.condition:
            items = [i for i in self.draft(identity)["items"] if i["include"]]
            require(bool(items), "empty-draft", "Include at least one draft item.")
            require(not any(j["state"] in ACTIVE and j["item"]["id"] in {i["id"] for i in items}
                            for j in self.jobs.values()), "job-running", "A track is already queued or running.", 409)
            created = []
            for item in items:
                job = dict(id=uuid.uuid4().hex, draftId=identity, item=deepcopy(item), state="queued", attempt=1, result=None, error=None)
                self.jobs[job["id"]] = job
                created.append(deepcopy(job))
            self.save("jobs")
            self.condition.notify_all()
            return created

    def list_jobs(self):
        with self.lock:
            return deepcopy(list(self.jobs.values()))

    def control(self, identity, operation):
        with self.condition:
            require(identity in self.jobs, "not-found", "Job not found.", 404)
            job = self.jobs[identity]
            if operation == "cancel":
                require(job["state"] in ACTIVE, "invalid-job-state", "Only active jobs can be cancelled.", 409)
                self.events.setdefault(identity, threading.Event()).set()
                if job["state"] == "queued":
                    job["state"] = "cancelled"
            else:
                require(operation == "retry" and job["state"] in RETRIABLE,
                        "invalid-job-state", "Only failed, cancelled or interrupted jobs can be retried.", 409)
                require(not any(other["id"] != identity and other["item"]["id"] == job["item"]["id"]
                                and other["state"] in ACTIVE for other in self.jobs.values()),
                        "job-running", "Another job for this track is active.", 409)
                job.update(state="queued", attempt=job["attempt"] + 1, result=None, error=None)
                self.events[identity] = threading.Event()
            self.save("jobs")
            self.condition.notify_all()
            return deepcopy(job)

    def _transition(self, job, state, **values):
        with self.lock:
            candidate = dict(job, state=state, **values)
            jobs = dict(self.jobs)
            jobs[job["id"]] = candidate
            try:
                atomic_write(self.data / "jobs.json", json_bytes(jobs))
            except Exception:
                raise ServiceError("storage-failed", "Job state could not be saved.") from None
            job.update(candidate)

    def _finish(self, job, state, error=None):
        with self.lock:
            if self.stopping.is_set():
                state, error = "interrupted", None
            try:
                self._transition(job, state, error=error)
            except Exception:
                # Preserve the last state; quarantine a queued job until storage recovers
                # or restart, while letting later jobs proceed. Never leak a handler error.
                job["error"] = {"error": "storage-failed", "message": "Job state could not be saved."}

    def _work(self):
        def queued(job):
            return job["state"] == "queued" and (job.get("error") or {}).get("error") != "storage-failed"

        while True:
            job = None
            try:
                with self.condition:
                    self.condition.wait_for(lambda: self.stopping.is_set() or any(queued(j) for j in self.jobs.values()))
                    if self.stopping.is_set():
                        return
                    job = next(j for j in self.jobs.values() if queued(j))
                    event = self.events.setdefault(job["id"], threading.Event())
                    self._transition(job, "acquiring", error=None)
                workdir = self.data / "work" / job["id"] / str(job["attempt"])
                workdir.mkdir(parents=True, exist_ok=True)
                source = workdir / "source.wav"
                self.acquisition.fetch(job["item"], source, event)
                checkpoint(event)
                self._transition(job, "processing")
                result = self.processor.process(source, job["item"], workdir, event)
                checkpoint(event)
                if not job["item"].get("license"):
                    result["flags"].append("license-unverified")
                with self.lock:
                    checkpoint(event)
                    self._transition(job, "ready", result=result)
            except Cancelled:
                self._finish(job, "cancelled")
            except Exception as error:
                if job is not None:
                    detail = ({"error": error.code, "message": error.message} if isinstance(error, ServiceError)
                              else {"error": "processing-failed", "message": "Local acquisition or processing failed."})
                    self._finish(job, "failed", detail)

    def latest_job(self, track):
        return next((j for j in reversed(list(self.jobs.values())) if j["item"]["id"] == track), None)

    def set_review(self, track, changes):
        with self.lock:
            require_utf8(changes)
            require(bool(changes) and set(changes) <= {"approved", "inSelection", "job"}
                    and all(type(v) is bool for k, v in changes.items() if k != "job")
                    and ("job" not in changes or isinstance(changes["job"], str)),
                    "invalid-request", "Use boolean review fields and a job ID for approval.")
            current_ids = {t["id"] for t in self.storage.current()["tracks"]}
            job = self.latest_job(track)
            require(track in current_ids or job is not None, "not-found", "Track not found.", 404)
            decision = dict(self.review_decisions.get(track, {}))
            if "approved" in changes:
                if changes["approved"]:
                    require(isinstance(changes.get("job"), str), "invalid-request", "Approval requires the reviewed job ID.")
                    job = self.jobs.get(changes["job"])
                    require(job is not None and job["item"]["id"] == track,
                            "not-ready", "The reviewed job must belong to this track.", 409)
                    require(job is not None and job["state"] == "ready", "not-ready", "The job must be ready before approval.", 409)
                    require(not job["result"]["reject_reasons"], "processor-rejected", "Rejected by processing; approval is unavailable.", 409)
                    require(not any(j["item"]["id"] == track and j["state"] in ACTIVE for j in self.jobs.values()),
                            "job-running", "A job for this track is active.", 409)
                    decision.update(approved=True, job=job["id"])
                    if "inSelection" not in decision:
                        decision.update(inSelection=True, selectionExplicit=False)
                else:
                    decision["approved"] = False
                    if track in current_ids and not decision.get("selectionExplicit", False):
                        decision.pop("inSelection", None)
                    elif track not in current_ids:
                        decision["inSelection"] = False
            if "inSelection" in changes:
                require(not changes["inSelection"] or track in current_ids or decision.get("approved"),
                        "unapproved", "Approve a new track before selecting it.", 409)
                decision["inSelection"] = changes["inSelection"]
                decision["selectionExplicit"] = True
            self.review_decisions[track] = decision
            self.save("review")
            return deepcopy(decision)

    def _proposal(self):
        current = self.storage.current()
        tracks = {t["id"]: deepcopy(t) for t in current["tracks"]}
        selection = set(current["selection"])
        assets = {}
        for track, decision in self.review_decisions.items():
            if decision.get("approved"):
                job = self.jobs.get(decision.get("job"))
                require(job is not None and job["state"] == "ready", "not-ready", "An approved job is no longer ready.", 409)
                require(not any(j["item"]["id"] == track and j["state"] in ACTIVE for j in self.jobs.values()),
                        "job-running", "An approved track has an active job.", 409)
                result, item = job["result"], job["item"]
                require(not result["reject_reasons"], "processor-rejected", "An approved result is rejected by processing.", 409)
                workdir = self.data / "work" / job["id"] / str(job["attempt"])
                audio = local_path(workdir, result["audio"])
                preview = local_path(workdir, result["preview"])
                sha = digest(audio)
                file_path = f"audio/{track}.{sha}{audio.suffix}"
                preview_path = f"previews/{track}.{digest(preview)}{preview.suffix}"
                assets.update({file_path: audio, preview_path: preview})
                tracks[track] = dict(id=track, title=item["title"], artist=item["artist"], category=item["category"],
                                     duration_sec=max(1, int(round(result["duration_sec"]))), file=file_path, bytes=audio.stat().st_size,
                                     sha256=sha, preview=preview_path, license=item.get("license") or "unknown",
                                     source_url=item["source_url"], loudness={k: result["loudness"][k] for k in ("i_lufs", "lra")},
                                     flags=result["flags"])
                selection.add(track)
            if "inSelection" in decision:
                if decision["inSelection"]:
                    require(track in tracks, "unapproved", "Selection includes an unapproved track.", 409)
                    selection.add(track)
                else:
                    selection.discard(track)
        proposed = dict(tracks=list(tracks.values()), selection=[t for t in tracks if t in selection])
        previous = {t["id"]: t for t in current["tracks"]}
        diff = dict(added=[t for t in tracks if t not in previous],
                    addedToSelection=[t for t in tracks if t in previous and t in selection and t not in current["selection"]],
                    removedFromSelection=[t for t in current["selection"] if t not in selection],
                    replaced=[t for t in tracks if t in previous and
                              (tracks[t]["sha256"], tracks[t]["preview"]) != (previous[t]["sha256"], previous[t]["preview"])])
        return current, proposed, assets, diff

    def review(self):
        with self.lock:
            current = self.storage.current()
            try:
                _, proposal, _, diff = self._proposal()
                error = None
            except ServiceError as exc:
                proposal, diff = None, None
                error = {"error": exc.code, "message": exc.message}
            except OSError:
                proposal, diff = None, None
                error = {"error": "local-failure", "message": "Local proposal assets could not be read."}
            pending = self.storage.pending()
            if pending:
                document, raw, rollback_of = pending
                titles = {t["id"]: t["title"] for t in document["tracks"]}
                entry = self.storage.entry(document, raw, rollback_of)
                pending = dict(entry, selection=[dict(id=t, title=titles[t]) for t in document["selection"]],
                               matchesProposal=proposal is not None and revision(document) == revision(proposal))
            return dict(current=current, proposed=proposal, diff=diff, error=error, pending=pending,
                        proposalRevision=revision(proposal) if proposal is not None else None,
                        decisions=deepcopy(self.review_decisions),
                        results=[deepcopy(j) for j in self.jobs.values() if j["state"] == "ready"])

    def publish(self, parent_version, confirm, proposal_revision):
        with self.lock:
            self.storage.require_no_pending()
            _, proposal, assets, _ = self._proposal()
            result = self.storage.publish(proposal, assets, parent_version, confirm, proposal_revision)
            self.review_decisions.clear()
            self.save("review")
            return result

    def complete_pending(self, sha256, confirm):
        with self.lock:
            pending = self.storage.pending()
            result = self.storage.complete_pending(sha256, confirm)
            if pending is not None and pending[2] is not None:
                # A completed rollback resets the review exactly as an uninterrupted rollback does.
                self.review_decisions.clear()
                self.save("review")
            # Otherwise keep later review decisions: they apply on top of the recovered current.
            return result

    def rollback(self, version, parent_version, confirm):
        with self.lock:
            result = self.storage.rollback(version, parent_version, confirm)
            self.review_decisions.clear()
            self.save("review")
            return result

    def media(self, parts):
        with self.lock:
            if len(parts) == 3 and parts[0] == "jobs" and parts[2] == "preview":
                job = self.jobs.get(parts[1])
                require(job is not None and job["state"] == "ready", "not-found", "Preview not found.", 404)
                return local_path(self.data / "work" / job["id"] / str(job["attempt"]), job["result"]["preview"])
            if len(parts) == 3 and parts[0] == "published" and parts[1] in ("audio", "previews"):
                relative = "/".join(parts[1:])
                require(any(relative in (t["file"], t["preview"]) for t in self.storage.current()["tracks"]),
                        "not-found", "Asset not found.", 404)
                return local_path(self.storage.root, relative)
            raise ServiceError("not-found", "Media not found.", 404)
