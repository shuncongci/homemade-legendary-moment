"""Native memory admission when the optional Windows psutil wheel is absent.
No guessed budgets: a missing or failed native query rejects before allocation.
"""
import sys


def _windows_memory():
    import ctypes as c
    from ctypes import wintypes as w
    class Status(c.Structure):
        _fields_ = [('length', w.DWORD), ('load', w.DWORD)] + [(n, c.c_ulonglong) for n in
            ('totalPhys', 'availPhys', 'totalPage', 'availPage', 'totalVirtual', 'availVirtual', 'availExtended')]
    api = c.WinDLL('kernel32', use_last_error=True)
    api.GlobalMemoryStatusEx.argtypes = [c.POINTER(Status)]
    api.GlobalMemoryStatusEx.restype = w.BOOL
    value = Status(); value.length = c.sizeof(value)
    if not api.GlobalMemoryStatusEx(c.byref(value)):
        raise c.WinError(c.get_last_error())
    return int(value.availPhys)


def available_memory():
    try:
        import psutil
    except ModuleNotFoundError as error:
        if error.name != 'psutil' or sys.platform != 'win32':
            raise
        value = _windows_memory()
    else:
        value = int(psutil.virtual_memory().available)
    if type(value) is not int or value <= 0:
        raise ValueError('无法读取可用内存，请关闭其他应用后重试。')
    return value
