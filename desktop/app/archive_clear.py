"""Explicit archive-only deletion. Preview is read-only; apply consumes a one-use token.

Only registered action JSONs or registered/checksummed legacy step blobs qualify.
Commit a narrow durable deletion journal BEFORE unlinking; never recursively erase a
calculation directory, its outputs, owner marker, shared caches or unknown files.
Interrupted/failed cleanup is retried only by a new explicit preview + confirmation.
"""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
import secrets
import stat
import time
from checkpoint_store import canonical, digest, safe_path

MAX_MANIFEST = 64 * 1024**2
MAX_FILES = 65536
PREVIEW_TTL = 300


def hex_id(value):
    return isinstance(value, str) and len(value) == 32 and all(c in '0123456789abcdef' for c in value)


def checked_target(target):
    if not isinstance(target, dict) or set(target) != {'inputId', 'jobId', 'kind', 'file', 'size', 'sha256'}:
        raise ValueError('Invalid archive cleanup descriptor')
    iid, jid, kind = target['inputId'], target['jobId'], target['kind']
    if not hex_id(iid) or not hex_id(jid) or kind not in ('action', 'legacy-blob', 'legacy-manifest'):
        raise ValueError('Invalid archive cleanup owner')
    parts = target['file'].split('/')
    if len(parts) != 4 or parts[:2] != ['checkpoints', iid]:
        raise ValueError('Invalid archive cleanup subtree')
    if kind == 'action':
        if parts[2:] != ['actions', jid + '.json']:
            raise ValueError('Invalid action cleanup path')
    elif not hex_id(parts[2]) or (kind == 'legacy-manifest' and parts[3] != 'steps.json') or (
            kind == 'legacy-blob' and (not parts[3].endswith('.npz') or not hex_id(parts[3][:-4]))):
        raise ValueError('Invalid legacy cleanup path')
    if type(target['size']) is not int or target['size'] < 0 or not isinstance(target['sha256'], str) or len(target['sha256']) != 64:
        raise ValueError('Invalid archive cleanup checksum')
    return target


def owner_area(app, iid):
    from session_store import checkpoint_area
    area = safe_path(app.folder, 'checkpoints/' + iid)
    if not area.is_dir():
        raise ValueError('Missing archive owner')
    checkpoint_area(app, iid)  # Existing marker only; the check must not create directories.
    return area


def protected_paths(app):
    paths = set()
    def visit(value):
        if isinstance(value, Path): paths.add(value.resolve())
        elif isinstance(value, dict):
            for v in value.values(): visit(v)
        elif isinstance(value, (list, tuple)):
            for v in value: visit(v)
    # Preserve every registered media/auxiliary/export dependency, not only downloadUrl.
    for value in (app.inputs, app.artifacts, app.jobs, app.export_cache, app.export_bundle_cache): visit(value)
    return paths


def inspect_file(app, target, protected, missing=False):
    checked_target(target)
    iid, jid = target['inputId'], target['jobId']
    job = app.jobs.get(jid)
    if iid not in app.inputs or not job or job['inputId'] != iid:
        raise ValueError('Missing registered archive owner')
    if target['kind'] != 'action' and job.get('_checkpointId') != target['file'].split('/')[2]:
        raise ValueError('Legacy job namespace differs')
    owner_area(app, iid)
    path = safe_path(app.folder, target['file'])
    if path.resolve() in protected:
        raise ValueError('Archive is a retained media dependency')
    try:
        info = path.lstat()
    except FileNotFoundError:
        if missing: return None
        raise
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError('Archive is not an unshared regular file')
    if info.st_size != target['size'] or digest(path) != target['sha256']:
        raise ValueError('Archive bytes changed')
    return info


def target_for(app, iid, jid, kind, relative, size, sha):
    return checked_target({'inputId': iid, 'jobId': jid, 'kind': kind,
                           'file': relative, 'size': size, 'sha256': sha})


def make_plan(app):
    import action_archive as aa
    protected = protected_paths(app)
    targets, detach, legacy_jobs, skipped = {}, set(), set(), []
    def skip(iid, kind):
        skipped.append({'name': app.inputs.get(iid, {}).get('name', '未核实存档'), 'kind': kind,
                        'reason': '归属、清单或文件校验未通过，已保留；不会删除未知文件。'})
    def row_for(target, missing=False):
        info = inspect_file(app, target, protected, missing=missing)
        row = {**target, 'exists': info is not None,
               'mtimeNs': info.st_mtime_ns if info else None}
        previous = targets.get(target['file'])
        if previous and previous != row: raise ValueError('Conflicting cleanup descriptors')
        return row
    def add(target, missing=False):
        if len(targets) >= MAX_FILES and target['file'] not in targets:
            raise ValueError('Archive cleanup list exceeds bound')
        targets[target['file']] = row_for(target, missing)
    # A durable, already-authorized narrow journal can outlive a killed process.
    for target in app.archive_clear_pending:
        try: add(target, missing=True)
        except (OSError, ValueError, TypeError, KeyError): skip(target.get('inputId'), '待清理存档')
    for entry in list(app.action_archives.values()) + list(app.archive_cleanup):
        try:
            aa.read_record(app, entry)
            add(target_for(app, entry['inputId'], entry['jobId'], 'action', entry['file'], entry['size'], entry['sha256']))
            detach.add(entry['file'])
        except (OSError, ValueError, TypeError, KeyError): skip(entry.get('inputId'), '完整动作快照')
    seen = set()
    for job in app.jobs.values():
        iid, namespace = job['inputId'], job.get('_checkpointId')
        if not namespace or job.get('_archiveCleared') or (iid, namespace) in seen: continue
        seen.add((iid, namespace))
        try:
            if not hex_id(namespace): raise ValueError('Invalid legacy namespace')
            area = owner_area(app, iid)
            manifest = safe_path(app.folder, (area / namespace / 'steps.json').relative_to(app.folder).as_posix())
            if not manifest.exists(): continue  # A task may have failed before its first checkpoint.
            if not 0 < manifest.stat().st_size <= MAX_MANIFEST: raise ValueError('Unbounded manifest')
            raw = json.loads(manifest.read_text(encoding='utf-8'))
            entries = raw['entries']
            if (raw['schema'] != 1 or raw['identity'] != aa.identity(job) or not isinstance(entries, dict) or
                    len(entries) > MAX_FILES or hashlib.sha256(canonical(entries)).hexdigest() != raw['sha256']):
                raise ValueError('Legacy manifest identity/checksum differs')
            group = []
            for entry in entries.values():
                name = entry['file']
                if not isinstance(name, str) or '/' in name or '\\' in name: raise ValueError('Invalid blob basename')
                relative = (manifest.parent / name).relative_to(app.folder).as_posix()
                target = target_for(app, iid, job['id'], 'legacy-blob', relative, entry['size'], entry['sha256'])
                group.append(target)
            group.append(target_for(app, iid, job['id'], 'legacy-manifest', manifest.relative_to(app.folder).as_posix(),
                                    manifest.stat().st_size, digest(manifest)))
            # Validate the manifest too, and publish the entire group atomically.
            staged = {t['file']: row_for(t) for t in group}
            if len(targets.keys() | staged.keys()) > MAX_FILES:
                raise ValueError('Archive cleanup list exceeds bound')
            targets.update(staged)
            legacy_jobs.update(j['id'] for j in app.jobs.values() if j['inputId'] == iid and j.get('_checkpointId') == namespace)
        except (OSError, ValueError, TypeError, KeyError): skip(iid, '旧步骤／视频帧档')
    rows = sorted(targets.values(), key=lambda r: (r['kind'] == 'legacy-manifest', r['file']))
    groups = {}
    for row in rows:
        if not row['exists']: continue
        key = (row['inputId'], '完整动作快照' if row['kind'] == 'action' else '旧步骤／视频帧档')
        group = groups.setdefault(key, {'inputId': key[0], 'name': app.inputs[key[0]]['name'],
                                       'kind': key[1], 'files': 0, 'bytes': 0})
        group['files'] += 1; group['bytes'] += row['size']
    fingerprint = hashlib.sha256(canonical({'session': app.session_id, 'revision': app.archive_revision,
        'inputs': sorted(app.inputs), 'jobs': [[j['id'], j['status'], bool(j.get('_archiveCleared'))] for j in app.jobs.values()],
        'protected': sorted(str(p) for p in protected), 'rows': rows, 'detach': sorted(detach),
        'legacyJobs': sorted(legacy_jobs), 'skipped': skipped, 'pending': app.archive_clear_pending})).hexdigest()
    return {'targets': rows, 'detach': detach, 'legacyJobs': legacy_jobs, 'fingerprint': fingerprint,
            'summary': {'files': sum(r['exists'] for r in rows), 'bytes': sum(r['size'] for r in rows if r['exists']),
                        'items': list(groups.values()), 'skipped': skipped, 'pending': len(app.archive_clear_pending),
                        'notice': '只清理校验通过的动作快照与旧步骤／视频帧档。原片、完整成片、导出文件和未知文件保留；清空后不能用这些存档恢复。'}}


def clear_archives(app, data):
    from workbench import ApiError, TERMINAL
    if set(data) - {'mode', 'confirmation'} or data.get('mode') not in ('preview', 'apply'):
        raise ApiError('请先预览并明确确认清空存档。')
    if not app.session_store: raise ApiError('此服务不支持清空持久存档，请使用新版入口。', 409)
    if not app.export_lock.acquire(blocking=False): raise ApiError('正在导出，暂不能清空存档。', 409)
    try:
        with app.lock:
            if app.reserved or app.importing or any(j['status'] not in TERMINAL for j in app.jobs.values()):
                raise ApiError('有上传、排队或计算任务，请等待结束；未清空存档。', 409)
            plan = make_plan(app)
            if data['mode'] == 'preview':
                token = secrets.token_urlsafe(32)
                app._archive_clear_preview = (token, plan['fingerprint'], time.monotonic())
                return {'ok': True, 'preview': plan['summary'], 'confirmation': token}
            issued = app._archive_clear_preview
            app._archive_clear_preview = None  # Consume even a stale/failed apply; never auto replay.
            if (not issued or not isinstance(data.get('confirmation'), str) or
                    not secrets.compare_digest(data['confirmation'], issued[0]) or issued[1] != plan['fingerprint'] or
                    time.monotonic() - issued[2] > PREVIEW_TTL):
                raise ApiError('存档列表已变化或确认已失效，请重新预览；未清空存档。', 409)
            if app.engine_process:
                app.engine_process.close(); app.engine_process = None
            old = (app.action_archives, app.archive_cleanup, app.archive_clear_pending, app.archive_revision)
            old_flags = {jid: app.jobs[jid].get('_archiveCleared') for jid in plan['legacyJobs']}
            app.action_archives = {iid: e for iid, e in app.action_archives.items() if e['file'] not in plan['detach']}
            app.archive_cleanup = [e for e in app.archive_cleanup if e['file'] not in plan['detach']]
            # Retain invalid previous journal entries; never quietly forget unreclaimed files.
            pending = {t['file']: copy.deepcopy(t) for t in app.archive_clear_pending}
            for row in plan['targets']:
                pending[row['file']] = {k: row[k] for k in ('inputId', 'jobId', 'kind', 'file', 'size', 'sha256')}
            app.archive_clear_pending = list(pending.values()); app.archive_revision += 1
            for jid in plan['legacyJobs']: app.jobs[jid]['_archiveCleared'] = True
            try: app.persist_session()
            except BaseException:
                app.action_archives, app.archive_cleanup, app.archive_clear_pending, app.archive_revision = old
                for jid, flag in old_flags.items():
                    if flag is None: app.jobs[jid].pop('_archiveCleared', None)
                    else: app.jobs[jid]['_archiveCleared'] = flag
                raise
            app._archives.clear()
            before = app.archive_clear_pending
            failed, blocked, removed, reclaimed = [], set(), 0, 0
            protected = protected_paths(app)
            # Blob failures keep their manifest, so the legacy group stays diagnosable.
            for target in sorted(before, key=lambda t: (t['kind'] == 'legacy-manifest', t['file'])):
                group = target['file'].rsplit('/', 1)[0]
                try:
                    if target['kind'] == 'legacy-manifest' and group in blocked: raise ValueError('Group has pending blobs')
                    info = inspect_file(app, target, protected, missing=True)
                    if info:
                        safe_path(app.folder, target['file']).unlink()
                        removed += 1; reclaimed += info.st_size
                except (OSError, ValueError, TypeError, KeyError):
                    failed.append(target); blocked.add(group)
            app.archive_clear_pending = failed
            saved = True
            try: app.persist_session()
            except Exception:
                app.archive_clear_pending = before; saved = False
            pending_count = len(app.archive_clear_pending)
            notice = (f'已清理 {removed} 个存档文件。原片和完整成片保留。' if not pending_count and not plan['summary']['skipped'] else
                      f'已清理 {removed} 个存档文件；部分存档未清理，不能视为全部空间已释放。原片和完整成片保留。')
            if not saved: notice += '清理结果登记未保存，请重新预览核对；不会自动重试。'
            return {'ok': True, 'removedFiles': removed, 'reclaimedBytes': reclaimed,
                    'cleanupPending': pending_count, 'skipped': plan['summary']['skipped'], 'notice': notice,
                    'recovery': app.recovery_state(), 'jobs': [app.public_job(j) for j in app.jobs.values()]}
    finally:
        app.export_lock.release()
