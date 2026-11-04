# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Constrain the local Myna adaptation to the ABI shapes actually exercised."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools import myna_bindings as myna


class MynaBindingsTest(unittest.TestCase):
    def test_every_target_has_matching_fingerprints_and_supported_abi_shapes(self):
        fingerprints = set()
        for target in myna.TARGETS:
            directory = myna.ROOT / "abi/generated" / target
            data = (directory / "seam.json").read_bytes()
            description = json.loads(data)
            myna.validate_description(description)
            fingerprint = hashlib.sha256(data).hexdigest()
            self.assertEqual(fingerprint, (directory / "seam.abi").read_text().strip())
            for source in ("SmhNative.java", "seam.rs"):
                self.assertIn(fingerprint, (directory / source).read_text())
            fingerprints.add(fingerprint)
        self.assertEqual(6, len(fingerprints))

    def test_unsupported_abi_and_optimizer_facts_are_rejected(self):
        directory = myna.ROOT / "abi/generated" / myna.TARGETS[0]
        valid = json.loads((directory / "seam.json").read_text())
        for field in (
            "structs",
            "callbacks",
            "opaqueTypes",
            "returnType",
            "facts",
            "parameterType",
            "parameterFacts",
        ):
            value = copy.deepcopy(valid)
            if field in {"structs", "callbacks", "opaqueTypes"}:
                value[field] = [{}]
            elif field == "parameterType":
                value["functions"][0]["abiParameters"][0]["type"] = "f64"
            elif field == "parameterFacts":
                value["functions"][0]["abiParameters"][0]["facts"] = ["nonnull"]
            else:
                value["functions"][0][field] = "f64" if field == "returnType" else ["nounwind"]
            with self.subTest(field=field), self.assertRaises(ValueError):
                myna.validate_description(value)

    def test_windows_adaptation_rejects_changed_upstream_anchors(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            validator = source / "dev/elide/myna/validate/Validator.java"
            types = source / "dev/elide/myna/backend/Types.java"
            validator.parent.mkdir(parents=True)
            types.parent.mkdir(parents=True)
            validator.write_text("changed upstream")
            with self.assertRaisesRegex(ValueError, "target table"):
                myna.extend_windows_subset(source)
            validator.write_text('"x86_64-unknown-linux-gnu");')
            types.write_text("changed upstream")
            with self.assertRaisesRegex(ValueError, "extension policy"):
                myna.extend_windows_subset(source)
            validator.write_text('"x86_64-unknown-linux-gnu");')
            types.write_text('if (target.equals("aarch64-unknown-linux-gnu")) return "";')
            myna.extend_windows_subset(source)
            self.assertIn('"aarch64-pc-windows-msvc"', validator.read_text())
            self.assertIn('target.contains("-windows-")', types.read_text())


if __name__ == "__main__":
    unittest.main()
