#!/usr/bin/env python3
# Minimal SnapVM snapshot v2 parser — walks the stream, reports sections
# and per-thread state/frames. Values are decoded enough to skip payload.
import struct, sys

data = open(sys.argv[1], 'rb').read()
pos = 0

def u8():
    global pos; v = data[pos]; pos += 1; return v
def i16():
    global pos; v = struct.unpack('>h', data[pos:pos+2])[0]; pos += 2; return v
def u16():
    global pos; v = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2; return v
def i32():
    global pos; v = struct.unpack('>i', data[pos:pos+4])[0]; pos += 4; return v
def i64():
    global pos; v = struct.unpack('>q', data[pos:pos+8])[0]; pos += 8; return v
def f32():
    global pos; v = struct.unpack('>f', data[pos:pos+4])[0]; pos += 4; return v
def f64():
    global pos; v = struct.unpack('>d', data[pos:pos+8])[0]; pos += 8; return v
def sstr():
    n = i32(); global pos; s = data[pos:pos+n].decode('utf-8', 'replace'); pos += n; return s

K = dict(NULL=0, INT=1, LONG=2, FLOAT=3, DOUBLE=4, BOOL=5, BYTE=6, SHORT=7,
         CHAR=8, WIDE=9, REF=10, CLASS=11)
R_KIND = {}

def value(depth=0):
    t = u8()
    if t == K['NULL'] or t == K['WIDE']: return None
    if t == K['INT']: return i32()
    if t == K['LONG']: return i64()
    if t == K['FLOAT']: return f32()
    if t == K['DOUBLE']: return f64()
    if t == K['BOOL'] or t == K['BYTE']: return u8()
    if t == K['SHORT']: return i16()
    if t == K['CHAR']: return u16()
    if t == K['CLASS']: sstr(); return None
    if t == K['REF']:
        oid = i32()
        if oid in R_KIND:
            return ('ref', oid)
        kind = u8()
        R_KIND[oid] = kind
        payload(kind, depth)
        return ('def', oid, kind)
    raise Exception(f'bad tag {t} at {pos}')

def payload(kind, depth):
    # STR=0 OBJ=1 THREAD_BOX=2 HANDLE=3 THROWABLE=4 PROXY=5 POBJ=6
    # ARR_B=7 ARR_S=8 ARR_C=9 ARR_I=10 ARR_Z=11 ARR_J=12 ARR_F=13 ARR_D=14
    # ARR_OBJ=15 ARR_TYPED=16
    if kind == 0: sstr(); return
    if kind == 1:  # OBJ
        sstr()              # class name
        i64()               # v2: serial
        nf = i32()
        for _ in range(nf): value(depth+1)
        if u8():            # shadow present
            sstr()          # host base class
            value(depth+1)  # codec record
        value(depth+1)      # superCtorArgs
        value(depth+1)      # exceptionCause
        if u8(): sstr()     # exceptionTrace
        return
    if kind == 2:           # THREAD_BOX
        return
    if kind == 3:           # HANDLE
        sstr(); value(depth+1); return
    if kind == 4:           # THROWABLE
        sstr(); sstr(); return
    if kind == 5:           # PROXY
        value(depth+1); return
    if kind == 6:           # POBJ
        return
    if 10 <= kind <= 17:
        n = i32()
        sizes = {10:1,11:2,12:2,13:4,14:1,15:8,16:4,17:8}
        global pos; pos += n * sizes[kind]; return
    if kind == 18:
        n = i32()
        for _ in range(n): value(depth+1)
        return
    if kind == 19:
        sstr(); n = i32()
        for _ in range(n): value(depth+1)
        return
    raise Exception(f'bad ref kind {kind} at {pos}')

assert data[:4] == b'SNAP'; pos = 4
ver = i16(); print('version', ver)

# globals
np = i32()
for _ in range(np): sstr(); sstr()
ni = i32()
for _ in range(ni): value()
print(f'globals: {np} props, {ni} interned')

# classes
nc = i32(); print(f'classes: {nc}')
for _ in range(nc):
    name = sstr(); initDone = u8(); initRunning = u8(); initTid = i32(); ns = i32()
    for _ in range(ns): value()

# threads
STATE = 'RUNNABLE BLOCKED WAITING TIMED_WAITING TERMINATED'.split()
nt = i32(); print(f'threads: {nt}')
threads = []
for i in range(nt):
    tid = i32(); state = u8(); wake = i64()
    wmk = u8()
    if wmk == 1: value()
    elif wmk == 3: sstr()
    wrc = i32(); resuming = u8(); interrupted = u8(); pending = u8(); ipc = i32()
    jt = i32(); nw = i32(); [i32() for _ in range(nw)]
    value(); value(); value(); value()
    if u8(): value()
    if u8():
        key = sstr(); na = i32()
        for _ in range(na): value()
    nf = i32()
    frames = []
    for _ in range(nf):
        cn = sstr(); mn = sstr(); md = sstr(); pc = i32(); sp = i32(); cpc = i32()
        fromCall = u8(); external = u8()
        value()  # syncOn
        nl = i32()
        for _ in range(nl): value()
        for _ in range(sp): value()
        frames.append(f'{cn}.{mn}{md}@{pc}')
    threads.append((tid, STATE[state], nf, wmk, frames))

for tid, st, nf, wmk, frames in threads:
    live = ' ' if st == 'TERMINATED' else '*'
    print(f'{live} tid={tid} {st} frames={nf} waitKind={wmk}')
    for fr in frames[:4]:
        print(f'      {fr}')

# monitors
nm = i32(); print(f'monitors: {nm}')
for _ in range(nm):
    value(); i32(); i32(); nw = i32(); [i32() for _ in range(nw)]

# arrays
na = i32()
for _ in range(na): i32(); sstr()
print(f'arrays: {na}')

# boxes
nb = i32()
for _ in range(nb):
    value(); i32(); value()
print(f'boxes: {nb}')

# proxies
npx = i32()
for _ in range(npx): sstr(); value()
print(f'proxies: {npx}')
print('rrCursor', i32())
print('trailing bytes:', len(data) - pos)
