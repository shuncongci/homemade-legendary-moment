"""Check the entire candidate public Git tree, not only ignored files or the diff.

Read-only; no downloads, Git writes, private directories or photo inputs.
This is a mechanical guard, not a substitute for a human rights/security review.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_CLASSES = re.compile(r'\b(?:Moment(?:Bridge|Math|Renderer|Assets|AssetKeys|Envelope|KernelImpl|Copy)|ProtectedLutLoader)\b')
SECRET = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|hf_[A-Za-z0-9]{25,})\b')
PERSONAL_PATH = re.compile(r'(?i)\b[A-Z]:[\\/]|/' + r'(?:Users|home|root)/')
TEXT = {'.kt', '.kts', '.cpp', '.h', '.py', '.md', '.txt', '.xml', '.json', '.properties', '.pro', '.map', '.bat'}
ALLOWED_ASSET_BINARIES = {
    'app/src/main/assets/leica/leica-cool-epoch6.fp16.onnx',
    'app/src/main/assets/leica/leica-warm-epoch6.fp16.onnx',
    'app/src/main/assets/looks/leica-instant-v2.bin',
    'app/src/main/assets/looks/leica-n-instant.bin',
}
DENIED_SUFFIX = {'.apk', '.aab', '.aar', '.jks', '.keystore', '.pem', '.p12', '.pfx', '.pth', '.pt', '.safetensors', '.mp4', '.mov', '.heic', '.dng', '.jpg', '.jpeg', '.png', '.webp'}
DENIED_PART = {'.env', 'local.properties', 'keystore.properties', 'moment-protection', 'private-imports', 'private-photos', 'signing', '.cxx', '.externalNativeBuild', 'build', '.gradle'}

def main():
    q = subprocess.run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT, check=True, stdout=subprocess.PIPE)
    names = sorted(set(q.stdout.decode('utf8').rstrip('\0').split('\0')) - {''})
    errors = []
    actual_assets = set()
    for name in names:
        p = ROOT / name
        parts = set(Path(name).parts)
        if not p.is_file() or p.is_symlink():
            errors.append((name, 'missing/non-regular/symlink')); continue
        if parts & DENIED_PART or p.suffix.lower() in DENIED_SUFFIX:
            errors.append((name, 'private material, build output or media filename'))
        if p.suffix.lower() == '.onnx' and name not in ALLOWED_ASSET_BINARIES:
            errors.append((name, 'third-party or non-public weight'))
        if name.startswith('app/src/main/assets/') and p.suffix.lower() in {'.bin', '.dat', '.onnx'}:
            actual_assets.add(name)
            if name not in ALLOWED_ASSET_BINARIES:
                errors.append((name, 'non-public runtime binary'))
        if p.suffix.lower() in TEXT or p.name in {'gradlew', '.gitignore', '.gitattributes', 'LICENSE'}:
            try:
                text = p.read_text('utf-8-sig')
            except UnicodeError:
                errors.append((name, 'text is not UTF-8')); continue
            if SECRET.search(text) or PERSONAL_PATH.search(text):
                errors.append((name, 'credential or personal absolute path'))
            if '\ufffd' in text or '\x00' in text:
                errors.append((name, 'damaged text'))
            if name.startswith('app/src/main/') and p.suffix.lower() in {'.kt', '.cpp', '.h'}:
                if PRIVATE_CLASSES.search(text) or re.search(r'LeicaStyle\.LEGENDARY|legendary-moment-v1|moment/[a-e]\.bin|moment/r[12]\.dat|MGP2', text):
                    errors.append((name, 'private implementation or runtime identity'))
    if actual_assets != ALLOWED_ASSET_BINARIES:
        errors.append(('assets', 'runtime binary inventory is not exact'))
    manifest = json.loads((ROOT / 'models.json').read_text('utf8'))
    bundled = [x for x in manifest['models'] if x['bundled']]
    if {x['path'] for x in bundled} != ALLOWED_ASSET_BINARIES:
        errors.append(('models.json', 'bundled inventory mismatch'))
    for x in bundled:
        p = ROOT / x['path']
        if p.is_file():
            with p.open('rb') as f:
                digest = hashlib.file_digest(f, 'sha256').hexdigest()
            if digest != x['sha256'] or p.stat().st_size != x['bytes']:
                errors.append((x['path'], 'resource identity mismatch'))
    style = (ROOT / 'app/src/main/java/xyz/shuncongci/leica/LeicaStyle.kt').read_text('utf8')
    if not all(x in style for x in ('COOL(', 'WARM(', 'LEICA_INSTANT(')) or 'LEGENDARY(' in style:
        errors.append(('LeicaStyle.kt', 'photo choices mismatch'))
    if errors:
        for name, reason in errors:
            print('REJECT', name, reason)
        return 1
    print('PASS entire candidate public tree:', len(names), 'files; 2 ONNX + 2 LUT; no private runtime implementation/material matched')
    print('Rights, provenance, Git staged tree, native/APK contents and phone behavior require their own review.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
