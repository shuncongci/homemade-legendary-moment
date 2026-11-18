"""The published LUTs and color models, on the CPU.

The NumPy interpolation and LAB merge are from the desktop pipeline. The
color files are shared with Android and verified before parsing/inference.
"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from contextvars import ContextVar
import os
import struct
import threading

import cv2
import numpy as np

from .public_assets import ASSETS, VIDEO_STYLES, verified_asset
from .instant_refinement import _CHECK, refine

_PARALLEL_EXECUTION = ContextVar('legend_parallel_execution', default=True)
_FUSE_POOL = None
_FUSE_POOL_LOCK = threading.Lock()

def is_parallel_enabled(parallel=None):
    return bool(_PARALLEL_EXECUTION.get() if parallel is None else parallel)

@contextmanager
def execution_parallel_scope(enabled=True):
    token = _PARALLEL_EXECUTION.set(bool(enabled))
    try:
        yield
    finally:
        _PARALLEL_EXECUTION.reset(token)

def _get_fuse_pool():
    global _FUSE_POOL
    if _FUSE_POOL is None:
        with _FUSE_POOL_LOCK:
            if _FUSE_POOL is None:
                _FUSE_POOL = ThreadPoolExecutor(max_workers=8, thread_name_prefix='lab-fuse')
    return _FUSE_POOL

def lab_fuse(orig_rgb, graded_rgb, l_sigma=25.0, ab_sigma=20.0, parallel=None):
    """Unchanged per-pixel arithmetic; bounded independent OpenCV channel work."""
    h,w,_ = orig_rgb.shape
    if graded_rgb.shape[:2] != (h,w):
        graded_rgb = cv2.resize(graded_rgb,(w,h),interpolation=cv2.INTER_CUBIC)
    use_parallel = is_parallel_enabled(parallel) and h*w >= 65536
    pool = _get_fuse_pool() if use_parallel else None
    future = pool.submit(cv2.cvtColor,graded_rgb,cv2.COLOR_RGB2LAB) if pool else None
    orig_lab = cv2.cvtColor(orig_rgb,cv2.COLOR_RGB2LAB)
    grad_lab = future.result() if future else cv2.cvtColor(graded_rgb,cv2.COLOR_RGB2LAB)
    result = np.empty_like(orig_lab)
    sigmas = [(0,l_sigma),(1,ab_sigma),(2,ab_sigma)]
    def merge(c,source,source_blur,graded_blur):
        source -= source_blur
        source += graded_blur
        np.clip(source,0,255,out=source)
        result[:,:,c] = source.astype(np.uint8)
    if use_parallel and h*w <= 16_000_000:
        channels=[(c,s,orig_lab[:,:,c].astype(np.float32),grad_lab[:,:,c].astype(np.float32)) for c,s in sigmas]
        futures=[(pool.submit(cv2.GaussianBlur,g,(0,0),sigmaX=s),pool.submit(cv2.GaussianBlur,a,(0,0),sigmaX=s)) for c,s,a,g in channels]
        try:
            for (c,s,a,g),(fg,fa) in zip(channels,futures):merge(c,a,fa.result(),fg.result())
        finally:
            for pair in futures:
                for f in pair:
                    try:f.result()
                    except BaseException:pass
    else:
        # Per-channel temporaries keep 100 MP fusion bounded without reducing pixels.
        for c,s in sigmas:
            source=orig_lab[:,:,c].astype(np.float32);graded=grad_lab[:,:,c].astype(np.float32)
            future=pool.submit(cv2.GaussianBlur,graded,(0,0),sigmaX=s) if pool else None
            graded_blur=None if future else cv2.GaussianBlur(graded,(0,0),sigmaX=s)
            source_blur=cv2.GaussianBlur(source,(0,0),sigmaX=s)
            merge(c,source,source_blur,future.result() if future else graded_blur)
    return cv2.cvtColor(result,cv2.COLOR_LAB2RGB)


class Plut3dLut:
    def __init__(self, data):
        if len(data) != 6591012 or data[:8] != b'PLUT0001' or struct.unpack('>I', data[8:12])[0] != 65:
            raise ValueError('无效的 65³ LUT 文件')
        self.table_np = np.ascontiguousarray(np.frombuffer(data[12:], dtype='>f8').reshape(65, 65, 65, 3), dtype=np.float32)
        if not np.isfinite(self.table_np).all():
            raise ValueError('LUT 包含无效数值')
        self.table_np.flags.writeable = False

    def apply_cpu_numpy(self, img_rgb: np.ndarray) -> np.ndarray:
        """Apply the public LUT with NumPy trilinear interpolation."""
        h, w, _ = img_rgb.shape
        flat = img_rgb.reshape(-1, 3).astype(np.float32)
        
        
        coords = flat * (64.0 / 255.0)
        r = coords[:, 0]
        g = coords[:, 1]
        b = coords[:, 2]

        r0 = np.clip(np.floor(r).astype(np.int32), 0, 63)
        r1 = r0 + 1
        dr = (r - r0)[:, None]

        g0 = np.clip(np.floor(g).astype(np.int32), 0, 63)
        g1 = g0 + 1
        dg = (g - g0)[:, None]

        b0 = np.clip(np.floor(b).astype(np.int32), 0, 63)
        b1 = b0 + 1
        db = (b - b0)[:, None]

        # Table indexing: [B, G, R, 3]
        c000 = self.table_np[b0, g0, r0]
        c001 = self.table_np[b0, g0, r1]
        c010 = self.table_np[b0, g1, r0]
        c011 = self.table_np[b0, g1, r1]
        c100 = self.table_np[b1, g0, r0]
        c101 = self.table_np[b1, g0, r1]
        c110 = self.table_np[b1, g1, r0]
        c111 = self.table_np[b1, g1, r1]

        c00 = c000 * (1 - dr) + c001 * dr
        c01 = c010 * (1 - dr) + c011 * dr
        c10 = c100 * (1 - dr) + c101 * dr
        c11 = c110 * (1 - dr) + c111 * dr

        c0 = c00 * (1 - dg) + c01 * dg
        c1 = c10 * (1 - dg) + c11 * dg

        out = c0 * (1 - db) + c1 * db
        out = np.clip(out * 255.0, 0, 255).astype(np.uint8)
        return out.reshape(h, w, 3)


class LeicaColorManager:
    def __init__(self):
        from config import get_current_config
        self.threads = get_current_config().get('cpu_threads', 0) or min(4, os.cpu_count() or 1)
        cv2.setNumThreads(self.threads)
        self._assets = {}
        self._lock = threading.Lock()

    def prepare(self, style):
        if style not in ASSETS:
            raise ValueError('不支持的色彩风格')
        with self._lock:
            if style not in self._assets:
                data = verified_asset(style)
                if style in VIDEO_STYLES:
                    node = Plut3dLut(data)
                else:
                    import onnxruntime as ort
                    options = ort.SessionOptions()
                    options.intra_op_num_threads = self.threads
                    options.inter_op_num_threads = 1
                    if os.name == 'nt':
                        options.add_session_config_entry('session.intra_op.allow_spinning', '0')
                    node = ort.InferenceSession(data, sess_options=options, providers=['CPUExecutionProvider'])
                self._assets[style] = node
            return self._assets[style]

    @staticmethod
    def backend(style):
        return {'backend': 'cpu', 'kind': 'numpy-lut' if style in VIDEO_STYLES else 'onnx-cpu'}

    def grade(self, img_rgb, *, style):
        if img_rgb.ndim != 3 or img_rgb.shape[2] != 3 or img_rgb.dtype != np.uint8 or not img_rgb.size:
            raise ValueError('需要非空 RGB 图像')
        check = _CHECK.get()
        check()
        node = self.prepare(style)
        h, w, _ = img_rgb.shape
        if style in VIDEO_STYLES:
            mapped = np.empty_like(img_rgb)
            rows = max(1, min(128, 1048576 // w))
            for y in range(0, h, rows):
                check()
                mapped[y:y+rows] = node.apply_cpu_numpy(img_rgb[y:y+rows])
            return refine(mapped, style, check=check)
        longest = max(h, w)
        mw = ((max(1, int(round(w * 1024.0 / longest))) + 15) // 16) * 16
        mh = ((max(1, int(round(h * 1024.0 / longest))) + 15) // 16) * 16
        resized = cv2.resize(img_rgb, (mw, mh), interpolation=cv2.INTER_LINEAR)
        feed = np.ascontiguousarray(resized.transpose(2, 0, 1)[None], dtype=np.float32) / 255.0
        output = node.run([node.get_outputs()[0].name], {node.get_inputs()[0].name: feed})[0]
        check()
        if output.shape != (1, 3, mh, mw) or not np.isfinite(output).all():
            raise ValueError('冷暖模型输出格式错误')
        out_rgb = np.clip(output[0].transpose(1, 2, 0) * 255.0, 0, 255).astype(np.uint8)
        return lab_fuse(img_rgb, out_rgb)
