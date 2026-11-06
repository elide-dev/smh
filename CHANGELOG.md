# Changelog

## [0.3.0](https://github.com/elide-dev/smh/compare/v0.2.0...v0.3.0) (2026-11-06)


### Features

* add interleaved startup comparisons and live variant charts ([a96db77](https://github.com/elide-dev/smh/commit/a96db77df299214aa2179447100f684018bf6d0e))
* add startup and soak benchmark modes ([27b2694](https://github.com/elide-dev/smh/commit/27b2694f972ba31569f3c032e945edde3ab252d7))
* **cli:** prepare applications before benchmark launches ([681fafb](https://github.com/elide-dev/smh/commit/681fafb3b0a1b92a92db06d6a0ef572bb017fdc7))
* compare delegated JMH and Leyden HotSpot benchmarks ([d3b40a2](https://github.com/elide-dev/smh/commit/d3b40a2dd23548ea3a9b9e618a3d5ef62fa410a1))
* explain cache misses, lead with verdicts, and accept external binaries ([21ef0d9](https://github.com/elide-dev/smh/commit/21ef0d961f216aef05fe2f2c099e9050fc900d27))
* report actionable CLI errors with structured diagnostics ([902643a](https://github.com/elide-dev/smh/commit/902643ab613627ba5b6fd9cc07005db19dcc15be))
* reuse benchmark bytecode across AOT and JIT hosts ([7834c9c](https://github.com/elide-dev/smh/commit/7834c9c8dc951e4ab2be4535de84067759713768))


### Bug Fixes

* **ci:** make JVM, Rust, and Crema checks independent of host state ([300aa3b](https://github.com/elide-dev/smh/commit/300aa3b6e0ea467f620bfdff8a0dbc3531239b97))
* **cli:** reap Windows process trees and report bytecode cache status ([71867d8](https://github.com/elide-dev/smh/commit/71867d8c94dff61bb56a04bc328f7b4d917c0c50))
* **cli:** retry publishing build logs that a finished writer still holds ([9f6d763](https://github.com/elide-dev/smh/commit/9f6d7633685a8064dbffe7579789496fa698c162))
* **cli:** terminate Windows process trees with taskkill ([8bcb88e](https://github.com/elide-dev/smh/commit/8bcb88e0ead301e8679f119753adcde613748019))
* **cli:** unlock experimental options before disabling JVMCI on HotSpot ([0e75da3](https://github.com/elide-dev/smh/commit/0e75da3a42075381257669f5abe990a4c91eb9e4))
* **cli:** wait for terminated processes to report exit ([ce87746](https://github.com/elide-dev/smh/commit/ce877469cd6dd79105ab6e0474c2a2d26e04fc26))
* keep benchmark reports compact and free of build chatter ([616dc58](https://github.com/elide-dev/smh/commit/616dc58b3a4c007427caad17dc7f1db67918f0cc))

## [0.2.0](https://github.com/elide-dev/smh/compare/v0.1.1...v0.2.0) (2026-11-05)


### Features

* add visual benchmark results and cached application benchmarks ([1101038](https://github.com/elide-dev/smh/commit/11010381de7af9b4d9a21835cb7ae44490d85f0b))

## [0.1.1](https://github.com/elide-dev/smh/compare/v0.1.0...v0.1.1) (2026-11-05)


### Bug Fixes

* fall back to a selected JDK when Elide is unavailable ([4aecda2](https://github.com/elide-dev/smh/commit/4aecda2621e68754aa9073ae8ccdb58576e3e881))
* stage verified release assets before immutable publication ([366a8cd](https://github.com/elide-dev/smh/commit/366a8cd9901337fac149397608138831c56d9274))

## 0.1.0 (2026-11-05)


### Features

* introduce SMH AOT benchmark harness and release automation ([f754de4](https://github.com/elide-dev/smh/commit/f754de40d0749d49cf347098a41ff632c68d2aee))


### Bug Fixes

* **ci:** install Rust lint components and validate nextest JUnit defaults ([ab04b8a](https://github.com/elide-dev/smh/commit/ab04b8ae50d5ee2012f68d54617cf76622de7901))
* **ci:** preserve ABI bytes on Windows and stabilize import classification ([974a710](https://github.com/elide-dev/smh/commit/974a71039bf2c59e9a72f018aee28bf31d724a7e))
* **ci:** select host linkers and preserve generated changelog licensing ([8d51566](https://github.com/elide-dev/smh/commit/8d51566fe4d3269818838305e167af50fdb9b05c))
* **ci:** use verified Scorecard release and cancel superseded runs ([9b2e934](https://github.com/elide-dev/smh/commit/9b2e934ef43b31ed71ed74aa9fdd85aa1184cd84))
* **release:** isolate publishing builds from branch CI concurrency ([4cc96c2](https://github.com/elide-dev/smh/commit/4cc96c228c6d03ba2ed1bf1d913c03b4ed824c2e))
* **release:** keep CLI version and contract synchronized with releases ([94ceea4](https://github.com/elide-dev/smh/commit/94ceea4fb7ea37cebe39c9e752f1fdbbfc6ac9ec))


### Performance Improvements

* **ci:** install coverage tooling only in jobs that use it ([023641b](https://github.com/elide-dev/smh/commit/023641bd096ff95d1eaf2dbb8f3fa124ee755a81))
