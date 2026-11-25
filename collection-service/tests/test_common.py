import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from dremla_collection.common import digest


class DigestTests(unittest.TestCase):
    def test_multichunk_digest_native_and_python38_fallback(self):
        content = bytes(range(256)) * 1025 + b"tail"
        expected = hashlib.sha256(content).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "chunks"
            path.write_bytes(content)
            self.assertEqual(digest(path), expected)
            with patch("dremla_collection.common.hashlib") as legacy:
                del legacy.file_digest
                legacy.sha256 = hashlib.sha256
                self.assertEqual(digest(path), expected)
