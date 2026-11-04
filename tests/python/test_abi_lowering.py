# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Detect ABI lowering drift instead of treating successful C compilation as sufficient."""

import unittest

from tools import verify_abi


class AbiLoweringTest(unittest.TestCase):
    IR = """declare i32 @graal_create_isolate(ptr noundef, ptr noundef, ptr noundef)
declare i32 @graal_tear_down_isolate(ptr noundef)
declare i64 @smh_abi_tag(ptr noundef, i32 noundef)
declare i32 @smh_run(ptr noundef, ptr noundef)
"""

    def test_opaque_pointer_contract_accepts_clang_attributes(self):
        self.assertEqual(
            {"return": "i64", "parameters": ["ptr", "i32"]},
            verify_abi.signatures(self.IR)["smh_abi_tag"],
        )

    def test_missing_duplicate_width_pointer_and_calling_convention_drift_fail(self):
        for wrong in (
            self.IR.replace("@smh_run", "@different"),
            self.IR + "declare i32 @smh_run(ptr, ptr)\n",
            self.IR.replace("declare i64", "declare i32"),
            self.IR.replace("ptr noundef", "i64 noundef"),
            self.IR.replace("declare i64", "declare fastcc i64"),
            self.IR.replace("declare i64", "declare cc 10 i64"),
            self.IR.replace("i32 noundef)", "i32 signext)"),
            self.IR.replace("i32 noundef)", "i32 inreg)"),
        ):
            with self.subTest(wrong=wrong), self.assertRaises(ValueError):
                verify_abi.signatures(wrong)


if __name__ == "__main__":
    unittest.main()
