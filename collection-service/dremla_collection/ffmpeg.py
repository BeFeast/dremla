"""Local ffmpeg processor, with cooperative subprocess cancellation.

Ported from prototype catalog builder build_catalog.py, sha256 06260f7ebdfc8b5c1173cdeae82fab8aec7ec6730d2952b780bd133abc99c5b5
Only the analysis, encode, preview and decision functions are ported. No acquisition,
metadata ingestion, catalog caps, piano leveler or machine classifiers are included.
"""
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile

from .common import checkpoint, require

TARGET_I, TARGET_TP, LN_LRA = -24.0, -2.0, 20.0
MAX_LRA, MAX_ST_OVER_I = 12.0, 10.0
FLAG_LRA, FLAG_ST_OVER_I = 8.0, 6.0
EDGE_LU = 6.0
FRAME_RE = re.compile(r"t:\s*([\d.]+)\s+TARGET:\S+ LUFS\s+M:\s*(-?[\d.]+|-?inf|nan)\s+S:\s*(-?[\d.]+|-?inf|nan)")


def run(args, cancelled):
    # Disk-backed stderr avoids pipe deadlock while cancellation is polled.
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as error:
        process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=output, stderr=error, start_new_session=True)
        try:
            while process.poll() is None:
                checkpoint(cancelled, 0.05)
            # A control-group stop can reach the child before the main thread's
            # 0.2 s signal poll. Allow cancellation to catch up before reporting a
            # tool failure. ffmpeg also uses 255 for a handled termination signal.
            checkpoint(cancelled, 1 if process.returncode < 0 or process.returncode == 255 else 0)
            require(process.returncode == 0, "processing-failed", "The local media tool failed.")
            output.seek(0)
            error.seek(0)
            return output.read().decode(), error.read().decode()
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def ff(args, cancelled):
    return run(["ffmpeg", "-hide_banner", "-nostats", "-nostdin", *map(str, args)], cancelled)[1]


def summary(err):
    tail = err[err.rfind("Summary:"):]
    def g(pattern):
        match = re.search(pattern, tail)
        require(match is not None, "processing-failed", "Media analysis was incomplete.")
        return float(match[1])
    return dict(i=g(r"I:\s*(-?[\d.]+) LUFS"),
                thresh=g(r"I:.*?\n\s*Threshold:\s*(-?[\d.]+) LUFS"),
                lra=g(r"LRA:\s*(-?[\d.]+) LU"),
                tp=g(r"Peak:\s*(-?[\d.]+) dBFS") if re.search(r"Peak:\s*-?[\d.]+", tail) else None)


def loudnorm_json(err):
    i = err.rfind("Parsed_loudnorm")
    return json.loads(err[err.index("{", i):err.index("}", i) + 1])


def fnum(x):
    try:
        value = float(x)
        return value if math.isfinite(value) else -120.0
    except ValueError:
        return -120.0


def analyze(source, cancelled):
    err = ff(["-protocol_whitelist", "file,pipe", "-i", source, "-map", "0:a:0", "-af",
              "ebur128=peak=true:framelog=info", "-f", "null", "-"], cancelled)
    frames = [(float(t), fnum(m), fnum(s)) for t, m, s in FRAME_RE.findall(err)]
    require(bool(frames), "processing-failed", "No audio frames were measured.")
    sm = summary(err)
    dur, integrated = frames[-1][0], sm["i"]
    st = [s for t, m, s in frames if t >= 3.0]
    head = [m for t, m, s in frames if 0.4 <= t <= 1.0]
    tail = [m for t, m, s in frames if t >= dur - 1.0]
    head10 = [m for t, m, s in frames if 0.4 <= t <= 10.0]
    tail10 = [m for t, m, s in frames if t >= dur - 10.0]
    run_length = best = 0
    for t, m, s in frames:
        checkpoint(cancelled)
        if 15.0 <= t <= dur - 15.0 and m < -60.0:
            run_length += 1
            best = max(best, run_length)
        else:
            run_length = 0
    return dict(duration=dur, i=integrated, lra=sm["lra"], tp=sm["tp"],
                max_st=max(st) if st else integrated, head_m=max(head) if head else -120.0,
                tail_m=max(tail) if tail else -120.0,
                head10_max_m=max(head10) if head10 else -120.0,
                tail10_max_m=max(tail10) if tail10 else -120.0,
                longest_mid_silence_s=round(best * 0.1, 1),
                loudnorm_pass1=dict(input_i=integrated, input_tp=sm["tp"], input_lra=sm["lra"],
                                    input_thresh=sm["thresh"], target_offset=0.0))


def decide(a):
    rejected, flags = [], []
    if a["lra"] > MAX_LRA:
        rejected.append("LRA %.1f LU > %g" % (a["lra"], MAX_LRA))
    over = a["max_st"] - a["i"]
    if over > MAX_ST_OVER_I:
        rejected.append("max short-term %.1f LUFS is %.1f LU above integrated %.1f (> %g)" %
                        (a["max_st"], over, a["i"], MAX_ST_OVER_I))
    if a["lra"] > FLAG_LRA:
        flags.append("lra-high")
    if over > FLAG_ST_OVER_I:
        flags.append("loud-peaks")
    if a["head_m"] >= a["i"] - EDGE_LU:
        flags.append("abrupt-start")
    if a["tail_m"] >= a["i"] - EDGE_LU:
        flags.append("abrupt-end")
    if a["longest_mid_silence_s"] >= 8.0:
        flags.append("mid-silence")
    if a["duration"] < 300:
        flags.append("short")
    return rejected, flags


def duration(path, cancelled):
    out, _ = run(["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries",
                  "format=duration", "-of", "csv=p=0", str(path)], cancelled)
    return float(out.strip())


class FFmpegProcessor:
    def process(self, source, item, workdir, cancelled):
        a = analyze(source, cancelled)
        rejected, flags = decide(a)
        m, dur = a["loudnorm_pass1"], a["duration"]
        af = ("loudnorm=I={I}:TP={TP}:LRA={LRA}:measured_I={mi}:measured_TP={mtp}:measured_LRA={mlra}:"
              "measured_thresh={mth}:offset={off}:linear=true:print_format=json,"
              "aresample=48000,afade=t=in:st=0:d=3,afade=t=out:st={fo:.3f}:d=5").format(
            I=TARGET_I, TP=TARGET_TP, LRA=LN_LRA, mi=m["input_i"], mtp=m["input_tp"], mlra=m["input_lra"],
            mth=m["input_thresh"], off=m["target_offset"], fo=max(0.0, dur - 5.0))
        audio, preview = Path(workdir) / "audio.opus", Path(workdir) / "preview.mp3"
        err = ff(["-y", "-protocol_whitelist", "file,pipe", "-i", source, "-map", "0:a:0", "-map_metadata", "-1",
                  "-af", af, "-ac", "2", "-c:a", "libopus", "-b:a", "96k", "-vbr", "on", "-application", "audio", audio], cancelled)
        ln2 = loudnorm_json(err)
        if ln2.get("normalization_type") != "linear":
            flags.append("loudnorm-dynamic")
        measured = summary(ff(["-protocol_whitelist", "file,pipe", "-i", audio, "-af",
                               "ebur128=peak=true:framelog=quiet", "-f", "null", "-"], cancelled))
        out_dur = duration(audio, cancelled)
        start = max(0.0, min(out_dur * 0.25, out_dur - 61.0))
        ff(["-y", "-ss", "%.3f" % start, "-t", "60", "-protocol_whitelist", "file,pipe", "-i", audio,
            "-map_metadata", "-1", "-af", "afade=t=in:st=0:d=2,afade=t=out:st=58:d=2",
            "-c:a", "libmp3lame", "-b:a", "128k", preview], cancelled)
        return dict(audio=audio.name, preview=preview.name, duration_sec=out_dur,
                    loudness={"i_lufs": measured["i"], "lra": measured["lra"], "true_peak": measured["tp"]},
                    flags=flags, reject_reasons=rejected, analysis=a)
