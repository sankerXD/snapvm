# AGENTS.md — snapvm maintainer notes

Standalone, dependency-free Java bytecode interpreter designed for
serializable VM state (snapshots). Consumed by JL-Mod-Modern as its future
execution core; kept as an independent repo so the wider J2ME/emulation
community can embed it.

## Hard rule

**Every interpreter change must ship with a smoke test** that proves the
behavior. Fixture classes are source strings compiled at test time with
`javax.tools` (`-source 8 -target 8`, avoids invokedynamic) and run inside the
VM — see `InterpreterTest.java`. Run `./test.sh`; all tests green before
committing. A compile-only change is not acceptable.

## Build

JDK 17+ required for Gradle; sources are Java 8–compatible and Android-safe.

```bash
./gradlew test
```

## Design invariants — don't break these

- Guest state is plain data: `VmObject` (fields), `Frame` (locals + operand
  stack + pc), `VmThread` (frame list + scheduler state). No guest state may
  hide in host JVM internals — that's what makes snapshots possible.
- All guest threads are **green threads** on one executor thread. Do not spawn
  real threads for guest code. Host calls (`HostBridge`) run synchronously.
- Two-slot types use a value slot + `Frame.WIDE` marker on the operand stack
  and in locals.
- Multi-byte instructions must set `f.pc`/`f.sp` and `return` — the switch tail
  only applies `pc+1` to single-byte ops that `break`.
- Exception-table range checks use the **instruction that threw**: for a frame
  whose callee just popped, that's `frame.callPc` (the invoke site), not `f.pc`.
- Host classes are resolved lazily via `Class.forName`; guest classes via
  `Vm.ClassSource`. `java.lang.Object/String/Class/Thread` semantics live in
  `HostBridge` builtins, not reflection on guest objects.
- `Interpreter.TRACE = true` = instruction-level stderr trace; keep it.

## History notes

- Written from scratch for JL-Mod-Modern (Oct 2025). SquirrelJME's SpringCoat
  was studied as an architectural reference (object-graph VM state) but no code
  was copied.
- M3 real-game fixes (Oct 2025):
  - `Class.getResourceAsStream` with a **host `Class` receiver** (the classic
    `Runtime.getRuntime().getClass()` jar-resource idiom) now routes through
    `vm.getResource` before falling back to host reflection; relative paths
    normalize against the class's package. Test: `hostClassResourceAsStream`.
  - Guest `System.getProperty` reads `j2meProps`; the JL-Modern adapter
    mirrors the whole host property table (profile `SystemProperties`
    included), matching what dex-path guests see.
