"""Console-free Windows inference child, with a private inherited pipe and Job.
No shell, network endpoint, PID adoption, dependency install or process-wide patch.
The child waits for its bootstrap until the Job owns it; descendants die on close.
"""
import os
from pathlib import Path
import subprocess
import sys


class OwnedJob:
    def __init__(self):
        import ctypes as c
        from ctypes import wintypes as w
        self.c = c
        self.api = c.WinDLL('kernel32', use_last_error=True)
        class Basic(c.Structure):
            _fields_ = [('userTime', c.c_longlong), ('jobTime', c.c_longlong), ('flags', w.DWORD),
                        ('minWorkingSet', c.c_size_t), ('maxWorkingSet', c.c_size_t), ('processLimit', w.DWORD),
                        ('affinity', c.c_size_t), ('priority', w.DWORD), ('scheduling', w.DWORD)]
        class Io(c.Structure):
            _fields_ = [(n, c.c_ulonglong) for n in ('readOps','writeOps','otherOps','readBytes','writeBytes','otherBytes')]
        class Extended(c.Structure):
            _fields_ = [('basic', Basic), ('io', Io), ('processMemory', c.c_size_t), ('jobMemory', c.c_size_t),
                        ('peakProcessMemory', c.c_size_t), ('peakJobMemory', c.c_size_t)]
        self.api.CreateJobObjectW.argtypes = [c.c_void_p, w.LPCWSTR]
        self.api.CreateJobObjectW.restype = w.HANDLE
        self.api.SetInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD]
        self.api.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
        self.api.TerminateJobObject.argtypes = [w.HANDLE, w.UINT]
        self.api.CloseHandle.argtypes = [w.HANDLE]
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle: raise c.WinError(c.get_last_error())
        value = Extended(); value.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.api.SetInformationJobObject(self.handle, 9, c.byref(value), c.sizeof(value)):
            error = c.WinError(c.get_last_error()); self.close(); raise error

    def assign(self, process_handle):
        if not self.api.AssignProcessToJobObject(self.handle, int(process_handle)):
            raise self.c.WinError(self.c.get_last_error())

    def terminate(self):
        if self.handle and not self.api.TerminateJobObject(self.handle, 1):
            raise self.c.WinError(self.c.get_last_error())

    def close(self):
        if self.handle:
            self.api.CloseHandle(self.handle); self.handle = None


class WindowsWorker:
    def __init__(self, connection):
        if sys.platform != 'win32': raise RuntimeError('Windows child requested on another platform')
        self.job = OwnedJob(); self.child = None
        handle = connection.fileno()
        startup = subprocess.STARTUPINFO()
        startup.lpAttributeList = {'handle_list': [handle]}
        inherited = os.get_handle_inheritable(handle)
        try:
            os.set_handle_inheritable(handle, True)
            self.child = subprocess.Popen([sys.executable, '-I', '-B', '-X', 'utf8', str(Path(__file__).resolve()), '--pipe', str(handle)],
                cwd=Path(__file__).resolve().parents[1], stdin=subprocess.DEVNULL,
                stdout=sys.stdout or subprocess.DEVNULL, stderr=sys.stderr or subprocess.DEVNULL,
                startupinfo=startup, close_fds=True, creationflags=subprocess.CREATE_NO_WINDOW)
            self.job.assign(self.child._handle)  # Actual creation handle, never a reopened PID.
        except BaseException:
            if self.child is not None:
                self.child.kill(); self.child.wait(timeout=5)
            self.job.close(); raise
        finally:
            os.set_handle_inheritable(handle, inherited)

    @property
    def pid(self): return self.child.pid
    @property
    def exitcode(self): return self.child.poll()
    def is_alive(self): return self.child.poll() is None
    def join(self, timeout=None):
        try: self.child.wait(timeout=timeout)
        except subprocess.TimeoutExpired: pass
    def terminate(self): self.job.terminate()
    def kill(self): self.job.terminate()
    def close(self): self.job.close()


def main():
    import argparse
    from multiprocessing.connection import PipeConnection as Connection
    parser = argparse.ArgumentParser(); parser.add_argument('--pipe', type=int, required=True)
    args = parser.parse_args()
    # Only a handle explicitly inherited by this invocation can carry the bootstrap.
    with Connection(args.pipe) as connection:
        folder, config = connection.recv()
        if not isinstance(folder, str) or not isinstance(config, dict): raise ValueError('Invalid worker bootstrap')
        sys.path[:0] = [str(Path(__file__).resolve().parent), str(Path(__file__).resolve().parents[1] / 'src')]
        from engine_process import _engine_main
        _engine_main(connection, folder, config)


if __name__ == '__main__': main()
