"""One verified COMPLETE user action per material; never a frame/step cache.

The session registry is the sole commit point. Stage before replacing that pointer,
then unlink only registered, checksum/owner-verified archive JSONs. Outputs, originals,
legacy checkpoints and unknown files are not eviction candidates.
"""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
import time
from checkpoint_store import atomic_json, canonical, digest, safe_path
from session_store import checkpoint_area, collect_urls

DEFAULT_LIMIT = 10
MAX_LIMIT = 64  # The workbench admits at most 64 materials, photos and videos combined.
MAX_RECORD = 64 * 1024**2


def checked_limit(value):
    from workbench import ApiError
    if type(value) is not int or not 1 <= value <= MAX_LIMIT:
        raise ApiError('存档数量须为1–64的整数（照片与视频合计，默认10）。')
    return value


def identity(job):
    return hashlib.sha256(canonical({
        'source': job['_sourceSha'], 'action': job['action'], 'ratio': job['ratio'],
        'options': job['options'], 'config': job['_config'], 'algorithm': job['_algorithm'],
        'playbackSource': job.get('_playbackSource', {}).get('sha256'),
        'sourceJobId': job.get('sourceJobId'),
    })).hexdigest()


def relative_for(input_id, job_id):
    for value in (input_id, job_id):
        if not isinstance(value, str) or len(value) != 32 or any(c not in '0123456789abcdef' for c in value):
            raise ValueError('Invalid action archive identity')
    return 'checkpoints/' + input_id + '/actions/' + job_id + '.json'


def read_record(app, entry):
    relative = relative_for(entry['inputId'], entry['jobId'])
    if entry['file'] != relative:
        raise ValueError('Invalid action archive path')
    area = safe_path(app.folder, 'checkpoints/' + entry['inputId'])
    if not area.is_dir():
        raise ValueError('Action archive owner directory is missing')
    checkpoint_area(app, entry['inputId'])  # Validate existing owner, never create one for a missing record.
    path = safe_path(app.folder, relative)
    if not 0 < entry['size'] <= MAX_RECORD or path.stat().st_size != entry['size'] or digest(path) != entry['sha256']:
        raise ValueError('Action archive checksum differs')
    raw = json.loads(path.read_text(encoding='utf-8'))
    payload = raw['payload']
    if raw['sha256'] != hashlib.sha256(canonical(payload)).hexdigest():
        raise ValueError('Action record checksum differs')
    if (payload['schema'] != 2 or payload['sessionId'] != app.session_id or
            payload['inputId'] != entry['inputId'] or payload['jobId'] != entry['jobId'] or
            payload['identity'] != entry['identity']):
        raise ValueError('Action record owner differs')
    return payload


def verify_assets(app, payload):
    assets = {}
    for aid, stored in payload['assets'].items():
        path = safe_path(app.folder, stored['relative'])
        if path.stat().st_size != stored['size'] or digest(path) != stored['sha256']:
            raise ValueError('Action output checksum differs')
        assets[aid] = {'path': path, 'name': stored['name'], 'type': stored['type'], '_owners': [payload['inputId']]}
    if not collect_urls(payload['result']).issubset(assets):
        raise ValueError('Action output reference is missing')
    return assets


def restore(app, entry, job):
    from workbench import ApiError
    try:
        payload = read_record(app, entry)
        if entry['identity'] != identity(job) or digest(app.inputs[job['inputId']]['path']) != job['_sourceSha']:
            raise ValueError('Action identity differs')
        return copy.deepcopy(payload['result']), verify_assets(app, payload)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ApiError('完整动作存档不可复用或校验失败；原文件与旧成片保留，请明确选择重新计算。', 409) from error


def stage(app, job, item, result):
    """Called by the parent only, after inference returned and cancellation was checked."""
    assets = {}
    for aid in collect_urls(result):
        asset = app.artifacts[aid]
        path = safe_path(app.folder, asset['path'].relative_to(app.folder).as_posix())
        assets[aid] = {'relative': path.relative_to(app.folder).as_posix(), 'size': path.stat().st_size,
                       'sha256': digest(path), 'name': asset['name'], 'type': asset['type']}
    payload = {'schema': 2, 'sessionId': app.session_id, 'inputId': item['id'], 'jobId': job['id'],
               'identity': identity(job), 'result': result, 'assets': assets}
    record = {'payload': payload, 'sha256': hashlib.sha256(canonical(payload)).hexdigest()}
    size = len(canonical(record))
    if size > MAX_RECORD:
        raise ValueError('Complete action record exceeds bounded metadata size')
    app.check(job)
    app.check_storage(size + 65536)
    checkpoint_area(app, item['id'])
    relative = relative_for(item['id'], job['id'])
    target = safe_path(app.folder, relative)
    if target.exists():
        raise ValueError('Never overwrite an immutable action archive')
    atomic_json(target, record)
    return {'inputId': item['id'], 'jobId': job['id'], 'file': relative, 'size': size,
            'sha256': digest(target), 'identity': payload['identity'], 'action': job['action'],
            'completedAt': time.time(), 'sequence': app.archive_sequence + 1}


def ordered(app, entries=None):
    return sorted((entries if entries is not None else app.action_archives).values(),
                  key=lambda e: (e['sequence'], e['jobId']))


def public_entry(app, entry):
    item = app.inputs[entry['inputId']]
    return {k: entry[k] for k in ('inputId', 'jobId', 'action', 'completedAt')} | {'name': item['name'], 'kind': item['kind']}


def state(app):
    return {'limit': app.archive_limit, 'maxFiles': MAX_LIMIT, 'revision': app.archive_revision,
            'count': len(app.action_archives), 'saved': [public_entry(app, e) for e in reversed(ordered(app))],
            'clearPending': len(app.archive_clear_pending),
            'cleanupPending': len(app.archive_cleanup), 'retention': 'latest-completed-action',
            'notice': '每个文件仅保留最近完整动作；按成功完成顺序更新，照片视频共用名额。不存中间步骤或视频帧；失败、取消不替换。淘汰不删除原片、历史完整成片、已下载文件或旧格式存档。'}


def queue_cleanup(app, entries):
    known = {e['file'] for e in app.archive_cleanup}
    for entry in entries:
        if entry['file'] not in known:
            app.archive_cleanup.append(copy.deepcopy(entry)); known.add(entry['file'])


def cleanup(app):
    """Best effort AFTER registry commit. Persisted pending list survives a crash.
    A changed/unowned file is retained and reported, never treated as reclaimed space.
    """
    pending = []
    active = {e['file'] for e in app.action_archives.values()}
    before = app.archive_cleanup
    for entry in before:
        try:
            if entry['file'] in active:
                raise ValueError('Cannot unlink an active archive')
            path = safe_path(app.folder, relative_for(entry['inputId'], entry['jobId']))
            if entry['file'] != path.relative_to(app.folder).as_posix():
                raise ValueError('Cleanup path differs')
            if path.exists():
                read_record(app, entry)  # Owner + checksum, never outputs or a recursive directory.
                path.unlink()
        except (OSError, ValueError, KeyError, TypeError):
            pending.append(entry)
    app.archive_cleanup = pending
    try:
        app.persist_session()
    except Exception:
        # Disk registry still contains the conservative pending list; retry is safe.
        app.archive_cleanup = before


def commit(app, entry):
    """Publish alongside the parent's done job and output registry, or roll back."""
    old = (app.action_archives, app.archive_cleanup, app.archive_sequence, app.archive_revision)
    app.action_archives = {**app.action_archives, entry['inputId']: entry}
    app.archive_cleanup = copy.deepcopy(app.archive_cleanup)
    retired = [old[0][entry['inputId']]] if entry['inputId'] in old[0] else []
    for expired in ordered(app)[:-app.archive_limit]:
        del app.action_archives[expired['inputId']]; retired.append(expired)
    queue_cleanup(app, retired)
    app.archive_sequence = entry['sequence']; app.archive_revision += 1
    try:
        app.persist_session()
    except BaseException:
        app.action_archives, app.archive_cleanup, app.archive_sequence, app.archive_revision = old
        raise


def set_limit(app, data):
    from workbench import ApiError, TERMINAL
    if set(data) - {'limit', 'mode', 'revision', 'confirmation'} or data.get('mode') not in ('preview', 'apply'):
        raise ApiError('请预览并确认本地动作存档设置。')
    limit = checked_limit(data.get('limit'))
    with app.lock:
        if not app.session_store:
            raise ApiError('此服务不支持持久动作存档设置。', 409)
        if app.importing or app.reserved or any(j['status'] not in TERMINAL for j in app.jobs.values()):
            raise ApiError('请等待上传、排队及计算结束后，再调整存档数量。', 409)
        if type(data.get('revision')) is not int or data['revision'] != app.archive_revision:
            raise ApiError('存档列表已变化，请重新打开设置预览，尚未淘汰任何项目。', 409)
        expired = ordered(app)[:-limit]
        confirmation = hashlib.sha256(canonical([app.session_id, app.archive_revision, limit, [e['file'] for e in expired]])).hexdigest()
        preview = {'ok': True, 'archives': state(app), 'wouldEvict': [public_entry(app, e) for e in expired], 'confirmation': confirmation}
        if data['mode'] == 'preview':
            return preview
        if expired and data.get('confirmation') != confirmation:
            raise ApiError('请先确认即将淘汰的文件存档，未保存或删除。', 409)
        old = (app.action_archives, app.archive_cleanup, app.archive_limit, app.archive_revision)
        app.action_archives = {k: v for k, v in app.action_archives.items() if v not in expired}
        app.archive_cleanup = copy.deepcopy(app.archive_cleanup)
        queue_cleanup(app, expired)
        app.archive_limit = limit; app.archive_revision += 1
        try:
            app.persist_session()
        except BaseException:
            app.action_archives, app.archive_cleanup, app.archive_limit, app.archive_revision = old
            raise
        cleanup(app)
        return {'ok': True, 'archives': state(app), 'evicted': len(expired)}
