# AGENTS.md — snapvm maintainer notes

Standalone, dependency-free Java bytecode interpreter designed for
serializable VM state (snapshots). Consumed by JL-Modern as its future
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

- Written from scratch for JL-Modern (Oct 2025). SquirrelJME's SpringCoat
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
  - `pop2` (0x58) dropped only ONE slot for two category-1 values — the
    `WIDE`-conditional second pop was wrong (pop2 always consumes two slots).
    Obfuscators emit `getstatic arr; arraylength; pop2` inside argument
    lists; the residue shifted every following arg one slot (`Lbx;` params
    arrived as Integer 0 → `invokevirtual` fell into host dispatch →
    "host class missing bx", thread death → frozen screen). Tests:
    `pop2TwoSingles` (hand-assembled residue-shifts-args shape),
    `pop2WidePair`.
  - Diagnostic: when `invokevirtual`/`invokeinterface` sees a non-VmObject
    receiver but the declared owner is a **guest** class (verifier-typed
    slot, so always a VM bug), the interpreter dumps recv type + frame
    locals + operand stack + the whole guest frame chain to stderr before
    host dispatch would mask it as "host class missing". This is how the
    pop2 corruption was localized to `c.l.b`'s locals in one shot.
  - `executor`/`executorThread` are now held ONLY for the duration of
    `Interpreter.execute` (helper `runSlice`), restored in finally.
    Previously pump() bound executorThread for its whole body and
    runUntilDone across park waits, and a VmError escaping execute skipped
    the restore — a stale `executorThread==current && executor==null` pair
    made every later callFromHost on that thread NPE on `cur.frames`, and
    the caller (JL-Modern CanvasEvent) swallowed it: keyReleased/paint
    callbacks vanished → stuck fire/runaway movement/latency on device.
    callFromHost also falls back to the fresh-thread path if executor is
    null. Test: `callFromHostWithStaleExecutorThread` (reflection-poisons
    the pair, proves delivery instead of NPE).
