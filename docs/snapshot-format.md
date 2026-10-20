# SnapVM snapshot format (v1)

The whole reason SnapVM exists: the entire machine state is plain data, so it
can be frozen to bytes at any scheduling boundary and revived later — true
arbitrary-frame save/load for J2ME.

Design rules:

- **Logical-value serialization, not memory-image serialization.** The stream
  describes guest *values* (ints, refs, strings, arrays, objects, thread
  records), never object layouts. `Frame` internals may be reimplemented
  (e.g. a future int[]+Object[] dual stack) without touching the format —
  the `WIDE` tag below is a stream marker, not the runtime sentinel.
- **Zero dependencies, Java 8.** `DataOutputStream`/`DataInputStream` only.
- **Explicit failure over silent corruption.** An unhandled host object or
  class mismatch throws `VmError` at save/load time, never a half-restored VM.
- **Class bytes are not serialized.** Restore resolves `VmClass`es by name
  through the embedder's own `ClassSource`s — same jars, same layout. The
  snapshot therefore captures only per-class *runtime* state (statics, init
  flags); field/static/method tables are re-derived identically.
- **One object-id space.** Strings, VmObjects, arrays, Thread boxes and
  codec'd host objects all share a single identity→id table. A `REF` writes
  its payload inline at first occurrence; later occurrences are the bare id,
  so shared identity and cycles survive exactly (`interned`-string `==`
  included). The reader reserves the id slot before reading the payload, so
  handle records nested inside their parent's payload still resolve.

## Stream layout

```
magic   "SNAP" + i16 version(1)
globals { i32 nProps, {str k, str v}*, i32 nInterned, value[nInterned] }
classes { i32 n, {str name, bool initDone, bool initRunning, i32 initThreadId,
                  i32 nStatics, value[nStatics]}* }
threads { i32 n, ThreadRec* }
monitors{ i32 n, {value object, i32 ownerThreadId, i32 count,
                  i32 nWaiters, i32 waiterTid[]}* }   // non-empty monitors only
arrays  { i32 n, {i32 objId, str componentClassName}* }   // guestArrayTypes
boxes   { i32 n, {value boxObj, i32 vmThreadId, value runnable}* }
proxies { i32 n, {str ifaceClassName, value guestObj}* }
rrCursor i32
```

`str` = i32 byte-length + UTF-8 bytes. Thread ids are pre-assigned 1..N in
list order before any record is written, so waiter/owner/initThread refs are
plain i32s resolved after all records are read.

## Value tags

| tag | payload | notes |
|---|---|---|
| 0 NULL | — | |
| 1 INT | i32 | also Z/B/C/S primitives (Integer-normalized in guest space) |
| 2 LONG | i64 | |
| 3 FLOAT | f32 | |
| 4 DOUBLE | f64 | |
| 5 BOOL | u8 | reference-slot boxed Boolean (kept for checkcast) |
| 6 BYTE | i8 | boxed Byte |
| 7 SHORT | i16 | boxed Short |
| 8 CHAR | u16 | boxed Character |
| 9 WIDE | — | second slot of J/D in locals/operand-stack sequences |
| 10 REF | i32 objId (+u8 kind + payload on first occurrence) | shared id table |
| 11 CLASS | str name | GuestClass → resolveClass(name).guestClass() |

REF kinds: `STR`(str), `OBJ`(below), `THREAD_BOX`, `HANDLE`(str codecClass +
value record), `THROWABLE`(str className + str message — revived reflectively
via (String) or () ctor), `PROXY`(value guestObj — a host interface proxy in
guest state unwraps to its guest), `POBJ`(— — plain `java.lang.Object`, a
pure identity token restored as `new Object()`),
`ARR_B/S/C/I/Z/J/F/D`(i32 len + raw elements), `ARR_OBJ`(i32 len + values —
legacy; revived as bare `Object[]`), `ARR_TYPED`(str componentClassName +
i32 len + values — revived via `Array.newInstance` so `int[][]`/`String[]`
keep their runtime type; guest component type lives in the `arrays`
section). New writes always emit `ARR_TYPED`.

Boxed scalars serialize by value — their `==` identity across a save is not
preserved (irrelevant: J2ME code cannot observe it — `if_acmp` on boxeds is
already unreliable on the host).

## OBJ payload

```
str className, i32 nFields, value[nFields],      // nFields must match layout
shadow: bool present + str hostBaseClass + value record,
superCtorArgs: value (Object[] or NULL),
exceptionCause: value,
exceptionTrace: bool + str
```

On restore the VmObject is built first (so field cycles resolve), then
`constructShadow` re-creates the host side via the registered
`ShadowFactory`, then `ShadowCodec.restore` folds the record back in. A
hostShadow with no registered codec **aborts the save**.

## ThreadRec

```
i32 threadId
u8 state                  // RUNNABLE/BLOCKED/WAITING/TIMED_WAITING/TERMINATED
i64 wakeDelta             // ms remaining for TIMED_WAITING (restored now+delta)
u8 waitMonKind: 0 none | 1 monitor(value object) | 2 PARKED | 3 clinit(str)
i32 waitReleasedCount, bool resumingFromWait
bool interrupted, pendingInterrupt, i32 interruptPc
i32 joinTargetId (0=none), i32 nJoinWaiters, i32 joinWaiterTid[]
value runTarget, value hostThread, value result, value nestedResult
bool hasLastError + value thrown
bool parked + str methodKey + i32 nArgs + value[nArgs]
i32 nFrames, FrameRec*
```

FrameRec: `str ownerClass, str methodName, str methodDesc, i32 pc, i32 sp,
i32 callPc, bool fromCall, bool external, value syncOn, i32 nLocals,
value[nLocals], value[sp stack slots]`.

## Restore semantics

- Restore into a `Vm` built exactly as for a cold boot (same ClassSources,
  ShadowFactories, FastCalls, ParkedCalls): `vm.restore(bytes)` wipes live
  state — every guest class resets to pre-init, all runtime maps clear —
  then the stream repopulates `statics`/`initDone` and everything else.
- Waiting threads come back in their scheduler state: `wait()` → WAITING on
  a rebuilt Monitor, `join` → WAITING + joinTarget, `sleep`/`wait(ms)` →
  TIMED_WAITING with `wakeTime = now + remaining`.
- `PARKED` threads (mid host call, e.g. `serviceRepaints`) **replay**: the
  recorded ParkedCall key + args are re-invoked after all state is loaded;
  the host handler performs its action again and `done()` wakes the thread.
- Interrupted flags, pendingInterrupt + interruptPc ride along, so a thread
  interrupted mid-sleep still throws IE at the same call site on resume.
- `guestArrayTypes`, `proxies`, `greenTargets`/`greenThreads` are rebuilt
  from their sections; `rrCursor` keeps round-robin fairness.
- `Handle` records must not reference the host object they describe (a
  record's inner REF to its own in-flight id fails loudly).

## API

```java
byte[] Vm.checkpoint();            // freezes under execLock — call at any
                                   // scheduling boundary (pump gap, UI action)
void   Vm.restore(byte[] snap);    // clears and repopulates all guest state

vm.registerHostHandle(Class<?> type, HostHandle h); // capture/revive records
vm.registerShadowCodec(Class<?> hostBase, ShadowCodec c);
```

Unregistered host objects reachable from guest state abort the save with a
`VmError` naming the class — the adapter adds codecs as real games demand.
Built-in codecs cover `java.util.Date` (epoch ms) and
`java.io.ByteArrayInputStream` (remaining bytes; position resets — observably
identical for read-once streams).
