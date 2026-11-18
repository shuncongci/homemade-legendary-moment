"""PC working dimensions: supports up to 200 MP (2亿像素).
Respects user-adjustable limits from config.
"""
import math

MAX_PIXELS = 200_000_000
MAX_BYTES = 1024 * 1024 * 1024  # 1 GiB for ultra-high-resolution images


def get_configured_max_pixels():
    try:
        from config import get_current_config
        return get_current_config().get('max_pixels', MAX_PIXELS)
    except Exception:
        return MAX_PIXELS

get_max_pixels = get_configured_max_pixels


def working_size(width, height, cap=None):
    if cap is None:
        cap = get_configured_max_pixels()
    if type(width) is not int or type(height) is not int or min(width, height) < 16:
        raise ValueError('Photo shortest edge must be at least 16 pixels')
    if width * height <= cap:
        return width, height
    
    
    scale = math.sqrt(float(cap) / float(width * height))
    nw = max(16, int(round(width * scale)))
    nh = max(16, int(round(height * scale)))
    return nw, nh
