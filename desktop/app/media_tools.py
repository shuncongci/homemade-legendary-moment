"""Use an explicitly chosen or system FFmpeg; no binary ships with the source."""
import os
from pathlib import Path
import shutil


def find_ffmpeg():
    chosen = os.environ.get('LEGEND_FFMPEG')
    if not chosen:
        return shutil.which('ffmpeg')
    path = Path(chosen).expanduser()
    return str(path.resolve()) if path.is_file() and os.access(path, os.X_OK) else None
