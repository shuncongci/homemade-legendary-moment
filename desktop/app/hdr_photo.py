"""True SDR/HDR companions. No SDR-to-HDR synthesis; neural networks only receive SDR.
Math follows mobile HdrMath/HdrReconstruction. Only supported Ultra HDR JPEG, sRGB/P3
base-primary gain maps are accepted. Edited results get a NEW RGB gain map.
"""
from functools import lru_cache
import io
import os
from pathlib import Path
import struct
import sys
import uuid
import numpy as np
from PIL import Image, ImageCms
EPSILON = 1 / 64


@lru_cache(maxsize=1)
def codec():
    try:
        import imagecodecs as ic
    except ImportError as exc:
        raise ValueError('本机缺少支持 Ultra HDR 的 imagecodecs，未自动改用 SDR') from exc
    if not getattr(getattr(ic, 'ULTRAHDR', None), 'available', False):
        raise ValueError('本机 HDR 编解码器不可用（imagecodecs / libultrahdr）')
    return ic


def available():
    try:
        codec()
        return True
    except (ImportError, ValueError, OSError):
        return False


def source_is_hdr(path):
    # Bounded header inspection avoids loading an ordinary SDR photo into a native codec.
    with Path(path).open('rb') as f:
        prefix = f.read(1024 * 1024)
    return prefix[:2] == b'\xff\xd8' and (b'hdr-gain-map' in prefix or b'urn:iso:std:iso:ts:21496' in prefix)


def checked(a):
    a = np.asarray(a, np.float32)
    if a.ndim != 3 or a.shape[2] != 3 or not np.isfinite(a).all() or a.min() < 0 or a.max() > 65504:
        raise ValueError('HDR线性像素无效或超出安全范围，未截断/降SDR')
    return a


def linear(a):
    a = np.asarray(a, np.float32)
    return np.where(a <= .04045, a / 12.92, ((a + .055) / 1.055) ** 2.4)


def transfer(sdr, hdr, colored):
    sdr, hdr = checked(sdr), checked(hdr)
    target = linear(np.asarray(colored, np.float32) / 255)
    if target.shape != hdr.shape or sdr.shape != hdr.shape:
        raise ValueError('SDR/HDR尺寸不一致')
    return checked(np.maximum(0, (hdr + EPSILON) * (target + EPSILON) / (sdr + EPSILON) - EPSILON))


def orient(a, orientation):
    if orientation == 2: return a[:, ::-1]
    if orientation == 3: return a[::-1, ::-1]
    if orientation == 4: return a[::-1]
    if orientation == 5: return a.transpose(1, 0, 2)
    if orientation == 6: return np.rot90(a, 3)
    if orientation == 7: return a.transpose(1, 0, 2)[::-1, ::-1]
    if orientation == 8: return np.rot90(a)
    return a


def area(a, size):
    import cv2
    if a.shape[1::-1] == tuple(size): return np.ascontiguousarray(a)
    if min(size) < 1 or size[0] > a.shape[1] or size[1] > a.shape[0]:
        raise ValueError('HDR不自动放大或缩小至未知尺寸')
    return np.maximum(cv2.resize(a, tuple(size), interpolation=cv2.INTER_AREA), 0).astype(np.float32)


def _p3_srgb_alias_eotf(profile):
    """Recognize this explicit P3 alias only with its supported RGB sRGB curves."""
    if len(profile) < 132 or profile[36:40] != b'acsp' or profile[12:24] != b'mntrRGB XYZ ':
        return False
    count = int.from_bytes(profile[128:132], 'big')
    end = 132 + count * 12
    if not 1 <= count <= 128 or end > len(profile):
        return False
    tags = {}
    for i in range(count):
        tag, offset, size = struct.unpack_from('>4sII', profile, 132 + i * 12)
        if tag in tags or offset < end or offset % 4 or offset + size > len(profile):
            return False
        if tag == b'cicp' or tag[:3] in (b'A2B', b'B2A', b'D2B', b'B2D'):
            return False
        tags[tag] = profile[offset:offset + size]
    curves = []
    for tag in (b'rTRC', b'gTRC', b'bTRC'):
        curve = tags.get(tag, b'')
        if len(curve) != 32 or curve[:12] != b'para' + bytes(4) + b'\0\3\0\0':
            return False
        curves.append(struct.unpack_from('>iiiii', curve, 12))
    if not curves[0] == curves[1] == curves[2]:
        return False
    # Exact sRGB and the rounded sRGB EOTF used by this named Ultra HDR profile.
    # One 16.16 LSB permits coefficient encoding, not arbitrary gamma/PQ/HLG.
    supported = ((2.4, 1 / 1.055, .055 / 1.055, 1 / 12.92, .04045),
                 (2.4, .948, .052, .077, .039))
    return any(all(abs(value - round(expected * 65536)) <= 1
                   for value, expected in zip(curves[0], parameters))
               for parameters in supported)


def _gamut(profile):
    if not profile:
        raise ValueError('HDR原片没有明确ICC色域，未假定sRGB')
    desc = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(profile))).strip().lower()
    # Validate colorants in addition to description; reject HDR transfer/PQ/unknown profiles.
    def xyz(tag):
        n = int.from_bytes(profile[128:132], 'big')
        for i in range(min(n, 128)):
            at=132+i*12
            if profile[at:at+4] == tag:
                off, count=struct.unpack_from('>II',profile,at+4)
                if off+20 > len(profile) or count < 20: break
                return tuple(v/65536 for v in struct.unpack_from('>iii',profile,off+8))
        raise ValueError('HDR ICC色度矩阵不完整')
    matrix=np.array([xyz(t) for t in (b'rXYZ',b'gXYZ',b'bXYZ')])
    srgb=np.array([[.4361,.2225,.0139],[.3851,.7169,.0971],[.1431,.0606,.7142]])
    p3=np.array([[.5151,.2412,-.0011],[.2920,.6922,.0419],[.1571,.0666,.7841]])
    if desc == 'srgb eotf with dci-p3 color gamut':
        if np.max(np.abs(matrix-p3)) < .015 and _p3_srgb_alias_eotf(profile):
            return True
        raise ValueError('HDR该P3 ICC的矩阵或传递曲线不受支持；未猜测sRGB、PQ或HLG')
    if 'srgb' in desc and np.max(np.abs(matrix-srgb)) < .015:return False
    if ('display p3' in desc or 'display-p3' in desc) and np.max(np.abs(matrix-p3)) < .015:return True
    raise ValueError('HDR仅支持明确的sRGB / Display-P3原片，未知ICC不猜测转换')


def _p3_to_srgb(a):
    matrix=np.array([[1.2249402,-.2249402,0],[-.04205696,1.042057,0],[-.01963755,-.07863605,1.0982736]],np.float32)
    rgb=a@matrix.T
    y=np.maximum(0,rgb@np.array([.2126,.7152,.0722],np.float32))[...,None]
    low=rgb.min(axis=2,keepdims=True)
    t=np.where(low<0,y/np.maximum(y-low,1e-12),1)
    return np.maximum(0,y+(rgb-y)*t)


def decode_pair(path, size, check=lambda:None):
    """Reconstruct native radiance BEFORE orientation/linear area reduction; no gain reuse."""
    from processing_options import memory_guard
    check()
    with Image.open(path) as original:
        memory_guard(*original.size,bytes_per_pixel=128,fixed=64*1024**2)
        orientation=original.getexif().get(274,1)
    raw=Path(path).read_bytes();ic=codec()
    if not ic.ultrahdr_check(raw):raise ValueError('原片没有可解码的 HDR 增益图，无法进行 HDR 处理；可选择 SDR 显影')
    base,gain,meta=ic.ultrahdr_decode_jpeg(raw)
    if not meta.get('use_base_cg'):raise ValueError('暂不支持替代色域增益图；未静默转换HDR')
    with Image.open(io.BytesIO(base)) as image:
        p3=_gamut(image.info.get('icc_profile'))
        sdr=linear(np.asarray(image.convert('RGB'),np.float32)/255)
    with Image.open(io.BytesIO(gain)) as image:
        import cv2
        gm=np.asarray(image.convert('RGB'),np.float32)/255
        gm=cv2.resize(gm,(sdr.shape[1],sdr.shape[0]),interpolation=cv2.INTER_LINEAR)
    values={}
    for key in ('min_content_boost','max_content_boost','gamma','offset_sdr','offset_hdr'):
        v=np.array(meta[key],np.float32)
        if v.shape != (3,) or not np.isfinite(v).all():raise ValueError('HDR增益图参数无效')
        values[key]=v
    lo,hi,gamma,es,eh=[values[k] for k in ('min_content_boost','max_content_boost','gamma','offset_sdr','offset_hdr')]
    if np.any(lo<=0) or np.any(hi<lo) or np.any(gamma<=0) or np.any(es<0) or np.any(eh<0):raise ValueError('HDR增益参数范围无效')
    # Adobe/libultrahdr gamma is the reciprocal of Android Gainmap.getGamma().
    hdr=np.maximum(0,(sdr+es)*np.exp(np.log(lo)+(np.log(hi)-np.log(lo))*gm**(1/gamma))-eh)
    if p3:sdr,hdr=_p3_to_srgb(sdr),_p3_to_srgb(hdr)
    check()
    return checked(area(orient(sdr,orientation),size)),checked(area(orient(hdr,orientation),size))


def _jpeg(image, quality=95):
    stream=io.BytesIO()
    profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    image.save(stream,format='JPEG',quality=quality,subsampling=0,icc_profile=profile)
    return stream.getvalue()


def _write_new(path, raw, check):
    path=Path(path);temp=path.with_name('.'+path.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with temp.open('xb') as f:f.write(raw);f.flush();os.fsync(f.fileno())
        check()
        if path.exists():raise FileExistsError('不覆盖已有HDR成片')
        if os.name=='nt':os.rename(temp,path)
        else:os.link(temp,path)
    finally:temp.unlink(missing_ok=True)


def encode_pair(sdr, hdr, source, target, check=lambda:None):
    """New map against ACTUAL decoded output JPEG; verify radiometric error before publish."""
    from photo_export import normalized_exif,jpeg_exif
    check();hdr=checked(hdr)
    if (sdr.width,sdr.height)!=hdr.shape[1::-1]:raise ValueError('SDR/HDR导出尺寸不一致')
    with Image.open(source) as image:exif=normalized_exif(image.info.get('exif',b''),sdr.width,sdr.height,False)
    base=jpeg_exif(_jpeg(sdr),exif)
    with Image.open(io.BytesIO(base)) as decoded:rgb=linear(np.asarray(decoded.convert('RGB'),np.float32)/255)
    ratios=np.log((hdr+EPSILON)/(rgb+EPSILON))
    lo=min(0,float(ratios.min()));hi=max(1e-6,float(ratios.max()));peak=max(1.0001,float(hdr.max()))
    with Image.fromarray(np.floor(np.clip((ratios-lo)/(hi-lo),0,1)*255+.5).astype(np.uint8)) as image:gain=_jpeg(image,100)
    ic=codec();raw=ic.ultrahdr_encode_jpeg(base,gain,gamut=ic.ULTRAHDR.CG.BT_709,gamma=1.,
        min_content_boost=float(np.exp(lo)),max_content_boost=float(np.exp(hi)),offset_sdr=EPSILON,offset_hdr=EPSILON,
        hdr_capacity_min=1.,hdr_capacity_max=max(peak,float(np.exp(hi))),use_base_cg=True)
    check()
    actual=ic.ultrahdr_decode(raw,dtype=np.float16)[...,:3].astype(np.float32)
    if actual.shape!=hdr.shape:raise ValueError('HDR回读尺寸不一致')
    delta=actual-hdr;error={'maxRelativeToPeak':float(np.max(np.abs(delta)))/peak,'rmseRelativeToPeak':float(np.sqrt(np.mean(delta.astype(np.float64)**2)))/peak,'peak':peak}
    if error['maxRelativeToPeak']>.08 or error['rmseRelativeToPeak']>.015:
        raise ValueError('HDR 重建误差超出安全范围，未生成新成片，也未改用 SDR。诊断信息：'+str(error))
    if not ic.ultrahdr_check(raw):raise ValueError('HDR容器校验失败')
    _write_new(target,raw,check)
    return {**error,'format':'hdr-gainmap','source':'true_source_gainmap','displayOnly':False}


def export_copy(render,source,target,badge=False,check=lambda:None):
    """Rebuild MPF after an EXIF-only change, retaining BOTH encoded JPEG pixel streams."""
    from photo_export import normalized_exif,jpeg_exif
    check();ic=codec();raw=Path(render).read_bytes()
    base,gain,m=ic.ultrahdr_decode_jpeg(raw)
    with Image.open(io.BytesIO(base)) as image:
        w,h=image.size
    with Image.open(source) as image:exif=normalized_exif(image.info.get('exif',b''),w,h,badge)
    marked=jpeg_exif(base,exif)
    out=ic.ultrahdr_encode_jpeg(marked,gain,gamut=ic.ULTRAHDR.CG.BT_709,**m)
    b,g,verify=ic.ultrahdr_decode_jpeg(out)
    with Image.open(io.BytesIO(b)) as image:
        flag=image.getexif().get_ifd(34665)[0x88B0];flag=flag[0] if isinstance(flag,bytes) else flag
        if bool(flag&1)!=badge:raise ValueError('HDR相册标识回读校验失败')
    # Container writers may update gainmap metadata; verify decoded pixel values exactly.
    if not np.array_equal(ic.ultrahdr_decode(raw,dtype=np.float16),ic.ultrahdr_decode(out,dtype=np.float16)):
        raise ValueError('HDR标识修改改变像素，未发布')
    _write_new(target,out,check)
    return {'format':'hdr-gainmap','mime':'image/jpeg','suffix':'.jpg','badge':badge,
            'metadataNotice':'已生成原片 HDR 的新增益图，并检查 EXIF 和相册标识。HDR 显示需要兼容的浏览器与显示器；相册标识是否显示取决于设备和相册版本。'}
