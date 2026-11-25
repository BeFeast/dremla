"""Fixture acquisition and processing; standard library only, no network I/O."""
from array import array
import math
from pathlib import Path
import sys
import wave

from .common import checkpoint, require, ServiceError
from .fixtures import CATALOGUE, PLAYLISTS, catalogue_item
from .urls import parse_url


def pcm_bytes(samples):
    data = array("h", samples)
    if sys.byteorder != "little":
        data.byteswap()
    return data.tobytes()


def pcm_samples(raw):
    data = array("h")
    data.frombytes(raw)
    if sys.byteorder != "little":
        data.byteswap()
    return data


class FixtureAcquisition:
    def __init__(self, delay=0, fail_once=()):
        self.delay = delay
        self.fail_once = set(fail_once)

    def resolve(self, url):
        source = parse_url(url)
        ids = [source.source_id] if source.kind == "track" else PLAYLISTS.get(source.source_id, [])
        require(bool(ids) and all(i in CATALOGUE for i in ids), "unknown-fixture", "That source is not in the fixture catalogue.")
        return dict(kind=source.kind, source_url=source.canonical, items=[catalogue_item(i) for i in ids])

    def fetch(self, item, path, cancelled):
        checkpoint(cancelled, self.delay)
        if item["source_id"] in self.fail_once:
            self.fail_once.remove(item["source_id"])
            raise ServiceError("acquisition-failed", "Fixture acquisition failed once; retry is available.")
        require(not item.get("acquisition_failure"), "acquisition-failed", "This fixture always fails acquisition.")
        rate = 16000
        with wave.open(str(path), "wb") as out:
            out.setparams((1, 2, rate, 0, "NONE", "not compressed"))
            for second in range(item["duration_sec"]):
                checkpoint(cancelled, self.delay)
                # Alternating 15 s plateaus yield >12 LU LRA, without metadata tricks
                # in the real processor. Both plateaus remain inside the loudness gate.
                amp = (0.015 if (second // 15) % 2 == 0 else 0.12) if item.get("high_lra") else 0.08 * (1 + 0.15 * math.sin(2 * math.pi * second / 30))
                samples = (int(32767 * amp * (math.sin(2 * math.pi * 220 * n / rate)
                           + 0.25 * math.sin(2 * math.pi * 331 * n / rate))) for n in range(rate))
                out.writeframesraw(pcm_bytes(samples))


class FixtureProcessor:
    def __init__(self, delay=0):
        self.delay = delay

    def process(self, source, item, workdir, cancelled):
        audio, preview = Path(workdir) / "audio.wav", Path(workdir) / "preview.wav"
        with wave.open(str(source), "rb") as inp:
            rate, count = inp.getframerate(), inp.getnframes()
            require(inp.getnchannels() == 1 and inp.getsampwidth() == 2, "processing-failed", "Expected mono PCM fixture.")
            total = 0
            while raw := inp.readframes(rate):
                checkpoint(cancelled, self.delay)
                total += sum(s * s for s in pcm_samples(raw))
            rms = math.sqrt(total / max(1, count))
            gain = (0.04 * 32767) / max(rms, 1)
            inp.rewind()
            with wave.open(str(audio), "wb") as out, wave.open(str(preview), "wb") as prev:
                out.setparams(inp.getparams())
                prev.setparams(inp.getparams())
                offset = 0
                while raw := inp.readframes(rate):
                    checkpoint(cancelled, self.delay)
                    samples = pcm_samples(raw)
                    faded = [int(max(-32768, min(32767, s * gain * min(1, (offset + n) / rate,
                              (count - offset - n - 1) / rate)))) for n, s in enumerate(samples)]
                    out.writeframesraw(pcm_bytes(faded))
                    if offset < rate * 20:
                        preview_samples = [int(s * min(1, max(0, rate * 20 - offset - n - 1) / rate))
                                           for n, s in enumerate(faded[:rate * 20 - offset])]
                        prev.writeframesraw(pcm_bytes(preview_samples))
                    offset += len(samples)
        rejected = ["LRA 18.0 LU > 12"] if item.get("high_lra") else []
        return dict(audio=audio.name, preview=preview.name, duration_sec=count / rate,
                    loudness={"i_lufs": -24.0, "lra": 18.0 if rejected else 1.0, "true_peak": -18.0},
                    flags=["short"] + (["lra-high"] if rejected else []), reject_reasons=rejected)
