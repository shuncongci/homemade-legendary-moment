"""Defaults and persisted settings for the CPU desktop edition."""
import os
import json
from pathlib import Path
from typing import Dict, Any

ROOT = Path(__file__).resolve().parent.parent
CONFIG_FILE = ROOT / 'config.json'

DEFAULT_CONFIG: Dict[str, Any] = {
    "max_pixels": 200_000_000,          
    "device_preference": "cpu",
    "cpu_threads": 0,                   # 0 selects a conservative default
    "enable_video_gpu": False,
    "video_batch_size": 2,
    "instant_backend": "cpu-exact",
    "execution_mode": "concurrent",     # serial = one frame owner, batch=1, no inner pools
    "video_color_workers": 2,
    "video_focus_workers": 1,           # 0 adaptive; positive safe integer = explicit per-frame owners
    "default_style": "leica-instant",
    "default_aperture": 2.8,            
    "allow_unlimited_aspect_ratio": True
}

_CURRENT_CONFIG: Dict[str, Any] = {}


def load_config() -> Dict[str, Any]:
    global _CURRENT_CONFIG
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_FILE.is_file():
        try:
            user_data = json.loads(CONFIG_FILE.read_text('utf-8', errors='ignore'))
            if isinstance(user_data, dict):
                cfg.update(user_data)
        except Exception:
            pass
    cfg.update(device_preference='cpu', enable_video_gpu=False, instant_backend='cpu-exact')
    _CURRENT_CONFIG = cfg
    return dict(cfg)


def save_config(updates: Dict[str, Any]) -> Dict[str, Any]:
    global _CURRENT_CONFIG
    cfg = load_config()
    for k, v in updates.items():
        if k in DEFAULT_CONFIG:
            cfg[k] = v
    try:
        CONFIG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding='utf-8')
    except Exception as e:
        print(f"Warning: Failed to save config: {e}")
    _CURRENT_CONFIG = cfg
    return dict(cfg)


def get_current_config() -> Dict[str, Any]:
    global _CURRENT_CONFIG
    if not _CURRENT_CONFIG:
        return load_config()
    return dict(_CURRENT_CONFIG)
