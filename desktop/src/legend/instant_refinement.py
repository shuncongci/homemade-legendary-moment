"""Approved R58 / R59-v2 SDR refinement AFTER one unchanged uint8 LUT pass.
Bounded row strips with full Gaussian halos; no shared mutable parameters, resize,
sharpening, models or temporal adaptation. Both looks use the same detail operator.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from types import MappingProxyType
import numpy as np
import cv2
import threading
import sys
_NATIVE = None  # Source distribution uses the NumPy/OpenCV path.
_COLOR_LOOKUPS = {}
_COLOR_LOOKUP_LOCK = threading.Lock()
LUMA = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
REVISION = 'r62-exact-native-refinement-1'
_CHECK = ContextVar('instant_refinement_check', default=lambda: None)
@contextmanager
def refinement_scope(check):
    token = _CHECK.set(check)
    try: yield
    finally: _CHECK.reset(token)
INSTANT = MappingProxyType({'version': 'r58-reference-adaptation-v1', 'base_chroma_retention': 0.92, 'strong_chroma_extra_reduction': 0.1, 'yellow_green_extra_reduction': 0.14, 'red_blue_retention_protection': 0.045, 'orange_midtone_retention_protection': 0.04, 'yellow_green_center_degrees': 73.0, 'yellow_green_sigma_degrees': 29.0, 'chroma_gate_min_rgb_span': 0.06, 'chroma_gate_full_rgb_span': 0.42, 'tone_input_8bit': (0, 8, 24, 48, 80, 128, 180, 218, 242, 255), 'tone_output_8bit': (0, 8, 25, 50, 82, 128, 177, 214, 239, 255), 'fine_detail_reduction': 0.38, 'medium_detail_reduction': 0.075, 'detail_reference_long_edge': 1600, 'detail_scale_range': (0.5, 4.0), 'fine_sigma_at_reference_pixels': 0.82, 'medium_sigma_at_reference_pixels': 2.1, 'strong_edge_residual_8bit': 22.0, 'strong_edge_minimum_softening': 0.3, 'extra_sharpening': False, 'added_grain_vignette_watermark': False})
N_INSTANT = MappingProxyType({'version': 'r59-n-gentle-plus-v2', 'base_chroma_retention': 0.932, 'strong_chroma_extra_reduction': 0.085, 'yellow_green_extra_reduction': 0.119, 'red_blue_retention_protection': 0.03825, 'orange_midtone_retention_protection': 0.034, 'yellow_green_center_degrees': 73.0, 'yellow_green_sigma_degrees': 29.0, 'chroma_gate_min_rgb_span': 0.06, 'chroma_gate_full_rgb_span': 0.42, 'tone_input_8bit': (0, 4, 8, 16, 24, 40, 64, 96, 128, 160, 180, 218, 242, 255), 'tone_output_8bit': (0, 4.6, 9.2, 17.8, 26.4, 43, 66.4, 97.2, 128, 159.4, 180, 218, 242, 255), 'fine_detail_reduction': 0.38, 'medium_detail_reduction': 0.075, 'detail_reference_long_edge': 1600, 'detail_scale_range': (0.5, 4.0), 'fine_sigma_at_reference_pixels': 0.82, 'medium_sigma_at_reference_pixels': 2.1, 'strong_edge_residual_8bit': 22.0, 'strong_edge_minimum_softening': 0.3, 'extra_sharpening': False, 'added_grain_vignette_watermark': False})
def _validate(rgb):
    if not isinstance(rgb,np.ndarray) or rgb.dtype!=np.uint8 or rgb.ndim!=3 or rgb.shape[2]!=3 or not rgb.size:
        raise ValueError('A nonempty RGB uint8 image is required')

def _smooth(x):
    t=np.clip(x,0,1)
    return t*t*(3-2*t)

def _hue_weight(h,center,width):
    d=np.abs((h-center+180)%360-180)
    return np.exp(-0.5*(d/width)**2)

def _gamut(y,c):
    # Uniform radial chroma compression, preserving luma and hue instead of per-channel clipping.
    lo=np.minimum(np.minimum(c[...,0],c[...,1]),c[...,2]); hi=np.maximum(np.maximum(c[...,0],c[...,1]),c[...,2])
    neg=y/np.maximum(-lo,1e-9); pos=(1-y)/np.maximum(hi,1e-9)
    scale=np.minimum(1,np.minimum(neg,pos))
    return np.clip(y[...,None]+c*np.clip(scale,0,1)[...,None],0,1)

def tone_curve(y,p):
    return np.interp(y,np.array(p['tone_input_8bit'])/255,np.array(p['tone_output_8bit'])/255).astype(np.float32)

def color_adjust(rgb,p,return_float=False):
    _validate(rgb)
    x=rgb.astype(np.float32)/255
    y=x@LUMA
    hsv=cv2.cvtColor(x,cv2.COLOR_RGB2HSV)
    h,s=hsv[...,0],hsv[...,1]
    span=np.maximum(np.maximum(x[...,0],x[...,1]),x[...,2])-np.minimum(np.minimum(x[...,0],x[...,1]),x[...,2])
    strong=_smooth((span-p['chroma_gate_min_rgb_span'])/(p['chroma_gate_full_rgb_span']-p['chroma_gate_min_rgb_span']))
    yellow=_hue_weight(h,p['yellow_green_center_degrees'],p['yellow_green_sigma_degrees'])
    redblue=np.maximum(_hue_weight(h,0,20),_hue_weight(h,222,34))
    skin=_hue_weight(h,28,16)*(1-_smooth((s-.50)/.35))*_smooth((y-.10)/.18)
    retain=p['base_chroma_retention']-p['strong_chroma_extra_reduction']*strong-p['yellow_green_extra_reduction']*yellow*strong+p['red_blue_retention_protection']*redblue*strong+p['orange_midtone_retention_protection']*skin
    retain=np.clip(retain,0.65,1)
    chroma=(x-y[...,None])*retain[...,None]
    out=_gamut(tone_curve(y,p),chroma)
    return out if return_float else np.rint(out*255).astype(np.uint8)

def detail_adjust(rgb,p,*,long_edge=None):
    # Positive Gaussian-band mixing only. Strong edges are attenuated less than fine texture.
    if rgb.dtype==np.uint8:
        _validate(rgb);x=rgb.astype(np.float32)/255
    else:
        x=np.asarray(rgb,dtype=np.float32)
        if x.ndim!=3 or x.shape[2]!=3 or not x.size or not np.isfinite(x).all() or x.min()<0 or x.max()>1:
            raise ValueError('Float input must be finite RGB in [0,1]')
    edge=max(x.shape[:2]) if long_edge is None else float(long_edge)
    if edge<=0:raise ValueError('long_edge must be positive')
    scale=float(np.clip(edge/p['detail_reference_long_edge'],*p['detail_scale_range']))
    y=x@LUMA
    fine=cv2.GaussianBlur(y,(0,0),p['fine_sigma_at_reference_pixels']*scale,borderType=cv2.BORDER_REFLECT_101)
    medium=cv2.GaussianBlur(y,(0,0),p['medium_sigma_at_reference_pixels']*scale,borderType=cv2.BORDER_REFLECT_101)
    min_strength=p['strong_edge_minimum_softening']
    if _NATIVE is not None:
        expval=np.exp(-((y-medium)*255/p['strong_edge_residual_8bit'])**2)
        return _NATIVE.detail(x,y,fine,medium,expval,p)
    protect=min_strength+(1-min_strength)*np.exp(-((y-medium)*255/p['strong_edge_residual_8bit'])**2)
    a=p['fine_detail_reduction']*protect
    b=p['medium_detail_reduction']*protect
    # (1-a)y + (a-b)fine + b*medium: all coefficients nonnegative, sum to one.
    new_y=y-a*(y-fine)-b*(fine-medium)
    out=_gamut(np.clip(new_y,0,1),x-y[...,None])
    return np.rint(out*255).astype(np.uint8)


def parameters(style):
    if style in ('leica-instant', 'leica-instant-v2'): return INSTANT
    if style == 'leica-n-instant': return N_INSTANT
    raise ValueError('Not an Instant refinement style')

def refine(rgb, style, *, check=None, strip_rows=128):
    _validate(rgb)
    if type(strip_rows) is not int or strip_rows < 1: raise ValueError('Invalid strip size')
    check = _CHECK.get() if check is None else check
    check(); p=parameters(style); h,w=rgb.shape[:2]
    scale=float(np.clip(max(h,w)/p['detail_reference_long_edge'],*p['detail_scale_range']))
    # OpenCV's automatic float32 Gaussian support is <= ceil(4*sigma)+1.
    halo=int(np.ceil(4*p['medium_sigma_at_reference_pixels']*scale))+2
    out=np.empty_like(rgb)
    for y in range(0,h,strip_rows):
        check(); end=min(h,y+strip_rows); a=max(0,y-halo); b=min(h,end+halo)
        colored=_lookup_color(rgb[a:b],p)
        softened=detail_adjust(colored,p,long_edge=max(h,w))
        out[y:end]=softened[y-a:end-a]
        check()
    return out


def _color_weights(rgb,p):
    x=rgb.astype(np.float32)/255
    hsv=cv2.cvtColor(x,cv2.COLOR_RGB2HSV);h,s=hsv[...,0],hsv[...,1]
    span=np.maximum(np.maximum(x[...,0],x[...,1]),x[...,2])-np.minimum(np.minimum(x[...,0],x[...,1]),x[...,2])
    strong=_smooth((span-p['chroma_gate_min_rgb_span'])/(p['chroma_gate_full_rgb_span']-p['chroma_gate_min_rgb_span']))
    yellow=_hue_weight(h,p['yellow_green_center_degrees'],p['yellow_green_sigma_degrees'])
    redblue=np.maximum(_hue_weight(h,0,20),_hue_weight(h,222,34))
    base=p['base_chroma_retention']-p['strong_chroma_extra_reduction']*strong-p['yellow_green_extra_reduction']*yellow*strong+p['red_blue_retention_protection']*redblue*strong
    skin=_hue_weight(h,28,16)*(1-_smooth((s-.50)/.35))
    return np.stack((base,skin),axis=-1)


def prepare_color_lookup(style, check=lambda: None, *, width=256):
    """Exact float32 point-color map, opt-in only after long-video RAM admission.

    Spatial detail is never cached or baked into a LUT. Publish a complete,
    immutable 192 MiB RGB (Windows) or 128 MiB hue-weight (Mac) map.
    Aliases share a key; cancellation leaves no partial published table.
    """
    if type(width) is not int or width < 1 or width % 32:
        raise ValueError('Color lookup requires a positive 32-aligned row width')
    p=parameters(style); key=p['version']
    if key in _COLOR_LOOKUPS and _COLOR_LOOKUPS[key][0] == width:return
    with _COLOR_LOOKUP_LOCK:
        if key in _COLOR_LOOKUPS and _COLOR_LOOKUPS[key][0] == width:return
        check()
        # NumPy/BLAS float32 dot-product rounding can depend on row geometry.
        # Bake with the render width, not a universal 256-column color plane.
        # Evict this style before replacement, keeping at most two style maps.
        _COLOR_LOOKUPS.pop(key,None)
        # Apple BLAS can vary dot-product rounding by column, not only width.
        # Keep the original-shaped dot product there; cache only hue coefficients.
        full_color=sys.platform=='win32'
        columns=width if full_color else 256
        table=np.empty((256**3,3 if full_color else 2),dtype=np.float32)
        sample=np.zeros(((65536+columns-1)//columns,columns,3),dtype=np.uint8)
        plane=sample.reshape(-1,3)
        plane[:65536,0]=np.tile(np.arange(256,dtype=np.uint8),256)
        plane[:65536,1]=np.repeat(np.arange(256,dtype=np.uint8),256)
        for blue in range(256):
            check();sample[...,2]=blue
            values=color_adjust(sample,p,True) if full_color else _color_weights(sample,p)
            table[blue*65536:(blue+1)*65536]=values.reshape(-1,table.shape[1])[:65536]
        check();table.flags.writeable=False
        _COLOR_LOOKUPS[key]=(width,table)


def _lookup_color(rgb,p):
    cached=_COLOR_LOOKUPS.get(p['version'])
    # Preserve BLAS row geometry and OpenCV/NumPy scalar-tail arithmetic. No
    # tolerance or approximate interpolation substitutes for the original math.
    if cached is None or cached[0] != rgb.shape[1] or rgb.shape[1] % 32:
        return color_adjust(rgb,p,True)
    table=cached[1]
    if _NATIVE is not None:
        values=_NATIVE.lookup(rgb,table)
        if table.shape[1]==3:return values
        x=rgb.astype(np.float32)/255;y=x@LUMA
        return _NATIVE.color_tail(rgb,values,y,tone_curve(y,p),p['orange_midtone_retention_protection'])
    key=rgb[...,2].astype(np.int32)*65536
    key+=rgb[...,1].astype(np.int32)*256
    key+=rgb[...,0]
    values=table[key]
    if table.shape[1]==3:return values
    x=rgb.astype(np.float32)/255;y=x@LUMA
    skin=values[...,1]*_smooth((y-.10)/.18)
    retain=np.clip(values[...,0]+p['orange_midtone_retention_protection']*skin,.65,1)
    return _gamut(tone_curve(y,p),(x-y[...,None])*retain[...,None])
