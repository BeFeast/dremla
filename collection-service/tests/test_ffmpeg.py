import math
import random
import shutil
import threading
import subprocess
import time
from unittest.mock import patch
import wave

from dremla_collection.adapters import FixtureAcquisition, pcm_bytes, pcm_samples
from dremla_collection.common import Cancelled
from dremla_collection.ffmpeg import FFmpegProcessor, analyze, decide, duration, ff, summary
from dremla_collection.fixtures import catalogue_item
from support import ServiceCase
import unittest


class DecisionTests(unittest.TestCase):
    def test_prototype_thresholds_and_flags(self):
        base = dict(i=-24, lra=12, max_st=-14, head_m=-30, tail_m=-30,
                    longest_mid_silence_s=8, duration=299)
        rejected, flags = decide(base)
        self.assertEqual(rejected, [])
        self.assertEqual(flags, ['lra-high', 'loud-peaks', 'abrupt-start', 'abrupt-end', 'mid-silence', 'short'])
        self.assertEqual(len(decide(dict(base, lra=12.1, max_st=-13.9))[0]), 2)
        self.assertEqual(decide(dict(base, lra=8, max_st=-18, head_m=-30.1, tail_m=-30.1,
                                     longest_mid_silence_s=7.9, duration=300)), ([], []))


@unittest.skipUnless(shutil.which("ffmpeg"), "SKIPPED (no ffmpeg)")
class FFmpegTests(ServiceCase):
    def test_shipped_opus_tone_and_noise_and_mp3_preview(self):
        event = threading.Event()
        item = catalogue_item("fxAmbient01")
        work = self.root / "ffmpeg"
        work.mkdir()
        source = work / "source.wav"
        for signal in ("tone", "noise"):
            with self.subTest(signal=signal):
                if signal == "tone":
                    FixtureAcquisition().fetch(item, source, event)
                else:
                    rng = random.Random(42)
                    chunk = pcm_bytes(rng.randint(-4000, 4000) for _ in range(16000))
                    with wave.open(str(source), "wb") as out:
                        out.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                        for second in range(90):
                            gain = 1 + 0.15 * math.sin(2 * math.pi * second / 30)
                            out.writeframesraw(pcm_bytes(int(s * gain) for s in pcm_samples(chunk)))
                result = FFmpegProcessor().process(source, item, work, event)
                actual = summary(ff(["-i", work / result["audio"], "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"], event))
                self.assertAlmostEqual(actual["i"], -24, delta=1)
                self.assertEqual(result["loudness"]["i_lufs"], actual["i"])
                self.assertLessEqual(actual["tp"], -1.0)
                self.assertEqual(result["reject_reasons"], [])
                self.assertNotIn("loudnorm-dynamic", result["flags"])
                self.assertEqual((work / result["audio"]).suffix, ".opus")
                self.assertEqual((work / result["preview"]).suffix, ".mp3")
                self.assertLessEqual(duration(work / result["preview"], event), 61)
                self.assertGreater(duration(work / result["preview"], event), 59)
                print(f"ffmpeg {signal}: {actual['i']} LUFS, LRA {actual['lra']}, peak {actual['tp']}; preview {duration(work / result['preview'], event):.3f} s", flush=True)

    def test_real_high_lra_input_rejected(self):
        event = threading.Event()
        item = catalogue_item("fxQuiet0001")
        work = self.root / "high-lra"
        work.mkdir()
        source = work / "source.wav"
        FixtureAcquisition().fetch(item, source, event)
        # Remove fixture rejection metadata: rejection must be based on measured audio.
        item.pop("high_lra")
        result = FFmpegProcessor().process(source, item, work, event)
        self.assertGreater(result["analysis"]["lra"], 12)
        self.assertTrue(any(reason.startswith("LRA ") for reason in result["reject_reasons"]))
        self.assertIn("lra-high", result["flags"])
        print("ffmpeg rejection:", result["reject_reasons"], flush=True)

    def test_cancels_long_subprocess(self):
        event = threading.Event()
        timer = threading.Timer(0.2, event.set)
        timer.start()
        children = []
        popen = subprocess.Popen
        def record(*args, **kwargs):
            self.assertIs(kwargs.get("start_new_session"), True)
            child = popen(*args, **kwargs)
            children.append(child)
            return child
        started = time.monotonic()
        try:
            with patch("dremla_collection.ffmpeg.subprocess.Popen", side_effect=record):
                with self.assertRaises(Cancelled):
                    ff(["-re", "-f", "lavfi", "-i", "sine=frequency=220", "-t", "120", "-f", "null", "-"], event)
            self.assertEqual(len(children), 1)
            self.assertIsNotNone(children[0].poll(), "Cancelled must reap its ffmpeg child")
            self.assertLess(time.monotonic() - started, 5)
        finally:
            timer.cancel()
            timer.join()
            for child in children:
                if child.poll() is None:
                    child.kill()
                    child.wait()
