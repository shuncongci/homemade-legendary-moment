"""Check locally prepared assets against the production model inventory. No downloads."""
from pathlib import Path
import argparse, hashlib, json, sys
ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundled-only', action='store_true', help='check only the included author models')
    args = parser.parse_args()
    errors = 0
    for entry in json.loads((ROOT / 'models.json').read_text(encoding='utf8'))['models']:
        if args.bundled_only and not entry['bundled']:
            continue
        path = ROOT / entry['path']
        if not path.is_file():
            print('MISSING', entry['path']); errors += 1; continue
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != entry['sha256'] or path.stat().st_size != entry['bytes']:
            print('DIFFERS', entry['path'], digest); errors += 1
        else:
            print('OK', entry['path'])
    if errors:
        print('See docs/MODELS.md. Third-party weights are intentionally not bundled.')
        print('Do not rename a PyTorch checkpoint to .onnx or disable runtime hash validation.')
    return 1 if errors else 0
if __name__ == '__main__':
    sys.exit(main())
