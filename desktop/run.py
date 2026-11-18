"""Start the desktop workbench on this computer, never on a public interface."""
import argparse
import platform
from pathlib import Path
import sys
import webbrowser

ROOT = Path(__file__).resolve().parent
sys.path[:0] = [str(ROOT / 'app'), str(ROOT / 'src')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8876, help='local port (default: 8876)')
    parser.add_argument('--browser', action='store_true', help='open the default browser')
    parser.add_argument('--check', action='store_true', help='check imports and public assets without starting the workbench')
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error('port must be 0–65535')
    if sys.version_info[:2] != (3, 12):
        parser.exit(2, '请使用 Python 3.12（见 desktop/README.md）。\n')
    try:
        import cv2, numpy, onnxruntime, PIL, psutil
        from legend.public_assets import ASSETS, verified_asset
        from workbench import WorkbenchServer
        for style in ASSETS:
            verified_asset(style)
    except (ImportError, OSError, ValueError) as exc:
        parser.exit(2, f'无法启动：{exc}\n请看 desktop/README.md。\n')
    if args.check:
        print(f'{sys.platform} {platform.machine()}: Python {platform.python_version()}；四份色彩资源校验通过。')
        return
    try:
        server = WorkbenchServer(('127.0.0.1', args.port))
    except (OSError, ValueError) as exc:
        parser.exit(2, f'无法打开工作台（端口 {args.port}）：{exc}\n')
    with server:
        url = f'http://127.0.0.1:{server.server_port}/'
        print(f'{url}\n处理完成后在此窗口按 Ctrl+C 退出。', flush=True)
        if args.browser:
            webbrowser.open(url)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
