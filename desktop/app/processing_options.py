"""Explicit, independent long-edge work limits; no hidden high-resolution fallback.
None means no *additional* long-edge cap, not infinite RAM or removal of pixel guards.
Existing worker call signatures/defaults are not changed by these adapter options.
"""
import math

RESOLUTION_DEFAULTS = {'subjectInputLongEdge': 1024, 'subjectOutputLongEdge': 2048,
                       'colorLongEdge': None, 'blurLongEdge': None,
                       'depthLongEdge': 1220, 'batchLongEdge': None, 'videoLongEdge': None}


def subject_concurrency(value):
    """Scheduling limit, not a mask parameter; no fixed business maximum."""
    if type(value) is not int or not 1 <= value <= 2**53 - 1:
        raise ValueError('保护点并发数必须是至少1的安全整数；默认1，不设固定最大值')
    return value


def long_edge(value):
    if value is None:
        return None
    if type(value) is not int or value < 512 or value > 2**53 - 1:
        raise ValueError('长边必须是至少512的整数，或选择不限制')
    return value


def resolutions(value):
    if value is None:
        return dict(RESOLUTION_DEFAULTS)
    if not isinstance(value, dict) or set(value) - RESOLUTION_DEFAULTS.keys():
        raise ValueError('不支持的分辨率参数')
    return {k: long_edge(value.get(k, default)) for k, default in RESOLUTION_DEFAULTS.items()}


def size_for(size, *limits, even=False):
    w, h = size
    if min(w, h) <= 0:
        raise ValueError('无效图像尺寸')
    scale = min([1.0] + [long_edge(v) / max(w, h) for v in limits if v is not None])
    ow, oh = max(1, round(w * scale)), max(1, round(h * scale))
    if even:
        ow, oh = max(2, ow // 2 * 2), max(2, oh // 2 * 2)
    return ow, oh


def depth_size(size, limit):
    """NativeDepthMath.analysisSize: integer half-up fitting, never upscale.

    Kept separate from output sizing. The workbench selects 1220 explicitly;
    a legacy CLI / explicit None keeps native-resolution depth.
    """
    w, h = size
    if min(w, h) <= 0:
        raise ValueError('无效深度分析尺寸')
    limit = long_edge(limit)
    edge = max(w, h)
    if limit is None or edge <= limit:
        return w, h
    return tuple(max(1, (n * limit + edge // 2) // edge) for n in (w, h))


def crop_pixels(crop, w, h):
    """Same pixel rounding as composition.pixels; geometry must not import Torch/models."""
    if not isinstance(crop, (list, tuple)) or len(crop) != 4 or any(type(v) not in (int, float) or not math.isfinite(v) for v in crop):
        raise ValueError('无效构图坐标')
    l, t, r, b = crop
    if not (0 <= l < r <= 1 and 0 <= t < b <= 1):
        raise ValueError('构图区域为空或越界')
    x, y = min(w-1, int(l*w+.5)), min(h-1, int(t*h+.5))
    return x, y, min(w, max(x+1, int(r*w+.5))), min(h, max(y+1, int(b*h+.5)))


def memory_guard(width, height, bytes_per_pixel=100, fixed=128 * 1024**2):
    """Reject before allocation; never downscale silently after resource failure."""
    from memory_policy import memory_override_active, MemoryPreflightError
    estimated = width * height * bytes_per_pixel + fixed
    if memory_override_active():
        return estimated
    from runtime_resources import available_memory
    if estimated > available_memory() * .72:
        raise MemoryPreflightError('当前可用内存不足以处理所选分辨率；请明确调低长边限制后重试，未自动缩图')
    return estimated
