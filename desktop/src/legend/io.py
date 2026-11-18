from pathlib import Path
from io import BytesIO
import hashlib, json, os, warnings, uuid, time
import numpy as np
from PIL import Image, ImageOps, ImageCms

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''): h.update(b)
    return h.hexdigest()

from contextlib import contextmanager
from threading import RLock
from .image_policy import working_size, MAX_BYTES, MAX_PIXELS
_OPEN_LOCK = RLock()

@contextmanager
def open_checked_photo(path, allow_working_resize):
    with _OPEN_LOCK:
        previous = Image.MAX_IMAGE_PIXELS
        try:
            if allow_working_resize: Image.MAX_IMAGE_PIXELS = None
            src = Image.open(path)
        finally:
            Image.MAX_IMAGE_PIXELS = previous
    try: yield src
    finally: src.close()

def decode(path, settings=None, progress=lambda *a: None):
    started = time.perf_counter()
    path = Path(path).resolve(strict=True)
    max_b = getattr(settings, 'max_bytes', MAX_BYTES)
    if path.stat().st_size > max_b:
        raise ValueError(f'Input byte limit exceeded: {path.stat().st_size} > {max_b}')
    cap = getattr(settings, 'max_working_pixels', MAX_PIXELS)
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', Image.DecompressionBombWarning)
        with open_checked_photo(path, cap is not None) as src:
            source_size = list(src.size)
            orientation = src.getexif().get(274, 1)
            profile = src.info.get('icc_profile')
            oriented_size = source_size[::-1] if orientation in (5, 6, 7, 8) else source_size[:]
            target = working_size(*oriented_size, cap=cap) if cap is not None else tuple(oriented_size)

            # exif_transpose returns a full-frame copy even for orientation 1.
            # Skip that redundant copy while still fully decoding/validating pixels.
            oriented = ImageOps.exif_transpose(src) if orientation in range(2, 9) else src
            oriented.load()
            timings = {'decodeOrientSeconds': round(time.perf_counter() - started, 6)}
            started = time.perf_counter()
            try:
                # Handle alpha transparency smoothly by compositing on white background
                if oriented.mode in ('RGBA', 'LA') or ('transparency' in oriented.info):
                    bg = Image.new('RGB', oriented.size, (255, 255, 255))
                    alpha_src = oriented.convert('RGBA')
                    try:
                        with alpha_src.getchannel('A') as alpha:
                            bg.paste(alpha_src, mask=alpha)
                    finally:
                        alpha_src.close()
                    im_rgb = bg
                else:
                    im_rgb = oriented.convert('RGB')

                if im_rgb.size != target:
                    reduced = im_rgb.resize(target, Image.Resampling.LANCZOS)
                    im_rgb.close()
                    im = reduced
                else:
                    im = im_rgb
                im.load()
            finally:
                oriented.close()

            timings['rgbResizeSeconds'] = round(time.perf_counter() - started, 6)
            started = time.perf_counter()
            if profile:
                try:
                    converted = ImageCms.profileToProfile(
                        im, ImageCms.ImageCmsProfile(BytesIO(profile)),
                        ImageCms.createProfile('sRGB'), outputMode='RGB'
                    )
                    im.close()
                    im = converted
                except Exception:
                    # Keep image as-is if ICC conversion fails instead of crashing
                    pass

            timings['iccSeconds'] = round(time.perf_counter() - started, 6)
            started = time.perf_counter()
            source_hash = sha256(path)
            timings['sourceHashSeconds'] = round(time.perf_counter() - started, 6)
            try:
                progress('reading-ready', '', timings)
            except BaseException:
                im.close()
                raise
            return im, {
                'source_sha256': source_hash,
                'timings': timings,
                'source_size': source_size,
                'oriented_size': oriented_size,
                'working_size': list(im.size),
                'exif_orientation': orientation,
                'icc': 'converted_srgb' if profile else 'assumed_srgb',
                'af_metadata': 'unsupported_not_inferred_from_standard_exif'
            }

def save_image_exclusive(image, path):
    path = Path(path)
    if path.suffix.lower() not in ('.jpg', '.jpeg', '.png'):
        raise ValueError('Output must be jpg/png')
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.parent / ('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with open(tmp, 'xb') as f:
            image.save(
                f,
                format='PNG' if path.suffix.lower() == '.png' else 'JPEG',
                quality=95,
                icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
            )
            f.flush()
            os.fsync(f.fileno())
        if path.exists():
            raise FileExistsError(path)
        if os.name == 'nt':
            os.rename(tmp, path)
        else:
            os.link(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)

def save_json_exclusive(value, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'x', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2, allow_nan=False)
