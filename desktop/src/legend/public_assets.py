"""The four color assets already tracked in the Android tree. No asset downloads."""
from hashlib import sha256
from pathlib import Path

ASSET_ROOT = Path(__file__).resolve().parents[3] / 'app' / 'src' / 'main' / 'assets'
STYLE_INSTANT = 'leica-instant'
STYLE_N_INSTANT = 'leica-n-instant'
STYLE_COOL = 'leica-cool'
STYLE_WARM = 'leica-warm'
VIDEO_STYLES = (STYLE_INSTANT, STYLE_N_INSTANT)
ASSETS = {
    STYLE_INSTANT: ('looks/leica-instant-v2.bin', 6591012, '87d1b46ae221b272227f845f2a7a984357b0d4056a79a2570b84c1271e3f9dcf'),
    STYLE_N_INSTANT: ('looks/leica-n-instant.bin', 6591012, '72d3e0d901aba3560738486a2fa2139a44e53b966e5d0b35ac0ca33d6fb16ff9'),
    STYLE_COOL: ('leica/leica-cool-epoch6.fp16.onnx', 10053669, 'f85e59126d86b5184c053b51048e36048ced56580b2c1a2c695f742cfa450d29'),
    STYLE_WARM: ('leica/leica-warm-epoch6.fp16.onnx', 10053669, 'b371371aa34c6b9af8ca0aa90cfa8159a04f4997a170792fab63eeb9523dbcab'),
}


def asset_path(style):
    return ASSET_ROOT / ASSETS[style][0]


def verified_asset(style):
    relative, length, expected = ASSETS[style]
    try:
        with asset_path(style).open('rb') as source:
            data = source.read(length + 1)
    except OSError as exc:
        raise ValueError(f'找不到公开色彩资源 {relative}。请保留完整仓库，不要只下载 desktop/。') from exc
    if len(data) != length or sha256(data).hexdigest() != expected:
        raise ValueError(f'公开色彩资源校验失败：{relative}。请重新获取仓库原文件。')
    return data
