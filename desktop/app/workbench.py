"""Loopback-only workbench adapter. No model files or filesystem paths are served.

Originals and full-size outputs live in one bounded, process-owned scratch session.
Existing worker/cache protocol and image algorithms are deliberately unchanged.
"""
from __future__ import annotations
import io
import hashlib
import base64
import copy
import json
import math
import os
import secrets
import shutil
import stat
import tempfile
import threading
import time
import traceback
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit, quote

from PIL import Image
from config import get_current_config, save_config, DEFAULT_CONFIG
from processing_options import resolutions, size_for, memory_guard, crop_pixels, subject_concurrency
from legend.public_assets import VIDEO_STYLES
from job_progress import new_progress, update_progress, finish_progress
from memory_policy import MEMORY_PREFLIGHT, MemoryPreflightError, is_memory_preflight, memory_override_scope

WEB = Path(__file__).resolve().parent / 'web'
STYLES = [
    {'id': 'leica-instant', 'name': '徕卡一瞬', 'english': 'Leica Instant', 'desc': '经典影调 · 浓郁层次', 'tone': '#bba38c'},
    {'id': 'leica-n-instant', 'name': '徕卡N瞬', 'english': 'Leica N-Instant', 'desc': '自然通透 · 柔和色彩', 'tone': '#acb49b'},
    {'id': 'leica-cool', 'name': '徕卡冷调', 'english': 'Leica Cool', 'desc': '冷冽蓝调 · 静谧光影', 'tone': '#8fa6b3'},
    {'id': 'leica-warm', 'name': '徕卡暖调', 'english': 'Leica Warm', 'desc': '温润暖意 · 琥珀日光', 'tone': '#c7a177'},
]
STYLE_IDS = {s['id'] for s in STYLES}
ACTIONS = ('photo', 'video-preview', 'video-playback', 'video')
APERTURES = (0.95, 1.8, 2.0, 2.8, 4.0, 5.6)
MAX_SESSION = 4 * 1024**3
VIDEO_WRITE_HEADROOM = 16 * 1024**2
MIN_DISK_FREE = 64 * 1024**2
TERMINAL = {'done', 'error', 'canceled', 'interrupted'}
PHASES = {
    'reading': ('读取原片', 8), 'depth': ('分析原片深度', 22),
    'gan_color': ('迁移色彩与影调', 38), 'color-ready': ('色彩处理完成', 58),
    'bokeh-start': ('按手动焦点渲染景深', 65), 'device_fallback': ('切换至 CPU 重试', 15),
    'subject': ('计算主体保护', 25),
    'composition': ('分析构图方案', 40), 'saving': ('准备原尺寸成片', 92),
}


class ApiError(Exception):
    def __init__(self, message, status=400):
        self.status = status
        super().__init__(message)


def finite_number(value, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ApiError('参数超出允许范围')
    return value


def validate_options(data):
    style = data.get('style', 'leica-instant')
    if not isinstance(style, str) or style not in STYLE_IDS:
        raise ApiError('请选择有效风格')
    color, blur = data.get('colorEnabled', True), data.get('blurEnabled', False)
    if type(color) is not bool or type(blur) is not bool:
        raise ApiError('处理选项无效，请重新设置色彩和景深开关')
    aperture = finite_number(data.get('aperture', 2.8), .95, 5.6)
    if aperture not in APERTURES:
        raise ApiError('不支持此光圈')
    point = data.get('point')
    if point is not None:
        if not isinstance(point, dict) or set(point) != {'x', 'y'}:
            raise ApiError('焦点格式错误')
        point = {k: finite_number(point[k], 0, 1) for k in ('x', 'y')}
    smart = data.get('smartFocus', False)
    if type(smart) is not bool:
        raise ApiError('智能焦点选项必须为开关')
    if blur and point is None and not smart:
        raise ApiError('请先设置手动焦点，或开启智能焦点，再启用景深显影')
    crop = data.get('crop')
    if crop is not None:
        if not isinstance(crop, list) or len(crop) != 4:
            raise ApiError('构图坐标错误')
        crop = [finite_number(v, 0, 1) for v in crop]
        if crop[0] >= crop[2] or crop[1] >= crop[3]:
            raise ApiError('构图区域为空')
    subject, batch = data.get('subjectEnabled', False), data.get('batch', False)
    if type(subject) is not bool or type(batch) is not bool:
        raise ApiError('主体保护或批量选项无效，请重新设置开关')
    points = data.get('subjectPoints', [])
    if blur or smart or subject or points != []:
        raise ApiError('桌面公开版不提供景深、智能焦点或主体保护')
    try:
        res = resolutions(data.get('resolutions'))
        concurrency = subject_concurrency(data.get('subjectConcurrency', 1))
    except ValueError as error:
        raise ApiError(str(error))
    hdr = data.get('hdrEnabled', False)
    if type(hdr) is not bool:
        raise ApiError('HDR 选项无效，请重新设置 HDR 开关')
    return dict(style=style, colorEnabled=color, blurEnabled=blur, smartFocus=smart, aperture=aperture, point=point, crop=crop,
                subjectEnabled=subject, subjectPoints=points, subjectConcurrency=concurrency,
                resolutions=res, batch=batch, hdrEnabled=hdr)


def validated_config(data):
    if not isinstance(data, dict) or set(data) - {'max_pixels', 'cpu_threads', 'video_batch_size', 'enable_video_gpu', 'video_color_workers', 'video_focus_workers', 'execution_mode', 'instant_backend', 'default_style'}:
        raise ApiError('不支持的设置项')
    for key, low, high in (('max_pixels', 10_000_000, 200_000_000), ('cpu_threads', 0, 32), ('video_batch_size', 1, 32)):
        if key in data and (type(data[key]) is not int or not low <= data[key] <= high):
            raise ApiError('设置数值超出范围')
    if data.get('instant_backend','cpu-exact') != 'cpu-exact' or data.get('enable_video_gpu',False) is not False:
        raise ApiError('桌面公开版只使用 CPU')
    if 'execution_mode' in data:
        from execution_policy import execution_mode
        try:
            execution_mode(data)
        except ValueError as error:
            raise ApiError(str(error)) from error
    if 'video_color_workers' in data:
        from color_concurrency import color_workers
        try:
            color_workers(data['video_color_workers'])
        except ValueError as error:
            raise ApiError(str(error)) from error
    if 'enable_video_gpu' in data and type(data['enable_video_gpu']) is not bool:
        raise ApiError('视频加速设置错误')
    if 'default_style' in data and data['default_style'] not in STYLE_IDS:
        raise ApiError('默认风格错误')
    return data


class Workbench:
    def __init__(self, folder=None, isolated=True, persistent=False):
        self.folder = Path(folder) if folder is not None else Path(tempfile.mkdtemp(prefix='leica-workbench-'))
        self.isolated = isolated
        self.session_id = uuid.uuid4().hex
        self.ui_state, self.pending_cleanup = {}, []
        self.recovery_deferred = False
        self.action_archives, self.archive_cleanup = {}, []
        self.archive_clear_pending, self._archive_clear_preview = [], None
        self.archive_limit, self.archive_sequence, self.archive_revision = 10, 0, 0
        self.session_store = None
        self._archives = {}
        self.engine_process = None
        self.token = secrets.token_urlsafe(32)
        self.lock = threading.RLock()
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='darkroom')
        self.inputs, self.jobs, self.artifacts = {}, {}, {}
        self._submission_receipts = {}  # Recent intentions, private to this live controller.
        self.export_lock = threading.Lock()
        self.export_cache = {}
        self.export_bundle_cache = {}
        self.reserved = 0
        self.importing = 0
        self._calculation_context = threading.local()
        self.photo_pipeline = self.video_pipeline = None
        self.instant_pipeline = self.instant_video = None
        self.started = time.time()
        self.config = {k: v for k, v in get_current_config().items() if k in DEFAULT_CONFIG}
        if persistent:
            from session_store import SessionStore
            self.folder = self.folder.resolve()
            self.session_store = SessionStore(self.folder)
            self.session_store.load(self)

    def persist_session(self):
        if self.session_store:
            with self.lock:
                self.session_store.save(self)

    def checkpoint_for(self, job, item, cb):
        if job.get('_archiveVersion') == 2:
            return None  # Complete actions only, never step/frame-batch disk writes.
        namespace = job.get('_checkpointId')
        if not namespace:
            return None
        if job['id'] not in self._archives:
            from checkpoint_store import StepArchive, digest
            from session_store import checkpoint_area
            if digest(item['path']) != job['_sourceSha']:
                raise ApiError('原片副本校验失败，未复用存档或发布成片。', 409)
            if self.session_id != job['_sessionId']:
                # The isolated worker owns no registry, but must use the parent
                # session's ownership marker. The parent registry never changes ID.
                if self.session_store:
                    raise ApiError('存档会话归属不一致，未覆盖旧记录。', 409)
                self.session_id = job['_sessionId']
            folder = checkpoint_area(self, item['id'])
            cache = folder / 'cache'
            cache.mkdir(exist_ok=True)
            identity = {'source': job['_sourceSha'], 'action': job['action'], 'ratio': job['ratio'],
                        'options': job['options'], 'config': self.config, 'algorithm': job['_algorithm'],
                        'playbackSource': job.get('_playbackSource', {}).get('sha256'), 'sourceJobId': job.get('sourceJobId')}
            self._archives[job['id']] = StepArchive(folder / namespace, identity,
                lambda: self.check(job), self.check_storage, cb)
        return self._archives[job['id']]

    def recovery_state(self):
        from action_archive import state
        jobs = [self.public_job(j) for j in self.jobs.values()
                if j['status'] in ('interrupted', 'error', 'canceled') and (j.get('_checkpointId') or j.get('_archiveVersion') == 2)
                and not j.get('_recoveredBy') and not j.get('_archiveCleared')]
        return {'pending': jobs, 'deferred': self.recovery_deferred, 'archives': state(self),
                'persistent': bool(self.session_store), 'sessionId': self.session_id,
                'location': getattr(self, 'storage_label', '本次隔离会话'),
                'notice': '只保存每文件最后一个完整动作。未完成动作从头重试，视频不保存帧或续写半成品。旧格式步骤存档保留、不自动迁移或复用。'}

    def recovery_decision(self, data):
        choice, jid = data.get('choice'), data.get('id')
        with self.lock:
            if choice == 'later':
                self.recovery_deferred = True
                self.persist_session()
                return {'ok': True, 'recovery': self.recovery_state()}
            if choice == 'restore':
                from action_archive import restore
                from checkpoint_store import algorithm_stamp, digest
                entry = next((e for e in self.action_archives.values() if e['jobId'] == jid), None)
                saved = self.jobs.get(jid)
                if not entry or not saved or saved['status'] != 'done':
                    raise ApiError('此文件存档已被更新或淘汰，请重新打开列表。', 409)
                if (saved.get('_algorithm') != algorithm_stamp() or saved.get('_config') != self.config or
                        saved.get('_sourceSha') != digest(self.inputs[saved['inputId']]['path'])):
                    raise ApiError('来源、算法或工作台设置已变化；存档保留，请明确重新计算。', 409)
                result, _ = restore(self, entry, saved)
                public = self.public_job(saved)
                public['result'] = result
                return {'ok': True, 'restored': public, 'recovery': self.recovery_state()}
            if choice not in ('continue', 'restart'):
                raise ApiError('请选择恢复完整动作、继续、重新或稍后')
            old = self.jobs.get(jid)
            if not old or old['status'] not in ('interrupted', 'error', 'canceled') or old.get('_recoveredBy'):
                raise ApiError('此任务当前不需要恢复，请更新记录', 409)
            if any(j['status'] not in TERMINAL for j in self.jobs.values()):
                raise ApiError('请先等待当前任务结束，再选择恢复', 409)
            options = {**old['options'], 'inputId': old['inputId'], 'action': old['action'], 'ratio': old['ratio']}
            if old.get('sourceJobId'): options['sourceJobId'] = old['sourceJobId']
            if choice == 'continue':
                if old.get('_archiveVersion') != 2:
                    raise ApiError('旧格式按步骤或帧批次保存，现已停止复用；旧文件保留，请明确选择重新计算。', 409)
                from checkpoint_store import algorithm_stamp, digest
                if (old.get('_algorithm') != algorithm_stamp() or
                        old.get('_sourceSha') != digest(self.inputs[old['inputId']]['path']) or
                        old.get('_config') != self.config):
                    raise ApiError('来源、算法或工作台设置已变化，不能复用旧步骤；请明确选择重新计算。', 409)
                options['_resumeFrom'] = old['id']
            job = self.submit(options)
            old['_recoveredBy'] = job['id']
            self.recovery_deferred = False
            self.persist_session()
            return {'ok': True, 'job': job, 'recovery': self.recovery_state()}

    def save_ui(self, data):
        # Unsubmitted client queues are deliberately never persisted as executable jobs.
        if set(data) - {'current', 'mode', 'sharedEdits', 'edits', 'view', 'override'}:
            raise ApiError('不支持的工作区记录')
        with self.lock:
            self.ui_state = copy.deepcopy(data)
            self.persist_session()
        return {'ok': True}

    def check_storage(self, additional=0):
        """Actual scratch bytes plus bounded reservations, never a whole-video pixel volume.

        additional is a one-step disk requirement (e.g. the mux's second encoded copy),
        not RAM and not a reservation held for the lifetime of the entire video.
        """
        if type(additional) is not int or additional < 0:
            raise ApiError('临时空间预留量必须是非负整数字节数')
        with self.lock:
            try:
                if self.folder.is_symlink() or (hasattr(self.folder, 'is_junction') and self.folder.is_junction()):
                    raise ApiError('会话目录含链接，已停止写入以保护原文件。', 409)
                try:
                    # rglob silently returns [] for a missing root (and can hide
                    # access errors). Check the root explicitly and enumerate each
                    # directory without following links or swallowing permissions.
                    root_info = self.folder.stat()
                    if not stat.S_ISDIR(root_info.st_mode):
                        raise NotADirectoryError(str(self.folder))
                except FileNotFoundError as error:
                    # Only an unused session can be safely recreated. Never hide the
                    # loss of registered originals/results or race an in-flight write.
                    if self.session_store or self.inputs or self.jobs or self.artifacts or self.reserved:
                        raise ApiError('会话临时目录已失效，已有素材或处理记录无法通过重建空目录恢复；'
                                       '未自动重置会话。请重新开启工作台并重新导入本机原片。', 409) from error
                    self.folder.mkdir(parents=True, exist_ok=True)
                used = 0
                directories = [self.folder]
                while directories:
                    # DirEntry reuses the OS enumeration metadata (not a cross-check cache).
                    # Every invocation still scans all entries, refuses links and checks free disk.
                    with os.scandir(directories.pop()) as entries:
                        for entry in entries:
                            try:
                                info = entry.stat(follow_symlinks=False)
                            except FileNotFoundError:
                                continue  # An owned partial can disappear during enumeration.
                            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_reparse_tag', None) == getattr(stat, 'IO_REPARSE_TAG_MOUNT_POINT', 0xA0000003):
                                raise ApiError('会话目录含链接，已停止写入以保护原文件。', 409)
                            if stat.S_ISREG(info.st_mode):
                                used += info.st_size
                            elif stat.S_ISDIR(info.st_mode):
                                directories.append(entry.path)
                free = shutil.disk_usage(self.folder).free
            except OSError as error:
                # Do not expose local paths or let a missing/unwritable scratch
                # directory escape as an unhandled upload/storage exception.
                raise ApiError('无法访问会话临时目录，请检查目录权限与磁盘状态后重试；'
                               '未自动清理文件或重置会话。', 507) from error
            if used + self.reserved + additional > MAX_SESSION:
                raise ApiError(f'本次会话临时文件预算不足（已用 {used / 1024**2:.1f} MiB，'
                               f'已预留 {self.reserved / 1024**2:.1f} MiB，本步骤另需 {additional / 1024**2:.1f} MiB，'
                               f'上限 {MAX_SESSION / 1024**2:.0f} MiB）；原片与已有成片保留。', 413)
            if free < self.reserved + additional + MIN_DISK_FREE:
                raise ApiError('临时目录所在磁盘可用空间不足，无法保留写盘安全余量；'
                               '请释放磁盘空间后再试，原片与已有成片保留。', 507)

    def reserve(self, size):
        with self.lock:
            self.check_storage(size)
            self.reserved += size

    def public_input(self, item):
        return {k: v for k, v in item.items() if k != 'path' and not k.startswith('_')}

    def public_job(self, job):
        public = copy.deepcopy({k: v for k, v in job.items() if k != 'cancel' and not k.startswith('_')})
        if job.get('_archiveCleared'):
            public['archive'] = {'saved': False, 'cleared': True, 'format': 1}
        elif job.get('_archiveVersion') == 2:
            entry = self.action_archives.get(job['inputId'])
            public['archive'] = {'saved': bool(entry and entry['jobId'] == job['id']),
                                 'reused': bool(job.get('_archiveReused')), 'format': 2}
        elif job.get('_checkpointId'):
            public['archive'] = {'saved': False, 'format': 1, 'legacy': True}
        public['memoryRetryAllowed'] = bool(job['status'] == 'error' and job.get('errorCode') == MEMORY_PREFLIGHT
                                            and not job.get('memoryOverrideUsed') and not job.get('_memoryRetriedBy'))
        return public

    def snapshot(self):
        from video_pipeline import find_ffmpeg
        from hdr_photo import available as hdr_available
        from video_playback import playback_available
        ffmpeg = find_ffmpeg()
        preview_available = playback_available(ffmpeg)
        with self.lock:
            active = [self.public_job(j) for j in self.jobs.values()]
            device = self.photo_pipeline.device if self.photo_pipeline else (self.engine_process.last_device if self.engine_process else '待首次处理检测')
            return dict(ok=True, version='Desktop Source Edition', token=self.token, styles=STYLES,
                        apertures=APERTURES, config=self.config, device=device,
                        audioAvailable=bool(ffmpeg),
                        inputs=[self.public_input(v) for v in self.inputs.values()], jobs=active,
                        recovery=self.recovery_state(), workspace=self.ui_state,
                        limits={'sessionBytes': MAX_SESSION, 'maxInputs': 64},
                        capabilities={'photo': True, 'depth': False, 'composition': False, 'batch': True,
                                      'video': True, 'archiveClear': bool(self.session_store), 'actionArchives': bool(self.session_store), 'videoColorConcurrency': True, 'sessionRecovery': bool(self.session_store), 'materialRemoval': True, 'videoFocusConcurrency': False, 'videoStorageGuard': True, 'videoPlaybackPreview': preview_available, 'videoResultPlaybackPreview': preview_available, 'smartFocus': False, 'videoSmartFocus': False, 'videoManualFocus': False, 'hdr': hdr_available(), 'subjectProtection': False,
                                      'subjectConcurrency': False, 'subjectPointCache': False, 'startupMetrics': True, 'memoryOverride': True, 'executionMode': True, 'instantBackend': False,
                                      'videoStyles': list(VIDEO_STYLES), 'continuousResolution': True,
                                       'metadataExport': True, 'exportFormats': ['png-lossless','jpeg-95','webp-90','webp-95','webp-lossless','hdr-gainmap'],
                                       'fastCancel': self.isolated, 'camera': True, 'externalWatch': True, 'space': False})

    def get_pipeline(self, progress=lambda *a: None):
        if self.photo_pipeline is None:
            from pipeline import LeicaPipeline
            self.photo_pipeline = LeicaPipeline()
        return self.photo_pipeline

    def get_instant_pipeline(self, progress=lambda *a: None):
        return self.get_pipeline(progress)

    def instant_only(self, opts):
        return opts['colorEnabled'] and opts['style'] in VIDEO_STYLES

    def get_video(self, progress=lambda *a: None, instant_only=False):
        if self.video_pipeline is None:
            from video_pipeline import LeicaVideoPipeline
            self.video_pipeline = LeicaVideoPipeline()
        return self.video_pipeline

    def decode(self, item, progress=lambda *a: None):
        from legend.io import decode
        image, meta = decode(item['path'], SimpleNamespace(max_bytes=1024**3, max_working_pixels=self.config['max_pixels']), progress=progress)
        image.info['subject_cache_identity'] = ('source', meta['source_sha256'], image.size)
        return image, meta

    def action_folder(self):
        return getattr(self._calculation_context, 'folder', self.folder)

    def image_asset(self, image, prefix, full=False):
        aid = uuid.uuid4().hex
        path = self.action_folder() / (aid + ('.png' if full else '.jpg'))
        working = image.copy()
        try:
            if not full:
                working.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
            estimate = working.width * working.height * (4 if full else 3) + 8192
            self.reserve(estimate)
            try:
                from legend.io import save_image_exclusive
                save_image_exclusive(working, path)
            finally:
                with self.lock:
                    self.reserved -= estimate
            with self.lock:
                self.artifacts[aid] = {'path': path, 'name': prefix + path.suffix,
                                       'type': 'image/png' if full else 'image/jpeg'}
            return '/media/' + aid
        finally:
            working.close()

    def import_file(self, path, name, kind):
        with self.lock:
            if len(self.inputs) + self.importing >= 64:
                raise ApiError('一次会话最多导入 64 个文件', 413)
            self.importing += 1
        try:
            return self._import_file(path, name, kind)
        finally:
            with self.lock:
                self.importing -= 1

    def _import_file(self, path, name, kind):
        path = Path(path).resolve(strict=True)
        # HTTP uploads already have an owned name. Direct callers must also get a
        # copy; deletion never targets a caller's original outside this session.
        if not path.is_relative_to(self.folder.resolve()):
            self.check_storage(path.stat().st_size)
            target = self.folder / (uuid.uuid4().hex + path.suffix)
            with path.open('rb') as source, target.open('xb') as output:
                shutil.copyfileobj(source, output)
            path = target
        iid = uuid.uuid4().hex
        item = {'id': iid, 'name': name[:180], 'kind': kind, 'path': path}
        if kind == 'photo':
            from hdr_photo import source_is_hdr
            item['hdrSource'] = source_is_hdr(path)
            with Image.open(path) as check:
                # Pillow labels JPEG base + gainmap MPF containers as MPO. Accept
                # that JPEG variant, not arbitrary stereo/multiframe MPO photos.
                # Native gainmap/gamut validation still runs before HDR processing.
                gainmap_jpeg = check.format == 'MPO' and item['hdrSource']
                if check.format not in ('JPEG', 'PNG', 'WEBP') and not gainmap_jpeg:
                    raise ApiError('照片仅支持 JPEG、PNG 或 WebP')
                check.verify()
            image, meta = self.decode(item)
            try:
                item.update(width=image.width, height=image.height, originalSize=meta['oriented_size'],
                            downscaled=list(image.size) != meta['oriented_size'],
                            previewUrl=self.image_asset(image, 'original-preview'))
            finally:
                image.close()
        else:
            # Metadata extraction does not initialize the color or depth engines.
            from video_pipeline import LeicaVideoPipeline
            info = LeicaVideoPipeline.get_video_info(None, path)
            if min(info['width'], info['height']) < 2 or info['durationSeconds'] <= 0:
                raise ApiError('无法读取有效视频')
            item.update({k: info[k] for k in ('width', 'height', 'fps', 'totalFrames', 'durationSeconds')})
            aid = uuid.uuid4().hex
            mime = {'.mov': 'video/quicktime', '.webm': 'video/webm', '.avi': 'video/x-msvideo', '.mkv': 'video/x-matroska'}.get(path.suffix, 'video/mp4')
            with self.lock:
                self.artifacts[aid] = {'path': path, 'type': mime, 'name': 'original-video' + path.suffix}
            item['videoUrl'] = '/media/' + aid
        with self.lock:
            from session_store import collect_urls
            for aid in collect_urls(item):
                self.artifacts[aid]['_owners'] = [iid]
            self.inputs[iid] = item
            self.persist_session()
        return self.public_input(item)

    def _remember_submission(self, request_id, fingerprint, jid):
        # Called only under self.lock. Bounded receipts never grant recovery/memory permission.
        if request_id:
            self._submission_receipts[request_id] = (fingerprint, jid)
            if len(self._submission_receipts) > 512:
                self._submission_receipts.pop(next(iter(self._submission_receipts)))

    def submit(self, data, *, memory_override=False, memory_retry_of=None):
        request_id = data.get('requestId')
        if 'requestId' in data and (not isinstance(request_id, str) or len(request_id) != 32 or
                                    any(c not in '0123456789abcdef' for c in request_id)):
            raise ApiError('提交标识无效，请重新明确点击显影')
        if type(memory_override) is not bool or any(k in data for k in ('memoryOverride', '_memoryOverride', 'skipMemoryCheck')):
            raise ApiError('内存预检仅可从失败提示明确单次重试；不接受全局跳过参数')
        action = data.get('action', 'photo')
        if action not in ACTIONS:
            raise ApiError('不支持的处理动作')
        with self.lock:
            item = self.inputs.get(data.get('inputId'))
            if not item:
                raise ApiError('素材已失效，请重新导入', 404)
            if (item['kind'] == 'video') != action.startswith('video'):
                raise ApiError('素材类型与操作不符')
            options = validate_options({} if action == 'video-playback' else data)
            if action in ('video', 'video-preview') and options['style'] not in VIDEO_STYLES:
                raise ApiError('视频只支持徕卡一瞬和 N 瞬')
            if options['hdrEnabled']:
                from hdr_photo import available as hdr_available
                if action != 'photo' or not item.get('hdrSource') or not hdr_available():
                    raise ApiError('HDR 显影需要可解码的增益图原片和本机 HDR 编解码器；当前条件不满足，未开始处理')
            if action.startswith('video'):
                if options['subjectEnabled'] or options['crop']:
                    raise ApiError('视频不支持主体保护或构图裁切')
                # validate_options requires an explicit point when smart focus is off.
            if action == 'photo' and not options['colorEnabled'] and not options['blurEnabled'] and options['crop'] is None:
                raise ApiError('请启用色彩、景深或选择构图')
            ratio = finite_number(data.get('ratio', .1), 0, 1)
            jid = uuid.uuid4().hex
            job = dict(id=jid, inputId=item['id'], name=item['name'], action=action, status='queued',
                       phase='等待处理', percent=0, createdAt=time.time(), result=None, error=None,
                       options=options, ratio=ratio, cancel=threading.Event(),
                       progress=new_progress(action, options), _visuals={},
                       _memoryOverride=memory_override, memoryOverrideUsed=memory_override,
                       _retryConfig=copy.deepcopy(self.config))
            if 'sourceJobId' in data and (action != 'video-playback' or not isinstance(data['sourceJobId'], str) or not data['sourceJobId']):
                raise ApiError('请明确选择有效的完整成片版本；未使用原片替代。')
            if action == 'video-playback' and data.get('sourceJobId'):
                # Only an explicit completed version of this material is accepted.
                # No arbitrary paths/URLs or fallback to the original can enter here.
                from checkpoint_store import digest, safe_path
                source = self.jobs.get(data['sourceJobId'])
                if not source or source['status'] != 'done' or source['action'] != 'video' or source['inputId'] != item['id']:
                    raise ApiError('请选择此素材已经完成的视频版本；未使用原片替代。', 409)
                url = source.get('result', {}).get('downloadUrl', '').split('?', 1)[0]
                asset = self.artifacts.get(url[7:]) if url.startswith('/media/') else None
                if not asset or asset.get('type') != 'video/mp4':
                    raise ApiError('完整成片文件不可用，未生成预览或替换原片。', 409)
                path = safe_path(self.folder, asset['path'].relative_to(self.folder).as_posix())
                if not path.is_file(): raise ApiError('完整成片文件不可用。', 409)
                result = source['result']
                job['sourceJobId'] = source['id']
                job['_playbackSource'] = {'path': path, 'sha256': digest(path),
                    'info': {'width': result['width'], 'height': result['height'],
                             'totalFrames': result['frames'], 'durationSeconds': item['durationSeconds'], 'fps': item['fps']}}
            fingerprint = json.dumps({'inputId': item['id'], 'action': action, 'options': options,
                'ratio': ratio if action == 'video-preview' else None, 'sourceJobId': data.get('sourceJobId'),
                'config': self.config, 'memoryOverride': memory_override,
                'memoryRetryOf': memory_retry_of, 'resumeFrom': data.get('_resumeFrom')},
                sort_keys=True, separators=(',', ':'), allow_nan=False)
            receipt = self._submission_receipts.get(request_id)
            if receipt and receipt[1] in self.jobs:
                if receipt[0] != fingerprint:
                    raise ApiError('同一次提交的参数已变化；未重复计算，请重新明确显影。', 409)
                return self.public_job(self.jobs[receipt[1]])
            for existing in self.jobs.values():
                if existing['status'] not in TERMINAL and existing.get('_submissionIdentity') == fingerprint:
                    self._remember_submission(request_id, fingerprint, existing['id'])
                    return self.public_job(existing)
            if len(self.jobs) >= 160 or sum(j['status'] not in TERMINAL for j in self.jobs.values()) >= 32:
                raise ApiError('任务队列已满，请等待当前任务结束', 409)
            job['_submissionIdentity'] = fingerprint
            if self.session_store:
                from checkpoint_store import algorithm_stamp, digest
                from action_archive import identity
                old = self.jobs.get(data.get('_resumeFrom'))
                job.update(_archiveVersion=2, _sessionId=self.session_id,
                           _sourceSha=digest(item['path']), _algorithm=algorithm_stamp(),
                           _config=copy.deepcopy(self.config))
                saved = self.action_archives.get(item['id']) if old else None
                if saved and saved['identity'] == identity(job):
                    job['_restoreArchive'] = copy.deepcopy(saved)
            retry_source = self.jobs.get(memory_retry_of) if memory_retry_of else None
            if retry_source is not None:
                retry_source['_memoryRetriedBy'] = retry_source['_recoveredBy'] = jid
            self.jobs[jid] = job
            try:
                self.persist_session()  # Intention and one-shot consumption share a commit.
            except BaseException:
                del self.jobs[jid]
                if retry_source is not None:
                    retry_source.pop('_memoryRetriedBy', None)
                    retry_source.pop('_recoveredBy', None)
                raise
            self.executor.submit(self.run, jid, item)
            self._remember_submission(request_id, fingerprint, jid)
            return self.public_job(job)

    def memory_retry(self, data):
        if set(data) != {'id', 'confirm'} or data.get('confirm') is not True or not isinstance(data.get('id'), str):
            raise ApiError('请明确点击“不管不顾，直接开始”，仅重试所选失败任务')
        with self.lock:
            old = self.jobs.get(data['id'])
            if not old or not self.public_job(old)['memoryRetryAllowed']:
                raise ApiError('此任务不能跳过内存预检或已经重试；未自动执行。', 409)
            if self.importing or self.reserved or any(j['status'] not in TERMINAL for j in self.jobs.values()):
                raise ApiError('请等待上传、排队或计算结束，再明确重试此任务。', 409)
            if old.get('_retryConfig', old.get('_config')) != self.config:
                raise ApiError('工作台设置已变化；请用新设置正常提交，未沿用旧内存授权。', 409)
            if old.get('_sourceSha'):
                from checkpoint_store import digest
                item = self.inputs.get(old['inputId'])
                if not item or digest(item['path']) != old['_sourceSha']:
                    raise ApiError('原片副本校验失败，未重新计算或发布成片。', 409)
            payload = {**copy.deepcopy(old['options']), 'inputId': old['inputId'],
                       'action': old['action'], 'ratio': old['ratio']}
            if old.get('sourceJobId'): payload['sourceJobId'] = old['sourceJobId']
            submitted = self.submit(payload, memory_override=True, memory_retry_of=old['id'])
            return {'ok': True, 'job': submitted, 'previous': self.public_job(old)}

    def check(self, job):
        if job['cancel'].is_set():
            raise InterruptedError()

    def progress(self, job, phase, message='', detail=None):
        self.check(job)
        # Ignore engine message text: it can contain private paths/identifiers.
        with self.lock:
            update_progress(job, phase, detail)
            if phase in ('checkpoint-saved', 'checkpoint-reused'):
                job['checkpoint'] = {'savedSteps': detail.get('checkpointSteps', 0),
                                     'reusedSteps': detail.get('reusedSteps', 0), 'lastStep': detail.get('step')}
                self.persist_session()
            if isinstance(detail, dict):
                self.keep_visual(job, phase, detail)
                if detail.get('unit') == 'frames':
                    job.update(frames=detail.get('completed', 0), totalFrames=detail.get('total'),
                               fps=detail.get('fps'), etaSeconds=detail.get('etaSeconds'))

    def keep_visual(self, job, phase, detail):
        if phase in ('bokeh-start', 'depth-ready'):
            cols, rows = detail.get('cols'), detail.get('rows')
            if (type(cols) is int and type(rows) is int and 1 <= cols <= 48 and 1 <= rows <= 48):
                grid = {'cols': cols, 'rows': rows, 'displayOnly': True}
                for key in ('values', 'focusValues'):
                    values = detail.get(key)
                    if (isinstance(values, list) and len(values) == cols * rows and
                            all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1 for v in values)):
                        grid[key] = values
                if 'values' in grid:
                    job['progress']['visuals']['grid'] = grid
        kind, encoded = (('depth', detail.get('thermalBase64')) if phase in ('bokeh-start', 'depth-ready')
                         else ('color' if phase == 'color-ready' else 'bokeh', detail.get('previewBase64')))
        if not encoded or phase not in ('color-ready', 'bokeh-pass', 'bokeh-start', 'depth-ready'):
            return
        # Bounded preview-only memory; never insert base64 or private model data in jobs JSON.
        if not isinstance(encoded, str) or len(encoded) > 700000:
            return
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError):
            return
        if not raw.startswith(b'\xff\xd8') or len(raw) > 512 * 1024:
            return
        used = sum(len(b) for j in self.jobs.values() for b in j.get('_visuals', {}).values())
        old = len(job['_visuals'].get(kind, b''))
        if used - old + len(raw) > 32 * 1024**2:
            return
        job['_visuals'][kind] = raw
        previous = job['progress']['visuals'].get(kind, {})
        seq = previous.get('sequence', 0) + 1
        job['progress']['visuals'][kind] = {
            'sequence': seq, 'url': f'/api/progress-image?job={job["id"]}&kind={kind}&v={seq}',
            'label': {'depth':'实际深度回传', 'color':'真实调色中间图', 'bokeh':'真实虚化尺度回传'}[kind],
            'pass': detail.get('pass', 0) if type(detail.get('pass', 0)) is int else 0,
            'displayOnly': True,
        }

    def run(self, jid, item):
        job = self.jobs[jid]
        start = time.perf_counter()
        try:
            self.check(job)
            with self.lock:
                job.update(status='running', phase='准备处理', percent=0, startedAt=time.time())
            action, opts = job['action'], job['options']
            cb = lambda *args: self.progress(job, *args)
            cb('prepare')
            assets = {}
            before_assets = dict(self.artifacts)
            if job.get('_restoreArchive'):
                from action_archive import restore
                entry = job['_restoreArchive']
                if self.action_archives.get(item['id']) != entry:
                    raise ApiError('最后完整动作已变化，请重新选择；未自动重算。', 409)
                result, assets = restore(self, entry, job)
                job['_archiveReused'] = True
            elif self.isolated and self.photo_pipeline is None:
                from engine_process import EngineProcess
                if self.engine_process is None:
                    self.engine_process = EngineProcess(self.folder, self.config)
                result, assets = self.engine_process.execute(job, item, cb)
            else:
                result = self.execute_action(job, item, cb)
            with self.lock:
                self.check(job)
                before_job = dict(job)
                before_job['progress'] = copy.deepcopy(job['progress'])
                staged = None
                try:
                    for asset in assets.values():
                        asset['_owners'] = [item['id']]
                    self.artifacts.update(assets)
                    from session_store import collect_urls
                    for aid in collect_urls(result):
                        if aid in self.artifacts: self.artifacts[aid]['_owners'] = [item['id']]
                    # Viewing proxies are not development actions; retain the complete-result archive.
                    if job.get('_archiveVersion') == 2 and job['action'] != 'video-playback':
                        from action_archive import stage
                        staged = stage(self, job, item, result)
                    self.check(job)
                    finish_progress(job, 'done')
                    job.update(status='done', phase='处理完成', percent=100, result=result, finishedAt=time.time(),
                               elapsedSeconds=round(time.perf_counter() - start, 2))
                    if staged:
                        from action_archive import commit
                        commit(self, staged)
                    else:
                        self.persist_session()
                except BaseException:
                    job.clear(); job.update(before_job)
                    self.artifacts = before_assets
                    if staged:
                        from action_archive import queue_cleanup
                        queue_cleanup(self, [staged])
                    raise
                if staged:
                    from action_archive import cleanup
                    cleanup(self)
        except InterruptedError:
            with self.lock:
                finish_progress(job, 'canceled')
                job.update(status='canceled', phase='已取消', result=None, finishedAt=time.time(), elapsedSeconds=round(time.perf_counter()-start, 2))
                self.persist_session()
        except Exception as error:
            # Diagnostics stay local; don't expose asset paths, engine metadata or stack traces via HTTP.
            traceback.print_exc()
            low_memory = is_memory_preflight(error)
            message = str(error) if isinstance(error, ApiError) or low_memory else '处理未完成，请检查本地终端日志后重试；原片未改变。'
            with self.lock:
                finish_progress(job, 'error')
                job.update(status='error', phase='处理失败', error=message,
                           errorCode=MEMORY_PREFLIGHT if low_memory else None,
                           result=None, finishedAt=time.time(), elapsedSeconds=round(time.perf_counter()-start, 2))
                self.persist_session()
        finally:
            job.pop('_memoryOverride', None)

    def execute_action(self, job, item, cb):
        # Scope is reset on success, cancellation and failure, including persistent workers.
        with memory_override_scope(job.get('_memoryOverride', False)):
            return self._execute_memory_action(job, item, cb)

    def _execute_memory_action(self, job, item, cb):
        if job.get('_archiveVersion') == 2:
            from checkpoint_store import digest, safe_path
            from session_store import checkpoint_area
            if digest(item['path']) != job['_sourceSha']:
                raise ApiError('原片副本校验失败，未处理或发布新成片。', 409)
            self.session_id = job['_sessionId'] if not self.session_store else self.session_id
            if self.session_id != job['_sessionId']:
                raise ApiError('动作存档归属不一致。', 409)
            checkpoint_area(self, item['id'])
            output = safe_path(self.folder, 'checkpoints/' + item['id'] + '/outputs')
            output.mkdir(exist_ok=True)
            previous = getattr(self._calculation_context, 'folder', self.folder)
            old_mode = getattr(self._calculation_context, 'action_only', False)
            self._calculation_context.folder, self._calculation_context.action_only = output, True
            try:
                return self._execute_action_body(job, item, cb)
            finally:
                self._calculation_context.folder, self._calculation_context.action_only = previous, old_mode
        checkpoint = self.checkpoint_for(job, item, cb)
        previous = getattr(self._calculation_context, 'folder', self.folder)
        try:
            if checkpoint:
                # Including killed encoder files, all per-material calculations
                # stay in the verified owner subtree, never beside external originals.
                folder = checkpoint.folder.parent / 'outputs'
                folder.mkdir(exist_ok=True)
                self._calculation_context.folder = folder
            return self._execute_checkpointed_action(job, item, cb)
        finally:
            self._calculation_context.folder = previous

    def _execute_checkpointed_action(self, job, item, cb):
        from checkpoint_store import digest, safe_path
        checkpoint = self.checkpoint_for(job, item, cb)
        if checkpoint:
            saved = checkpoint.load('action-complete')
            if saved:
                result, assets = saved
                restored = {}
                for aid, asset in assets.items():
                    path = safe_path(self.folder, asset['relative'])
                    if not path.is_file() or path.stat().st_size != asset['size'] or digest(path) != asset['sha256']:
                        raise ApiError('完整结果存档校验失败；旧文件已保留，请选择重新计算。', 409)
                    restored[aid] = {'path': path, 'name': asset['name'], 'type': asset['type']}
                self.artifacts.update(restored)
                return result
        before = set(self.artifacts)
        result = self._execute_action_body(job, item, cb)
        if checkpoint:
            assets = {}
            for aid in set(self.artifacts) - before:
                asset = self.artifacts[aid]
                assets[aid] = {'relative': asset['path'].relative_to(self.folder).as_posix(),
                               'size': asset['path'].stat().st_size, 'sha256': digest(asset['path']),
                               'name': asset['name'], 'type': asset['type']}
            checkpoint.save('action-complete', (result, assets))
        return result

    def _execute_action_body(self, job, item, cb):
        action, opts = job['action'], validate_options(job['options'])
        if action not in ACTIONS or action in ('video', 'video-preview') and opts['style'] not in VIDEO_STYLES:
            raise ApiError('桌面公开版不支持此动作或视频风格')
        checkpoint = self.checkpoint_for(job, item, cb)
        if action == 'photo':
            return self.process_photo(job, item, cb)
        if action == 'video-playback':
            from video_pipeline import find_ffmpeg
            from video_playback import create_playback_preview
            cb('reading'); self.check(job)
            source = job.get('_playbackSource')
            if source:
                from checkpoint_store import digest, safe_path
                source_path = safe_path(self.folder, source['path'].relative_to(self.folder).as_posix())
                if digest(source_path) != source['sha256']:
                    raise ApiError('完整成片校验失败，未转换或使用原片替代。', 409)
                info = source['info']
            else:
                source_path, info = item['path'], item
            ffmpeg = find_ffmpeg()
            suffix = '.mp4' if ffmpeg else '.webm'
            output = self.action_folder() / (uuid.uuid4().hex + suffix)
            # Streaming output: hold one bounded write's headroom, not a guessed
            # whole-video bitrate. Actual growth remains subject to session/disk limits.
            estimate = VIDEO_WRITE_HEADROOM
            self.reserve(estimate)
            try:
                def storage_guard():
                    self.check(job)
                    self.check_storage()
                raw = create_playback_preview(source_path, output, info, ffmpeg, lambda: self.check(job), cb,
                                              storage_guard=storage_guard)
                cb('saving')
                aid = uuid.uuid4().hex
                with self.lock:
                    storage_guard()
                    self.artifacts[aid] = {'path': output, 'type': raw.get('mime', 'video/webm'),
                                           'name': ('result-playback-preview' if source else 'original-playback-preview') + suffix}
                return {**raw, 'playbackUrl': '/media/' + aid,
                        **({'sourceJobId': job['sourceJobId'], 'sourceKind': 'completed-output',
                            'notice': raw.get('notice', '正在播放完整成片的兼容预览；仅供查看，下载仍为原完整MP4。')} if source else {})}
            except BaseException as error:
                output.unlink(missing_ok=True)
                if isinstance(error, ValueError):
                    raise ApiError(str(error)) from error
                raise
            finally:
                with self.lock:
                    self.reserved -= estimate
        if action == 'video-preview':
            video = self.get_video(cb, instant_only=self.instant_only(opts))
            cb('reading')
            frame_processor = None
            try:
                raw = video.get_preview_frame(item['path'], job['ratio'], opts['style'], output_size=self.video_size(item, opts), progress=cb,
                                              **({'frame_processor':frame_processor} if frame_processor else {}))
            finally:
                if frame_processor is not None and callable(getattr(frame_processor, 'close', None)):
                    frame_processor.close()
            cb('saving'); self.check(job)
            result = {k: raw[k] for k in ('frameIndex', 'originalBase64', 'gradedBase64', 'style', 'width', 'height')}
            if frame_processor:
                result['videoFocus'] = frame_processor.summary()
                result['smartFocus'] = result['videoFocus'] if opts['smartFocus'] else None
                result['notice'] = result['videoFocus']['notice']
            return result
        return self.process_video(job, item, cb)



    def video_size(self, item, opts):
        res = opts['resolutions']
        size = size_for((item['width'], item['height']), res['videoLongEdge'], res['colorLongEdge'],
                        res['blurLongEdge'] if opts['blurEnabled'] else None,
                        res['batchLongEdge'] if opts['batch'] else None, even=True)
        if size[0] * size[1] > self.config['max_pixels']:
            raise ApiError('视频帧超过工作像素保护，请明确设置视频长边限制')
        try:
            memory_guard(*size, bytes_per_pixel=48 * (1 if opts['blurEnabled'] else self.config['video_batch_size']))
            if opts['blurEnabled']:
                memory_guard(item['width'], item['height'], bytes_per_pixel=96)
        except ValueError as error:
            raise ApiError(str(error))
        return size

    def process_photo(self, job, item, cb):
        opts = job['options']
        checkpoint = self.checkpoint_for(job, item, cb)
        pipeline = self.get_pipeline(cb) if opts['colorEnabled'] else None
        cb('reading')
        load_source = lambda: self.decode(item, cb)
        original, meta = checkpoint.compute('photo-source', load_source) if checkpoint else load_source()
        result = compare_original = None
        report = {}
        protection = focus = None
        try:
            self.check(job)
            res = opts['resolutions']
            # Explicit working-frame caps; smallest applicable cap wins. No upscaling.
            size = size_for(original.size, res['colorLongEdge'] if opts['colorEnabled'] else None,
                            res['blurLongEdge'] if opts['blurEnabled'] else None,
                            res['batchLongEdge'] if opts['batch'] else None)
            try:
                memory_guard(*size)
            except ValueError as error:
                raise ApiError(str(error))
            hdr_pair = None
            if opts.get('hdrEnabled'):
                from hdr_photo import decode_pair
                cb('hdr-source')
                hdr_work = lambda: decode_pair(item['path'], size, lambda: self.check(job))
                hdr_pair = checkpoint.compute('photo-hdr-source', hdr_work) if checkpoint else hdr_work()
            if size != original.size:
                resized = original.resize(size, Image.Resampling.LANCZOS)
                original.close(); original = resized
            completed = checkpoint.load('photo-calculation') if checkpoint and not self.instant_only(opts) else None
            if completed:
                result, report = completed
            elif opts['colorEnabled'] or opts['blurEnabled']:
                result, report = pipeline.process_image(
                    original, style=opts['style'], progress=cb,
                    **({'hdr_pair': hdr_pair} if hdr_pair is not None else {}),
                    **({'checkpoint': checkpoint.scope('photo-render')} if checkpoint else {}))
            else:
                result = original.copy()
                if hdr_pair is not None:
                    report['_hdrLinear'] = hdr_pair[1]
            if checkpoint and not completed and not self.instant_only(opts):
                checkpoint.save('photo-calculation', (result, report))
            hdr_linear = report.pop('_hdrLinear', None)
            self.check(job)
            compare_original = original.copy()
            if opts['crop']:
                cb('crop')
                box = crop_pixels(opts['crop'], *result.size)
                cropped, crop_orig = result.crop(box), compare_original.crop(box)
                result.close()
                compare_original.close()
                result, compare_original = cropped, crop_orig
                if hdr_linear is not None:
                    x0,y0,x1,y1 = box
                    hdr_linear = hdr_linear[y0:y1,x0:x1].copy()
            hdr_extra = {}
            if hdr_linear is not None:
                from hdr_photo import encode_pair
                cb('hdr-saving')
                aid = uuid.uuid4().hex
                path = self.action_folder() / (aid + '.jpg')
                estimate = result.width * result.height * 12 + 131072
                self.reserve(estimate)
                try:
                    hdr_report = encode_pair(result, hdr_linear, item['path'], path, lambda: self.check(job))
                except ValueError as error:
                    raise ApiError(str(error))
                finally:
                    with self.lock:
                        self.reserved -= estimate
                with self.lock:
                    self.artifacts[aid] = {'path': path, 'name': opts['style'] + '-' + job['id'][:8] + '-hdr.jpg', 'type': 'image/jpeg'}
                hdr_extra = {'hdrDownloadUrl': '/media/' + aid + '?download=1', 'hdr': hdr_report}
            cb('saving')
            name = opts['style'] if opts['colorEnabled'] else 'original-color'
            output = self.image_asset(result, name + '-' + job['id'][:8], full=True)
            preview = self.image_asset(result, 'result-preview')
            before = self.image_asset(compare_original, 'original-preview')
            return dict(downloadUrl=output + '?download=1', previewUrl=preview, originalUrl=before,
                        width=result.width, height=result.height, originalSize=meta['oriented_size'],
                        downscaled=list(original.size) != meta['oriented_size'],
                        blurRendered=bool(report.get('rendered')), device=report.get('color', {}).get('device', self.photo_pipeline.device if self.photo_pipeline else 'cpu') if report.get('color') else (self.photo_pipeline.device if self.photo_pipeline else 'cpu'),
                        notice=('焦点附近深度不可靠，本次未添加虚化，请重新点选。' if opts['blurEnabled'] and not report.get('rendered') else ''),
                        protection=protection, subjectProtected=bool(report.get('subjectProtection')),
                        depthCache=({k: report.get('depth', {}).get(k) for k in ('cached', 'cacheSource')} if report.get('depth') else None),
                        parameters=opts, smartFocus=focus, colorBackend=(report.get('color') or {}).get('backend'), **hdr_extra)
        finally:
            original.close()
            if result is not None:
                result.close()
            if compare_original is not None:
                compare_original.close()


    def process_video(self, job, item, progress_callback=None):
        opts = job['options']
        emit = progress_callback or (lambda *args: self.progress(job, *args))
        output = self.action_folder() / (uuid.uuid4().hex + '.mp4')
        output_size = self.video_size(item, opts)
        # One write's headroom only. Frames are encoded as they finish; their raw
        # pixel volumes and the source file's size are not all resident or preallocated.
        estimate = max(VIDEO_WRITE_HEADROOM, output_size[0] * output_size[1] * 3 + 65536)
        frame_processor = None
        self.reserve(estimate)
        try:
            vp = self.get_video(emit, instant_only=self.instant_only(opts))
            emit('reading')
            def storage_guard(additional=0):
                self.check(job)
                self.check_storage(additional)
            def cb(progress):
                self.check(job)
                with self.lock:
                    phase = progress.get('phase', 'grading')
                    detail = {'completed':progress.get('processedFrames'), 'total':item['totalFrames'],
                              'unit':'frames', 'fps':progress.get('fps'), 'etaSeconds':progress.get('etaSeconds')}
                    emit(phase, '', detail)
                    job.update(frames=progress.get('processedFrames', job.get('frames', 0)), totalFrames=item['totalFrames'],
                               fps=progress.get('fps', job.get('fps')), etaSeconds=progress.get('etaSeconds'))
            raw = vp.process_video(item['path'], output, style=opts['style'], progress_cb=cb,
                                   cancel_token=job['cancel'].is_set, output_size=output_size,
                                   storage_guard=storage_guard,
                                   **({'checkpoint': self.checkpoint_for(job, item, emit)} if job.get('_checkpointId') else {}),
                                   **({'frame_processor': frame_processor} if frame_processor else {}))
        finally:
            try:
                if frame_processor is not None and callable(getattr(frame_processor, 'close', None)):
                    frame_processor.close()
            finally:
                with self.lock:
                    self.reserved -= estimate
        self.check(job)
        self.progress(job, 'saving')
        if not output.is_file() or not output.stat().st_size:
            raise ApiError('视频未生成有效文件')
        aid = uuid.uuid4().hex
        with self.lock:
            self.artifacts[aid] = {'path': output, 'type': 'video/mp4', 'name': opts['style'] + '-' + job['id'][:8] + '.mp4'}
        focus_summary = frame_processor.summary() if frame_processor else None
        return dict(downloadUrl='/media/' + aid + '?download=1', width=output_size[0], height=output_size[1],
                    frames=raw['frames'], device=(raw.get('colorBackend') or {}).get('backend', vp.device),
                    colorBackend=raw.get('colorBackend'),
                    audioMuxed=bool(raw.get('hasAudio')),
                    audioStatus=('已完成音轨封装步骤（源若有音轨）；请试听导出结果' if raw.get('hasAudio') else '未完成原音轨封装，本次为无声视频'),
                    colorConcurrency=raw.get('colorConcurrency'),
                    videoFocus=focus_summary, smartFocus=focus_summary if opts['smartFocus'] else None,
                    notice=focus_summary['notice'] if focus_summary else '',
                    parameters=opts)

    def export_photo(self, data):
        if not self.export_lock.acquire(blocking=False):
            raise ApiError('另一个导出正在编码，请稍候', 409)
        try:
            return self._export_photo_locked(data)
        finally:
            self.export_lock.release()

    def _export_photo_locked(self, data):
        """Caller owns export_lock; immutable exports never replay an inference task."""
        from photo_export import FORMATS, export_image
        format_id, badge = data.get('format', 'png-lossless'), data.get('badge', False)
        if not isinstance(format_id, str) or format_id not in (*FORMATS, 'hdr-gainmap') or type(badge) is not bool:
            raise ApiError('导出格式或标识开关错误')
        with self.lock:
            job = self.jobs.get(data.get('id'))
            if not job or job['status'] != 'done' or job['action'] != 'photo':
                raise ApiError('请先完成照片显影', 409)
            item = self.inputs[job['inputId']]
            if format_id == 'hdr-gainmap' and not job['result'].get('hdrDownloadUrl'):
                raise ApiError('此成片没有已计算的HDR伴随结果；导出不会重新运行模型', 409)
            url = job['result']['hdrDownloadUrl'] if format_id == 'hdr-gainmap' else job['result']['downloadUrl']
            artifact = self.artifacts[urlsplit(url).path.removeprefix('/media/')]
        # The public single/batch entry points serialize this encoder.
        key = (job['id'], format_id, badge)
        if key in self.export_cache:
            return self.export_cache[key]
        if len(self.export_cache) >= 160:
            raise ApiError('本会话导出版本已达上限，已有成片保留', 413)
        effective = 'jpeg-95' if badge else format_id
        suffix = '.jpg' if format_id == 'hdr-gainmap' else FORMATS[effective][1]
        aid = uuid.uuid4().hex
        path = self.folder / (aid + suffix)
        estimate = job['result']['width'] * job['result']['height'] * 5 + 131072
        self.reserve(estimate)
        try:
            if format_id == 'hdr-gainmap':
                from hdr_photo import export_copy
                report = export_copy(artifact['path'], item['path'], path, badge)
            else:
                report = export_image(artifact['path'], item['path'], path, format_id, badge)
        except ValueError as error:
            raise ApiError(str(error))
        finally:
            with self.lock:
                self.reserved -= estimate
        with self.lock:
            self.artifacts[aid] = {'path': path, 'name': artifact['name'].rsplit('.', 1)[0] + '-export' + suffix, 'type': report['mime']}
            result = {'downloadUrl': '/media/' + aid + '?download=1', **report}
            self.artifacts[aid]['_owners'] = [item['id']]
            self.export_cache[key] = result
            self.persist_session()
        return result

    def export_bundle(self, data):
        from photo_export import FORMATS
        ids = data.get('ids')
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 160 or
                any(not isinstance(i, str) for i in ids) or len(set(ids)) != len(ids)):
            raise ApiError('请选择1–160个不同的已完成照片任务')
        fmt, badge = data.get('format', 'png-lossless'), data.get('badge', False)
        if not isinstance(fmt, str) or fmt not in (*FORMATS, 'hdr-gainmap') or type(badge) is not bool:
            raise ApiError('导出格式或标识开关错误')
        with self.lock:
            if any(i not in self.jobs or self.jobs[i]['status'] != 'done' or self.jobs[i]['action'] != 'photo' for i in ids):
                raise ApiError('批量包含未完成或非照片任务；未开始编码', 409)
            if fmt == 'hdr-gainmap' and any(not self.jobs[i]['result'].get('hdrDownloadUrl') for i in ids):
                raise ApiError('批量包含没有HDR伴随结果的成片，未开始编码', 409)
        if not self.export_lock.acquire(blocking=False):
            raise ApiError('另一个导出正在编码，请稍候', 409)
        try:
            key = (tuple(ids), fmt, badge)
            if key in self.export_bundle_cache:
                return self.export_bundle_cache[key]
            if len(self.export_bundle_cache) >= 32:
                raise ApiError('本会话批量导出版本已达上限；已有导出保留', 413)
            needed = sum((i, fmt, badge) not in self.export_cache for i in ids)
            if len(self.export_cache) + needed > 160:
                raise ApiError('本会话导出版本不足以完成此批量，未开始编码', 413)
            copies = [self._export_photo_locked({'id': i, 'format': fmt, 'badge': badge}) for i in ids]
            assets = [self.artifacts[urlsplit(c['downloadUrl']).path.removeprefix('/media/')] for c in copies]
            estimate = sum(a['path'].stat().st_size for a in assets) + 8192 + len(assets) * 512
            self.reserve(estimate)
            aid = uuid.uuid4().hex
            path = self.folder / (aid + '.zip')
            try:
                with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_STORED) as archive:
                    for n, a in enumerate(assets, 1):
                        archive.write(a['path'], f'{n:03d}-' + a['name'])
            finally:
                with self.lock:
                    self.reserved -= estimate
            with self.lock:
                self.artifacts[aid] = {'path': path, 'name': 'leica-exports-' + aid[:8] + '.zip', 'type': 'application/zip'}
                result = {'downloadUrl': '/media/' + aid + '?download=1', 'format': copies[0]['format'], 'count': len(copies),
                          'badge': badge, 'metadataNotice': copies[0]['metadataNotice']}
                self.artifacts[aid]['_owners'] = list({self.jobs[jid]['inputId'] for jid in ids})
                self.export_bundle_cache[key] = result
                self.persist_session()
            return result
        finally:
            self.export_lock.release()

    def update_config(self, updates):
        validated_config(updates)
        with self.lock:
            if any(j['status'] not in TERMINAL for j in self.jobs.values()):
                raise ApiError('请等待任务结束后再更改设置', 409)
            cfg = save_config(updates)
            if 'cpu_threads' in updates:
                self.photo_pipeline = self.video_pipeline = None
            if self.engine_process:
                self.engine_process.close()
                self.engine_process = None
            self.instant_pipeline = self.instant_video = None
            self.config = {k: v for k, v in cfg.items() if k in DEFAULT_CONFIG}
        return self.config

    def close(self):
        for j in self.jobs.values():
            j['cancel'].set()
        self.executor.shutdown(wait=True, cancel_futures=True)
        if self.engine_process:
            self.engine_process.close()
        # No scan or deletion of old caches/uploads, originals, exports, or unknown files.
        # Scratch is intentionally retained on exit for crash recovery; capped per session.
        if self.session_store:
            try: self.persist_session()
            finally: self.session_store.close()


class WorkbenchHandler(BaseHTTPRequestHandler):
    server_version = 'LocalWorkbench'
    def log_message(self, *args):
        pass

    @property
    def app(self):
        return self.server.workbench

    def guard(self, mutation=False):
        port = self.server.server_port
        hosts = {f'127.0.0.1:{port}', f'localhost:{port}'}
        if self.headers.get('Host') not in hosts or self.headers.get('Sec-Fetch-Site') == 'cross-site':
            raise ApiError('仅允许本机工作台访问', 403)
        origin = self.headers.get('Origin')
        if origin and origin not in {f'http://{h}' for h in hosts}:
            raise ApiError('不允许跨站访问', 403)
        if mutation and not secrets.compare_digest(self.headers.get('X-Workbench-Token', ''), self.app.token):
            raise ApiError('会话已更新，请刷新页面', 403)

    def headers_for(self, status, mime, length):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(length))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False, allow_nan=False).encode('utf-8')
        self.headers_for(status, 'application/json; charset=utf-8', len(body))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, mime, filename=None):
        size = path.stat().st_size
        start, end, status = 0, size - 1, 200
        requested = self.headers.get('Range')
        if requested and mime.startswith('video/'):
            import re
            match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested)
            if not match or not any(match.groups()):
                raise ApiError('不支持此视频范围请求', 416)
            left, right = match.groups()
            if left:
                start = int(left)
                end = min(int(right), size - 1) if right else size - 1
            else:
                start = max(0, size - int(right))
            if start >= size or end < start:
                raise ApiError('视频范围超出文件', 416)
            status = 206
        self.headers_for(status, mime, end - start + 1)
        if mime.startswith('video/'):
            self.send_header('Accept-Ranges', 'bytes')
        if status == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        if filename:
            self.send_header('Content-Disposition', 'attachment; filename="' + filename + '"')
        self.end_headers()
        with path.open('rb') as stream:
            stream.seek(start)
            remaining = end - start + 1
            while remaining:
                block = stream.read(min(1024 * 1024, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)

    def do_GET(self):
        try:
            self.guard()
            url = urlsplit(self.path)
            static = {'/': ('index.html', 'text/html; charset=utf-8'), '/index.html': ('index.html', 'text/html; charset=utf-8'),
                      '/style.css': ('style.css', 'text/css; charset=utf-8'), '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                      '/ux.css': ('ux.css', 'text/css; charset=utf-8'), '/controls.js': ('controls.js', 'text/javascript; charset=utf-8'),
                      '/progress-ui.js': ('progress-ui.js', 'text/javascript; charset=utf-8'),
                      '/video-compare.js': ('video-compare.js', 'text/javascript; charset=utf-8'),
                      '/mobile-motion.js': ('mobile-motion.js', 'text/javascript; charset=utf-8'),
                      '/workspace-features.js': ('workspace-features.js', 'text/javascript; charset=utf-8')}
            if url.path in static:
                name, mime = static[url.path]
                return self.send_file(WEB / name, mime)
            if url.path == '/api/status':
                return self.send_json(self.app.snapshot())
            if url.path == '/api/jobs':
                with self.app.lock:
                    jobs = [self.app.public_job(j) for j in self.app.jobs.values()]
                return self.send_json({'ok': True, 'jobs': jobs,
                    'inputs': [self.app.public_input(i) for i in self.app.inputs.values()],
                    'recovery': self.app.recovery_state()})
            if url.path == '/api/progress-image':
                query = parse_qs(url.query)
                jid, kind = query.get('job', [''])[0], query.get('kind', [''])[0]
                if kind not in ('color', 'depth', 'bokeh'):
                    raise ApiError('没有此过程预览', 404)
                with self.app.lock:
                    raw = self.app.jobs.get(jid, {}).get('_visuals', {}).get(kind)
                if not raw:
                    raise ApiError('过程预览尚未回传', 404)
                self.headers_for(200, 'image/jpeg', len(raw))
                self.end_headers()
                self.wfile.write(raw)
                return
            if url.path.startswith('/media/'):
                aid = url.path.removeprefix('/media/')
                artifact = self.app.artifacts.get(aid)
                if not artifact:
                    raise ApiError('文件不存在或会话已结束', 404)
                return self.send_file(artifact['path'], artifact['type'], artifact['name'] if 'download' in parse_qs(url.query) else None)
            if url.path == '/api/bundle':
                ids = parse_qs(url.query).get('ids', [''])[0].split(',')
                jobs = [self.app.jobs.get(i) for i in ids]
                if not jobs or len(jobs) > 160 or any(not j or j['status'] != 'done' or j['action'] not in ('photo', 'video') for j in jobs):
                    raise ApiError('请选择已完成的照片或视频任务')
                artifacts = [self.app.artifacts[urlsplit(j['result']['downloadUrl']).path.removeprefix('/media/')] for j in jobs]
                size = sum(a['path'].stat().st_size for a in artifacts)
                self.app.reserve(size + 8192)
                target = self.app.folder / (uuid.uuid4().hex + '.zip')
                try:
                    with zipfile.ZipFile(target, 'x', compression=zipfile.ZIP_STORED) as archive:
                        for a in artifacts:
                            archive.write(a['path'], a['name'])
                finally:
                    with self.app.lock:
                        self.app.reserved -= size + 8192
                return self.send_file(target, 'application/zip', 'leica-photos-' + target.stem[:8] + '.zip')
            raise ApiError('未找到此页面', 404)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            self.send_json({'ok': False, 'error': str(error) if isinstance(error, ApiError) else '无法读取此内容'}, error.status if isinstance(error, ApiError) else 500)

    def read_json(self):
        length = self.length(65536)
        try:
            data = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeError):
            raise ApiError('请求不是有效 JSON')
        if not isinstance(data, dict):
            raise ApiError('请求必须是对象')
        return data

    def length(self, limit):
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            raise ApiError('请求长度错误')
        if not 0 < length <= limit or self.headers.get('Transfer-Encoding'):
            raise ApiError('文件过大或请求为空', 413)
        return length

    def do_POST(self):
        try:
            self.guard(mutation=True)
            url = urlsplit(self.path)
            if url.path == '/api/import':
                query = parse_qs(url.query)
                name = query.get('name', ['untitled'])[0]
                kind = query.get('kind', ['photo'])[0]
                suffix = Path(name.replace('\\', '/')).suffix.lower()
                allowed = {'.jpg', '.jpeg', '.png', '.webp'} if kind == 'photo' else {'.mp4', '.mov', '.m4v', '.avi', '.mkv', '.webm'}
                if kind not in ('photo', 'video') or suffix not in allowed:
                    raise ApiError('不支持此文件类型')
                with self.app.lock:
                    if len(self.app.inputs) >= 64:
                        raise ApiError('一次会话最多导入 64 个文件', 413)
                length = self.length(1024**3 if kind == 'photo' else 2 * 1024**3)
                self.app.reserve(length)
                path = self.app.folder / (uuid.uuid4().hex + suffix)
                try:
                    with path.open('xb') as output:
                        remaining = length
                        while remaining:
                            block = self.rfile.read(min(1024 * 1024, remaining))
                            if not block:
                                raise ApiError('文件上传中断')
                            output.write(block)
                            remaining -= len(block)
                    item = self.app.import_file(path, name, kind)
                finally:
                    with self.app.lock:
                        self.app.reserved -= length
                return self.send_json({'ok': True, 'input': item})
            data = self.read_json()
            if url.path == '/api/jobs':
                # Internal recovery fields are never accepted from arbitrary HTTP jobs.
                data.pop('_resumeFrom', None)
                return self.send_json({'ok': True, 'job': self.app.submit(data)}, 202)
            if url.path == '/api/memory-retry':
                return self.send_json(self.app.memory_retry(data), 202)
            if url.path == '/api/clear-archives':
                from archive_clear import clear_archives
                return self.send_json(clear_archives(self.app, data))
            if url.path == '/api/action-archives':
                from action_archive import set_limit
                return self.send_json(set_limit(self.app, data))
            if url.path == '/api/recovery':
                return self.send_json(self.app.recovery_decision(data))
            if url.path == '/api/workspace':
                return self.send_json(self.app.save_ui(data))
            if url.path == '/api/remove-inputs':
                from session_store import remove_inputs
                if data.get('confirm') is not True: raise ApiError('请确认仅移除工作台副本')
                return self.send_json(remove_inputs(self.app, data.get('ids')))
            if url.path == '/api/cancel':
                with self.app.lock:
                    job = self.app.jobs.get(data.get('id'))
                    if not job:
                        raise ApiError('未找到此任务', 404)
                    if job['status'] not in TERMINAL:
                        job['cancel'].set()
                        job['cancelRequested'] = True
                        job['phase'] = '取消中 · 正在停止本次计算'
                        job['progress'].update(label=job['phase'], message='停止本次计算子进程；保留原片、已完成缓存和上次完整成片。')
                    return self.send_json({'ok': True, 'job': self.app.public_job(job)})
            if url.path == '/api/export':
                return self.send_json({'ok': True, 'export': self.app.export_photo(data)})
            if url.path == '/api/export-bundle':
                return self.send_json({'ok': True, 'export': self.app.export_bundle(data)})
            if url.path == '/api/config':
                return self.send_json({'ok': True, 'config': self.app.update_config(data)})
            raise ApiError('未找到此操作', 404)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            if not isinstance(error, ApiError):
                traceback.print_exc()
            self.send_json({'ok': False, 'error': str(error) if isinstance(error, ApiError) else '操作未完成，请检查文件格式或本地终端日志'}, error.status if isinstance(error, ApiError) else 500)


class WorkbenchServer(ThreadingHTTPServer):
    daemon_threads = True
    # HTTPServer normally reuses addresses. On Windows that can silently share
    # the old service's port and send the browser to the wrong UI/API.
    allow_reuse_address = False

    def server_bind(self):
        import socket
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def __init__(self, address, folder=None, persistent=None):
        # Bind first: a busy legacy port must not open/write its persistence area.
        super().__init__(address, WorkbenchHandler)
        persistent = address[1] != 0 if persistent is None else persistent
        if persistent and folder is None:
            folder = Path(__file__).resolve().parent.parent / 'workbench-data' / ('port-' + str(self.server_port))
        try:
            self.workbench = Workbench(folder=folder, persistent=persistent)
            self.workbench.storage_label = ('workbench-data/port-' + str(self.server_port)) if persistent else '本次隔离会话'
        except BaseException:
            super().server_close()
            raise

    def server_close(self):
        super().server_close()
        # TCPServer invokes this even when bind fails before Workbench exists.
        if hasattr(self, 'workbench'):
            self.workbench.close()
