#!/usr/bin/env python3
"""Fetch pinned build dependencies/assets. Never connects to Android devices."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MAX_MEMBER = 200 * 1024 * 1024
MAX_EXPANDED = 1024 * 1024 * 1024


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def member_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name or not path.parts:
        raise ValueError('Unsafe archive member')
    return path


def download(spec, cache):
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / spec['cache']
    if output.is_file():
        if output.stat().st_size > spec['maxBytes'] or digest(output) != spec['sha256']:
            raise ValueError('Cached checksum differs: ' + spec['id'])
        return output
    if not spec['url'].startswith('https://'):
        raise ValueError('Dependency source must use HTTPS')
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            request = urllib.request.Request(spec['url'], headers={'User-Agent': 'android-assistant-bootstrap'})
            with urllib.request.urlopen(request, timeout=30) as response:
                if not response.geturl().startswith('https://'):
                    raise ValueError('Insecure dependency redirect')
                size = 0
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    size += len(block)
                    if size > spec['maxBytes']:
                        raise ValueError('Dependency exceeds size bound')
                    stream.write(block)
            stream.flush()
            if digest(temporary) != spec['sha256']:
                raise ValueError('Downloaded checksum differs: ' + spec['id'])
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)
    return output


def install_source(archive, output, spec):
    # Never replace a developer's edited checkout or follow archive links.
    if output.exists() or output.is_symlink():
        marker = output / '.assistant-source.json'
        if not output.is_symlink() and marker.is_file() and json.loads(marker.read_text()) == spec:
            return
        raise ValueError('Source destination already exists; inspect it manually: ' + str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staging = Path(tmp) / 'source'
        staging.mkdir()
        seen, prefixes, expanded = set(), set(), 0
        with tarfile.open(archive) as source:
            for item in source:
                path = member_path(item.name)
                prefixes.add(path.parts[0])
                if len(prefixes) != 1:
                    raise ValueError('Multiple archive roots')
                if len(path.parts) == 1 and item.isdir():
                    continue
                relative = PurePosixPath(*path.parts[1:])
                if len(path.parts) < 2 or str(relative) in seen:
                    raise ValueError('Duplicate or empty source member')
                seen.add(str(relative))
                target = staging / str(relative)
                if item.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif item.isfile() and 0 <= item.size <= MAX_MEMBER:
                    expanded += item.size
                    if expanded > MAX_EXPANDED:
                        raise ValueError('Expanded source exceeds bound')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(item) as stream, target.open('xb') as dest:
                        shutil.copyfileobj(stream, dest)
                else:
                    raise ValueError('Unsupported source archive member')
        (staging / '.assistant-source.json').write_text(json.dumps(spec))
        staging.rename(output)


def package_model(archive, output, spec):
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as stream:
        temporary = Path(stream.name)
    seen, roots, found, expanded = set(), set(), set(), 0
    try:
        with tarfile.open(archive) as source, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as target:
            for item in source:
                path = member_path(item.name)
                roots.add(path.parts[0])
                if len(roots) != 1:
                    raise ValueError('Multiple model archive roots')
                if item.isdir():
                    continue
                if not item.isfile() or not 0 <= item.size <= MAX_MEMBER or len(path.parts) < 2:
                    raise ValueError('Unsupported model archive member')
                if item.name in seen:
                    raise ValueError('Duplicate model archive member')
                seen.add(item.name)
                expanded += item.size
                if expanded > MAX_EXPANDED:
                    raise ValueError('Expanded model exceeds bound')
                expected = spec.get('members')
                if expected is not None and path.name not in expected:
                    continue
                if expected is not None and path.name in found:
                    raise ValueError('Duplicate selected model member')
                with source.extractfile(item) as stream:
                    data = stream.read()
                if expected is not None and hashlib.sha256(data).hexdigest() != expected[path.name]:
                    raise ValueError('Model member checksum differs')
                found.add(path.name)
                # AssetModels strips exactly one top-level directory at runtime.
                info = zipfile.ZipInfo(item.name, date_time=(2025, 9, 8, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                target.writestr(info, data)
            if spec.get('members') is not None and found != set(spec['members']):
                raise ValueError('Incomplete model archive')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def install(archive, output, spec):
    if digest(archive) != spec['sha256']:
        raise ValueError('Input checksum differs')
    if output.is_symlink():
        raise ValueError('Symlink destination is refused')
    if spec['format'] == 'source-tar':
        install_source(archive, output, spec)
    elif spec['format'] == 'model-tar':
        package_model(archive, output, spec)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with archive.open('rb') as source:
                    shutil.copyfileobj(source, stream)
                stream.flush()
                temporary.replace(output)
            finally:
                temporary.unlink(missing_ok=True)


def package_files(spec, output, cache):
    """Bundle individually pinned upstream weights; never execute model repository code."""
    if output.is_symlink():
        raise ValueError('Symlink destination is refused')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        names, expanded = set(), 0
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as target:
            for part in spec['files']:
                name = str(member_path(part['name']))
                if name in names:
                    raise ValueError('Duplicate model member')
                names.add(name)
                source = download(part, cache)
                expanded += source.stat().st_size
                if expanded > MAX_EXPANDED:
                    raise ValueError('Expanded model exceeds bound')
                info = zipfile.ZipInfo('model/' + name, date_time=(2025, 12, 16, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with source.open('rb') as src, target.open(info, 'w', force_zip64=True) as dst:
                    shutil.copyfileobj(src, dst)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--group', choices=('build', 'speech', 'ocr', 'piper'), default='build')
    parser.add_argument('--cache', type=Path, default=Path.home() / '.cache/android-assistant-dependencies')
    parser.add_argument('--accept-noncommercial', action='store_true', help='Acknowledge the optional voice terms described in THIRD_PARTY.md')
    args = parser.parse_args()
    specs = json.loads((ROOT / 'dependencies.json').read_text())['artifacts']
    selected = [s for s in specs if s['group'] == args.group]
    if any(s.get('noncommercial') for s in selected) and not args.accept_noncommercial:
        parser.error('Review the voice license in THIRD_PARTY.md; piper requires --accept-noncommercial')
    for spec in selected:
        output = ROOT / str(member_path(spec['target']))
        if spec['format'] == 'model-files':
            package_files(spec, output, args.cache)
        else:
            archive = download(spec, args.cache)
            install(archive, output, spec)
        print('Prepared ' + spec['id'])


if __name__ == '__main__':
    main()
