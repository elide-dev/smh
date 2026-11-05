# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Release bundles must reject corrupt layers and mismatched reusable hosts."""

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from tools import package_release


class ReleaseLayersTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / "build/lib").mkdir(parents=True)
        (self.root / "build/crema").mkdir()
        (self.root / "Cargo.toml").write_text('[package]\nversion = "1.2.3"\n')
        for name in ("LICENSE", "LICENSE-APACHE", "LICENSE-MIT", "README.md"):
            (self.root / name).write_text("fixture")
        for name in (
            "smh.jar",
            "smh-runtime.jar",
            "smh-jmh.jar",
            "lib/test.jar",
            "smh",
            "smh-bootstrap",
            "crema/smh-crema",
            "crema/libjava.so",
        ):
            (self.root / "build" / name).write_text("artifact marker, never executed")
        digest = hashlib.sha256((self.root / "build/crema/smh-crema").read_bytes()).hexdigest()
        self.host = self.root / "build/crema/smh-crema.json"
        self.host.write_text(
            json.dumps(
                {
                    "sha256": digest,
                    "runtimeSha256": digest,
                    "files": {"smh-crema": digest, "libjava.so": digest},
                }
            )
        )
        self.layer = self.root / "build/layers" / ("a" * 64)
        self.layer.mkdir(parents=True)
        (self.layer / "smh-base.nil").write_bytes(b"layer marker")
        (self.layer / "SHA256").write_text(hashlib.sha256(b"layer marker").hexdigest())
        self.addCleanup(patch.stopall)
        patch.object(package_release, "ROOT", self.root).start()
        patch.object(package_release.subprocess, "check_output", return_value=b"revision\n").start()

    def test_separate_bundles_have_checksums_and_portable_layer_paths(self):
        ignored = self.root / "build/layers/pending-incomplete"
        ignored.mkdir()
        (ignored / "private-log").write_text("not published")
        package_release.main()
        dist = self.root / "build/dist"
        for line in (dist / "SHA256SUMS").read_text().splitlines():
            digest, filename = line.split()
            self.assertEqual(digest, hashlib.sha256((dist / filename).read_bytes()).hexdigest())
        with zipfile.ZipFile(dist / "smh-1.2.3-linux-amd64-layers.zip") as bundle:
            self.assertIn(f"layers/{'a' * 64}/smh-base.nil", bundle.namelist())
            self.assertFalse(any("pending" in name for name in bundle.namelist()))
        with zipfile.ZipFile(dist / "smh-1.2.3-linux-amd64-crema.zip") as bundle:
            self.assertIn("smh-jmh.jar", bundle.namelist())
            self.assertIn("crema/smh-crema.json", bundle.namelist())
            self.assertIn("crema/libjava.so", bundle.namelist())

    def test_bad_layers_and_hosts_are_not_packaged(self):
        (self.layer / "smh-base.nil").write_bytes(b"corrupted")
        with self.assertRaisesRegex(RuntimeError, "layer checksum"):
            package_release.main()
        (self.layer / "smh-base.nil").write_bytes(b"layer marker")
        host = json.loads(self.host.read_text())
        host["sha256"] = "bad"
        self.host.write_text(json.dumps(host))
        with self.assertRaisesRegex(RuntimeError, "host digest"):
            package_release.main()
        host["sha256"] = host["runtimeSha256"]
        host["runtimeSha256"] = "bad"
        self.host.write_text(json.dumps(host))
        with self.assertRaisesRegex(RuntimeError, "SDK mismatch"):
            package_release.main()
        host["runtimeSha256"] = host["sha256"]
        self.host.write_text(json.dumps(host))
        (self.root / "build/crema/libjava.so").write_bytes(b"corrupt native dependency")
        with self.assertRaisesRegex(RuntimeError, "native file mismatch"):
            package_release.main()
        (self.layer / "SHA256").unlink()
        with self.assertRaisesRegex(RuntimeError, "Prepare release layers"):
            package_release.main()
