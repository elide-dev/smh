# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Reject incomplete and misleading compiler evidence, including broken controls."""

import tempfile
import unittest
from pathlib import Path

from tools import qualify_compiler as proof


def cfg(body, stage="After FinalCodeAnalysisStage"):
    return f'begin_cfg\nname "{stage}"\nLIR\n{body}\nend_cfg'


def call(callee=None, frame="unknown", outer=""):
    target = f"HostedMethod<{callee} void>" if callee else "null"
    return (
        f"nr 1 <|@ instruction CALL_DIRECT callTarget: {target}\n"
        f"reference-map: []\n{frame}\n{outer}\n<|@ <|@\n"
    )


class CompilerEvidenceTest(unittest.TestCase):
    def graph(self, lir, text=""):
        return {"path": "fixture.cfg", "sha256": "fixture-digest", "lir": lir, "text": text}

    def test_only_one_final_lir_stage_is_accepted(self):
        final = cfg("instruction RETURN")
        self.assertIn("RETURN", proof.final_lir(cfg("instruction EARLY", "early") + final))
        for invalid in ("", cfg("instruction EARLY", "early"), final + final, cfg("nothing")):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                proof.final_lir(invalid)

    def test_graph_loader_requires_native_hosted_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.cfg"
            path.write_text('method "HotSpotCompilation-[Some.method()]"\n' + cfg("instruction X"))
            with self.assertRaises(ValueError):
                proof.load_graphs(temporary)
            path.write_text(
                'method "SubstrateHostedCompilation-1[dev.elide.smh.generated.Benchmarks$B0.invoke()]"\n'
                + cfg("instruction RETURN")
            )
            graphs = proof.load_graphs(temporary)
            self.assertEqual(["dev.elide.smh.generated.Benchmarks.B0.invoke"], list(graphs))
            self.assertEqual(proof.sha256(path), next(iter(graphs.values()))[0]["sha256"])

    def test_sinks_need_both_intrinsic_nodes_and_final_lir_for_every_adapter(self):
        name = "dev.elide.smh.generated.Benchmarks$B0.invoke"
        key = name.replace("$", ".")
        inventory = [{"adapter": name, "sink": "jmh-1.37-consume"}]
        node = "jdk.graal.compiler.nodes.debug.BlackholeNode"
        good = self.graph("instruction BLACKHOLE value: v0", node)
        self.assertEqual(1, proof.verify_sinks({key: [good]}, inventory)[0]["finalBlackholes"])
        for graphs, cases in (
            ({}, inventory),
            ({key: [good]}, []),
            ({key: [self.graph("instruction RETURN", node)]}, inventory),
            ({key: [self.graph("instruction BLACKHOLE value: v0")]}, inventory),
            ({key: [good]}, [{"adapter": name, "sink": "none"}]),
        ):
            with self.subTest(graphs=graphs), self.assertRaises(ValueError):
                proof.verify_sinks(graphs, cases)
        disabled = self.graph("instruction RETURN")
        self.assertEqual(
            0,
            proof.verify_disabled_sinks({key: [disabled]}, inventory)["methods"][0][
                "finalBlackholes"
            ],
        )
        # Missing graphs are failed experiments, never successful negative controls.
        for graphs, cases in (({}, inventory), ({key: [good]}, inventory), ({key: [disabled]}, [])):
            with self.assertRaises(ValueError):
                proof.verify_disabled_sinks(graphs, cases)

    def test_scheduled_borders_handle_indirect_clocks_and_ignore_outer_error_frames(self):
        clock = call(frame="Posix.clock_gettime(clock.java:1)")
        invoke = call("BenchmarkCase.invoke")
        error = call(
            frame="VMError.shouldNotReachHere(error.java:1)", outer="System.nanoTime(Time.java:2)"
        )
        key = proof.PROOF + ".measure"
        good = self.graph(clock + error + invoke + clock)
        self.assertEqual(4, len(proof.verify_borders({key: [good]})[0]["orderedCalls"]))
        for invalid in (
            clock + clock + invoke,
            invoke + clock + clock,
            clock + invoke,
            clock + invoke + invoke + clock,
            error + invoke + clock,
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                proof.verify_borders({key: [self.graph(invalid)]})
        with self.assertRaises(ValueError):
            proof.verify_borders({})


if __name__ == "__main__":
    unittest.main()
