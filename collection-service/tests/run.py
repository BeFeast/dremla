#!/usr/bin/env python3
"""Isolated offline unittest discovery; outbound connections fail closed."""
from pathlib import Path
import shutil
import socket
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))
original_connect = socket.socket.connect
original_connect_ex = socket.socket.connect_ex


def only_loopback(address):
    if not isinstance(address, tuple) or address[0] not in ("localhost", "127.0.0.1", "::1"):
        raise AssertionError("Tests may connect only to loopback fixtures")


def connect(sock, address):
    only_loopback(address)
    return original_connect(sock, address)


def connect_ex(sock, address):
    only_loopback(address)
    return original_connect_ex(sock, address)


socket.socket.connect = connect
socket.socket.connect_ex = connect_ex
if not shutil.which("ffmpeg"):
    print("SKIPPED (no ffmpeg)", flush=True)
suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_*.py")
sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
