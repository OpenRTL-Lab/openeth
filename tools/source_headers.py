#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/source_headers.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Verify coverage, exact source-body equivalence, and safe generated headers."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', 'build', 'out', 'target', 'test_run_dir', '__pycache__', '.bsp'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def header(name):
    fields = ['OpenRTL-source-header',
              'Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)',
              'Organization introduction: Open hardware, from algorithms to RTL.',
              'Repository: OpenRTL-Lab/openeth',
              'Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.',
              'File: ' + name, 'Website: https://openrtl.top/', 'SPDX-License-Identifier: MIT']
    if name.endswith('.md'):
        return ('<!-- ' + '\n'.join(fields) + '\n-->\n').encode()
    prefix = '#' if name.endswith(('.py', '.sh', '.f', '.tcl', '.license')) or Path(name).name in ('Makefile', '.gitignore') else '//'
    return ('\n'.join(prefix + ' ' + line for line in [*fields, 'OpenRTL-source-header-end']) + '\n').encode()


def stamp(name, data):
    """Only add the precisely defined header; repeat calls are byte-idempotent."""
    offset = data.index(b'\n') + 1 if data.startswith((b'#!', b'//|')) else 0
    h = header(name)
    if data.count(b'OpenRTL-source-header\n') > 1:
        raise ValueError('duplicate source header: ' + name)
    if data[offset:].startswith(h):
        return data
    if b'OpenRTL-source-header' in data:
        raise ValueError('unexpected or altered existing header: ' + name)
    return data[:offset] + h + data[offset:]


def paths(root):
    return {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()
            and not any(part in EXCLUDED for part in p.relative_to(root).parts)}


def private_material(data):
    """Detect host-specific locations, private endpoints and credential material."""
    patterns = (
        rb'(?i)(?<![A-Za-z0-9])/(?:home|root|Users|mnt|media|Volumes)(?:/|\b)',
        rb'(?i)\b[A-Z]:[\\/](?:Users|Documents and Settings)[\\/]',
        rb'\b(?:10\.\d{1,3}\.\d{1,3}(?:\.\d{1,3})?|192\.168\.\d{1,3}(?:\.\d{1,3})?|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}(?:\.\d{1,3})?)\b',
        rb'-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----',
        rb'(?i)Authorization:\s*Bearer\s+\S+',
        rb'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}',
    )
    return any(re.search(pattern, data) for pattern in patterns)


def check(root=ROOT):
    metadata = {'PUBLIC_FILES.json', 'PUBLIC_FILES.json.license'}
    for name in paths(root):
        if private_material(name.encode()) or private_material((root / name).read_bytes()):
            raise ValueError('private material: ' + name)
    coverage = json.loads((root / 'PUBLIC_FILES.json').read_text())
    entries = coverage['files']
    if len({x['path'] for x in entries}) != len(entries):
        raise ValueError('duplicate coverage entry')
    if paths(root) != {x['path'] for x in entries} | metadata:
        raise ValueError('unknown or missing file: ' + str(paths(root) ^ ({x['path'] for x in entries} | metadata)))
    for row in entries:
        data = (root / row['path']).read_bytes()
        if sha(data) != row['sha256']:
            raise ValueError('payload hash changed: ' + row['path'])
        if row['classification'] == 'original-inline':
            if data.count(b'OpenRTL-source-header\n') != 1:
                raise ValueError('missing/duplicate header: ' + row['path'])
            if row['path'].endswith('.md'):
                expected = header(row['path'])
                if not data.startswith(expected):
                    raise ValueError('altered Markdown header')
            else:
                offset = data.index(b'\n') + 1 if data.startswith((b'#!', b'//|')) else 0
                if not data[offset:].startswith(header(row['path'])):
                    raise ValueError('altered header: ' + row['path'])
        elif row['classification'] == 'original-companion':
            sidecar = root / (row['path'] + '.license')
            if not sidecar.is_file() or b'SPDX-License-Identifier: MIT' not in sidecar.read_bytes():
                raise ValueError('missing exact companion: ' + row['path'])
        elif row['classification'] not in ('third-party', 'legal'):
            raise ValueError('unknown classification')
    manifest = json.loads((root / 'SOURCE_MANIFEST.json').read_text())
    for row in manifest['files']:
        data = (root / row['path']).read_bytes()
        if row['header_offset'] is not None:
            offset = row['header_offset']
            h = header(row['path'])
            if data[offset:offset + len(h)] != h:
                raise ValueError('source header mismatch')
            data = data[:offset] + data[offset + len(h):]
        if sha(data) != row['source_sha256']:
            raise ValueError('source body changed: ' + row['path'])
    if (root / 'VERSION').read_bytes() != b'1.1.0\n':
        raise ValueError('version mismatch')
    print('PUBLIC_SOURCE_CHECK_PASS', len(entries) + 2, 'files;', len(manifest['files']), 'frozen source bodies')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['check', 'stamp-generated'])
    parser.add_argument('directory', nargs='?', type=Path)
    args = parser.parse_args()
    if args.mode == 'check':
        check()
    else:
        if args.directory is None or not args.directory.is_dir():
            parser.error('stamp-generated requires an existing generated output directory')
        files = list(args.directory.rglob('*.sv'))
        if not files:
            raise ValueError('no generated SystemVerilog')
        for path in files:
            path.write_bytes(stamp(path.name, path.read_bytes()))
        print('GENERATED_HEADERS_STAMPED', len(files))


if __name__ == '__main__':
    main()
