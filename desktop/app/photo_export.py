"""Export-only encoding + byte-offset-preserving source EXIF transfer.
Never a model call. Never changes the saved render, source, or paints a watermark.
Private gallery BYTE flag follows mobile XiaomiLeicaBadge; OEM recognition is not guaranteed.
"""
from pathlib import Path
import io, os, struct, uuid, zlib
from PIL import Image, ImageCms

FORMATS = {
    'png-lossless': ('PNG', '.png', 'image/png', {}),
    'jpeg-95': ('JPEG', '.jpg', 'image/jpeg', {'quality': 95}),
    'webp-90': ('WEBP', '.webp', 'image/webp', {'quality': 90}),
    'webp-95': ('WEBP', '.webp', 'image/webp', {'quality': 95}),
    'webp-lossless': ('WEBP', '.webp', 'image/webp', {'lossless': True, 'quality': 75}),
}
BADGE = 0x88B0


def normalized_exif(raw, width, height, badge=False):
    """Append replacement IFDs, never relocate original values/MakerNote/GPS payloads.
    Old directory bytes remain at their original TIFF-relative offsets; thumbnails are
    no longer referenced. New geometry/orientation fields describe the encoded pixels.
    """
    raw = bytes(raw or b'')
    tiff = bytearray(raw[6:] if raw.startswith(b'Exif\x00\x00') else raw)
    if not tiff:
        tiff = bytearray(b'II*\x00\x08\x00\x00\x00\x00\x00\x00\x00\x00\x00')
    if len(tiff) > 60000 or len(tiff) < 8 or tiff[:2] not in (b'II', b'MM'):
        raise ValueError('原片EXIF结构或大小不兼容，未丢弃或猜测元数据')
    endian = '<' if tiff[:2] == b'II' else '>'
    unpack = lambda f, at: struct.unpack_from(endian + f, tiff, at)[0]
    if unpack('H', 2) != 42:
        raise ValueError('不支持的EXIF TIFF结构')
    def entries(offset):
        if offset < 8 or offset + 2 > len(tiff): raise ValueError('EXIF目录指针无效')
        n = unpack('H', offset)
        if n > 2048 or offset + 2 + n * 12 + 4 > len(tiff): raise ValueError('EXIF目录不完整')
        out = {}
        for i in range(n):
            entry = bytes(tiff[offset + 2 + i * 12:offset + 14 + i * 12])
            tag = struct.unpack(endian + 'H', entry[:2])[0]
            if tag in out: raise ValueError('EXIF含重复字段，未猜测元数据')
            out[tag] = entry
        return out
    def number(tag, value, typ=4):
        data = struct.pack(endian + ('I' if typ == 4 else 'H'), value).ljust(4, b'\x00')
        return struct.pack(endian + 'HHI', tag, typ, 1) + data
    primary = entries(unpack('I', 4))
    private = {}
    if 34665 in primary:
        ptr = primary[34665]
        if struct.unpack(endian + 'HI', ptr[2:8]) != (4, 1): raise ValueError('EXIF子目录格式不兼容')
        private = entries(struct.unpack(endian + 'I', ptr[8:])[0])
    old = private.get(BADGE)
    bits = 0
    if old:
        if struct.unpack(endian + 'HI', old[2:8]) != (1, 1): raise ValueError('相册私有标识字段类型不兼容，未更改原片')
        bits = old[8]
    private[BADGE] = struct.pack(endian + 'HHI', BADGE, 1, 1) + bytes([(bits | 1) if badge else (bits & 254), 0, 0, 0])
    private[40962] = number(40962, width)
    private[40963] = number(40963, height)
    private[40961] = number(40961, 1, 3)  # output is sRGB, never copy the source ICC as output ICC.
    # Source pixel-area and embedded-preview offsets no longer describe a cropped/edited output.
    for tag in (37396, 41492, 41493): private.pop(tag, None)
    for tag in (273, 279, 513, 514): primary.pop(tag, None)
    primary[274] = number(274, 1, 3)
    primary[256] = number(256, width); primary[257] = number(257, height)
    def append_directory(items):
        if len(tiff) % 2: tiff.append(0)
        offset = len(tiff)
        tiff.extend(struct.pack(endian + 'H', len(items)))
        for tag in sorted(items): tiff.extend(items[tag])
        tiff.extend(b'\x00' * 4)  # no stale thumbnail/next IFD.
        return offset
    private_offset = append_directory(private)
    primary[34665] = number(34665, private_offset)
    primary_offset = append_directory(primary)
    struct.pack_into(endian + 'I', tiff, 4, primary_offset)
    result = b'Exif\x00\x00' + bytes(tiff)
    if len(result) > 65520: raise ValueError('EXIF超出JPEG段限制，未丢弃元数据')
    return result


def jpeg_exif(data, exif):
    """Replace only EXIF APP1; entropy-coded pixel bytes are never decoded here."""
    if data[:2] != b'\xff\xd8': raise ValueError('需要JPEG编码')
    out = bytearray(data[:2]); pos = 2
    out.extend(b'\xff\xe1' + struct.pack('>H', len(exif) + 2) + exif)
    while pos < len(data):
        if data[pos] != 255: raise ValueError('JPEG段边界异常')
        start = pos
        while data[pos] == 255: pos += 1
        marker = data[pos]; pos += 1
        if marker in (0xDA, 0xD9): out.extend(data[start:]); return bytes(out)
        length = int.from_bytes(data[pos:pos+2], 'big')
        if length < 2 or pos + length > len(data): raise ValueError('JPEG段长度异常')
        payload = data[pos+2:pos+length]
        if not (marker == 0xE1 and payload.startswith(b'Exif\x00\x00')): out.extend(data[start:pos+length])
        pos += length
    raise ValueError('JPEG没有像素数据')


def export_image(render, source, target, format_id='png-lossless', badge=False, check=lambda: None):
    if type(badge) is not bool or format_id not in FORMATS: raise ValueError('导出格式或标识开关无效')
    format_id = 'jpeg-95' if badge else format_id
    fmt, suffix, mime, settings = FORMATS[format_id]
    check()
    with Image.open(source) as original:
        # Read raw packet instead of Pillow's reserialized IFDs (which can relocate MakerNote).
        raw = original.info.get('exif', b'')
        if not raw and original.format == 'PNG': raw = original.info.get('exif', b'')
    target = Path(target)
    temp = target.with_name('.' + target.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with Image.open(render) as image:
            from processing_options import memory_guard
            memory_guard(*image.size, bytes_per_pixel=16, fixed=32*1024**2)
            if fmt == 'WEBP' and max(image.size) > 16383: raise ValueError('WebP单边最多16383像素；请选择PNG或JPEG，不会自动缩图')
            exif = normalized_exif(raw, *image.size, badge)
            check()
            profile = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
            with temp.open('xb') as stream:
                image.save(stream, format=fmt, exif=exif, icc_profile=profile, **settings)
                stream.flush(); os.fsync(stream.fileno())
            check()
            with Image.open(temp) as verify:
                if verify.size != image.size: raise ValueError('导出尺寸校验失败')
                info = verify.getexif()
                private = info.get_ifd(34665)
                actual = private.get(BADGE)
                actual = actual[0] if isinstance(actual, bytes) else actual
                if actual is None or bool(actual & 1) != badge or info.get(274) != 1:
                    raise ValueError('元数据标识回读校验失败，未发布成片')
                verify.verify()
        check()
        if target.exists(): raise FileExistsError('不覆盖已有导出文件')
        if os.name == 'nt': os.rename(temp, target)
        else: os.link(temp, target)
        return {'format': format_id, 'mime': mime, 'suffix': suffix, 'badge': badge,
                'metadata': '已保留兼容的 EXIF，更新方向、尺寸、sRGB 和相册标识；相机品牌沿用原片',
                'metadataNotice': '已保留兼容的 EXIF 信息；不迁移独立 XMP / IPTC 信息。相册标识是否显示取决于设备和相册版本；分享前请检查位置等隐私信息。'}
    finally:
        temp.unlink(missing_ok=True)
