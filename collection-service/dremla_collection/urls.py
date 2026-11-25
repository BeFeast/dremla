"""Parse source identity without making network requests."""
from dataclasses import dataclass
import re
from urllib.parse import parse_qs, urlsplit

from .common import ServiceError, require

HOSTS = {"www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}
VIDEO = r"[A-Za-z0-9_-]{11}"
PLAYLIST = r"[A-Za-z0-9_-]{2,100}"


@dataclass(frozen=True)
class SourceURL:
    kind: str
    source_id: str
    canonical: str


def track_id(video_id):
    require(isinstance(video_id, str) and re.fullmatch(VIDEO, video_id),
            "invalid-url", "Invalid video ID.")
    return "yt-" + video_id


def parse_url(value):
    invalid = ServiceError("invalid-url", "Use an HTTPS YouTube track or playlist URL without userinfo or a port.")
    if not isinstance(value, str) or re.search(r"[\s\\]", value):
        raise invalid
    try:
        u = urlsplit(value)
        if u.scheme != "https" or u.netloc.lower() not in HOSTS or u.fragment:
            raise invalid
        query = parse_qs(u.query, keep_blank_values=True)
        if any(len(v) != 1 for v in query.values()):
            raise invalid
        if u.hostname == "youtu.be":
            kind, identity = "track", u.path[1:] if u.path.startswith("/") else ""
        elif u.path == "/watch":
            kind, identity = "track", query.get("v", [""])[0]
        elif u.path == "/playlist":
            kind, identity = "playlist", query.get("list", [""])[0]
        else:
            raise invalid
        if not re.fullmatch(VIDEO if kind == "track" else PLAYLIST, identity):
            raise invalid
        if "list" in query and not re.fullmatch(PLAYLIST, query["list"][0]):
            raise invalid
    except ValueError:
        raise invalid from None
    canonical = "https://www.youtube.com/" + ("watch?v=" if kind == "track" else "playlist?list=") + identity
    return SourceURL(kind, identity, canonical)
