#!/usr/bin/env python3
# SnapVM snapshot v2: per-ref-kind count + byte accounting
import struct, sys, collections

data = open(sys.argv[1], 'rb').read()
pos = 0
kind_count = collections.Counter()
kind_bytes = collections.Counter()

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
    n = i32(); global pos; s = data[pos:pos+n].decode('utf-8','replace'); pos += n; return s

K = dict(NULL=0, INT=1, LONG=2, FLOAT=3, DOUBLE=4, BOOL=5, BYTE=6, SHORT=7,
         CHAR=8, WIDE=9, REF=10, CLASS=11)
NAMES = {0:'STR',1:'OBJ',2:'THREAD_BOX',3:'HANDLE',4:'THROWABLE',5:'PROXY',
         6:'POBJ',10:'ARR_B',11:'ARR_S',12:'ARR_C',13:'ARR_I',14:'ARR_Z',
         15:'ARR_J',16:'ARR_F',17:'ARR_D',18:'ARR_OBJ',19:'ARR_TYPED'}
R_KIND = {}

def value(depth=0):
    t = u8()
    if t in (K['NULL'], K['WIDE']): return
    if t == K['INT']: i32(); return
    if t == K['LONG']: i64(); return
    if t == K['FLOAT']: f32(); return
    if t == K['DOUBLE']: f64(); return
    if t in (K['BOOL'], K['BYTE']): u8(); return
    if t == K['SHORT']: i16(); return
    if t == K['CHAR']: u16(); return
    if t == K['CLASS']: sstr(); return
    if t == K['REF']:
        oid = i32()
        if oid in R_KIND: return
        kind = u8(); R_KIND[oid] = kind
        payload(kind, depth)
        return
    raise Exception(f'bad tag {t} at {pos}')

def payload(kind, depth):
    global pos
    start = pos
    kind_count[kind] += 1
    if kind == 0: sstr()
    elif kind == 1:
        cn = sstr(); i64(); nf = i32()
        for _ in range(nf): value(depth+1)
        if u8(): sstr(); value(depth+1)
        value(depth+1); value(depth+1)
        if u8(): sstr()
    elif kind == 2: pass
    elif kind == 3: sstr(); value(depth+1)
    elif kind == 4: sstr(); sstr()
    elif kind == 5: value(depth+1)
    elif kind == 6: pass
    elif 10 <= kind <= 17:
        n = i32(); sizes = {10:1,11:2,12:2,13:4,14:1,15:8,16:4,17:8}
        pos += n * sizes[kind]
    elif kind == 18:
        n = i32()
        for _ in range(n): value(depth+1)
    elif kind == 19:
        sstr(); n = i32()
        for _ in range(n): value(depth+1)
    else: raise Exception(f'bad ref kind {kind} at {pos}')
    kind_bytes[kind] += pos - start

assert data[:4] == b'SNAP'; pos = 4
ver = i16()
np = i32()
for _ in range(np): sstr(); sstr()
ni = i32()
for _ in range(ni): value()
nc = i32()
cls_names = []
for _ in range(nc):
    name = sstr(); u8(); u8(); i32(); ns = i32()
    for _ in range(ns): value()
    cls_names.append(name)

nt = i32()
live = dead = 0
for i in range(nt):
    tid = i32(); state = u8(); i64()
    wmk = u8()
    if wmk == 1: value()
    elif wmk == 3: sstr()
    i32(); u8(); u8(); u8(); i32(); i32()
    nw = i32()
    for _ in range(nw): i32()
    value(); value(); value(); value()
    if u8(): value()
    if u8():
        sstr(); na_ = i32()
        for _ in range(na_): value()
    nf = i32()
    for _ in range(nf):
        sstr(); sstr(); sstr(); i32(); sp = i32(); i32(); u8(); u8(); value()
        nl = i32()
        for _ in range(nl): value()
        for _ in range(sp): value()
    if state == 4: dead += 1
    else: live += 1
print(f'threads {nt} live={live} dead={dead}')

nm = i32()
for _ in range(nm):
    value(); i32(); i32(); nw = i32()
    for _ in range(nw): i32()
na = i32()
for _ in range(na): i32(); sstr()
nb = i32()
for _ in range(nb): value(); i32(); value()
npx = i32()
for _ in range(npx): sstr(); value()
i32()

print(f'total {len(data)} bytes')
for k, c in kind_count.most_common():
    print(f'{NAMES.get(k,k):>10} x{c:>6}  {kind_bytes[k]:>9} B')
