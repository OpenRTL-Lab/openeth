#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/tcp_integrity_peer.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
import argparse
import hashlib
import json
import socket
import struct
import time
from pathlib import Path

def pattern(block,seed):
    # Independent Python reference to the target's byte loop.
    data=bytearray(((seed+block*29+i*17)^(i>>3))&255 for i in range(65536))
    data[:8]=struct.pack('<Q',block)
    return data

def read_exact(s,n):
    data=bytearray()
    while len(data)<n:
        more=s.recv(n-len(data))
        if not more: raise ValueError('premature EOF')
        data+=more
    return data

def run(host,port,size,corrupt=False):
    if size%65536 or size<=0: raise ValueError('block aligned size')
    started=time.time(); tx=hashlib.sha256();rx=hashlib.sha256();expect=hashlib.sha256()
    with socket.create_connection((host,port),timeout=120) as s:
        s.settimeout(120);s.sendall(struct.pack('<Q',size))
        for block in range(size//65536):
            b=pattern(block,0x37)
            if corrupt and block==0: b[123]^=1
            tx.update(b);s.sendall(b)
        bad=struct.unpack('<Q',read_exact(s,8))[0]
        if corrupt:
            if bad!=1: raise ValueError('negative control not rejected exactly')
            return dict(status=0,negative_control=True,target_mismatches=bad)
        if bad: raise ValueError(f'target payload mismatches {bad}')
        for block in range(size//65536):
            b=read_exact(s,65536);e=pattern(block,0xa9);rx.update(b);expect.update(e)
            if b!=e: raise ValueError(f'reverse corruption block {block}')
        if s.recv(1): raise ValueError('extra payload')
    if rx.digest()!=expect.digest(): raise ValueError('reverse SHA')
    return dict(status=0,started=started,finished=time.time(),rx_bytes=size,tx_bytes=size,target_mismatches=bad,
        host_tx_sha256=tx.hexdigest(),host_rx_sha256=rx.hexdigest(),expected_rx_sha256=expect.hexdigest())

def main():
    p=argparse.ArgumentParser();p.add_argument('--host',default='192.0.2.1');p.add_argument('--port',type=int,default=5002)
    p.add_argument('--bytes',type=int,default=256*1024*1024);p.add_argument('--corrupt',action='store_true');p.add_argument('--out',type=Path,required=True)
    a=p.parse_args(); result=run(a.host,a.port,a.bytes,a.corrupt)
    a.out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)

if __name__=='__main__':main()
