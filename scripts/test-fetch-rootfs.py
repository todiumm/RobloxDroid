"""Offline regression tests for the actual rootfs download script."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().with_name("fetch-rootfs.sh")

class FetchRootfsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        shutil.copyfile(SCRIPT, self.root / "scripts/fetch-rootfs.sh")
        self.payload = b"verified rootfs fixture"
        self.asset = self.root / "app/src/main/assets/rootfs.tar.xz"
        self.asset.parent.mkdir(parents=True)
        self.manifest = self.root / "rootfs.properties"
        self.manifest.write_text("url=https://example.invalid/rootfs\nsha256=" + hashlib.sha256(self.payload).hexdigest() + "\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        curl = self.bin / "curl"
        curl.write_text("#!/bin/sh\nwhile [ \"$1\" != '-o' ]; do shift; done\nshift\nprintf '%s' \"$TEST_PAYLOAD\" > \"$1\"\nexit \"${TEST_EXIT:-0}\"\n")
        curl.chmod(0o700)

    def run_fetch(self, payload=None, code=0):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   TEST_PAYLOAD=(self.payload if payload is None else payload).decode(), TEST_EXIT=str(code))
        return subprocess.run(["bash", str(self.root / "scripts/fetch-rootfs.sh")], env=env, capture_output=True)

    def test_missing_asset_downloaded(self):
        self.assertEqual(self.run_fetch().returncode, 0)
        self.assertEqual(self.asset.read_bytes(), self.payload)

    def test_valid_asset_does_not_download(self):
        self.asset.write_bytes(self.payload)
        self.assertEqual(self.run_fetch(code=9).returncode, 0)

    def test_corrupt_existing_asset_replaced(self):
        self.asset.write_bytes(b"partial")
        self.assertEqual(self.run_fetch().returncode, 0)
        self.assertEqual(self.asset.read_bytes(), self.payload)

    def test_bad_checksum_not_published(self):
        self.asset.write_bytes(b"old")
        self.assertNotEqual(self.run_fetch(payload=b"bad").returncode, 0)
        self.assertEqual(self.asset.read_bytes(), b"old")
        self.assertEqual(list(self.asset.parent.glob(".rootfs-*")), [])

    def test_network_failure_not_published(self):
        self.assertNotEqual(self.run_fetch(code=22).returncode, 0)
        self.assertFalse(self.asset.exists())

if __name__ == "__main__":
    unittest.main()
