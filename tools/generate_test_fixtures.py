"""Recreate the public RGB math fixtures without photographs or neural-network outputs.

Requires numpy, Pillow and opencv-python. The expected arrays are calculated by
Pillow/OpenCV, not by the Kotlin implementation under test. Historical filenames
are retained because the JVM tests refer to them; 'expected' here is a synthetic
second RGB image, not an evaluation of a trained color model.
"""
from pathlib import Path
import struct, hashlib, json
import numpy as np
import cv2
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/test/resources/leica'

def write(name, array):
    array = np.ascontiguousarray(array, dtype=np.uint8)
    h, w, c = array.shape
    assert c == 3
    path = OUT / (name + '.rgb')
    path.write_bytes(struct.pack('>ii', w, h) + array.tobytes())

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    # Smooth colour ramps, diagonal structure, and broad variations in all channels.
    # No source image, external dataset or model is read.
    h, w = 1364, 1024
    y, x = np.mgrid[:h, :w].astype(np.float64)
    x /= w - 1; y /= h - 1
    src = np.stack((60 + 100*x + 15*np.sin(y*8),
                    65 + 95*y + 12*np.cos(x*9),
                    70 + 65*(x+y)/2 + 12*np.sin((x+y)*7)), axis=-1)
    src = np.clip(np.rint(src), 0, 255).astype(np.uint8)
    synthetic_color = np.clip(src.astype(np.int16) + np.array([7, 2, -4]), 0, 255).astype(np.uint8)
    write('input_1024', src)
    write('leica-modern-1.0.expected_1024', synthetic_color)
    write('bilinear_500x666', np.asarray(Image.fromarray(src).resize((500, 666), Image.Resampling.BILINEAR)))
    write('bicubic_777x1033', np.asarray(Image.fromarray(src).resize((777, 1033), Image.Resampling.BICUBIC)))
    write('bicubic_1500x1998', np.asarray(Image.fromarray(synthetic_color).resize((1500, 1998), Image.Resampling.BICUBIC)))
    lab_src = cv2.cvtColor(src, cv2.COLOR_RGB2LAB)
    lab_color = cv2.cvtColor(synthetic_color, cv2.COLOR_RGB2LAB)
    write('lab_input_1024', lab_src)
    result = lab_src.astype(np.float32)
    for c, sigma in enumerate((25, 20, 20)):
        low_src = cv2.GaussianBlur(lab_src[:, :, c].astype(np.float32), (0, 0), sigmaX=sigma)
        low_color = cv2.GaussianBlur(lab_color[:, :, c].astype(np.float32), (0, 0), sigmaX=sigma)
        result[:, :, c] = np.clip(low_color + lab_src[:, :, c] - low_src, 0, 255)
    write('fusion_modern_1024', cv2.cvtColor(result.astype(np.uint8), cv2.COLOR_LAB2RGB))
    manifest = {'input': 'procedural RGB ramps; no photos',
                'generator': 'tools/generate_test_fixtures.py',
                'versions': {'numpy': np.__version__, 'opencv': cv2.__version__, 'pillow': Image.__version__},
                'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.glob('*.rgb'))}}
    (OUT / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    print('Generated 7 public synthetic fixtures.')

if __name__ == '__main__':
    main()
