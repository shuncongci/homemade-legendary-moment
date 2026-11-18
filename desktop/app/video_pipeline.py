"""CPU LUT video with the local workbench's cancellation and output checks."""
import os
import sys
import time
import shutil
import subprocess
from pathlib import Path
from typing import Callable, Optional, Dict, Any, List, Tuple, Union
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'src'))

from legend.leica_all_colors import LeicaColorManager
from legend.public_assets import STYLE_N_INSTANT, VIDEO_STYLES
from config import get_current_config
from execution_policy import scoped_execution, parallel_enabled
from legend.leica_all_colors import execution_parallel_scope


def find_ffmpeg() -> Optional[str]:
    from media_tools import find_ffmpeg as resolve
    return resolve()


def _mux_video(command, check):
    """Bounded subprocess buffers; storage/cancel errors must never become fallback success."""
    check()
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        return False  # Preserve the existing no-audio fallback for encoder launch failure.
    started = time.monotonic()
    try:
        while True:
            check()
            try:
                code = process.wait(timeout=.1)
            except subprocess.TimeoutExpired:
                if time.monotonic() - started >= 120:
                    return False
            else:
                check()
                return code == 0
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()


class LeicaVideoPipeline:
    def __init__(self):
        self.device = 'cpu'
        self.color_manager = LeicaColorManager()
        self.ffmpeg_path = find_ffmpeg()

    def get_video_info(self, video_path: Union[str, Path]) -> Dict[str, Any]:
        """Reads video metadata (fps, frame count, dimensions, duration)."""
        vp = str(Path(video_path).resolve())
        cap = cv2.VideoCapture(vp)
        if not cap.isOpened():
            raise ValueError(f"Cannot open video file: {video_path}")
        try:
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fps = float(cap.get(cv2.CAP_PROP_FPS)) or 30.0
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            duration_sec = total_frames / fps if fps > 0 else 0.0
            return {
                "width": width,
                "height": height,
                "fps": round(fps, 3),
                "totalFrames": total_frames,
                "durationSeconds": round(duration_sec, 2),
                "path": vp
            }
        finally:
            cap.release()

    @scoped_execution
    def get_preview_frame(self, video_path: Union[str, Path], position_ratio: float = 0.1, style: str = STYLE_N_INSTANT, output_size=None, progress=lambda *a: None, frame_processor=None) -> Dict[str, Any]:
        """Extracts a preview frame and returns original & graded previews."""
        if style not in VIDEO_STYLES or frame_processor is not None:
            raise ValueError('视频只支持一瞬 / N 瞬 LUT')
        progress('video-read')
        vp = str(Path(video_path).resolve())
        cap = cv2.VideoCapture(vp)
        if not cap.isOpened():
            raise ValueError(f"Cannot open video file: {video_path}")
        try:
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 100
            target_frame = max(0, min(total_frames - 1, int(total_frames * position_ratio)))
            cap.set(cv2.CAP_PROP_POS_FRAMES, target_frame)
            ret, bgr_frame = cap.read()
            if not ret or bgr_frame is None:
                
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                ret, bgr_frame = cap.read()
            if not ret or bgr_frame is None:
                raise RuntimeError("Failed to decode video frame")

            rgb_frame = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2RGB)
            if output_size is not None and rgb_frame.shape[1::-1] != tuple(output_size):
                rgb_frame = cv2.resize(rgb_frame, tuple(output_size), interpolation=cv2.INTER_AREA)
            from legend.instant_refinement import refinement_scope
            with refinement_scope(lambda: progress('gan_color')):
                graded_rgb = self.color_manager.grade(rgb_frame, style=style)

            if graded_rgb.shape != rgb_frame.shape or graded_rgb.dtype != np.uint8:
                raise ValueError('Invalid processed preview frame')
            import io, base64
            def to_b64(img_np):
                h, w, _ = img_np.shape
                
                scale = min(1.0, 800.0 / max(h, w))
                sw, sh = int(w * scale), int(h * scale)
                thumb = cv2.resize(img_np, (sw, sh), interpolation=cv2.INTER_AREA)
                pil_im = Image.fromarray(thumb)
                buf = io.BytesIO()
                pil_im.save(buf, format='JPEG', quality=85)
                return base64.b64encode(buf.getvalue()).decode('ascii')

            progress('color-ready', '', {'previewBase64':to_b64(graded_rgb), 'displayOnly':True})
            return {
                "frameIndex": target_frame,
                "originalBase64": to_b64(rgb_frame),
                "gradedBase64": to_b64(graded_rgb),
                "style": style, "width": rgb_frame.shape[1], "height": rgb_frame.shape[0]
            }
        finally:
            cap.release()

    @scoped_execution
    def process_video(
        self,
        input_path: Union[str, Path],
        output_path: Union[str, Path],
        style: str = STYLE_N_INSTANT,
        progress_cb: Optional[Callable[[Dict[str, Any]], None]] = None,
        cancel_token: Optional[Callable[[], bool]] = None,
        output_size=None,
        frame_processor=None,
        storage_guard=None,
        checkpoint=None
    ) -> Dict[str, Any]:
        """Grade the requested frames on the CPU and try to carry over the first audio track."""
        t_start = time.perf_counter()
        inp = Path(input_path).resolve(strict=True)
        outp = Path(output_path).resolve()
        if inp == outp or outp.exists():
            raise ValueError('Refusing to overwrite source or existing output')
        if style not in VIDEO_STYLES or frame_processor is not None:
            raise ValueError('Unsupported video style')
        outp.parent.mkdir(parents=True, exist_ok=True)

        info = self.get_video_info(inp)
        width, height, fps, total_frames = info['width'], info['height'], info['fps'], info['totalFrames']
        if output_size is not None:
            width, height = output_size
            if any(type(v) is not int or v < 2 or v % 2 for v in (width, height)) or width > info['width'] or height > info['height']:
                raise ValueError('Invalid video working size')

        cap = cv2.VideoCapture(str(inp))
        if not cap.isOpened():
            raise ValueError(f"Failed to read input video: {inp}")

        
        temp_out = outp.parent / (".temp_video_" + __import__("uuid").uuid4().hex + ".mp4")
        writer = None
        color_pool = None
        def cancel_check():
            if cancel_token and cancel_token():
                raise InterruptedError('Video processing canceled by user')
        def check(additional=0):
            cancel_check()
            if storage_guard:
                storage_guard(additional)
        try:
            check()
            fourcc = cv2.VideoWriter_fourcc(*'mp4v')
            writer = cv2.VideoWriter(str(temp_out), fourcc, fps, (width, height))
            if not writer.isOpened():
                fourcc = cv2.VideoWriter_fourcc(*'avc1')
                writer = cv2.VideoWriter(str(temp_out), fourcc, fps, (width, height))
    
            if not writer.isOpened():
                cap.release()
                raise RuntimeError("Failed to initialize VideoWriter. Please check video codecs.")
    
            cfg = get_current_config()
            parallel = parallel_enabled(cfg)
            from video_progress import FrameProgress
            from color_concurrency import plan_color_workers
            from legend.instant_refinement import refinement_scope
            color_plan = plan_color_workers(cfg.get('video_color_workers', 2) if parallel else 1,
                (info['width'], info['height']), (width, height), total_frames,
                int(cfg.get('video_batch_size', 2)) if parallel else 1)
            workers, batch_size = color_plan['effectiveWorkers'], color_plan['batchSize']
            self.color_manager.prepare(style)
            if workers > 1:
                color_pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix='video-color')
            if checkpoint:
                checkpoint = checkpoint.scope(f'public-cpu-v1/workers-{workers}/batch-{batch_size}/size-{width}x{height}')
            processed_frames = 0
            frame_progress = FrameProgress(progress_cb, total_frames, style, t_start)

            if progress_cb:
                progress_cb({'phase':'grading', 'processedFrames':0, 'totalFrames':total_frames})
            try:
                while True:
                    check()
    
                    frames_bgr = []
                    for _ in range(batch_size):
                        ret, frame = cap.read()
                        if not ret or frame is None:
                            break
                        if frame.shape[1::-1] != (width, height):
                            frame = cv2.resize(frame, (width, height), interpolation=cv2.INTER_AREA)
                        frames_bgr.append(frame)
    
                    if not frames_bgr:
                        break
    
                    
                    frames_rgb = [cv2.cvtColor(f, cv2.COLOR_BGR2RGB) for f in frames_bgr]
    
                    # Only complete lossless frame calculations are reusable. Decode
                    # and final encoding restart sequentially; no half-written MP4 reuse.
                    batch_name = 'video-batch-' + str(processed_frames)
                    saved = checkpoint.load(batch_name) if checkpoint else None
                    if saved is not None:
                        graded_rgb_list = saved['frames']
                        if len(graded_rgb_list) != len(frames_rgb):
                            raise ValueError('Checkpoint frame count differs')
                    else:
                        def grade_frame(frame):
                            with execution_parallel_scope(parallel), refinement_scope(cancel_check):
                                return self.color_manager.grade(frame, style=style)
                        graded_rgb_list = list(color_pool.map(grade_frame, frames_rgb) if color_pool else map(grade_frame, frames_rgb))

                    if checkpoint and saved is None:
                        for graded in graded_rgb_list:
                            if graded.shape != (height, width, 3) or graded.dtype != np.uint8:
                                raise ValueError('Invalid frame calculation; not checkpointed')
                        checkpoint.save(batch_name, {'frames': graded_rgb_list})
                    
                    for frame_number, graded in enumerate(graded_rgb_list, 1):
                        check()
                        if graded.shape != (height, width, 3) or graded.dtype != np.uint8:
                            raise ValueError('Invalid processed video frame; incomplete video not published')
                        graded_bgr = cv2.cvtColor(graded, cv2.COLOR_RGB2BGR)
                        writer.write(graded_bgr)
                        check()  # Account actual encoded growth before another frame is read.
                        frame_progress.update(processed_frames + frame_number)
    
                    processed_frames += len(frames_bgr)
                    frame_progress.update(processed_frames, force=processed_frames == total_frames)
    
            finally:
                cap.release()
                writer.release()

            check()  # VideoWriter.release may flush additional encoded bytes.
            if processed_frames == 0 or (total_frames > 0 and processed_frames != total_frames):
                raise RuntimeError('Decoded frame count differs; incomplete video not published')
            
            final_file = outp
            has_audio = False
            if self.ffmpeg_path and os.path.isfile(self.ffmpeg_path):
                # Muxing temporarily holds a second compressed video copy. Check
                # its measured size, not width*height*all_frames. Audio growth is
                # also monitored while the owned FFmpeg process is running.
                check(temp_out.stat().st_size)
                if progress_cb:
                    progress_cb({"phase": "muxing", "percent": 99.0, "message": "正在同步音频与终稿封装"})
                cmd = [self.ffmpeg_path, '-nostdin', '-v', 'error', '-n',
                       '-i', str(temp_out), '-i', str(inp), '-c:v', 'copy', '-c:a', 'aac',
                       '-map', '0:v:0', '-map', '1:a:0', str(outp)]
                # Storage/cancellation exceptions propagate, remove only this new
                # partial output, and must not silently publish the video-only file.
                if _mux_video(cmd, check) and outp.is_file() and outp.stat().st_size > 0:
                    has_audio = True
                    temp_out.unlink(missing_ok=True)
                else:
                    outp.unlink(missing_ok=True)
                    check()
                    shutil.move(str(temp_out), str(outp))
            else:
                if temp_out.is_file():
                    shutil.move(str(temp_out), str(outp))
    
            check()
            # Validate the encoded or muxed file, including every frame, before publishing.
            verify = cv2.VideoCapture(str(outp))
            try:
                if not verify.isOpened():
                    raise RuntimeError('Cannot read back rendered video; not published')
                verified_frames = 0
                while True:
                    cancel_check()
                    ok, frame = verify.read()
                    if not ok:
                        break
                    if frame is None or frame.shape != (height, width, 3):
                        raise RuntimeError('Rendered video readback dimensions differ; not published')
                    verified_frames += 1
                if verified_frames != processed_frames:
                    raise RuntimeError('Rendered video readback frame count differs; not published')
            finally:
                verify.release()
            check()
            total_elapsed = round(time.perf_counter() - t_start, 2)
            return {
                "ok": True,
                "outputPath": str(outp),
                "style": style,
                "frames": processed_frames,
                "width": width, "height": height,
                "fps": info['fps'],
                "elapsedSeconds": total_elapsed,
                "hasAudio": has_audio,
                "colorConcurrency": color_plan,
                "colorBackend": self.color_manager.backend(style),
                "executionMode": "concurrent" if parallel else "serial",
                "averageFps": round(processed_frames / total_elapsed, 1) if total_elapsed > 0 else 0
            }
        except BaseException:
            outp.unlink(missing_ok=True)
            raise
        finally:
            if color_pool is not None:
                color_pool.shutdown(wait=True, cancel_futures=True)
            cap.release()
            if writer is not None:
                writer.release()
            temp_out.unlink(missing_ok=True)
