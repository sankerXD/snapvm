# snapvm

**A Java bytecode interpreter whose entire machine state is serializable.**

snapvm is an embeddable, dependency-free JVM-subset interpreter designed so that
the guest heap, object graph, operand stacks, call frames, threads and monitors
are plain data structures — not hidden inside a host JVM. That makes
**true arbitrary-time snapshots** possible: freeze the VM, write its state out,
restore it later, and resume execution at the exact same bytecode. This is the
missing piece emulator authors (J2ME and otherwise) have been looking for — you
cannot snapshot code running directly on ART/HotSpot, so you interpret it
yourself instead.

Originally built to replace the dex→ART execution core of
[JL-Mod-Modern](https://github.com/sankerXD/JL-Mod-Modern) (a J2ME emulator for
Android handhelds), where it will give 500+ retro Java games real save states.

## How it works

Guest objects (`VmObject`), classes (`VmClass`), frames (`Frame`) and threads
(`VmThread`) are ordinary Java objects you own. All guest threads run as
cooperative green threads on a single executor — there is no hidden host
threading, so the whole machine state is one object graph you can walk and
serialize. Calls into the host (`java.lang.*`, or your own APIs like MIDP) go
through a reflection bridge (`HostBridge`) with cached `Method` handles,
automatic marshaling, and `Proxy`-based callbacks for guest implementations of
host interfaces.

```
guest bytecode → snapvm interpreter ──invoke──→ host Java objects (native speed)
        │
        └ guest heap + stacks + threads = serializable object graph
```

## Status

Early development — **M1 milestone**: classfile parser and full Java 8
instruction-set interpreter working, verified by an on-the-fly compiled smoke
suite (arithmetic, branches/switch, objects + virtual dispatch + inheritance,
fields, arrays, exceptions + finally, `<clinit>`, String interning, long/double
wide operands, green threads with `start`/`join`).

Not yet implemented: snapshot serialization, `wait`/`notify` edge cases,
a CLDC class library, Android/J2ME host bindings.

## Build & test

Requires **JDK 17+** and nothing else — the Gradle wrapper is included.

```bash
./gradlew test     # compiles the VM + runs the smoke suite
./test.sh          # same thing, convenience wrapper
```

The interpreter itself is Java 8–compatible (`org.snapvm`, zero runtime
dependencies) and can be embedded as a plain jar — including on Android ART.

### Debugging

`Interpreter.TRACE = true` prints every executed guest instruction (frame, pc,
opcode, operand stack) to stderr.

## Contributing

PRs are welcome. Two rules are **hard requirements**:

1. **Every interpreter change must come with a smoke test** that proves the new
   behavior. Tests live in `src/test/java/org/snapvm/InterpreterTest.java` —
   fixture classes are written as source strings, compiled at test time with
   `javax.tools` to Java 8 bytecode (so no `invokedynamic`), then executed
   inside snapvm and asserted. Follow that pattern.
2. **Run the test script and paste the result** in your PR: `./test.sh` must
   end with `BUILD SUCCESSFUL` / `12 tests, 0 failures`. A change that compiles
   but has no passing test behind it will not be merged.

## License

Apache-2.0 — see [LICENSE](LICENSE).
