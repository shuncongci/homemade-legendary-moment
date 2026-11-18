"""One serialized, persistent inference process per local workbench.

Cancellation terminates only our owned worker, never a server/UI/other user's process.
Completed on-disk subject caches survive a worker restart. Incomplete files are private
and are not registered as downloadable results. No automatic replay after worker loss.
"""
from __future__ import annotations
import multiprocessing as mp
import sys
import threading
import time
from pathlib import Path


def _engine_main(connection, folder, config):
    # Native failures otherwise leave only an EOF in the parent. Diagnostics stay local.
    try:
        import faulthandler
        faulthandler.enable(all_threads=True)
    except (OSError, ValueError, RuntimeError):
        pass  # Some embedded stderr streams have no native descriptor.
    # This boundary is reached only after spawn's main-module imports have completed.
    connection.send(('worker-started',))
    setup_started = time.perf_counter()
    from workbench import Workbench
    app = Workbench(folder=Path(folder), isolated=False)
    app.config = config
    # Keep the child runtime's cached settings aligned with this job owner,
    # without writing config.json or adopting another running session's settings.
    import config as config_module
    config_module._CURRENT_CONFIG = dict(config)
    connection.send(('progress', ('engine-worker-ready', '', {'workerSetupSeconds': round(time.perf_counter() - setup_started, 6)})))
    try:
        while True:
            request = connection.recv()
            if request is None:
                return
            job, item = request
            job['cancel'] = threading.Event()
            before = set(app.artifacts)
            def progress(*args):
                connection.send(('progress', args))
            try:
                result = app.execute_action(job, item, progress)
                from session_store import collect_urls
                # Cached action-complete may restore an existing artifact ID in
                # this worker; include all result references, not just new keys.
                assets = {k: v for k, v in app.artifacts.items() if k not in before or k in collect_urls(result)}
                device = result.get('device') or (app.photo_pipeline.device if app.photo_pipeline else 'cpu')
                connection.send(('result', result, assets, device))
            except Exception as error:
                import traceback
                traceback.print_exc()
                from workbench import ApiError
                from memory_policy import MEMORY_PREFLIGHT, is_memory_preflight
                connection.send(('error', str(error) if isinstance(error, (ApiError, ValueError)) else '本地计算未完成，请检查终端日志；原片未改变。',
                                 MEMORY_PREFLIGHT if is_memory_preflight(error) else None))
    except (EOFError, BrokenPipeError):
        return
    finally:
        connection.close()


class EngineProcess:
    def __init__(self, folder, config, worker_target=None):
        self.folder, self.config = str(folder), dict(config)
        self.worker_target = worker_target or _engine_main
        self.process = self.connection = None
        self.last_device = '待首次处理检测'

    def start(self):
        if self.process and self.process.is_alive():
            return
        self.close()
        context = mp.get_context('spawn')
        parent, child = context.Pipe()
        try:
            if sys.platform == 'win32' and self.worker_target is _engine_main:
                from windows_worker import WindowsWorker
                self.process = WindowsWorker(child)
                self.connection = parent
                parent.send((self.folder, self.config))
            else:
                self.process = context.Process(target=self.worker_target, args=(child, self.folder, self.config), daemon=True, name='legend-local-inference')
                self.process.start()
                self.connection = parent
        except BaseException:
            parent.close()
            try:
                self.close()
            except Exception as cleanup_error:
                print("engine-start-cleanup-failed", repr(cleanup_error), file=sys.stderr, flush=True)
            raise
        finally:
            child.close()

    def _worker_lost(self, job, cause=None):
        from workbench import ApiError
        if job['cancel'].is_set():
            return InterruptedError()
        process = self.process
        if process:
            process.join(timeout=.3)
        code = process.exitcode if process else None
        progress = getattr(self, 'last_progress', None) or ('', '', {})
        detail = progress[2] if len(progress) > 2 and isinstance(progress[2], dict) else {}
        print('engine-worker-loss', 'job='+str(job.get('id', 'unknown')),
              'pid='+str(process.pid if process else None), 'exitcode='+str(code),
              'cause='+str(type(cause).__name__ if cause else 'process-exit'),
              'lastCode='+str(progress[0]), 'completed='+str(detail.get('completed')),
              'total='+str(detail.get('total')), file=sys.stderr, flush=True)
        reason = ('意外退出（退出码'+str(code)+' / 0x'+format(code & 0xffffffff, '08X')+'）'
                  if code is not None else '通信中断（未取得退出码）')
        return ApiError('本地计算子进程'+reason+'；本次未发布新成片，原片及上次完整结果保留；未自动重试。请检查本地终端日志。')

    def _ipc(self, job, operation, *args):
        try:
            return operation(*args)
        except (EOFError, OSError) as error:
            raise self._worker_lost(job, error) from error

    def execute(self, job, item, callback):
        startup_started = time.perf_counter()
        self.start()
        # Threading primitives/visual bytes never cross the job protocol.
        payload = {k: v for k, v in job.items() if k not in ('cancel', '_visuals')}
        self.last_progress = None
        try:
            self._ipc(job, self.connection.send, (payload, item))
            while True:
                if job['cancel'].is_set():
                    self.close()
                    raise InterruptedError()
                if self._ipc(job, self.connection.poll, .025):
                    message = self._ipc(job, self.connection.recv)
                    if message[0] == 'worker-started':
                        callback('engine-spawn-ready', '', {'workerStartupSeconds': round(time.perf_counter() - startup_started, 6)})
                    elif message[0] == 'progress':
                        self.last_progress = message[1]
                        callback(*message[1])
                    elif message[0] == 'result':
                        if job['cancel'].is_set():
                            raise InterruptedError()
                        self.last_device = message[3]
                        return message[1], message[2]
                    else:
                        from workbench import ApiError
                        from memory_policy import MEMORY_PREFLIGHT, MemoryPreflightError
                        if len(message) > 2 and message[2] == MEMORY_PREFLIGHT:
                            raise MemoryPreflightError(message[1])
                        raise ApiError(message[1])
                elif not self.process.is_alive():
                    raise self._worker_lost(job)
        except BaseException:
            try:
                self.close()
            except Exception as cleanup_error:
                print("engine-cleanup-failed", repr(cleanup_error), file=sys.stderr, flush=True)
            raise

    def close(self):
        process, connection = self.process, self.connection
        self.process = self.connection = None
        if process:
            # Include only descendants of this owned inference process (e.g. its FFmpeg).
            descendants = []
            if process.is_alive():
                psutil = None
                if not (sys.platform == 'win32' and hasattr(process, 'job')):
                    try:
                        import psutil
                    except ModuleNotFoundError as error:
                        if error.name != 'psutil': raise
                if psutil is not None:
                    try:
                        descendants = psutil.Process(process.pid).children(recursive=True)
                    except psutil.Error:
                        pass
                for child in descendants:
                    try:
                        child.terminate()
                    except psutil.Error:
                        pass
                process.terminate()
            process.join(timeout=.4)
            if process.is_alive():
                process.kill()
                process.join(timeout=.4)
            for child in descendants:
                try:
                    if child.is_running():
                        child.kill()
                except psutil.Error:
                    pass
            process.close()
        if connection:
            connection.close()
