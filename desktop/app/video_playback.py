"""Explicit local WebM viewing proxy of an original or verified complete output. Never replaces an export."""
from pathlib import Path
import subprocess
import time
import math
import os
import tempfile
from functools import lru_cache
import cv2

MAX_SECONDS = 600


@lru_cache(maxsize=1)
def native_preview_available():
    """Probe installed OpenCV VP8 once, using a generated frame, never user media."""
    import numpy as np
    try:
        with tempfile.TemporaryDirectory(prefix='leica-vp8-probe-') as folder:
            path = Path(folder) / 'probe.webm'
            writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*'VP80'), 1., (16, 16))
            try:
                if not writer.isOpened():
                    return False
                writer.write(np.zeros((16, 16, 3), np.uint8))
            finally:
                writer.release()
            capture = cv2.VideoCapture(str(path))
            try:
                ok, frame = capture.read()
                return bool(ok and frame is not None and frame.shape[:2] == (16, 16))
            finally:
                capture.release()
    except (OSError, cv2.error):
        return False


def playback_available(ffmpeg):
    # An explicit invalid encoder setting must not silently choose another tool.
    return bool(ffmpeg or (not os.environ.get('LEGEND_FFMPEG') and native_preview_available()))


def _encode_native(source, output, width, height, guard):
    """Silent viewing copy only. Formal video/audio and grading are never changed."""
    capture = cv2.VideoCapture(str(source))
    writer = None
    try:
        fps = capture.get(cv2.CAP_PROP_FPS)
        if not capture.isOpened() or not math.isfinite(fps) or fps <= 0:
            raise ValueError('兼容预览无法读取原视频帧率，未发布。')
        writer = cv2.VideoWriter(str(output), cv2.VideoWriter_fourcc(*'VP80'), fps, (width, height))
        if not writer.isOpened():
            raise ValueError('本机 VP8 编码器不可用；正式成片保留，可下载查看。')
        while True:
            guard()
            ok, frame = capture.read()
            if not ok:
                break
            if frame is None:
                raise ValueError('兼容预览读取帧失败，未发布。')
            if frame.shape[:2] != (height, width):
                frame = cv2.copyMakeBorder(frame, 0, height - frame.shape[0], 0, width - frame.shape[1],
                                           cv2.BORDER_CONSTANT, value=0)
            writer.write(frame)
    finally:
        capture.release()
        if writer is not None:
            writer.release()
    guard()


def create_playback_preview(source, output, info, ffmpeg, check=lambda: None,
                            progress=lambda *a: None, max_bytes=None, storage_guard=None):
    source = Path(source).resolve(strict=True)
    output = Path(output).resolve()
    if output.exists() or output == source:
        raise ValueError('Refusing to overwrite original or existing preview')
    if ffmpeg and not Path(ffmpeg).is_file() or not playback_available(ffmpeg):
        raise ValueError('兼容播放预览需要本机 FFmpeg 或可用的 VP8 编码器；仍可抽帧预览和下载。')
    # Preview only: no upscaling, grading, focus inference or source replacement.
    # Preserve all source pixels; odd dimensions gain at most one black border pixel.
    width = max(2, (int(info['width']) + 1) // 2 * 2)
    height = max(2, (int(info['height']) + 1) // 2 * 2)
    # A bitrate estimate is not an upper bound (OpenCV VP8 has no bitrate control).
    # Managed calls account actual bytes + one write's headroom as the file grows.
    # Standalone calls retain a local cap; an explicit max_bytes is always a hard cap.
    if max_bytes is not None and (type(max_bytes) is not int or max_bytes < 0):
        raise ValueError('兼容预览空间上限必须是非负整数字节数。')
    budget = max_bytes
    if budget is None and storage_guard is None:
        budget = max(65536, int(info['durationSeconds'] * 400000))
    cmd = [str(ffmpeg), '-nostdin', '-v', 'error', '-n', '-i', str(source),
           '-map', '0:v:0', '-map', '0:a:0?', '-sn', '-dn', '-map_metadata', '-1',
           '-vf', f'pad={width}:{height}:0:0:color=black,scale=in_range=auto:out_range=tv',
           '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '14', '-pix_fmt', 'yuv420p',
           '-color_range', 'tv', '-threads', '4', '-fps_mode', 'passthrough',
           '-c:a', 'aac', '-ac', '2', '-ar', '48000', '-movflags', '+faststart', '-f', 'mp4', str(output)]
    process = None
    started = time.monotonic()

    checked_at, checked_bytes = started, 0

    def guard(force=False):
        nonlocal checked_at, checked_bytes
        check()
        now = time.monotonic()
        if now - started > MAX_SECONDS:
            raise ValueError('兼容播放预览超时；未替换原片或正式成片。')
        size = output.stat().st_size if output.exists() else 0
        if budget is not None and size > budget:
            raise ValueError('兼容播放预览超过本次临时空间预算；未发布，原片与旧成片保留。')
        # Keep cancellation per frame; bound the cost of full-session enumeration.
        if storage_guard is not None and (force or size - checked_bytes >= 1024**2 or now - checked_at >= .5):
            storage_guard()
            checked_at, checked_bytes = now, size

    try:
        guard(force=True)
        progress('playback-encode')
        if ffmpeg:
            process = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            while True:
                guard()
                try:
                    process.communicate(timeout=.1)
                    break
                except subprocess.TimeoutExpired:
                    continue
        else:
            _encode_native(source, output, width, height, guard)
        guard(force=True)
        if (process is not None and process.returncode) or not output.is_file() or not output.stat().st_size:
            # Keep encoder paths/logs out of the public API. Do not auto-retry.
            raise ValueError('当前 FFmpeg 无法生成 H.264/AAC 兼容预览；仍可抽帧和下载，原片保留。')
        progress('playback-verify')
        verify = cv2.VideoCapture(str(output))
        frames = 0
        try:
            if not verify.isOpened():
                raise ValueError('兼容预览无法回读，未发布。')
            while True:
                guard()
                ok, frame = verify.read()
                if not ok:
                    break
                if frame is None or frame.shape[:2] != (height, width):
                    raise ValueError('兼容预览尺寸校验失败，未发布。')
                frames += 1
            if not frames or (info['totalFrames'] > 0 and frames != info['totalFrames']):
                raise ValueError('兼容预览帧数校验失败，未发布。')
        finally:
            verify.release()
        guard(force=True)
        return {'width': width, 'height': height, 'frames': frames, 'previewOnly': True,
                'codec': 'H.264/AAC' if ffmpeg else 'VP8 (silent)',
                'mime': 'video/mp4' if ffmpeg else 'video/webm',
                'previewVersion': 2, 'quality': 'high-quality-lossy' if ffmpeg else 'native-uncontrolled',
                'sourceWidth': info['width'], 'sourceHeight': info['height'],
                **({'audioIncluded': False} if not ffmpeg else {}),
                'audioPolicy': 'preserve-if-present' if ffmpeg else 'silent',
                'notice': ('当前为原尺寸高画质兼容副本（H.264，有损编码，不缩小、不丢帧）；下载仍为原完整MP4。' if ffmpeg else
                           '当前为原尺寸无声兼容画面（VP8）；本机未找到 FFmpeg，编码速度和质量不可控，声音请在正式MP4中查看。'),
                'originalUnchanged': True,
                'scaledForPlayback': False,
                'paddedForPlayback': (width, height) != (info['width'], info['height'])}
    except BaseException:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.communicate(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
        output.unlink(missing_ok=True)
        raise
