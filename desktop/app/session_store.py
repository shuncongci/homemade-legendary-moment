"""Durable workbench registry. Explicit per-port storage, exclusive owner, fail closed.
The registry publishes only immutable, verified files. Old temporary sessions are not
scanned, migrated or deleted. UI state and pending jobs never authorize auto replay.
"""
from __future__ import annotations
import copy
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import uuid
from checkpoint_store import atomic_json, canonical, digest, safe_path


class SessionStore:
    def __init__(self, folder):
        self.folder = Path(folder)
        if self.folder.is_symlink() or (hasattr(self.folder, 'is_junction') and self.folder.is_junction()):
            raise ValueError('存档目录不能是链接，未写入或清理。')
        self.folder = self.folder.resolve()
        self.folder.mkdir(parents=True, exist_ok=True)
        self.manifest = self.folder / 'session.json'
        self.cache = {}
        self.lease = (self.folder / '.session.lock').open('a+b')
        try:
            if os.name == 'nt':
                import msvcrt
                self.lease.seek(0)
                if not self.lease.read(1): self.lease.write(b'0'); self.lease.flush()
                self.lease.seek(0); msvcrt.locking(self.lease.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.lease.close()
            raise ValueError('此存档正由另一个工作台占用；未重置或覆盖，请使用原入口。')

    def close(self):
        if not self.lease.closed: self.lease.close()

    def descriptor(self, path):
        path = Path(path)
        relative = path.relative_to(self.folder).as_posix() if path.is_absolute() else path.as_posix()
        path = safe_path(self.folder, relative)
        info = path.stat()
        key = (relative, info.st_size, info.st_mtime_ns)
        if key not in self.cache: self.cache[key] = digest(path)
        return {'$file': relative, 'size': info.st_size, 'sha256': self.cache[key]}

    def save(self, app):
        def encode(value):
            if isinstance(value, Path): return self.descriptor(value)
            if isinstance(value, dict): return {key: encode(v) for key, v in value.items() if key not in ('cancel', '_visuals', '_memoryOverride')}
            if isinstance(value, (tuple, list)): return [encode(v) for v in value]
            return value
        payload = encode({'schema': 1, 'sessionId': app.session_id, 'inputs': app.inputs, 'jobs': app.jobs,
                          'artifacts': app.artifacts, 'ui': app.ui_state, 'deferred': app.recovery_deferred,
                          'pendingCleanup': app.pending_cleanup, 'actionArchives': app.action_archives,
                          'archiveLimit': app.archive_limit, 'archiveSequence': app.archive_sequence,
                          'archiveRevision': app.archive_revision, 'archiveCleanup': app.archive_cleanup,
                          'archiveClearPending': app.archive_clear_pending,
                          'savedAt': time.time()})
        atomic_json(self.manifest, {'payload': payload, 'sha256': hashlib.sha256(canonical(payload)).hexdigest()})

    def load(self, app):
        if not self.manifest.exists(): return
        try:
            raw = json.loads(self.manifest.read_text(encoding='utf-8'))
            payload = raw['payload']
            if payload['schema'] != 1 or raw['sha256'] != hashlib.sha256(canonical(payload)).hexdigest():
                raise ValueError('Session manifest checksum differs')
            def decode(value):
                if isinstance(value, dict):
                    if '$file' in value:
                        path = safe_path(self.folder, value['$file'])
                        if path.stat().st_size != value['size'] or digest(path) != value['sha256']:
                            raise ValueError('Session file checksum differs')
                        return path
                    return {key: decode(v) for key, v in value.items()}
                if isinstance(value, list): return [decode(v) for v in value]
                return value
            data = decode(payload)
            if not isinstance(data['inputs'], dict) or not isinstance(data['jobs'], dict) or not isinstance(data['artifacts'], dict):
                raise ValueError('Invalid session registry')
            app.inputs, app.jobs, app.artifacts = data['inputs'], data['jobs'], data['artifacts']
            app.ui_state, app.session_id = data.get('ui', {}), data['sessionId']
            app.recovery_deferred, app.pending_cleanup = data.get('deferred', False), data.get('pendingCleanup', [])
            from action_archive import checked_limit, relative_for
            app.archive_limit = checked_limit(data.get('archiveLimit', 10))
            app.action_archives = data.get('actionArchives', {})
            app.archive_cleanup = data.get('archiveCleanup', [])
            app.archive_clear_pending = data.get('archiveClearPending', [])
            from archive_clear import checked_target, MAX_FILES
            if not isinstance(app.archive_clear_pending, list) or len(app.archive_clear_pending) > MAX_FILES:
                raise ValueError('Invalid archive clear journal')
            for target in app.archive_clear_pending: checked_target(target)
            app.archive_sequence, app.archive_revision = data.get('archiveSequence', 0), data.get('archiveRevision', 0)
            if (not isinstance(app.action_archives, dict) or len(app.action_archives) > app.archive_limit or
                    not isinstance(app.archive_cleanup, list) or
                    any(type(v) is not int or v < 0 for v in (app.archive_sequence, app.archive_revision))):
                raise ValueError('Invalid action archive registry')
            for iid, entry in app.action_archives.items():
                if iid not in app.inputs or iid != entry['inputId'] or entry['jobId'] not in app.jobs:
                    raise ValueError('Invalid archive material/job owner')
                if entry['file'] != relative_for(iid, entry['jobId']):
                    raise ValueError('Invalid action archive path')
                safe_path(self.folder, entry['file'])
                if type(entry['sequence']) is not int or not 0 < entry['sequence'] <= app.archive_sequence:
                    raise ValueError('Invalid action archive order')
            for job in app.jobs.values():
                job.pop('_memoryOverride', None)  # Audit flags never authorize a restored calculation.
                job['cancel'] = threading.Event(); job['_visuals'] = {}
                for name in ('color', 'bokeh', 'depth'):
                    job.get('progress', {}).get('visuals', {}).pop(name, None)
                if job['status'] not in ('done', 'error', 'canceled', 'interrupted'):
                    job.update(status='interrupted', phase='上次处理已中断 · 等待选择', result=None, finishedAt=time.time())
            # A new process asks again, including work postponed in the previous process.
            app.recovery_deferred = False
        except (OSError, ValueError, KeyError, TypeError) as error:
            self.close()
            raise ValueError('本地存档未通过完整性校验，未用空会话覆盖；请保留存档目录并检查日志。') from error


def checkpoint_area(app, item_id):
    """A new, uniquely owned cache subtree. Never follows imported external paths."""
    if not isinstance(item_id, str) or len(item_id) != 32 or any(c not in '0123456789abcdef' for c in item_id):
        raise ValueError('Invalid material identity')
    folder = safe_path(app.folder, 'checkpoints/' + item_id)
    marker = folder / 'owner.json'
    expected = {'kind': 'workbench-calculations-v1', 'inputId': item_id, 'sessionId': app.session_id}
    if folder.exists():
        if not marker.is_file() or json.loads(marker.read_text(encoding='utf-8')) != expected:
            raise ValueError('计算存档归属无效，未清理或覆盖。')
    else:
        folder.mkdir(parents=True)
        atomic_json(marker, expected)
    return folder


def collect_urls(value):
    if isinstance(value, str) and value.startswith('/media/'):
        return {value.split('?', 1)[0][7:]}
    if isinstance(value, dict): return set().union(*(collect_urls(v) for v in value.values()), set())
    if isinstance(value, (tuple, list)): return set().union(*(collect_urls(v) for v in value), set())
    return set()


def remove_inputs(app, ids):
    """Commit the smaller registry first. Failed unlink is reported, never rolled into
    false success. Only explicit owned files or verified per-material subtrees qualify.
    Active jobs/uploads/exports block deletion; no cancellation is implied by Clear.
    """
    from workbench import ApiError, TERMINAL
    if not isinstance(ids, list) or not 1 <= len(ids) <= 64 or any(not isinstance(v, str) for v in ids) or len(set(ids)) != len(ids):
        raise ApiError('请明确选择1–64个不同素材')
    if not app.export_lock.acquire(blocking=False): raise ApiError('正在导出，暂不能移除素材', 409)
    try:
        with app.lock:
            if app.reserved or app.importing or any(j['status'] not in TERMINAL for j in app.jobs.values()):
                raise ApiError('有上传、计算或排队任务，请等待结束或先取消；未移除素材。', 409)
            if any(i not in app.inputs for i in ids): raise ApiError('素材列表已变化，请重新选择', 409)
            chosen = set(ids)
            removed_jobs = {j['id'] for j in app.jobs.values() if j['inputId'] in chosen}
            urls = set().union(*(collect_urls(app.inputs[i]) for i in ids),
                               *(collect_urls(j.get('result')) for j in app.jobs.values() if j['id'] in removed_jobs), set())
            aids = {aid for aid, a in app.artifacts.items() if chosen.intersection(a.get('_owners', [])) or aid in urls}
            # Do not unlink anything still referenced by retained records.
            retained = set().union(*(collect_urls(i) for iid, i in app.inputs.items() if iid not in chosen),
                                   *(collect_urls(j.get('result')) for jid, j in app.jobs.items() if jid not in removed_jobs), set())
            aids -= retained
            paths = {app.inputs[i]['path'] for i in ids}
            paths |= {app.artifacts[a]['path'] for a in aids}
            kept_paths = {i['path'] for iid, i in app.inputs.items() if iid not in chosen}
            kept_paths |= {a['path'] for aid, a in app.artifacts.items() if aid not in aids}
            paths -= kept_paths
            paths = {safe_path(app.folder, Path(p).relative_to(app.folder).as_posix()) for p in paths}
            for iid in ids:
                subtree = safe_path(app.folder, 'checkpoints/' + iid)
                if subtree.exists():
                    checkpoint_area(app, iid)  # Verify owner before traversing.
                    for p in subtree.rglob('*'):
                        checked = safe_path(app.folder, p.relative_to(app.folder).as_posix())
                        if checked.is_file(): paths.add(checked)
            if app.engine_process:
                app.engine_process.close(); app.engine_process = None
            old = (app.inputs, app.jobs, app.artifacts, app.ui_state, app.pending_cleanup,
                   app.action_archives, app.archive_cleanup, app.archive_revision, app.archive_clear_pending)
            app.action_archives = {iid: e for iid, e in app.action_archives.items() if iid not in chosen}
            app.archive_cleanup = [e for e in app.archive_cleanup if e['inputId'] not in chosen]
            app.archive_clear_pending = [e for e in app.archive_clear_pending if e['inputId'] not in chosen]
            app.archive_revision += 1
            app.inputs = {iid: i for iid, i in app.inputs.items() if iid not in chosen}
            app.jobs = {jid: j for jid, j in app.jobs.items() if jid not in removed_jobs}
            app.artifacts = {aid: a for aid, a in app.artifacts.items() if aid not in aids}
            app.ui_state = {}  # Removed URLs/selections must not resurrect on reopen.
            app.pending_cleanup = sorted(set(app.pending_cleanup) | {p.relative_to(app.folder).as_posix() for p in paths})
            try: app.persist_session()
            except BaseException:
                (app.inputs, app.jobs, app.artifacts, app.ui_state, app.pending_cleanup,
                 app.action_archives, app.archive_cleanup, app.archive_revision, app.archive_clear_pending) = old
                raise
            app.export_cache = {k: v for k, v in app.export_cache.items() if k[0] not in removed_jobs}
            app._archives = {k: v for k, v in app._archives.items() if k not in removed_jobs}
            app.export_bundle_cache.clear()
            failed = []
            for relative in app.pending_cleanup:
                try: safe_path(app.folder, relative).unlink(missing_ok=True)
                except OSError: failed.append(relative)
            app.pending_cleanup = failed
            # Only empty owned directories. Do not recursively remove any unrelated path.
            for iid in ids:
                subtree = app.folder / 'checkpoints' / iid
                if subtree.is_dir():
                    for p in sorted(subtree.rglob('*'), key=lambda p: len(p.parts), reverse=True):
                        if p.is_dir():
                            try: p.rmdir()
                            except OSError: pass
                    try: subtree.rmdir()
                    except OSError: pass
            app.persist_session()
            return {'ok': True, 'removed': ids, 'cleanupPending': len(failed),
                    'notice': '工作台记录已移除；部分副本仍被占用，尚未释放空间。' if failed else '已移除工作台副本和关联记录，本机原文件未改动。'}
    finally:
        app.export_lock.release()
