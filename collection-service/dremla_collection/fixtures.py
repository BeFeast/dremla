"""Invented metadata; all audio is synthesized locally, never downloaded."""
from copy import deepcopy

from .urls import track_id

SINGLE = "https://www.youtube.com/watch?v=fxAmbient01"
PLAYLIST = "https://www.youtube.com/playlist?list=PLFixtureCalm01"


def item(video, title, **extra):
    return dict(source_id=video, id=track_id(video), title=title, artist="Fixture Ensemble",
                duration_sec=90, source_url="https://www.youtube.com/watch?v=" + video,
                license="CC0-1.0", category="ambient", **extra)


CATALOGUE = {
    "fxAmbient01": item("fxAmbient01", "Still Lantern"),
    "fxPiano0001": item("fxPiano0001", "Paper Moonlight"),
    "fxFail00001": item("fxFail00001", "Missing Lantern", acquisition_failure=True),
    "fxQuiet0001": item("fxQuiet0001", "Uneven Horizon", high_lra=True),
}
PLAYLISTS = {"PLFixtureCalm01": list(CATALOGUE)}


def catalogue_item(video):
    return deepcopy(CATALOGUE[video])
