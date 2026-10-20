#!/usr/bin/env python3
"""Checks the platform-neutral catalog and online-playback vectors
(docs/collection-client-contract.md).

No arguments. Validates contracts/fixtures/catalog-*.json and their expected selections, requires
every file in contracts/fixtures/invalid/ to be rejected, and replays contracts/fixtures/playback-*.json
through a small reference state machine of the failure semantics. Standard library only; no network.
"""
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent / "fixtures"
VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")
PLAYLIST_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
CATALOG_KEYS = {"schema", "version", "generated", "tracks", "playlists", "excluded", "active"}
TRACK_KEYS = {"id", "source", "title", "artist", "category", "durationSec"}
TRACK_OPTIONAL = {"gainDb"}
FAILURES = {"login-required", "unavailable", "age-restricted", "network", "live-or-playlist"}
MAX_RETRIES_PER_NIGHT = 3


class Invalid(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise Invalid(message)


def load(path):
    def unique(pairs):
        out = {}
        for key, value in pairs:
            need(key not in out, "duplicate key " + key)
            out[key] = value
        return out
    return json.loads(path.read_text(), object_pairs_hook=unique)


def selection(catalog):
    """What the TV plays: the active playlist minus the excluded tracks, in playlist order."""
    active = next(p for p in catalog["playlists"] if p["id"] == catalog["active"])
    excluded = set(catalog["excluded"])
    return [t for t in active["trackIds"] if t not in excluded]


def validate_catalog(c):
    need(isinstance(c, dict) and set(c) == CATALOG_KEYS, "unexpected catalog fields")
    need(c["schema"] == "dremla.catalog/1", "schema")
    need(type(c["version"]) is int and c["version"] >= 1, "version")
    need(isinstance(c["generated"], str) and c["generated"].endswith("Z"), "generated")
    ids = set()
    for t in c["tracks"]:
        need(isinstance(t, dict) and TRACK_KEYS <= set(t) <= TRACK_KEYS | TRACK_OPTIONAL,
             "unexpected track fields (no media, stream or credential fields)")
        s = t["source"]
        need(isinstance(s, dict) and set(s) == {"kind", "videoId", "url"} and s["kind"] == "youtube", "source")
        need(isinstance(s["videoId"], str) and VIDEO_ID.fullmatch(s["videoId"]), "videoId")
        need(t["id"] == "yt-" + s["videoId"], "id must be yt-<videoId>")
        need(s["url"] == "https://www.youtube.com/watch?v=" + s["videoId"], "url must be canonical")
        need(t["id"] not in ids, "duplicate track")
        ids.add(t["id"])
        for key in ("title", "artist"):
            need(isinstance(t[key], str) and t[key].strip(), key)
        need(t["category"] in ("ambient", "piano"), "category")
        need(type(t["durationSec"]) is int and t["durationSec"] > 0, "durationSec")
        if "gainDb" in t:
            need(type(t["gainDb"]) in (int, float) and -40 <= t["gainDb"] <= 0, "gainDb must be in -40..0 (attenuate only)")
    playlist_ids = set()
    for p in c["playlists"]:
        need(isinstance(p, dict) and set(p) == {"id", "title", "trackIds"}, "playlist fields")
        need(isinstance(p["id"], str) and PLAYLIST_ID.fullmatch(p["id"]) and p["id"] not in playlist_ids, "playlist id")
        playlist_ids.add(p["id"])
        need(isinstance(p["title"], str) and p["title"].strip(), "playlist title")
        need(len(p["trackIds"]) == len(set(p["trackIds"])), "duplicate track in playlist")
        need(all(t in ids for t in p["trackIds"]), "unknown track in playlist")
    need(isinstance(c["excluded"], list) and len(c["excluded"]) == len(set(c["excluded"]))
         and all(t in ids for t in c["excluded"]), "excluded")
    need(c["active"] in playlist_ids, "active playlist")
    need(selection(c), "empty selection")


class Night:
    """Reference state machine for the online failure semantics.

    phases: starting (resolving the current track), playing, resuming (after a drop),
    unavailable (distinct failed IDs covered the whole selection; silent, timer still running),
    finished (timer expired).

    Budget: start retries and resume attempts share MAX_RETRIES_PER_NIGHT. A track gets at most
    one start retry (network only) and at most one resume per play attempt; the per-track
    allowances reset when the next track is started. The failure streak counts distinct failed track IDs
    since the last track that played to its end (trackEnd), so endless start-and-drop loops end in
    `unavailable` instead of a retry storm. Errors never stop or extend the timer; recovery never
    autoplays.
    """

    def __init__(self, sel):
        self.sel, self.index, self.phase = sel, 0, "starting"
        self.timer, self.budget = "running", MAX_RETRIES_PER_NIGHT
        self.failed_tracks = set()
        self.start_retried = self.resumed = False

    def _next_track(self):
        self.index = (self.index + 1) % len(self.sel)
        self.start_retried = self.resumed = False

    def _fail(self):
        self.failed_tracks.add(self.sel[self.index])
        if self.failed_tracks.issuperset(self.sel):
            self.phase = "unavailable"
        else:
            self._next_track()
            self.phase = "starting"

    def apply(self, step):
        event = step["event"]
        if self.phase == "finished":
            return
        if event == "timerExpired":
            self.phase, self.timer = "finished", "expired"
        elif event == "networkRecovered":
            pass
        elif event == "resolve":
            need(self.phase == "starting", "resolve outside starting")
            need(step["track"] == self.sel[self.index], f"resolved {step['track']}, expected {self.sel[self.index]}")
            result = step["result"]
            need(result == "ok" or result in FAILURES, "result code")
            if result == "ok":
                self.phase = "playing"
            elif result == "network" and not self.start_retried and self.budget > 0:
                self.start_retried, self.budget = True, self.budget - 1
            else:
                self._fail()
        elif event == "drop":
            need(self.phase == "playing", "drop outside playing")
            if not self.resumed and self.budget > 0:
                self.phase, self.resumed, self.budget = "resuming", True, self.budget - 1
            else:
                self._fail()
        elif event == "resume":
            need(self.phase == "resuming", "resume outside resuming")
            if step["result"] == "ok":
                self.phase = "playing"
            else:
                self._fail()
        elif event == "trackEnd":
            need(self.phase == "playing", "trackEnd outside playing")
            self.failed_tracks.clear()
            self._next_track()
            self.phase = "starting"
        else:
            raise Invalid("unknown event " + event)

    def state(self):
        return {"phase": self.phase, "sound": self.phase == "playing", "timer": self.timer}


def main():
    if len(sys.argv) != 1:
        print("usage: contracts/check-catalog.py (no arguments)", file=sys.stderr)
        return 2
    failures = []

    def check(label, fn):
        try:
            fn()
            print("PASS", label)
        except (Invalid, KeyError, TypeError, StopIteration, ValueError) as error:
            failures.append(label)
            print("FAIL", label, "-", error)

    def distinct_failures():
        night = Night(["A", "B", "C"])
        # A shuffle-bag boundary may revisit B before A. Keep this outside the
        # round-robin vectors: only the next-track order differs from their model.
        order = iter([1, 2, 1, 0])
        next_track = night._next_track
        def shuffled_next():
            next_track()
            night.index = next(order)
        night._next_track = shuffled_next
        night.apply({"event": "resolve", "track": "A", "result": "ok"})
        night.apply({"event": "trackEnd"})
        for track in ["B", "C", "B"]:
            night.apply({"event": "resolve", "track": track, "result": "unavailable"})
            need(night.phase == "starting", "repeated failed ID hid an untried track")
        need(night.sel[night.index] == "A", "working track was not retried")
        night.apply({"event": "resolve", "track": "A", "result": "unavailable"})
        need(night.phase == "unavailable", "all distinct failed IDs must stop sound")
        # A completed track clears the distinct-ID set for the next streak.
        night = Night(["A", "B", "C"])
        night.apply({"event": "resolve", "track": "A", "result": "unavailable"})
        night.apply({"event": "resolve", "track": "B", "result": "ok"})
        night.apply({"event": "trackEnd"})
        order = iter([1, 0])
        next_track = night._next_track
        night._next_track = shuffled_next
        for track in ["C", "B"]:
            night.apply({"event": "resolve", "track": track, "result": "unavailable"})
            need(night.phase == "starting", "trackEnd did not clear the failed IDs")
        night.apply({"event": "resolve", "track": "A", "result": "unavailable"})
        need(night.phase == "unavailable", "new streak must cover the selection")
    check("distinct failures across order boundary and trackEnd reset", distinct_failures)

    catalogs = {}
    for path in sorted(ROOT.glob("catalog-v*.json")):
        def one(path=path):
            c = load(path)
            validate_catalog(c)
            catalogs[c["version"]] = c
        check("valid " + path.name, one)
    for path in sorted((ROOT / "invalid").glob("*.json")):
        def rejected(path=path):
            try:
                validate_catalog(load(path))
            except (Invalid, KeyError, TypeError, StopIteration):
                return
            raise Invalid("accepted an invalid catalog")
        check("rejects invalid/" + path.name, rejected)

    def versions():
        need(sorted(catalogs) == list(range(1, len(catalogs) + 1)), "catalog versions must be consecutive from 1")
    check("consecutive versions", versions)
    for path in sorted(ROOT.glob("selection-v*.json")):
        def sel(path=path):
            expected = load(path)
            need(selection(catalogs[expected["catalogVersion"]]) == expected["selection"], "selection differs")
        check("selection " + path.name, sel)
    for path in sorted(ROOT.glob("playback-*.json")):
        def replay(path=path):
            vector = load(path)
            night = Night(vector["selection"])
            for number, step in enumerate(vector["steps"], 1):
                night.apply(step)
                need(night.state() == step["expect"], f"step {number} ({step['event']}): {night.state()} != {step['expect']}")
                need(not (night.state()["sound"] and step.get("result") not in (None, "ok")), "a failure produced sound")
                need(night.timer in ("running", "expired"), "timer stopped by an error")
        check("playback " + path.name, replay)
    print("check-catalog: " + ("FAIL" if failures else "PASS"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
