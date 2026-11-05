# Changelog

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
