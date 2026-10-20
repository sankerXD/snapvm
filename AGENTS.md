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
- `step()` is an inlined `for(;;)` dispatch loop, not one-call-per-op.
  Neutral ops advance local `pc`/`sp` and `continue`; frame/state changes
  (invoke push, return pop, clinit push, park) set `frameChanged` or
  `return used`. Loop top syncs `f.pc = pc; f.sp = sp` — that anchors
  exception/handler pc and retry semantics; don't remove it.
- **`step()` bytecode must stay < 8000 bytes** (HotSpot `HugeMethodLimit`;
  over it the whole loop never JITs — measured 75M→10M ops/s). Cold ops
  live in helpers (`dupX2`/`tableSwitchOff`/`wideOp`/`newOp`/`checkCast`/
  `multiNewArray`); when adding cases, measure with `javap -c` first.
- Never write the loop-local `sp` back to `f.sp` on an early return after
  `invoke()` — `popToArray` already consumed the args; reverting
  resurrects them on the operand stack (caught as guest AIOOBE by
  `synchronizedMethod`). Frame state after invoke is whatever invoke left.

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
- Perf sprint + Pet Kingdom 6 bugs (Oct 2025):
  - Game `extends java.util.TimerTask` fed to `Timer.schedule` crashed
    marshal with "no shadow factory" → pump thread died → frozen screen.
    Fix: `SnapTimerTaskShadow` + `registerShadow`. Any host type a guest
    may subclass needs a registered shadow BEFORE the game does it.
    Test: `timerTaskShadow`.
  - Long shifts pushed `Integer` results without `Frame.WIDE`
    (lshr/lushr family) — next `land`/`lor` read `Long` and got CCE.
    Symptom surfaced deep in the game's resource decoder, nowhere near
    the faulting op. `TRACE` at the last-good instruction identified it.
    Test: `longShiftsKeepWide`.
  - `dup2_x2` "two singles over a wide pair" read the wide value from
    `sp-5` (should be `sp-4`) and wrote one slot low — with a 4-deep
    stack that's index -1; deeper stacks silently scrambled the pair.
    Test: `dup2x2TwoSinglesOverWide` (hand-assembled bytecode).
  - `pushInvoke`/`pushStatic` external frames: `doReturn*` used to push
    the return value onto the caller's operand stack unconditionally —
    but the caller was mid-instruction with no invoke-result slot, so a
    `String` polluted an `iadd` expression stack. `Frame.external` marks
    host-originated frames; their results go to `t.nestedResult` only.
    `callFromHost` on the executor thread had the same latent bug.
    Tests: `stringMarshalGuest`, `fastBuiltins` (guest-object toString
    via valueOf).
  - Android Gradle strips `.class` files from library jar resources —
    guest CLDC classes ship as `*.classdata` and `Vm`'s builtin
    ClassSource reads those. If a guest class suddenly resolves host-side
    on Android but guest-side on desktop, check the APK contents first.
  - Refactoring `step` to the inline loop, my early-out wrote the stale
    local `sp` back over `invoke()`'s consumed args → sleep left
    `[15, WIDE]` residue → later astore got an AIOOBE (see invariant
    above). The test suite caught it immediately; don't remove the
    per-iteration `f.pc`/`f.sp` sync to "save stores" — every throw site
    and helper relies on it.
- Silent Hill 3 bugs (Oct 2025) — three independent failures chained:
  - **Resource stream short reads**: `Vm.getResource` returned the raw
    `ZipFile` inflater stream; Android returns partial reads on DEFLATED
    entries (measured 468/471). The game's fixed-buffer `read(471)` left
    zero tail bytes → digest check failed → null resource → NPE →
    `notifyDestroyed` → process suicide. Fix: buffer fully into a
    `ByteArrayInputStream` for ALL sources (matches J2ME jar-resource
    semantics and the dex path's `AppClassLoader`). Lesson: any
    single-`read()` resource consumer is a latent bug — never hand a
    short-reading stream to guest code.
  - **`new Thread(null)` silent drop**: the game's midlet raced its own
    startup — `Display.setCurrent` + immediate `isShown()` returned
    false (host Canvas required surface attach), triggering a second
    `startApp` that hit `new Thread(h.a)` before `h.a` was assigned.
    `startGreen` then silently returned with `target=null` and the
    game's main loop never existed. Host-side fix is in JL-Modern's
    `Canvas.isShown` (`visible || super.isShown()`); VM-side lesson:
    `startGreen`/`pushInvoke` host-fallback logging (gated by
    `TRACE_CALLS`) is what exposed it — keep those print statements.
  - **Scheduler starvation**: `l.run` spins on `isShown()` with no
    sleep — real J2ME threads are preemptive, but the old
    first-runnable-wins `nextRunnable` let it starve every later-created
    thread forever. `rrCursor` round-robin now resumes scanning after
    the last-served thread. Any guest busy-loop without blocking ops is
    a starvation source — fairness is required for correctness, not
    just niceness. Test: `roundRobinScheduling`.
  - **`fromHost` wrapper destruction**: every host `Short`/`Byte`/
    `Character`/`Boolean` was normalized to `Integer` on entry — so
    `checkcast java/lang/Short` could NEVER succeed (PointFont's
    Hashtable of boxed shorts → CCE → main loop died → frozen splash).
    `fromHost(v, primitiveSlot)` now keeps real wrapper objects in
    reference-typed slots (method/field/param types drive the choice);
    primitive slots still normalize. Consequence for `pushGuestFrame`:
    its locals-scan must NOT re-normalize wrappers — arg slots can
    legitimately carry real `Short` objects now. Test:
    `boxedWrappersKeepIdentity`.
- 劲舞花儿朵朵 bug (Oct 2025) — stage rendered, actors missing:
  - **Hidden-field defaults lost**: obfuscated `j extends o` redeclares
    `a:I`/`b:I`/`a:[[I` — same name+desc as `o`'s private fields. The
    flat `fieldSlots` map is keyed `name|desc`, so the child's
    `put(key, inst++)` overwrote the super's entry — and `fieldDefaults`
    was built from that map's entrySet, so the hidden super slot never
    got its JVM 0. `o.b` does `a += k` on never-written `o.a` →
    `getfield` returned null → host unboxing NPE at `iadd` (guest NPE
    at `o.b@241`), caught by the game and printed ×919 — the actor
    update died before every render → empty stage. Fix: collect
    per-slot defaults during slot assignment (declaration order ==
    slot order), not from the deduplicated map afterwards. Lesson: any
    field table keyed name|desc silently drops hidden declarations —
    anything derived from it must iterate declarations, not the map.
    Test: `hiddenSuperFieldKeepsJvmDefault`.
- M4 thread semantics (Oct 2025):
  - **`Thread.interrupt` is real now**: `VmThread.interrupted` is the JVM
    status flag; `pendingInterrupt` + `interruptPc` defer delivery.
    `interruptGreen` sets the flag always, but only WAITING/TIMED_WAITING
    threads in an *interruptible* park (sleep/wait/join) wake early —
    BLOCKED (monitor contention), clinit-gated (`waitMonitor==VmClass`)
    and host-callback (`waitMonitor==PARKED`) threads keep the flag for
    their next interruptible call, matching the JVM. Waking removes the
    thread from monitor wait-sets and join waiter lists before resume;
    `execute` delivers the guest `InterruptedException` at `interruptPc`
    (the recorded invoke site — required so catch ranges match) and
    clears both flags. Pre-set flags also throw immediately at the next
    sleep/wait/join entry, before parking. `interrupted()` (static)
    reads+clears, `isInterrupted()` reads on the *target* green.
  - **join bookkeeping**: `joinTarget` tracks which thread a join() is
    parked on (join parks WAITING with `waitMonitor==null` — invisible
    otherwise). Cleared on resume/interrupt so `terminate()` can't
    spuriously wake a joiner long gone. Also silently fixes timed-join
    leaving stale waiter entries.
  - **Deadlock detection lives ONLY in `runUntilDone`**: when the entry
    thread is parked, nothing else is runnable or timed, and no thread
    is `PARKED` on a host callback, entry calls throw `VmError` instead
    of hanging. NEVER put this in `pump()` — a live game idling on key
    input is a legal state, not a deadlock. `guestDeadlocked()` treats
    `waitMonitor==PARKED` as "host may still wake it" — keep that.
  - `setPriority`/`setDaemon`/`setName` on a guest `extends Thread`
    (VmObject, no host Thread) are no-ops returning defaults — a
    scheduler hint, not worth marshaling. Tests: `interruptSleep`,
    `interruptWait`, `interruptJoin`, `joinTimeoutAndFlags`,
    `timedWaitAndInterruptFlags`, `threadOpsOnGuestThreadNoCrash`,
    `guestDeadlockThrowsVmError`.
- M5a snapshot engine (Oct 2025) — `docs/snapshot-format.md` is the spec:
  - `Vm.checkpoint()` freezes the whole machine under `execLock` (call
    between pump slices); `Vm.restore(bytes)` wipes live state on any
    same-configured Vm and repopulates. `Snapshot.java` does a
    logical-value walk — guest values by tag, object graph through one
    identity→id table, payload inline at first REF so cycles/interned
    `==` survive.
  - **Reader reserves the id slot (`PEND`) before reading a payload** —
    a HANDLE record nests inside its parent's payload and its inner
    objects take later ids; materializing parent-first breaks the
    numbering. OBJ/ARR additionally set their slot early so self-cycles
    resolve.
  - Guest `new Object()` lands a real `java.lang.Object` in guest state
    — `K_POBJ` treats it as an identity token (lock objects everywhere).
    `java.util.Date`/`ByteArrayInputStream` ship built-in codecs
    (`Vm.registerDefaultHandles`); anything else host-typed in guest
    state needs `registerHostHandle` or the save throws `VmError`.
  - `PARKED` threads can't serialize their done-callback — `callParked`
    records `parkedCallKey`/`parkedArgs` on the thread and restore
    **replays** the host call; the new done() wakes it.
  - `ShadowCodec` (registered per host base, like ShadowFactory) folds
    host-shadow state back on restore; a live hostShadow without a codec
    refuses the save — silent corruption is worse than a loud refusal.
    JL-Modern adapter codecs are the M5b half.
  - Tests `SnapshotTest` ×7: round-trip worker (same-vm + fresh-vm),
    heap identity/cycles/intern, wait-restore, sleep-delta, parked
    replay, unhandled-host refusal, built-in handles.
- On-device diagnostic notes:
  - `System.err` goes to `/dev/null` on Android — all GuestCall/
    GuestThrow/uncaught output is invisible unless the adapter
    redirects stderr. `snapvm.err` gate file → app-owned
    `snapvm_err.log` (shell-created markers are owned by `media_rw`:
    `exists()` works but the app can't write them — so the gate and
    the log are separate files).
  - `Interpreter.TRACE_CALLS` is a `.matches()` regex — use `.*x.*`
    shapes, not bare substrings.
  - `dumpThreads(true)` prints live + terminated threads and a
    locals[0] field summary (Vector sizes show as `size=N`).
