# Conformance

Gate G3 is decided: native Swift core (2026-10-16, project owner). The conformance tests therefore live inside the Swift package as the test target `ios/GuandanCore/Tests/GuandanConformanceTests`, and this folder holds no code. It is kept only as a pointer.

Those tests read the shared fixtures in `rules/` and `core/src/test/resources/fixtures/` by path from the repository; nothing is copied into `ios/`. A test that fails there is a divergence between platforms and blocks WI-11 acceptance.

```sh
cd ios/GuandanCore && swift build && swift test
```

Today they check fixture structure only. The rules engine is NOT ported (WI-11 needs the implementation and a physical iPhone), and the report shows this as the skipped test `testRulesEnginePortIsPendingWI11`.
