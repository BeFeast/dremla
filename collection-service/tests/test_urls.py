import unittest
from unittest.mock import patch

from dremla_collection.adapters import FixtureAcquisition
from dremla_collection.common import ServiceError
from dremla_collection.fixtures import SINGLE, PLAYLIST
from dremla_collection.urls import parse_url, track_id
from support import ServiceCase


class URLTests(unittest.TestCase):
    def test_accepted_forms_and_canonical_identity(self):
        for host in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"):
            for suffix in ("", "&list=PLFixtureCalm01", "&t=5"):
                source = parse_url(f"https://{host}/watch?v=fxAmbient01" + suffix)
                self.assertEqual((source.kind, source.source_id, source.canonical), ("track", "fxAmbient01", SINGLE))
        self.assertEqual(parse_url("https://youtu.be/fxAmbient01?si=fixture").canonical, SINGLE)
        self.assertEqual(parse_url(PLAYLIST).kind, "playlist")

    def test_refused_forms(self):
        urls = ["http://youtube.com/watch?v=fxAmbient01", "https://other.example/watch?v=fxAmbient01",
                "https://youtube.com.evil.example/watch?v=fxAmbient01", "https://user@youtube.com/watch?v=fxAmbient01",
                "https://youtube.com:443/watch?v=fxAmbient01", "https://youtube.com:/watch?v=fxAmbient01",
                "https://youtube.com/watch", "https://youtube.com/watch?v=short", "https://youtu.be/",
                "https://youtu.be/fxAmbient01/extra", "https://youtube.com/playlist?list=",
                "https://youtube.com/watch?v=fxAmbient01&v=fxPiano0001", "https://youtube.com/watch?v=fxAmbient01&list=bad%20id",
                "https://youtube.com/watch?v=fxAmbient0!", "https://youtube.com/shorts/fxAmbient01",
                "https://youtube.com/watch?v=fxAmbient01#part", " https://youtube.com/watch?v=fxAmbient01",
                "https://youtube.com/wa\ntch?v=fxAmbient01", "https://youtube.com\\@other.example/watch?v=fxAmbient01",
                "https://youtube.com/playlist?list=%2F..", None, 12, {}]
        for value in urls:
            with self.subTest(value=value), self.assertRaises(ServiceError):
                parse_url(value)

    def test_stable_ids_single_playlist_and_reimport(self):
        adapter = FixtureAcquisition()
        single = adapter.resolve(SINGLE)["items"][0]
        playlist = adapter.resolve(PLAYLIST)["items"]
        self.assertEqual(single, next(i for i in playlist if i["id"] == single["id"]))
        self.assertEqual(single["id"], adapter.resolve("https://youtu.be/fxAmbient01")["items"][0]["id"])
        self.assertEqual(track_id("fxAmbient01"), "yt-fxAmbient01")


class DraftTests(ServiceCase):
    def test_invalid_url_refused_before_adapter(self):
        with patch.object(self.service.acquisition, "resolve") as resolve:
            self.assert_code("invalid-url", lambda: self.service.add_draft("https://other.example/track"))
            resolve.assert_not_called()

    def test_unknown_fixture_and_metadata_only_resolution(self):
        with patch.object(self.service.acquisition, "fetch") as fetch:
            draft = self.service.add_draft(PLAYLIST)
            self.assertEqual(len(draft["items"]), 4)
            fetch.assert_not_called()
        self.assert_code("unknown-fixture", lambda: self.service.add_draft("https://youtu.be/fxUnknown01"))
        self.assertFalse((self.root / "state/work").exists())

    def test_dedup_against_current_and_edit_fields(self):
        self.approved()
        self.service.publish(0, True)
        draft = self.service.add_draft(PLAYLIST)
        first = draft["items"][0]
        self.assertTrue(first["inCollection"])
        self.assertFalse(first["include"])
        other = draft["items"][1]
        edited = self.service.edit_item(draft["id"], other["id"], {"title": "New Lantern", "category": "piano", "include": False})
        self.assertEqual((edited["title"], edited["category"], edited["include"]), ("New Lantern", "piano", False))
        for changes in ({"include": "yes"}, {"title": ""}, {"category": "rock"}, {"id": "changed"}):
            self.assert_code("invalid-request", lambda: self.service.edit_item(draft["id"], other["id"], changes))
        self.service.close()
        self.service = self.open_service()
        self.assertEqual(self.service.draft(draft["id"])["items"][1], edited)
