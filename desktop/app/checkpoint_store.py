"""Verified immutable step blobs + atomic manifests. No pickle, partial reuse or replay.

A checkpoint is a COMPLETE logical calculation (not a progress percentage). A killed
calculation is retried from its last complete step; video encoding starts from frame 0
but lossless completed frame calculations can be reused. Disk quota is not relaxed.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import uuid
import zipfile


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + uuid.uuid4().hex + '.partial')
    try:
        with temporary.open('xb') as stream:
            stream.write(canonical(value)); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name != 'nt':
            descriptor = os.open(str(path.parent), os.O_RDONLY)
            try: os.fsync(descriptor)
            finally: os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def safe_path(root, relative):
    root = Path(root)
    if root.is_symlink() or (hasattr(root, 'is_junction') and root.is_junction()):
        raise ValueError('Managed root cannot be a link')
    root = root.resolve()
    if not isinstance(relative, str) or not relative or '\\' in relative:
        raise ValueError('Invalid managed path')
    raw = Path(relative)
    if raw.is_absolute() or any(p in ('', '.', '..') for p in relative.split('/')) or ':' in relative:
        raise ValueError('Invalid managed path')
    path = root / raw
    at = path
    while at != root:
        if at.is_symlink() or (hasattr(at, 'is_junction') and at.is_junction()):
            raise ValueError('Managed path crosses a link')
        at = at.parent
    if not path.resolve().is_relative_to(root):
        raise ValueError('Managed path escapes session')
    return path


_stamp_lock = threading.Lock()
_stamp_cache = {}
def algorithm_stamp():
    """Content digests, cached only while stat identity is unchanged. Includes models.
    Do not substitute version labels or configuration alone for calculation identity.
    """
    root = Path(__file__).resolve().parent.parent
    paths = [p for directory in ('app', 'src') for p in (root / directory).rglob('*.py')
             if not p.name.startswith('test_') and '__pycache__' not in p.parts]
    from legend.public_assets import ASSETS, asset_path
    paths += [asset_path(style) for style in ASSETS]
    with _stamp_lock:
        records = []
        for path in sorted(paths):
            info = path.stat()
            key = (str(path), info.st_size, info.st_mtime_ns)
            if key not in _stamp_cache:
                _stamp_cache[key] = digest(path)
            records.append((path.relative_to(root.parent).as_posix(), _stamp_cache[key]))
        return hashlib.sha256(canonical(records)).hexdigest()


class StepArchive:
    def __init__(self, folder, identity, check=lambda: None, guard=lambda n=0: None, event=lambda *a: None):
        self.folder = Path(folder)
        self.identity = hashlib.sha256(canonical(identity)).hexdigest()
        self.check, self.guard, self.event = check, guard, event
        self.lock = threading.RLock()
        self.path = self.folder / 'steps.json'
        self.entries = {}
        self.reused = 0
        self.damaged = False
        self.incompatible = False
        if self.path.is_file():
            try:
                raw = json.loads(self.path.read_text(encoding='utf-8'))
                if raw['schema'] != 1 or raw['identity'] != self.identity:
                    self.incompatible = True
                    return  # Never overwrite an archive belonging to another identity.
                if hashlib.sha256(canonical(raw['entries'])).hexdigest() != raw['sha256']:
                    raise ValueError('Step manifest checksum differs')
                self.entries = raw['entries']
            except (OSError, ValueError, KeyError, TypeError):
                self.damaged = True  # Retain diagnostics; refuse overwriting this manifest.

    def scope(self, prefix):
        return StepScope(self, prefix + '/')

    def load(self, name):
        import numpy as np
        from PIL import Image
        self.check()
        with self.lock:
            entry = self.entries.get(name)
            if not entry:
                return None
            try:
                path = safe_path(self.folder, entry['file'])
                if path.stat().st_size != entry['size'] or digest(path) != entry['sha256']:
                    raise ValueError('Step blob checksum differs')
                with zipfile.ZipFile(path) as archive:
                    if sum(i.file_size for i in archive.infolist()) > 4 * 1024**3:
                        raise ValueError('Checkpoint exceeds session budget')
                with np.load(path, allow_pickle=False) as stored:
                    def unpack(node):
                        kind = node[0]
                        if kind == 'value': return node[1]
                        if kind == 'dict': return {key: unpack(value) for key, value in node[1]}
                        if kind in ('list', 'tuple'):
                            values = [unpack(v) for v in node[1]]
                            return tuple(values) if kind == 'tuple' else values
                        array = stored[node[1]].copy()
                        if list(array.shape) != node[2] or str(array.dtype) != node[3] or array.dtype.hasobject:
                            raise ValueError('Checkpoint array differs')
                        if np.issubdtype(array.dtype, np.number) and not np.isfinite(array).all():
                            raise ValueError('Checkpoint contains nonfinite values')
                        return Image.fromarray(array) if kind == 'image' else array
                    value = unpack(entry['tree'])
                self.check()
                self.reused += 1
                self.event('checkpoint-reused', '', {'step': name, 'checkpointSteps': len(self.entries), 'reusedSteps': self.reused})
                return value
            except (OSError, ValueError, KeyError, TypeError, EOFError, zipfile.BadZipFile):
                # Keep corrupt bytes for diagnosis, but never reuse them.
                return None

    def save(self, name, value):
        import numpy as np
        from PIL import Image
        self.check()
        with self.lock:
            if self.damaged or self.incompatible:
                raise ValueError('存档清单损坏或身份不一致，旧文件已保留；请选择重新计算以建立独立新存档。')
            arrays = {}
            def pack(v):
                if isinstance(v, (Image.Image, np.ndarray)):
                    array = np.array(v) if isinstance(v, Image.Image) else np.asarray(v)
                    if array.dtype.hasobject or (np.issubdtype(array.dtype, np.number) and not np.isfinite(array).all()):
                        raise ValueError('Cannot checkpoint invalid array')
                    key = 'a' + str(len(arrays)); arrays[key] = array
                    return ['image' if isinstance(v, Image.Image) else 'array', key, list(array.shape), str(array.dtype)]
                if isinstance(v, np.generic): v = v.item()
                if isinstance(v, dict): return ['dict', [[str(k), pack(item)] for k, item in v.items()]]
                if isinstance(v, (list, tuple)): return ['tuple' if isinstance(v, tuple) else 'list', [pack(item) for item in v]]
                if v is None or isinstance(v, (str, bool, int, float)):
                    canonical(v)
                    return ['value', v]
                raise TypeError('Unsupported checkpoint value: ' + type(v).__name__)
            tree = pack(value)
            self.guard(sum(a.nbytes for a in arrays.values()) + len(canonical(tree)) + 65536)
            self.folder.mkdir(parents=True, exist_ok=True)
            temporary = self.folder / ('.' + uuid.uuid4().hex + '.partial')
            try:
                with temporary.open('xb') as stream:
                    np.savez_compressed(stream, **arrays)
                    stream.flush(); os.fsync(stream.fileno())
                self.check()
                sha = digest(temporary)
                blob = self.folder / (uuid.uuid4().hex + '.npz')
                os.replace(temporary, blob)
                entry = {'file': blob.name, 'size': blob.stat().st_size, 'sha256': sha, 'tree': tree}
                updated = {**self.entries, name: entry}
                self.guard(len(canonical(updated)) + 65536)
                self.check()
                atomic_json(self.path, {'schema': 1, 'identity': self.identity, 'entries': updated,
                                        'sha256': hashlib.sha256(canonical(updated)).hexdigest()})
                self.entries = updated
                self.event('checkpoint-saved', '', {'step': name, 'checkpointSteps': len(updated), 'reusedSteps': self.reused})
            finally:
                temporary.unlink(missing_ok=True)
        return value

    def compute(self, name, calculation):
        value = self.load(name)
        return value if value is not None else self.save(name, calculation())


class StepScope:
    def __init__(self, archive, prefix): self.archive, self.prefix = archive, prefix
    def load(self, name): return self.archive.load(self.prefix + name)
    def save(self, name, value): return self.archive.save(self.prefix + name, value)
    def compute(self, name, fn): return self.archive.compute(self.prefix + name, fn)
    def scope(self, prefix): return StepScope(self.archive, self.prefix + prefix + '/')
