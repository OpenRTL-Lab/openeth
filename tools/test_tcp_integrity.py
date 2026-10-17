#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/test_tcp_integrity.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Compile and test the C server against the Python peer; no FPGA access."""
import os
from pathlib import Path
import selectors
import shlex
import socket
import subprocess
import tempfile

from tcp_integrity_peer import run

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix='openeth-integrity-') as temp:
        binary = Path(temp) / 'tcp_integrity'
        subprocess.run([*shlex.split(os.environ.get('CC', 'cc')), '-O2', '-Wall',
                        '-Wextra', '-Werror', str(ROOT / 'software/linux/tcp_integrity.c'),
                        '-o', str(binary)], check=True)
        for corrupt in (False, True):
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            # The application binds INADDR_ANY, even though this test connects
            # only to localhost. Run on a trusted or network-isolated host.
            server = subprocess.Popen([str(binary), str(port)], stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True)
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(server.stdout, selectors.EVENT_READ)
                    if not selector.select(timeout=10):
                        raise RuntimeError('server readiness timeout')
                    ready = server.stdout.readline()
                if ready.strip() != f'INTEGRITY_SERVER_READY port={port}':
                    raise RuntimeError('server did not bind: ' + ready)
                result = run('127.0.0.1', port, 1048576, corrupt)
                output, _ = server.communicate(timeout=10)
                expected_exit = 10 if corrupt else 0
                if server.returncode != expected_exit:
                    raise RuntimeError(f'server exit {server.returncode}: {output}')
                if result['target_mismatches'] != int(corrupt):
                    raise RuntimeError('unexpected mismatch count')
                marker = ('mismatches=1' if corrupt else
                          'INTEGRITY_SERVER_PASS rx_bytes=1048576 tx_bytes=1048576')
                if marker not in output:
                    raise RuntimeError('missing server result: ' + output)
                print('TCP_INTEGRITY_CASE_PASS corrupt=' + str(int(corrupt)), flush=True)
            finally:
                if server.poll() is None:
                    server.terminate()
                    server.wait(timeout=10)
                server.stdout.close()
    print('TCP_INTEGRITY_HOST_PASS bytes_per_direction=1048576 negative_server_exit=10 hardware_access=0')


if __name__ == '__main__':
    main()
